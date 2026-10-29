"""Meshtastic radio over USB serial, using the radio's own client API (the same one the
phone apps use): 0x94 0xC3 + 16-bit length + a ToRadio/FromRadio protobuf.

Written fresh from the public Meshtastic protobufs (mesh.proto, config.proto,
channel.proto, admin.proto).
One process owns the port at a time: the LoRa service (lora.py).
"""
import hashlib
import os
import re
import socket
import threading
import time

from . import pb

START1, START2 = 0x94, 0xC3
MAX_FRAME = 512
BROADCAST = 0xFFFFFFFF
TEXT_APP, POSITION_APP, NODEINFO_APP, ADMIN_APP, TRACEROUTE_APP, ATAK_APP, PRIVATE_APP = 1, 3, 4, 6, 70, 72, 256
ROUTING_APP, TELEMETRY_APP = 5, 67
HEARTBEAT_S = 300
TCP_PORT = 4403   # Meshtastic's network API (same framing as serial)
TCP_RE = re.compile(r'^tcp:([A-Za-z0-9.-]{1,253})(?::(\d{1,5}))?$')


def tcp_target(path):
    """(host, port) for a 'tcp:host[:port]' radio address, else None."""
    m = TCP_RE.match(path or '') if isinstance(path, str) else None
    if not m:
        return None
    port = int(m.group(2) or TCP_PORT)
    return (m.group(1), port) if 0 < port < 65536 else None
ROLES = ['DISABLED', 'PRIMARY', 'SECONDARY']
FULL_PRECISION = 32

REGIONS = ['UNSET', 'US', 'EU_433', 'EU_868', 'CN', 'JP', 'ANZ', 'KR', 'TW', 'RU', 'IN', 'NZ_865',
           'TH', 'LORA_24', 'UA_433', 'UA_868', 'MY_433', 'MY_919', 'SG_923', 'PH_433', 'PH_868',
           'PH_915', 'ANZ_433', 'KZ_433', 'KZ_863', 'NP_865', 'BR_902']
PRESETS = ['LONG_FAST', 'LONG_SLOW', 'VERY_LONG_SLOW', 'MEDIUM_SLOW', 'MEDIUM_FAST', 'SHORT_SLOW',
           'SHORT_FAST', 'LONG_MODERATE', 'SHORT_TURBO', 'LONG_TURBO']
# The firmware's well-known default key is "psk = [1]"; with an empty channel name that is
# the public LongFast channel every new radio joins.
DEFAULT_PSK = b'\x01'
# Display name (what the firmware hashes into a frequency slot when the primary channel
# has no name) and bandwidth in kHz, per preset.
PRESET_INFO = {'LONG_FAST': ('LongFast', 250), 'LONG_SLOW': ('LongSlow', 125),
               'VERY_LONG_SLOW': ('VLongSlow', 62.5), 'MEDIUM_SLOW': ('MediumSlow', 250),
               'MEDIUM_FAST': ('MediumFast', 250), 'SHORT_SLOW': ('ShortSlow', 250),
               'SHORT_FAST': ('ShortFast', 250), 'LONG_MODERATE': ('LongMod', 125),
               'SHORT_TURBO': ('ShortTurbo', 500), 'LONG_TURBO': ('LongTurbo', 500)}
# Band edges in MHz, only for regions we have checked; the frequency shown is for
# information and the radio does its own sums.
BANDS = {'US': (902.0, 928.0), 'ANZ': (915.0, 928.0), 'EU_868': (869.4, 869.65),
         'EU_433': (433.0, 434.0), 'CN': (470.0, 510.0)}


def djb2(s):
    h = 5381
    for c in s.encode():
        h = (h * 33 + c) & 0xFFFFFFFF
    return h


def frequency_mhz(region, preset, primary_name='', channel_num=0):
    """Centre frequency the radio uses: an explicit slot, else the primary channel's name
    (or the preset's name for an unnamed channel) hashed over the band."""
    band, info = BANDS.get(region), PRESET_INFO.get(preset)
    if not band or not info:
        return None
    bw = info[1] / 1000.0
    slots = int(round((band[1] - band[0]) / bw, 6))
    if slots < 1:
        return None
    slot = (channel_num - 1 if channel_num else djb2(primary_name or info[0])) % slots
    return round(band[0] + bw / 2 + slot * bw, 4)


def public_channel_num(region, preset):
    """LoRaConfig.channel_num (1-based) of the public channel's frequency for this
    preset, so a radio whose primary is the team channel still sits on (and hears) the
    public channel. None where we do not know the band."""
    band, info = BANDS.get(region), PRESET_INFO.get(preset)
    if not band or not info:
        return None
    slots = int(round((band[1] - band[0]) / (info[1] / 1000.0), 6))
    return djb2(info[0]) % slots + 1 if slots >= 1 else None


def preset_fits(region, preset):
    band, info = BANDS.get(region), PRESET_INFO.get(preset)
    return not band or not info or (band[1] - band[0]) * 1000 >= info[1]


def rand32():
    return int.from_bytes(os.urandom(4), 'little') or 1


def frame(payload):
    if len(payload) > MAX_FRAME:
        raise ValueError('packet too large for the radio')
    return bytes([START1, START2, len(payload) >> 8, len(payload) & 0xFF]) + payload


class Deframer:
    """Pull API frames out of the serial byte stream; the radio also prints plain-text
    debug lines between frames, which are skipped."""

    def __init__(self):
        self.buf = bytearray()

    def feed(self, data):
        self.buf += data
        out = []
        while True:
            i = self.buf.find(bytes([START1, START2]))
            if i < 0:
                del self.buf[:max(0, len(self.buf) - 1)]
                return out
            del self.buf[:i]
            if len(self.buf) < 4:
                return out
            n = self.buf[2] << 8 | self.buf[3]
            if n > MAX_FRAME:
                del self.buf[:1]  # not a real header; resync
                continue
            if len(self.buf) < 4 + n:
                return out
            out.append(bytes(self.buf[4:4 + n]))
            del self.buf[:4 + n]


def psk_from_passphrase(passphrase):
    """AES-256 channel key from a team passphrase, so every node set with the same words
    joins the same private channel."""
    return hashlib.sha256(passphrase.encode()).digest()


def psk_kind(psk):
    if not psk:
        return 'none'
    if len(psk) == 1:
        return 'open'  # one of the firmware's built-in keys
    return 'private'


def replace_fields(raw, new):
    """Re-encode a message keeping every field we do not change (set_config replaces the
    whole LoRa section, so dropping unknown fields would reset them)."""
    out = b''.join(pb.key(f, w) + (pb.varint(len(v)) + v if w == pb.LEN else
                                   pb.varint(v) if w == pb.VARINT else v)
                   for f, w, v in pb.fields(raw) if f not in new)
    return out + b''.join(new.values())


def channel_settings(name, psk, precision, base=b''):
    """ChannelSettings: psk 2, name 3, module_settings 7 (position_precision 1).
    Precision 0 means this radio never puts its position on that channel."""
    module = pb.uint(1, precision)
    return replace_fields(base, {2: pb.blob(2, psk), 3: pb.text(3, name), 7: pb.blob(7, module)})


def position_precision(settings_raw):
    module = pb.first(pb.decode(settings_raw or b''), 7)
    return pb.first(pb.decode(module), 1, 0) if module is not None else None


def b64url(data):
    """Unpadded URL-safe base64 (the base64 module is not part of every node's Python)."""
    alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
    out = []
    for i in range(0, len(data), 3):
        chunk = data[i:i + 3]
        n = int.from_bytes(chunk + b'\0' * (3 - len(chunk)), 'big')
        out.extend(alphabet[n >> s & 63] for s in (18, 12, 6, 0))
        if len(chunk) < 3:
            del out[len(chunk) - 3:]
    return ''.join(out)


def b64url_decode(text):
    alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
    text = text.strip().rstrip('=').replace('+', '-').replace('/', '_')
    bits, n, out = 0, 0, bytearray()
    for ch in text:
        i = alphabet.find(ch)
        if i < 0:
            raise ValueError('not base64')
        n, bits = (n << 6) | i, bits + 6
        if bits >= 8:
            bits -= 8
            out.append(n >> bits & 0xff)
    return bytes(out)


def team_from_url(url):
    """(name, psk) of the first named private channel in a Meshtastic channel link, so a
    scanned QR (ours or a stock Meshtastic app's) can set the team channel."""
    if not isinstance(url, str) or len(url) > 2048 or '#' not in url:
        raise ValueError('Not a Meshtastic channel link')
    try:
        body = pb.decode(b64url_decode(url.split('#', 1)[1]))
        channels = [pb.decode(raw) for _, raw in body.get(1, [])]
    except (ValueError, IndexError, TypeError):
        raise ValueError('The channel link is damaged')
    for settings in channels:
        psk, name = pb.first(settings, 2, b''), pb.as_text(pb.first(settings, 3, b''))
        if psk_kind(psk) == 'private' and len(psk) in (16, 32) and 1 <= len(name.encode()) <= 11:
            return name, psk
    raise ValueError('The link has no private named channel')


def channel_url(settings_list, lora_raw):
    """The standard Meshtastic channel link (ChannelSet: settings 1, lora_config 2) that
    the stock phone apps scan from a QR code. It contains the channel keys."""
    body = b''.join(pb.blob(1, s) for s in settings_list) + pb.blob(2, lora_raw or b'')
    return 'https://meshtastic.org/e/#' + b64url(body)


def parse_lora(raw):
    m = pb.decode(raw)
    region = pb.first(m, 7, 0)
    preset = pb.first(m, 2, 0)
    return {'use_preset': bool(pb.first(m, 1, 0)),
            'region': REGIONS[region] if region < len(REGIONS) else str(region),
            'modem_preset': PRESETS[preset] if preset < len(PRESETS) else str(preset),
            'hop_limit': pb.first(m, 8, 0),
            'tx_enabled': bool(pb.first(m, 9, 0)),
            'tx_power_dbm': pb.as_int32(pb.first(m, 10, 0)),
            'channel_num': pb.first(m, 11, 0)}


class Radio:
    """Keeps the link up, tracks what the radio reports, and hands received packets to
    `on_packet(portnum, payload, info)`. `port` is any object with read/write/close
    (tests pass a fake); by default the serial device is opened raw."""

    def __init__(self, path, on_packet=None, opener=None, clock=time.time):
        self.path = path
        self.on_packet = on_packet or (lambda *a: None)
        self.opener = opener or (self._open_tcp if tcp_target(path) else self._open_serial)
        self.clock = clock
        self.lock = threading.Lock()
        self.port = None
        self.stop = threading.Event()
        self.disconnects = []      # times the link dropped, for the "check the cable" warning
        self.pending = {}          # request packet id -> [event, (payload, info)]
        self.on_ack = lambda packet_id, ok, reason: None   # want_ack results (Routing replies)
        self.airtime = {}          # own radio: channel_util / air_util_tx percent, and when
        self.reset_state()

    def reset_state(self):
        self.my_num = None
        self.firmware = None
        self.lora_raw = None
        self.lora = None
        self.config = {}           # Config section number -> raw bytes (1 position, 6 lora, ...)
        self.channels = {}
        self.nodes = {}
        self.config_complete = False
        self.config_id = None
        self.last_rx = None
        self.error = None

    @staticmethod
    def _open_serial(path):
        """Raw 8N1 with HUPCL cleared, so closing the port does not reset the radio.
        (OpenMANET has no stty; termios is part of its Python.) USB CDC ignores the baud."""
        import termios
        fd = os.open(path, os.O_RDWR | os.O_NOCTTY)
        try:
            iflag, oflag, cflag, lflag, ispeed, ospeed, cc = termios.tcgetattr(fd)
            cflag = (cflag & ~(termios.CSIZE | termios.PARENB | termios.CSTOPB | termios.HUPCL)
                     | termios.CS8 | termios.CREAD | termios.CLOCAL)
            cc[termios.VMIN], cc[termios.VTIME] = 1, 0
            termios.tcsetattr(fd, termios.TCSANOW, [0, 0, cflag, 0, termios.B115200, termios.B115200, cc])
            termios.tcflush(fd, termios.TCIOFLUSH)
        except Exception:
            os.close(fd)
            raise
        return os.fdopen(fd, 'r+b', buffering=0)

    @staticmethod
    def _open_tcp(path):
        """A radio on Wi-Fi (ESP32 boards): Meshtastic's TCP API speaks the same framed
        protocol as USB. Keepalive so a radio that drops off Wi-Fi is noticed."""
        sock = socket.create_connection(tcp_target(path), timeout=10)
        sock.settimeout(None)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        for opt, val in (('TCP_KEEPIDLE', 30), ('TCP_KEEPINTVL', 10), ('TCP_KEEPCNT', 3)):
            if hasattr(socket, opt):
                sock.setsockopt(socket.IPPROTO_TCP, getattr(socket, opt), val)
        return sock.makefile('rwb', buffering=0)

    # ---- link ----
    def connect(self):
        self.reset_state()
        self.port = self.opener(self.path)
        # The firmware wakes its API on a burst of START2 bytes, then sends its whole
        # database (my info, nodes, config, channels) after want_config_id.
        self._write(bytes([START2]) * 32)
        self.request_config()

    def request_config(self):
        self.config_id = rand32() & 0x7FFFFFFF or 1
        self.config_complete = False
        self._send_toradio(pb.uint(3, self.config_id))

    def _write(self, data):
        with self.lock:
            if self.port is None:
                raise OSError('radio not connected')
            self.port.write(data)
            if hasattr(self.port, 'flush'):
                self.port.flush()

    def _send_toradio(self, body):
        self._write(frame(body))

    def close(self):
        port, self.port = self.port, None
        if port is not None:
            try:
                port.close()
            except OSError:
                pass

    def run_forever(self):
        """Reader loop with reconnect; returns when stop is set."""
        backoff = 2
        while not self.stop.is_set():
            try:
                self.connect()
                self.error = None
                backoff = 2
                deframer = Deframer()
                last_beat = self.clock()
                while not self.stop.is_set():
                    data = self.port.read(256)
                    if not data:
                        raise OSError('radio disconnected')
                    for payload in deframer.feed(data):
                        self.handle(payload)
                    if self.clock() - last_beat > HEARTBEAT_S:
                        self._send_toradio(pb.blob(7, b''))
                        last_beat = self.clock()
            except Exception as e:  # unplugged, rebooting after a settings change, busy port
                self.error = str(e) or e.__class__.__name__
                self.disconnects = [t for t in self.disconnects if self.clock() - t < 600] + [self.clock()]
                self.close()
                self.config_complete = False
                self.stop.wait(backoff)
                backoff = min(backoff * 2, 30)

    @property
    def connected(self):
        return self.port is not None and self.config_complete

    # ---- receive ----
    def handle(self, payload):
        self.last_rx = self.clock()
        for field, _, value in pb.fields(payload):
            if field == 2:
                self._packet(value)
            elif field == 3:
                self.my_num = pb.first(pb.decode(value), 1)
            elif field == 4:
                self._node_info(value)
            elif field == 5:
                for section, _, raw in pb.fields(value):
                    self.config[section] = raw
                    if section == 6:
                        self.lora_raw = raw
                        self.lora = parse_lora(raw)
            elif field == 7 and value == self.config_id:
                self.config_complete = True
            elif field == 8 and value:
                self.request_config()  # radio rebooted (for example after a settings change)
            elif field == 10:
                self._channel(value)
            elif field == 13:
                self.firmware = pb.as_text(pb.first(pb.decode(value), 1)) or None

    def _node_info(self, raw):
        m = pb.decode(raw)
        num = pb.first(m, 1)
        if num is None:
            return
        user = pb.decode(pb.first(m, 2, b''))
        node = self.nodes.setdefault(num, {'num': num})
        node.update({'id': pb.as_text(pb.first(user, 1)) or '!%08x' % num,
                     'long_name': pb.as_text(pb.first(user, 2)),
                     'short_name': pb.as_text(pb.first(user, 3)),
                     'snr': pb.as_float(pb.first(m, 4)),
                     'last_heard': pb.as_u32(pb.first(m, 5)),
                     'hops_away': pb.first(m, 9)})
        if num == self.my_num:
            self._metrics(pb.first(m, 6))

    def _metrics(self, raw):
        """DeviceMetrics: channel_utilization (3) and air_util_tx (4), both percent."""
        if not raw:
            return
        m = pb.decode(raw)
        cu, tx = pb.as_float(pb.first(m, 3)), pb.as_float(pb.first(m, 4))
        if cu is not None or tx is not None:
            self.airtime = {'channel_util': None if cu is None else round(cu, 1),
                            'air_util_tx': None if tx is None else round(tx, 1), 'at': int(self.clock())}

    def _channel(self, raw):
        m = pb.decode(raw)
        index = pb.as_int32(pb.first(m, 1, 0))
        settings_raw = pb.first(m, 2, b'')
        s = pb.decode(settings_raw)
        self.channels[index] = {'index': index, 'role': ROLES[min(pb.first(m, 3, 0), 2)],
                                'name': pb.as_text(pb.first(s, 3)), 'security': psk_kind(pb.first(s, 2, b'')),
                                'position_precision': position_precision(settings_raw),
                                'raw': settings_raw}

    def _packet(self, raw):
        m = pb.decode(raw)
        data = pb.first(m, 4)
        if data is None:
            return  # still encrypted: a channel this radio has no key for
        d = pb.decode(data)
        sender = pb.as_u32(pb.first(m, 1))
        hop_start, hop_limit = pb.first(m, 15), pb.first(m, 9, 0)
        info = {'from': sender, 'to': pb.as_u32(pb.first(m, 2)), 'channel': pb.first(m, 3, 0),
                'snr': pb.as_float(pb.first(m, 8)), 'rssi': pb.as_int32(pb.first(m, 12, 0)) or None,
                'id': pb.as_u32(pb.first(m, 6)), 'time': self.clock(),
                'hops': hop_start - hop_limit if hop_start is not None and hop_start >= hop_limit else None}
        if sender is not None and sender != self.my_num:
            node = self.nodes.setdefault(sender, {'num': sender, 'id': '!%08x' % sender})
            node.update({'snr': info['snr'], 'last_heard': int(self.clock()), 'channel': info['channel']})
            if info['hops'] is not None:
                node['hops_away'] = info['hops']
        portnum = pb.first(d, 1, 0)
        payload = pb.first(d, 2, b'')
        waiter = self.pending.get(pb.as_u32(pb.first(d, 6)))
        if waiter is not None:
            waiter[1] = (portnum, payload, info)
            waiter[0].set()
        if portnum == ROUTING_APP:
            rid = pb.as_u32(pb.first(d, 6))
            if rid is not None:
                reason = pb.first(pb.decode(payload), 3, 0)
                self.on_ack(rid, reason == 0, reason)
        if portnum == TELEMETRY_APP and sender == self.my_num:
            self._metrics(pb.first(pb.decode(payload), 2))
        if portnum == NODEINFO_APP and sender is not None:
            user = pb.decode(payload)
            self.nodes[sender].update({'long_name': pb.as_text(pb.first(user, 2)),
                                       'short_name': pb.as_text(pb.first(user, 3))})
        self.on_packet(portnum, payload, info)

    # ---- send ----
    def send(self, portnum, payload, dest=BROADCAST, channel=0, want_ack=False, want_response=False):
        packet_id = rand32()
        data = pb.uint(1, portnum) + pb.blob(2, payload) + pb.boolean(3, want_response)
        packet = (pb.fixed32(2, dest) + pb.uint(3, channel) + pb.blob(4, data) +
                  pb.fixed32(6, packet_id) + pb.boolean(10, want_ack))
        self._send_toradio(pb.blob(1, packet))
        return packet_id

    def request(self, portnum, payload, dest, timeout, channel=0):
        """Send and wait for the packet that answers it (Data.request_id); None on timeout."""
        waiter = [threading.Event(), None]
        packet_id = rand32()  # registered before sending, so a fast answer is not missed
        self.pending[packet_id] = waiter
        try:
            data = pb.uint(1, portnum) + pb.blob(2, payload) + pb.boolean(3, True)
            packet = pb.fixed32(2, dest) + pb.uint(3, channel) + pb.blob(4, data) + pb.fixed32(6, packet_id)
            self._send_toradio(pb.blob(1, packet))
            waiter[0].wait(timeout)
            return waiter[1]
        finally:
            self.pending.pop(packet_id, None)

    def ping(self, timeout=5.0):
        """Round trip to our own radio in ms (asks for its device metadata), or None."""
        if self.my_num is None or self.port is None:
            return None
        t0 = time.monotonic()
        got = self.request(ADMIN_APP, pb.boolean(12, True), self.my_num, timeout)
        return round((time.monotonic() - t0) * 1000) if got else None

    def traceroute(self, num, timeout=60.0):
        """Route to another radio and back: {'ms', 'hops_out', 'hops_back', 'snr'} or None."""
        t0 = time.monotonic()
        got = self.request(TRACEROUTE_APP, b'', num, timeout)
        if not got or got[0] != TRACEROUTE_APP:
            return None
        m = pb.decode(got[1])

        def count(field):
            n = 0
            for wire, v in m.get(field, []):
                n += len(v) // 4 if wire == pb.LEN else 1
            return n
        return {'ms': round((time.monotonic() - t0) * 1000), 'hops_out': count(1) + 1,
                'hops_back': count(3) + 1 if 3 in m else None, 'snr': got[2].get('snr')}

    def admin(self, *messages):
        """AdminMessages to our own radio. A local client may change settings without the
        remote-admin session key. Edits are batched so the radio saves (and reboots) once."""
        if self.my_num is None:
            raise OSError('radio not ready')
        for body in (pb.boolean(64, True),) + messages + (pb.boolean(65, True),):
            self.send(ADMIN_APP, body, dest=self.my_num)

    def set_lora(self, region=None, preset=None):
        if self.lora_raw is None:
            raise OSError('radio settings not loaded yet')
        new = {}
        if region is not None:
            new[7] = pb.uint(7, REGIONS.index(region))
        if preset is not None:
            new[1] = pb.boolean(1, True)
            new[2] = pb.uint(2, PRESETS.index(preset)) or pb.key(2, pb.VARINT) + b'\x00'
        if self.profile() in ('open', 'covert'):
            # Stay on the public frequency, which moves with the preset.
            num = public_channel_num(region or self.lora['region'], preset or self.lora['modem_preset'])
            new[11] = pb.uint(11, num) if num else b''
        config = pb.blob(6, replace_fields(self.lora_raw, new))
        self.admin(pb.blob(34, config))

    def set_primary_channel(self, name, psk):
        current = self.channels.get(0, {}).get('raw', b'')
        settings = replace_fields(current, {2: pb.blob(2, psk), 3: pb.blob(3, name)})
        channel = pb.uint(1, 0) + pb.blob(2, settings) + pb.uint(3, 1)
        self.admin(pb.blob(33, channel))

    @staticmethod
    def channel_msg(index, settings, role):
        return pb.blob(33, pb.uint(1, index) + pb.blob(2, settings) + pb.uint(3, ROLES.index(role)))

    @staticmethod
    def lora_msg(lora_raw, new):
        return pb.blob(34, pb.blob(6, replace_fields(lora_raw or b'', new)))

    @staticmethod
    def owner_msg(long_name, short_name):
        return pb.blob(32, pb.text(2, long_name) + pb.text(3, short_name))

    def position_msg(self, broadcast_s, gps=True):
        """PositionConfig: position_broadcast_secs 1, smart broadcast 2, gps_mode 13 (1 on)."""
        new = {1: pb.uint(1, broadcast_s), 2: pb.key(2, pb.VARINT) + b'\x00',
               13: pb.uint(13, 1 if gps else 0) or pb.key(13, pb.VARINT) + b'\x00'}
        return pb.blob(34, pb.blob(1, replace_fields(self.config.get(1, b''), new)))

    def team_channel(self):
        """Index of the private team channel (primary first), or None."""
        for i in sorted(self.channels):
            c = self.channels[i]
            if c['role'] != 'DISABLED' and c['security'] == 'private':
                return i
        return None

    def team_psk(self):
        i = self.team_channel()
        return pb.first(pb.decode(self.channels[i]['raw']), 2) if i is not None else None

    def profile(self):
        """open, covert or dark when the radio is laid out the way
        Field Extension sets them; 'custom' for any other layout (for example one set by
        another tool), 'public' when the radio has no team channel, None before loading.
        All three keep the team channel primary; they differ in frequency and in the
        public channel: dark = no public channel, frequency from the team name;
        open/covert = public channel too, on the public frequency."""
        if not self.channels or not self.lora:
            return None
        team = self.team_channel()
        public = [c for c in self.channels.values() if c['role'] != 'DISABLED' and c['security'] == 'open']
        if team is None:
            return 'public'
        if team != 0:
            return 'custom'
        slot = self.lora.get('channel_num', 0)
        if not public:
            return 'dark' if slot == 0 else 'custom'
        if slot and slot == public_channel_num(self.lora['region'], self.lora['modem_preset']):
            return 'covert' if all(not c['position_precision'] for c in public) else 'open'
        return 'custom'

    def share_url(self):
        enabled = [self.channels[i]['raw'] for i in sorted(self.channels) if self.channels[i]['role'] != 'DISABLED']
        return channel_url(enabled, self.lora_raw)

    def status(self):
        primary = self.channels.get(0)
        return {
            'connected': self.connected,
            'airtime': dict(self.airtime) or None,
            'port': self.path,
            'error': self.error,
            'node_id': '!%08x' % self.my_num if self.my_num is not None else None,
            'name': (self.nodes.get(self.my_num) or {}).get('long_name'),
            'firmware': self.firmware,
            'lora': self.lora,
            'channel': {'name': primary['name'] or ('LongFast' if primary['security'] == 'open' else ''),
                        'security': primary['security']} if primary else None,
            'channels': [{'index': c['index'], 'role': c['role'], 'security': c['security'],
                          'name': c['name'] or (PRESET_INFO.get((self.lora or {}).get('modem_preset'), ('LongFast',))[0]
                                                if c['security'] == 'open' else ''),
                          'shares_position': bool(c['position_precision'])}
                         for i, c in sorted(self.channels.items()) if c['role'] != 'DISABLED'],
            'profile': self.profile(),
            'frequency_mhz': frequency_mhz(self.lora['region'], self.lora['modem_preset'],
                                           primary['name'] if primary else '', self.lora.get('channel_num', 0))
            if self.lora else None,
            'disconnects_10min': len([t for t in self.disconnects if self.clock() - t < 600]),
            'heard': sorted(({k: v for k, v in n.items() if k != 'num'} for num, n in self.nodes.items()
                             if num != self.my_num and n.get('last_heard')),
                            key=lambda n: -(n.get('last_heard') or 0))[:30],
        }


def probe(path, wait_s=20):
    """Read-only check: connect, load the radio's settings and node list, print them.
    Sends only the API wake-up and the settings request; changes nothing on the radio."""
    import json
    radio = Radio(path)
    t = threading.Thread(target=radio.run_forever, daemon=True)
    t.start()
    end = time.time() + wait_s
    while time.time() < end and not radio.connected:
        time.sleep(0.5)
    radio.stop.set()
    out = radio.status()
    out['channels'] = [{k: v for k, v in c.items() if k != 'raw'} for c in radio.channels.values()]
    print(json.dumps(out, indent=2))
    radio.close()
    return 0 if out['connected'] else 1


if __name__ == '__main__':
    import sys
    sys.exit(probe(sys.argv[1] if len(sys.argv) > 1 else '/dev/ttyACM0'))
