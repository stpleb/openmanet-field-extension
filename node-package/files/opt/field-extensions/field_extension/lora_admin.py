"""LoRa radio management for the LoRa service: privacy profiles, fleet push, choosing
and resetting the USB radio, setting up guest radios, and map markers over LoRa.

Profiles:
  open    team channel plus the public channel; this radio's position is shared on both
  covert  same channels, but this radio never puts its position on the public one
  dark    team channel only, on a frequency derived from the team channel's name
"""
import hashlib
import json
import os
import threading
import time
from pathlib import Path

from . import meshtastic as mt, pb, tak

PROFILES = ('open', 'covert', 'dark')
FLEET_MAGIC, MARKER_MAGIC = b'FE1', b'FM1'
FLEET_MAX_AGE_S = 3600
GUEST_POSITION_S = 120
SERIAL_HINTS = ('meshtastic', 'heltec', 'rak', 'seeed', 'lilygo', 'espressif', 'nrf52', 'adafruit',
                'cp210', 'ch340', 'ch9102', 'usb_serial', 'usb-serial')
NOT_RADIO = ('u-blox', 'ublox', 'gnss', 'gps', 'quectel', 'sierra', 'modem')


# ---- choosing the radio ----
def list_devices(root=Path('/')):
    """USB serial devices that could be a Meshtastic radio, one entry per device."""
    out, seen = [], set()
    by_id = root / 'dev/serial/by-id'
    try:
        names = sorted(p.name for p in by_id.iterdir())
    except OSError:
        names = []
    for name in names:
        path = by_id / name
        real = os.path.realpath(path)
        if real not in seen:
            seen.add(real)
            label = name.replace('usb-', '').rsplit('-if', 1)[0].replace('_', ' ')
            out.append({'path': str(path), 'name': label, 'port': os.path.basename(real)})
    for pattern in ('ttyACM', 'ttyUSB'):
        try:
            tty = sorted(p for p in (root / 'dev').iterdir() if p.name.startswith(pattern))
        except OSError:
            tty = []
        for p in tty:
            real = os.path.realpath(p)
            if real not in seen:
                seen.add(real)
                out.append({'path': str(p), 'name': p.name, 'port': p.name})
    return out


def usb_reset(device, sys_root=Path('/sys')):
    """Unplug and replug the radio in software (USB 'authorized' off, then on). Returns
    True if the port was found. The radio's own power is not cut on every hub, but this
    clears a USB link that has stopped answering, which is the usual failure."""
    port = os.path.basename(os.path.realpath(device))
    iface = os.path.realpath(sys_root / 'class/tty' / port / 'device')
    usb_dev = Path(iface).parent
    knob = usb_dev / 'authorized'
    if not knob.exists():
        return False
    knob.write_text('0')
    time.sleep(2)
    knob.write_text('1')
    return True


# ---- profiles and team channel ----
def profile_messages(radio, profile):
    """Admin messages that move the radio to a profile, keeping the team channel."""
    team = radio.team_channel()
    if team is None:
        raise ValueError('Set a team channel first')
    team_raw = radio.channels[team]['raw']
    team_settings = mt.channel_settings(radio.channels[team]['name'], radio.team_psk(), mt.FULL_PRECISION, team_raw)
    lora = radio.lora or {}
    return layout_messages(radio, team_settings, profile, lora.get('region'), lora.get('modem_preset'))


def layout_messages(radio, team_settings, profile, region, preset, lora_extra=None):
    """The team channel stays primary in every profile, so switching profile never moves the team key to another slot.
    Dark: no public channel, frequency picked from the team channel's name.
    Open/Covert: public channel as secondary, radio moved to the public frequency."""
    if profile == 'dark':
        slot, keep = b'', {0}
    else:
        num = mt.public_channel_num(region, preset)
        if num is None:
            raise ValueError('Open and Covert need the region set (and a known band)')
        slot, keep = pb.uint(11, num), {0, 1}
    msgs = [radio.lora_msg(radio.lora_raw, {**(lora_extra or {}), 11: slot}),
            radio.channel_msg(0, team_settings, 'PRIMARY')]
    if profile != 'dark':
        public = mt.channel_settings('', mt.DEFAULT_PSK, mt.FULL_PRECISION if profile == 'open' else 0)
        msgs.append(radio.channel_msg(1, public, 'SECONDARY'))
    msgs += [radio.channel_msg(i, b'', 'DISABLED') for i, c in sorted(radio.channels.items())
             if i not in keep and c['role'] != 'DISABLED']
    return msgs


def setup_messages(radio, region, preset, name, psk, profile='dark'):
    """Everything a new or foreign radio needs to work with Field Extension, in one
    edit (one restart): region and speed, transmit on, the team channel, and the
    channel layout for the chosen profile."""
    team = mt.channel_settings(name, psk, mt.FULL_PRECISION)
    lora = {1: pb.boolean(1, True),
            2: pb.uint(2, mt.PRESETS.index(preset)) or pb.key(2, pb.VARINT) + b'\x00',
            7: pb.uint(7, mt.REGIONS.index(region)), 9: pb.boolean(9, True)}
    return layout_messages(radio, team, profile, region, preset, lora)


def needs_setup(radio):
    lora = getattr(radio, 'lora', None)
    if not lora or not getattr(radio, 'channels', None):
        return False  # settings not loaded yet
    return lora['region'] == 'UNSET' or radio.team_channel() is None


def apply_profile(radio, profile):
    if profile not in PROFILES:
        raise ValueError('profile must be open, covert or dark')
    radio.admin(*profile_messages(radio, profile))


def apply_team(radio, name, psk):
    index = radio.team_channel()
    if index is None:
        # First team channel: start Dark, so LoRa bursts stay off
        # the public frequency.
        settings = mt.channel_settings(name, psk, mt.FULL_PRECISION, radio.channels.get(0, {}).get('raw', b''))
        msgs = [radio.channel_msg(0, settings, 'PRIMARY')]
        msgs += [radio.channel_msg(i, b'', 'DISABLED') for i, c in radio.channels.items()
                 if i != 0 and c['role'] != 'DISABLED']
    else:
        c = radio.channels[index]
        settings = mt.channel_settings(name, psk, mt.FULL_PRECISION, c['raw'])
        msgs = [radio.channel_msg(index, settings, c['role'])]
    radio.admin(*msgs)


def wipe(radio):
    """Forget the team: the radio goes back to the public LongFast channel only, so a lost
    or captured node can no longer read or send team traffic."""
    settings = mt.channel_settings('', mt.DEFAULT_PSK, mt.FULL_PRECISION, radio.channels.get(0, {}).get('raw', b''))
    msgs = [radio.channel_msg(0, settings, 'PRIMARY')]
    msgs += [radio.channel_msg(i, b'', 'DISABLED') for i, c in radio.channels.items()
             if i != 0 and c['role'] != 'DISABLED']
    radio.admin(*msgs)


# ---- fleet push: settings sent to every node on the team channel ----
def _hmac(key, msg):
    key = key.ljust(64, b'\0')
    inner = hashlib.sha256(bytes(b ^ 0x36 for b in key) + msg).digest()
    return hashlib.sha256(bytes(b ^ 0x5C for b in key) + inner).digest()


def fleet_key(psk):
    return hashlib.sha256(b'field-extensions fleet' + psk).digest()


def fleet_encode(kind, body, psk, now):
    msg = json.dumps({'k': kind, 'id': os.urandom(4).hex(), 'at': int(now), **body},
                     separators=(',', ':')).encode()
    return FLEET_MAGIC + _hmac(fleet_key(psk), msg)[:8] + msg


def fleet_decode(packet, psk, now):
    """The command if it is signed with our team key and recent, else None. LoRa packets
    on the team channel are already encrypted; the tag keeps anything that merely
    reaches the port from changing settings, and the age limit stops replays."""
    if not psk or not packet.startswith(FLEET_MAGIC) or len(packet) < 12:
        return None
    tag, msg = packet[3:11], packet[11:]
    if _hmac(fleet_key(psk), msg)[:8] != tag:
        return None
    try:
        cmd = json.loads(msg)
    except ValueError:
        return None
    if not isinstance(cmd, dict) or cmd.get('k') not in ('profile', 'radio', 'team', 'wipe'):
        return None
    at = cmd.get('at')
    clocks_set = now > 1.7e9 and isinstance(at, int) and at > 1.7e9
    if clocks_set and abs(now - at) > FLEET_MAX_AGE_S:
        return None
    return cmd


# ---- map markers ----
def marker_encode(ev):
    """Compact marker (or delete) for LoRa, read only by other Field Extension nodes."""
    if ev.get('delete'):
        body = {'u': ev['uid'], 'd': 1}
    else:
        body = {'u': ev['uid'], 't': ev['type'][:24], 'c': (ev.get('callsign') or '')[:40],
                'a': round(ev['lat'], 6), 'o': round(ev['lon'], 6)}
        if ev.get('hae') is not None and abs(ev['hae']) < 9e5:
            body['h'] = round(ev['hae'])
    data = MARKER_MAGIC + json.dumps(body, separators=(',', ':')).encode()
    return data if len(data) <= 200 else None


def marker_decode(packet):
    if not packet.startswith(MARKER_MAGIC):
        return None
    try:
        b = json.loads(packet[3:])
    except ValueError:
        return None
    if not isinstance(b, dict) or not isinstance(b.get('u'), str):
        return None
    if b.get('d'):
        return {'kind': 'delete', 'uid': b['u']}
    try:
        return {'kind': 'marker', 'uid': b['u'], 'type': str(b['t']), 'callsign': str(b.get('c') or ''),
                'lat': float(b['a']), 'lon': float(b['o']), 'hae': b.get('h')}
    except (KeyError, TypeError, ValueError):
        return None


# ---- guest and team radios ----
class Provisioner:
    """Copies this node's LoRa settings and channels to other radios plugged into the
    node (a friend's Meshtastic, or the team's radios on a USB hub), one at a time."""

    STEPS = ['Connecting', 'Reading its settings', 'Writing team settings', 'Restarting the radio', 'Checking']

    def __init__(self, source, radio_factory=None, timeout=45.0, clock=time.time):
        self.source = source
        self.factory = radio_factory or (lambda path: mt.Radio(path))
        self.timeout = timeout
        self.clock = clock
        self.state = {'running': False, 'radios': []}
        self.thread = None

    def start(self, jobs):
        if self.state['running']:
            raise ValueError('Already setting up a radio')
        self.state = {'running': True, 'radios': [{'path': j['path'], 'name': j.get('long_name') or j['path'],
                                                     'step': 0, 'steps': len(self.STEPS), 'status': 'waiting',
                                                     'error': None} for j in jobs]}
        self.thread = threading.Thread(target=self._run, args=(jobs,), daemon=True)
        self.thread.start()

    def _wait(self, cond, timeout):
        end = self.clock() + timeout
        while self.clock() < end:
            if cond():
                return True
            time.sleep(0.5)
        return cond()

    def _run(self, jobs):
        try:
            for job, st in zip(jobs, self.state['radios']):
                try:
                    self._one(job, st)
                    st['status'] = 'done'
                except Exception as e:
                    st['status'], st['error'] = 'failed', str(e) or e.__class__.__name__
        finally:
            self.state['running'] = False

    def messages(self, guest, long_name=None, short_name=None):
        src = self.source
        msgs = []
        if long_name or short_name:
            msgs.append(guest.owner_msg(long_name or short_name, (short_name or long_name)[:4]))
        lora = pb.decode(src.lora_raw or b'')
        # Keep the guest's own transmit power: radios differ in what they can do.
        copy = {f: b''.join(pb.key(f, w) + (pb.varint(len(v)) + v if w == pb.LEN else
                                             pb.varint(v) if w == pb.VARINT else v) for w, v in lora.get(f, []))
                for f in (1, 2, 7, 8, 11)}
        msgs.append(guest.lora_msg(guest.lora_raw, copy))
        for i in range(8):
            c = src.channels.get(i)
            if c and c['role'] != 'DISABLED':
                msgs.append(guest.channel_msg(i, c['raw'], c['role']))
            elif (guest.channels.get(i) or {}).get('role', 'DISABLED') != 'DISABLED':
                msgs.append(guest.channel_msg(i, b'', 'DISABLED'))
        msgs.append(guest.position_msg(GUEST_POSITION_S, gps=True))
        return msgs

    def _one(self, job, st):
        if os.path.realpath(job['path']) == os.path.realpath(self.source.path):
            raise ValueError("That is this node's own radio")
        st['status'] = 'working'
        guest = self.factory(job['path'])
        t = threading.Thread(target=guest.run_forever, daemon=True)
        t.start()
        try:
            st['step'] = 1
            if not self._wait(lambda: guest.connected, self.timeout):
                raise OSError(guest.error or 'The radio did not answer')
            st['step'] = 2
            if guest.lora_raw is None:
                raise OSError('Could not read its LoRa settings')
            st['step'] = 3
            guest.admin(*self.messages(guest, job.get('long_name'), job.get('short_name')))
            st['step'] = 4
            # The radio saves and restarts; the link drops and comes back by itself.
            self._wait(lambda: not guest.connected, 15)
            if not self._wait(lambda: guest.connected, self.timeout):
                raise OSError('The radio did not come back after restarting')
            st['step'] = 5
            ok = (guest.team_psk() == self.source.team_psk() and guest.lora
                  and self.source.lora and guest.lora['modem_preset'] == self.source.lora['modem_preset']
                  and guest.lora['region'] == self.source.lora['region'])
            if not ok:
                raise OSError('Settings did not stick. Try again.')
        finally:
            guest.stop.set()
            guest.close()
