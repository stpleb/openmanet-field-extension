"""LoRa fallback: carries ATAK positions and chat over the node's Meshtastic radio when
the Wi-Fi/HaLow mesh is gone, and gives the web UI and plugin control of the radio.

Runs as its own process (procd instance "lora"), the only owner of the radio's serial
port. The web server talks to it over a root-only Unix socket with one JSON line per
request. Modes: "auto" sends over LoRa only while the node has no live mesh neighbor,
"always" sends regardless, "off" leaves the radio alone. Packets heard on LoRa are
always given to this node's phones (unless off).

LoRa is slow (LongFast is about 1 kbit/s), so positions go at most once a minute per
phone, chat goes first, map markers go only when placed, moved or deleted, and photos
and voice stay on the mesh.
"""
import json
import os
import re
import socket
import threading
import time
from pathlib import Path

from . import lora_admin, meshtastic as mt, pb, radios, tak
from .core import Node, run

STATE = Path('/etc/field-extensions')
SETTINGS = STATE / 'lora.json'
SOCKET = '/var/run/field-lora.sock'
MODES = ('auto', 'always', 'off')
DEFAULTS = {'mode': 'auto', 'device': None, 'pli_interval_s': 60, 'node_radios_in_atak': False}
RADIO_ID_FILE = Path('/var/run/field-lora-radio')   # this node's radio number, for /api/field/hello
FLEET_CHECK_S = 60
SEND_GAP_S = 3.0          # spacing between our transmissions, so the channel is shared
MESH_CHECK_S = 5.0
CHAT_DEDUPE_S = 120
LORA_ONLY_S = 600         # a teammate heard on LoRa counts for 10 minutes
PLAIN_USER_S = 1800       # a plain Meshtastic user heard on the team channel gets our chat for 30 minutes
MESH_SEEN_S = 120         # ...unless the same ATAK user was seen on the mesh within 2 minutes
LORA_TAG = b'_fe_lora'   # marks CoT this node re-broadcast from LoRa
MAX_TEXT = 200
FLEET_REPEATS = 3         # a fleet change is sent three times (LoRa has no delivery receipt)
FLEET_APPLY_S = 15        # receivers switch this long after first hearing it
FLEET_SELF_S = 25         # the sender switches after its copies have gone out
PENDING_FILE = STATE / 'state' / 'lora-pending.json'   # fleet changes waiting to apply
PENDING_MAX_LATE_S = 600  # after a restart, a missed change is still applied this late
WATCH_S = 20              # radio health check interval


def plain_line(callsign, text):
    """Team chat as a plain Meshtastic text: "Callsign: message"."""
    return ('%s: %s' % (callsign or 'ATAK', text or ''))[:MAX_TEXT]


def fleet_watch(node, radio, bridge, stop, get_json=None, id_file=None):
    """Publish this node's radio number and learn the other nodes' radios, so their
    positions are not shown in ATAK as if they were people."""
    from .fleet_update import PORT, http_json
    get_json, id_file = get_json or http_json, id_file or RADIO_ID_FILE
    while not stop.is_set():
        try:
            if getattr(radio, 'my_num', None) is not None:
                Path(id_file).write_text('%d\n' % radio.my_num)
            for n in node.mesh_nodes().get('nodes', []):
                if n.get('self') or not n.get('ip'):
                    continue
                try:
                    num = get_json('http://%s:%d/api/field/hello' % (n['ip'], PORT)).get('lora_radio')
                except Exception:
                    continue
                if isinstance(num, int) and not isinstance(num, bool):
                    bridge.fleet_radios.add(num)
        except Exception:
            pass
        stop.wait(FLEET_CHECK_S)


def load_settings(path=SETTINGS):
    try:
        data = json.loads(Path(path).read_text())
    except (OSError, ValueError):
        data = {}
    out = dict(DEFAULTS)
    if data.get('mode') in MODES:
        out['mode'] = data['mode']
    if isinstance(data.get('device'), str) and (data['device'].startswith('/dev/') or mt.tcp_target(data['device'])):
        out['device'] = data['device']
    if isinstance(data.get('pli_interval_s'), int) and 30 <= data['pli_interval_s'] <= 3600:
        out['pli_interval_s'] = data['pli_interval_s']
    if isinstance(data.get('node_radios_in_atak'), bool):
        out['node_radios_in_atak'] = data['node_radios_in_atak']
    return out


def save_settings(settings, path=SETTINGS):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(settings) + '\n')
    tmp.replace(path)


def find_device(root=Path('/')):
    """The USB radio when exactly one is plugged in; with two or more the node does not
    guess (a guest radio being set up must never become this node's radio)."""
    devices = lora_admin.list_devices(root)
    return devices[0]['path'] if len(devices) == 1 else None


def wifi_radio(leases='/tmp/dhcp.leases'):
    """'tcp:<ip>' of a Meshtastic radio on this node's Wi-Fi (its DHCP host name starts
    with Meshtastic), for a node whose USB is unusable. None when there is none or more
    than one (the node does not guess)."""
    try:
        lines = Path(leases).read_text().splitlines()
    except OSError:
        return None
    found = [p[2] for p in (l.split() for l in lines) if len(p) >= 4 and p[3].lower().startswith('meshtastic')]
    return 'tcp:' + found[0] if len(found) == 1 else None


def pick_device(settings, root=Path('/'), save=save_settings, leases='/tmp/dhcp.leases'):
    """(device, choices). Remembers the radio it finds alone, by its stable by-id name,
    so a second radio plugged in later does not make it hesitate. A radio set by
    network address (tcp:host) is used as is; with no USB radio at all, a single
    Meshtastic radio on this node's Wi-Fi is used (not saved, its address can change)."""
    if settings['device'] and (mt.tcp_target(settings['device']) or Path(settings['device']).exists()):
        return settings['device'], []
    devices = lora_admin.list_devices(root)
    likely = [d for d in devices if any(h in d['name'].lower() for h in lora_admin.SERIAL_HINTS)
              and not any(g in d['name'].lower() for g in lora_admin.NOT_RADIO)]
    if len(devices) == 1 or len(likely) == 1:
        settings['device'] = (likely or devices)[0]['path']
        save(settings)
        return settings['device'], devices
    if not devices:
        wifi = wifi_radio(leases)
        if wifi:
            return wifi, []
    return None, devices


class Bridge:
    def __init__(self, radio, send_multicast, mesh_up, settings, clock=time.time, save=save_settings,
                 pending_file=None):
        self.radio = radio
        self.pending_file = pending_file       # None: fleet changes are not kept over a restart
        self.send_multicast = send_multicast   # (group, port, bytes)
        self.mesh_up = mesh_up                 # () -> bool
        self.settings = settings
        self.clock = clock
        self.save = save
        self.lock = threading.Lock()
        self.queue = []                        # [(priority, key, payload, portnum, queued_at)]
        self.last_pli = {}                     # uid -> time sent
        self.recent_chat = {}                  # (uid, text) -> time
        self.last_marker = {}                  # uid -> packet last sent
        self.mesh = None
        self.counters = {'sent_positions': 0, 'sent_chats': 0, 'sent_markers': 0, 'sent_settings': 0,
                         'heard_positions': 0, 'heard_chats': 0, 'heard_markers': 0, 'heard_radios': 0}
        self.log = []                          # last messages for the web page
        self.last_send = 0.0
        self.scheduled = []                    # [(due, label, fn)] settings changes waiting
        self.changes = []                      # last applied settings changes
        self.fleet_seen = {}                   # fleet command id -> time
        self.health = {'ping_ms': None, 'checked': None, 'fails': 0, 'repairs': []}
        self.traces = {}                       # node id -> traceroute result
        self.choices = []                      # radios to choose from when there are several
        self.provisioner = None
        self.restart = lambda: None            # ask the service to restart (new radio chosen)
        self.chat_notes = {}                   # queued chat key -> its log entry
        self.ack_wait = {}                     # packet id -> log entry waiting for delivery
        if hasattr(radio, 'on_ack'):
            radio.on_ack = self.acked
        self.lora_heard = {}                   # uid -> (time, callsign): team members heard on LoRa
        self.mesh_seen = {}                    # uid -> time: ATAK users heard on the mesh
        self.fleet_radios = set()              # radio numbers of other Field Extension nodes
        self.plain_users = {}                  # radio num -> time: plain Meshtastic texters on the team channel
        self.chat_seen = {}                    # "callsign: text" -> time, chats this node already has

    # ---- decisions ----
    @property
    def mode(self):
        return self.settings['mode']

    def sending(self):
        if self.mode == 'off':
            return False
        return self.mode == 'always' or self.mesh is False or bool(self.lora_only())

    def lora_only(self):
        """Teammates heard on LoRa recently but not on the mesh: a phone with only a
        Meshtastic radio. While there are any, AUTO keeps sending so they still see us."""
        now = self.clock()
        with self.lock:
            self.lora_heard = {u: v for u, v in self.lora_heard.items() if now - v[0] <= LORA_ONLY_S}
            self.mesh_seen = {u: t for u, t in self.mesh_seen.items() if now - t <= MESH_SEEN_S}
            return sorted(v[1] for u, v in self.lora_heard.items() if u not in self.mesh_seen)

    def plain_listening(self):
        """A plain Meshtastic user texted on the team channel lately: copy team chat to
        them as plain text, which the Meshtastic app shows (it ignores ATAK packets)."""
        now = self.clock()
        with self.lock:
            self.plain_users = {u: t for u, t in self.plain_users.items() if now - t <= PLAIN_USER_S}
            return bool(self.plain_users)

    def saw_chat(self, ev):
        """Remember a chat this node has (from its phones, the mesh or ATAK-over-LoRa), so
        the plain-text copy another node sends of it is not shown twice."""
        chat = ev.get('chat') or {}
        line = plain_line(chat.get('sender_callsign') or ev.get('callsign'), chat.get('text'))
        now = self.clock()
        with self.lock:
            self.chat_seen = {k: t for k, t in self.chat_seen.items() if now - t < CHAT_DEDUPE_S}
            self.chat_seen[line] = now

    def heard_on_lora(self, uid, callsign):
        with self.lock:
            self.lora_heard[uid] = (self.clock(), callsign or uid)

    def on_mesh(self, data):
        """Any ATAK packet on the mesh (any node's phones). Our own LoRa re-broadcasts,
        and other nodes', carry LORA_TAG and do not count."""
        if LORA_TAG in data:
            return
        ev = tak.parse(data)
        if ev and ev.get('uid'):
            with self.lock:
                self.mesh_seen[ev['uid']] = self.clock()
            if tak.kind(ev) == 'chat':
                self.saw_chat(ev)

    def team(self):
        f = getattr(self.radio, 'team_channel', None)
        i = f() if f else None
        return 0 if i is None else i

    def note(self, direction, ev, text=None):
        entry = {'time': int(self.clock()), 'direction': direction,
                 'callsign': ev.get('callsign') or ev.get('uid'),
                 'kind': ev.get('kind'), 'text': text}
        self.log.append(entry)
        del self.log[:-20]
        return entry

    def acked(self, packet_id, ok, reason=0):
        """A Routing reply for a chat we sent with want_ack: on a broadcast, ok means at
        least one other radio passed it on (Meshtastic's implicit ack)."""
        entry = self.ack_wait.pop(packet_id, None)
        if entry is not None:
            entry['delivered'] = bool(ok)

    def _queue(self, prio, key_, packet, portnum=mt.ATAK_APP):
        self.queue = [q for q in self.queue if q[1] != key_]
        self.queue.append((prio, key_, packet, portnum, self.clock()))

    # ---- phones -> LoRa ----
    def from_phones(self, data):
        ev = tak.parse(data)
        if ev is None or self.mode == 'off':
            return
        k = tak.kind(ev)
        if k == 'chat':
            self.saw_chat(ev)
            if self.plain_listening():
                self.queue_plain(ev)
        if not self.sending():
            return
        if k in ('marker', 'delete'):
            uid = ev['link_uid'] if k == 'delete' else ev['uid']
            packet = lora_admin.marker_encode({**ev, 'uid': uid, 'delete': k == 'delete'})
            with self.lock:
                if packet is None or self.last_marker.get(uid) == packet:
                    return  # ATAK repeats markers; only changes go over LoRa
                self.last_marker[uid] = packet
                self._queue(1, ('marker', uid), packet, mt.PRIVATE_APP)
            return
        packet = tak.to_takpacket(ev) if k else None
        if packet is None:
            return
        now = self.clock()
        with self.lock:
            if k == 'pli':
                if now - self.last_pli.get(ev['uid'], 0) < self.settings['pli_interval_s']:
                    return
                self.last_pli[ev['uid']] = now
                # Only the newest position per phone waits in the queue.
                self._queue(1, ('pli', ev['uid']), packet)
            else:
                dedupe = (ev['chat'].get('sender_uid') or ev['uid'], ev['chat']['text'])
                self.recent_chat = {key: t for key, t in self.recent_chat.items() if now - t < CHAT_DEDUPE_S}
                if dedupe in self.recent_chat:
                    return
                self.recent_chat[dedupe] = now
                self._queue(0, ('chat', dedupe), packet)
                entry = self.note('sent', {**ev, 'kind': 'chat'}, ev['chat']['text'])
                entry['delivered'] = None
                self.chat_notes[('chat', dedupe)] = entry

    def queue_plain(self, ev):
        chat = ev['chat']
        line = plain_line(chat.get('sender_callsign') or ev.get('callsign'), chat.get('text'))
        with self.lock:
            self._queue(0, ('text', line), line.encode()[:MAX_TEXT * 2], mt.TEXT_APP)

    def pump(self):
        """Send the most urgent queued packet if the gap since the last one has passed."""
        now = self.clock()
        with self.lock:
            if not self.queue or now - self.last_send < SEND_GAP_S or not self.radio.connected:
                return False
            self.queue.sort(key=lambda q: (q[0], q[4]))
            prio, key_, packet, portnum, _ = self.queue.pop(0)
        if key_[0] == 'chat':
            packet_id = self.radio.send(portnum, packet, channel=self.team(), want_ack=True)
            entry = self.chat_notes.pop(key_, None)
            if entry is not None and packet_id is not None:
                self.ack_wait[packet_id] = entry
                self.ack_wait = dict(list(self.ack_wait.items())[-20:])
        else:
            self.radio.send(portnum, packet, channel=self.team())
        self.last_send = now
        name = {'chat': 'sent_chats', 'text': 'sent_chats', 'pli': 'sent_positions', 'marker': 'sent_markers', 'fleet': 'sent_settings'}
        self.counters[name[key_[0]]] += 1
        return True

    # ---- LoRa -> phones ----
    def from_radio(self, portnum, payload, info):
        if self.mode == 'off':
            return
        on_team = info.get('channel', 0) == self.team()
        if portnum == mt.PRIVATE_APP:
            if on_team:
                self.from_fleet(payload)
            return
        if portnum == mt.POSITION_APP:
            return self.radio_position(payload, info, on_team)
        if portnum == mt.ATAK_APP:
            if not on_team:
                return
            ev = tak.from_takpacket(payload)
        elif portnum == mt.TEXT_APP:
            # A plain Meshtastic user typed a message on the team channel: show it in All
            # Chat Rooms. Public-channel chatter from strangers stays out of team chat.
            if not on_team:
                return
            text = payload.decode('utf-8', 'replace')
            with self.lock:
                if text in self.chat_seen:
                    return   # another node's plain copy of a chat we already have
                self.plain_users[info.get('from')] = self.clock()
            sender = (self.radio.nodes.get(info.get('from')) or {})
            node_id = sender.get('id') or '!%08x' % (info.get('from') or 0)
            ev = {'kind': 'chat', 'uid': 'MESHTASTIC-' + node_id.lstrip('!'),
                  'callsign': sender.get('long_name') or sender.get('short_name') or node_id,
                  'chat': {'text': payload.decode('utf-8', 'replace')[:MAX_TEXT],
                           'room': tak.ALL_CHAT, 'room_id': tak.ALL_CHAT}}
        else:
            return
        if not ev:
            return
        cot = tak.to_cot(ev, now=self.clock())
        if portnum == mt.ATAK_APP:
            # Only ATAK users count: node radios also send plain positions on the team
            # channel, and those must not keep AUTO sending forever.
            self.heard_on_lora(ev.get('uid'), ev.get('callsign'))
        if ev['kind'] == 'chat':
            self.saw_chat(ev)
        if ev['kind'] == 'pli':
            self.send_multicast(tak.SA_GROUP, tak.SA_PORT, cot)
            self.counters['heard_positions'] += 1
        else:
            self.send_multicast(tak.CHAT_GROUP, tak.CHAT_PORT, cot)
            self.counters['heard_chats'] += 1
            self.note('heard', ev, ev['chat']['text'])

    def radio_position(self, payload, info, on_team):
        """Plain Meshtastic radios (a guest's, or strangers' on the public channel) on the
        phones' map: team radios as friendly, public ones as neutral."""
        sender = info.get('from')
        if sender is None or sender == getattr(self.radio, 'my_num', None):
            return
        p = pb.decode(payload)
        lat, lon = pb.as_s32(pb.first(p, 1)), pb.as_s32(pb.first(p, 2))
        if not lat or not lon:
            return
        if sender in self.fleet_radios and not self.settings.get('node_radios_in_atak'):
            return   # another node's own radio: the node is not a person, keep it off ATAK
        node = self.radio.nodes.get(sender) or {}
        node_id = node.get('id') or '!%08x' % sender
        ev = {'uid': 'MESHTASTIC-' + node_id.lstrip('!'), 'lat': lat / 1e7, 'lon': lon / 1e7,
              'hae': pb.as_int32(pb.first(p, 3)) if pb.first(p, 3) is not None else None,
              'callsign': node.get('long_name') or node.get('short_name') or node_id}
        if on_team:
            cot = tak.to_cot({**ev, 'kind': 'pli'}, now=self.clock(), stale_s=900)
        else:
            cot = tak.marker_cot({**ev, 'kind': 'marker', 'type': 'a-n-G'}, now=self.clock(), stale_s=1800)
        self.send_multicast(tak.SA_GROUP, tak.SA_PORT, cot)
        self.counters['heard_radios'] += 1

    def from_fleet(self, payload):
        marker = lora_admin.marker_decode(payload)
        if marker is not None:
            self.send_multicast(tak.SA_GROUP, tak.SA_PORT, tak.marker_cot(marker, now=self.clock()))
            self.counters['heard_markers'] += 1
            return
        now = self.clock()
        cmd = lora_admin.fleet_decode(payload, self.radio.team_psk(), time.time())
        if cmd is None or cmd['id'] in self.fleet_seen:
            return
        self.fleet_seen = {k: t for k, t in self.fleet_seen.items() if now - t < 2 * lora_admin.FLEET_MAX_AGE_S}
        self.fleet_seen[cmd['id']] = now
        try:
            label, fn = self.fleet_action(cmd)
        except (ValueError, KeyError, TypeError):
            return
        # The sender repeats the command for a few seconds; everyone switches together.
        self.schedule(FLEET_APPLY_S, label + ' (from the team)', fn, cmd)

    def fleet_action(self, cmd):
        k = cmd['k']
        if k == 'profile':
            p = cmd['p']
            if p not in lora_admin.PROFILES:
                raise ValueError(p)
            return 'LoRa mode %s' % p.title(), lambda: lora_admin.apply_profile(self.radio, p)
        if k == 'radio':
            region, preset = cmd.get('r'), cmd.get('m')
            if (region is not None and region not in mt.REGIONS[1:]) or (preset is not None and preset not in mt.PRESETS):
                raise ValueError('radio')
            return 'Radio %s' % ' '.join(x for x in (region, preset) if x), \
                lambda: self.radio.set_lora(region=region, preset=preset)
        if k == 'wipe':
            me = getattr(self.radio, 'my_num', None)
            if me is None or cmd['t'] != '!%08x' % me:
                raise ValueError('not for this node')
            return 'Wipe team key (asked by the team)', self.wipe_here
        if k == 'team':
            name, psk = cmd['n'], bytes.fromhex(cmd['s'])
            if not (1 <= len(name.encode()) <= 11 and len(psk) == 32):
                raise ValueError('team')
            return 'Team channel %s' % name, lambda: lora_admin.apply_team(self.radio, name, psk)
        raise ValueError(k)

    def wipe_here(self):
        lora_admin.wipe(self.radio)
        for name in ('devices.json',):   # remembered sign-ins: nobody stays signed in
            try:
                (STATE / 'state' / name).unlink()
            except OSError:
                pass
        self.changes.append({'time': int(self.clock()), 'what': 'Team key wiped', 'error': None})

    # ---- settings changes, now or together with the fleet ----
    def schedule(self, delay, label, fn, cmd=None):
        """Run fn after delay. A fleet command (cmd) is also written to disk until it has
        run, so a node restarted in between (an install, a reboot) still applies it."""
        with self.lock:
            self.scheduled.append((self.clock() + delay, label, fn))
            if cmd is not None:
                self._pending_write(self._pending_read() + [{'label': label, 'cmd': cmd,
                                                             'due': int(time.time() + delay)}])

    def _pending_read(self):
        if self.pending_file is None:
            return []
        try:
            data = json.loads(Path(self.pending_file).read_text())
        except (OSError, ValueError):
            return []
        return [e for e in data if isinstance(e, dict) and isinstance(e.get('cmd'), dict)] if isinstance(data, list) else []

    def _pending_write(self, entries):
        if self.pending_file is None:
            return
        path = Path(self.pending_file)
        try:
            if not entries:
                path.unlink(missing_ok=True)
                return
            path.parent.mkdir(parents=True, exist_ok=True)
            tmp = path.with_name(path.name + '.tmp')
            tmp.write_text(json.dumps(entries))
            os.chmod(tmp, 0o600)   # a team change holds the channel key
            tmp.replace(path)
        except OSError:
            pass

    def restore_pending(self):
        """After a restart: re-schedule fleet changes that had not run yet (or ran late by
        less than PENDING_MAX_LATE_S). Returns how many."""
        now, kept = time.time(), []
        for e in self._pending_read():
            due = e.get('due') if isinstance(e.get('due'), (int, float)) else now
            if now - due > PENDING_MAX_LATE_S:
                continue
            try:
                label, fn = self.fleet_action(e['cmd'])
            except (ValueError, KeyError, TypeError):
                continue
            with self.lock:
                self.scheduled.append((self.clock() + max(5, due - now), e.get('label') or label, fn))
            kept.append(e)
        self._pending_write(kept)
        return len(kept)

    def tick(self):
        now = self.clock()
        with self.lock:
            if not getattr(self.radio, 'connected', True):
                return   # keep waiting changes until the radio is back
            due = [s for s in self.scheduled if s[0] <= now]
            self.scheduled = [s for s in self.scheduled if s[0] > now]
            if due and self.pending_file is not None:
                labels = {s[1] for s in due}
                self._pending_write([e for e in self._pending_read() if e.get('label') not in labels])
        for _, label, fn in due:
            try:
                fn()
                result = None
            except Exception as e:
                result = str(e) or e.__class__.__name__
            self.changes.append({'time': int(now), 'what': label, 'error': result})
            del self.changes[:-10]

    def change(self, label, fn, fleet=None):
        """Apply a settings change here, or with fleet=(kind, body) first send it to every
        node on the team channel (three times) and switch together a little later."""
        if not fleet:
            fn()
            self.changes.append({'time': int(self.clock()), 'what': label, 'error': None})
            return {'ok': True, 'note': 'The radio restarts to apply this (about 10 seconds).'}
        psk = self.radio.team_psk()
        if not psk:
            return {'ok': False, 'error': 'Pushing to the fleet needs a team channel first'}
        with self.lock:
            for i in range(FLEET_REPEATS):
                packet = lora_admin.fleet_encode(fleet[0], fleet[1], psk, time.time())
                self.queue.append((-1, ('fleet', i), packet, mt.PRIVATE_APP, self.clock()))
        self.schedule(FLEET_SELF_S, label, fn, {'k': fleet[0], **fleet[1]})
        return {'ok': True, 'note': 'Sending to every node on the team channel. All radios switch in about %d seconds.'
                % FLEET_SELF_S}

    # ---- control (web UI / plugin) ----
    def warnings(self, st):
        out = []
        if self.choices and not st.get('connected'):
            out.append('Two or more radios are plugged in. Choose which one is this node\'s radio.')
        elif st.get('port') and not st.get('connected'):
            out.append('Reconnecting to the radio.')
        if self.health['fails']:
            out.append('The radio is not responding. The node will retry, then reset its USB port.')
        if st.get('disconnects_10min', 0) >= 3:
            out.append('The radio keeps disconnecting. This is usually a bad USB cable.')
        oldest = min((q[4] for q in self.queue if q[0] >= 0), default=None)
        if len(self.queue) > 6 or (oldest is not None and self.clock() - oldest > 90):
            out.append('LoRa is backed up: messages are waiting to send.')
        return out

    def presets(self, st):
        lora_ = st.get('lora') or {}
        region = lora_.get('region')
        name = ''
        if st.get('channels'):
            primary = st['channels'][0]
            name = primary['name'] if primary['security'] == 'private' else ''
        return [{'id': p, 'name': mt.PRESET_INFO[p][0], 'bandwidth_khz': mt.PRESET_INFO[p][1],
                 'fits': mt.preset_fits(region, p),
                 'frequency_mhz': mt.frequency_mhz(region, p, name, lora_.get('channel_num', 0))}
                for p in mt.PRESETS]

    def status(self):
        st = self.radio.status()
        now = self.clock()
        return {'available': True, 'mode': self.mode, 'mesh_up': self.mesh,
                'sending_over_lora': self.sending(), 'queued': len(self.queue),
                'lora_only': self.lora_only(),
                'pli_interval_s': self.settings['pli_interval_s'],
                'node_radios_in_atak': bool(self.settings.get('node_radios_in_atak')),
                'fleet_radios': len(self.fleet_radios),
                'counters': dict(self.counters),
                'recent': [{**e, 'acked': e['delivered']} if 'delivered' in e else dict(e) for e in self.log],
                'airtime': st.get('airtime') if isinstance(st, dict) else None, 'radio': st,
                'presets': self.presets(st), 'warnings': self.warnings(st),
                'health': {k: v for k, v in self.health.items()},
                'needs_setup': lora_admin.needs_setup(self.radio),
                'traces': dict(self.traces), 'choices': list(self.choices),
                'devices': self.devices(),
                'scheduled': [{'what': s[1], 'in_s': max(0, round(s[0] - now))} for s in self.scheduled],
                'changes': list(self.changes),
                'provisioning': self.provisioner.state if self.provisioner else None}

    def command(self, req):
        cmd = req.get('cmd')
        push = req.get('fleet') is True
        if cmd == 'status':
            return {'ok': True, **self.status()}
        if cmd == 'set_node_radios':
            if not isinstance(req.get('show'), bool):
                return {'ok': False, 'error': 'show must be true or false'}
            self.settings['node_radios_in_atak'] = req['show']
            self.save(self.settings)
            return {'ok': True, 'note': 'Node radios now show on ATAK maps.' if req['show'] else
                    'Node radios no longer show on ATAK maps (nodes still show in the app).'}
        if cmd == 'set_mode':
            if req.get('mode') not in MODES:
                return {'ok': False, 'error': 'mode must be auto, always or off'}
            self.settings['mode'] = req['mode']
            self.save(self.settings)
            return {'ok': True, 'mode': self.mode}
        if cmd == 'set_radio':
            region, preset = req.get('region'), req.get('preset')
            if region is not None and region not in mt.REGIONS[1:]:
                return {'ok': False, 'error': 'unknown region'}
            if preset is not None and preset not in mt.PRESETS:
                return {'ok': False, 'error': 'unknown modem preset'}
            if region is None and preset is None:
                return {'ok': False, 'error': 'nothing to change'}
            if preset is not None and not mt.preset_fits(region or (self.radio.status().get('lora') or {}).get('region'), preset):
                return {'ok': False, 'error': 'That speed is too wide for this region'}
            body = {k: v for k, v in (('r', region), ('m', preset)) if v is not None}
            return self.change('Radio %s' % ' '.join(body.values()),
                               lambda: self.radio.set_lora(region=region, preset=preset),
                               ('radio', body) if push else None)
        if cmd == 'set_profile':
            p = req.get('profile')
            if p not in lora_admin.PROFILES:
                return {'ok': False, 'error': 'profile must be open, covert or dark'}
            if getattr(self.radio, 'team_channel', lambda: None)() is None:
                return {'ok': False, 'error': 'Set a team channel and passphrase first'}
            return self.change('LoRa mode %s' % p.title(), lambda: lora_admin.apply_profile(self.radio, p),
                               ('profile', {'p': p}) if push else None)
        if cmd == 'set_channel':
            if req.get('security') == 'open':
                self.radio.set_primary_channel('', mt.DEFAULT_PSK)
                return {'ok': True, 'note': 'Public LongFast channel. Anyone with a Meshtastic radio can read it.'}
            name, words = req.get('name'), req.get('passphrase')
            if req.get('url') is not None:   # a scanned channel QR
                try:
                    name, psk = mt.team_from_url(req['url'])
                except ValueError as e:
                    return {'ok': False, 'error': str(e)}
            else:
                if not (isinstance(name, str) and 1 <= len(name.encode()) <= 11):
                    return {'ok': False, 'error': 'channel name must be 1 to 11 characters'}
                if not (isinstance(words, str) and 8 <= len(words) <= 128):
                    return {'ok': False, 'error': 'team passphrase must be at least 8 characters'}
                psk = mt.psk_from_passphrase(words)
            if not hasattr(self.radio, 'team_channel'):
                self.radio.set_primary_channel(name, psk)
                return {'ok': True, 'note': 'Private channel set. Use the same name and passphrase on every node.'}
            result = self.change('Team channel %s' % name, lambda: lora_admin.apply_team(self.radio, name, psk),
                                 ('team', {'n': name, 's': psk.hex()}) if push else None)
            if result['ok'] and not push:
                result['note'] = 'Team channel set. Use the same name and passphrase on every node.'
            return result
        if cmd == 'setup':
            region, preset, name, words = req.get('region'), req.get('preset') or 'LONG_FAST', req.get('name'), req.get('passphrase')
            profile = req.get('profile') or 'dark'
            if region not in mt.REGIONS[1:]:
                return {'ok': False, 'error': 'Choose your region'}
            if preset not in mt.PRESETS or not mt.preset_fits(region, preset):
                return {'ok': False, 'error': 'Choose a speed that fits this region'}
            if profile not in lora_admin.PROFILES:
                return {'ok': False, 'error': 'mode must be open, covert or dark'}
            if not (isinstance(name, str) and 1 <= len(name.encode()) <= 11):
                return {'ok': False, 'error': 'team channel name must be 1 to 11 characters'}
            if not (isinstance(words, str) and 8 <= len(words) <= 128):
                return {'ok': False, 'error': 'team passphrase must be at least 8 characters'}
            if getattr(self.radio, 'lora_raw', None) is None:
                return {'ok': False, 'error': 'Radio settings not loaded yet'}
            psk = mt.psk_from_passphrase(words)
            result = self.change('Radio set up (%s, %s, team %s)' % (region, preset, name),
                                 lambda: self.radio.admin(*lora_admin.setup_messages(self.radio, region, preset, name, psk, profile)))
            if result['ok']:
                result['note'] = 'Radio set up. It restarts once (about 10 seconds). Use the same team name and passphrase on every node.'
            return result
        if cmd == 'wipe':
            target = req.get('target')
            if target == 'self':
                self.wipe_here()
                return {'ok': True, 'note': 'Team key wiped. This radio is on the public channel only, and every '
                        'sign-in on this node was cleared.'}
            if not (isinstance(target, str) and re.match(r'^![0-9a-f]{8}$', target)):
                return {'ok': False, 'error': 'target must be "self" or a radio id like !8fa703c0'}
            psk = self.radio.team_psk()
            if not psk:
                return {'ok': False, 'error': 'No team channel to send the wipe on'}
            with self.lock:
                for i in range(FLEET_REPEATS):
                    packet = lora_admin.fleet_encode('wipe', {'t': target}, psk, time.time())
                    self.queue.append((-1, ('fleet', 'wipe%d' % i), packet, mt.PRIVATE_APP, self.clock()))
            return {'ok': True, 'note': 'Wipe sent to %s. It works only if that node is switched on and in LoRa '
                    'range. Then give the rest of the team a new key (see Team channel).' % target}
        if cmd == 'send_text':
            text = req.get('text')
            if not (isinstance(text, str) and 0 < len(text) <= MAX_TEXT):
                return {'ok': False, 'error': 'text must be 1 to 200 characters'}
            packet_id = self.radio.send(mt.TEXT_APP, text.encode(), channel=self.team(), want_ack=True)
            entry = self.note('sent', {'callsign': 'this node', 'kind': 'chat'}, text)
            entry['delivered'] = None
            if packet_id is not None:
                self.ack_wait[packet_id] = entry
            return {'ok': True}
        if cmd == 'clear_log':
            with self.lock:
                self.log, self.chat_notes, self.ack_wait = [], {}, {}
            return {'ok': True}
        if cmd == 'ping':
            ms = self.radio.ping(timeout=3.0)
            self.health.update({'ping_ms': ms, 'checked': int(self.clock())})
            return {'ok': ms is not None, 'ms': ms, 'error': None if ms is not None else 'The radio did not answer'}
        if cmd == 'trace':
            return self.trace(req.get('node'))
        if cmd == 'share':
            if not getattr(self.radio, 'channels', None):
                return {'ok': False, 'error': 'Radio settings not loaded yet'}
            return {'ok': True, 'url': self.radio.share_url(),
                    'note': 'This code contains the team key. Only show it to people on your team.'}
        if cmd == 'devices':
            return {'ok': True, 'devices': self.devices()}
        if cmd == 'set_device':
            paths = {d['path'] for d in lora_admin.list_devices()}
            if req.get('device') in (None, '', 'auto'):
                req = {**req, 'device': None}   # back to finding the radio by itself
            elif not mt.tcp_target(req.get('device')) and req.get('device') not in paths:
                return {'ok': False, 'error': 'That radio is not plugged in (or give tcp:<address> for a radio on Wi-Fi)'}
            self.settings['device'] = req['device']
            self.save(self.settings)
            self.restart()
            return {'ok': True, 'note': 'Switching to that radio.'}
        if cmd == 'provision':
            return self.provision(req)
        return {'ok': False, 'error': 'unknown command'}

    def devices(self):
        mine = os.path.realpath(self.radio.path) if getattr(self.radio, 'path', None) else None
        return [{**d, 'this_node': os.path.realpath(d['path']) == mine} for d in lora_admin.list_devices()]

    def trace(self, node_id):
        nodes = {n.get('id'): num for num, n in getattr(self.radio, 'nodes', {}).items()}
        if node_id not in nodes:
            return {'ok': False, 'error': 'Unknown radio'}
        if (self.traces.get(node_id) or {}).get('state') == 'running':
            return {'ok': True}
        self.traces[node_id] = {'state': 'running', 'started': int(self.clock())}

        def go():
            try:
                r = self.radio.traceroute(nodes[node_id])
            except OSError:
                r = None
            self.traces[node_id] = ({'state': 'done', **r} if r else {'state': 'failed'})
            self.traces[node_id]['time'] = int(self.clock())
        threading.Thread(target=go, daemon=True).start()
        return {'ok': True, 'note': 'Pinging over LoRa. This can take up to a minute.'}

    def provision(self, req):
        if not getattr(self.radio, 'connected', False) or self.radio.team_channel() is None:
            return {'ok': False, 'error': "This node's radio needs to be connected with a team channel first"}
        jobs = req.get('radios')
        if not isinstance(jobs, list) or not 1 <= len(jobs) <= 8:
            return {'ok': False, 'error': 'Choose 1 to 8 radios'}
        paths = {d['path'] for d in lora_admin.list_devices()}
        clean = []
        for j in jobs:
            if not isinstance(j, dict) or j.get('path') not in paths:
                return {'ok': False, 'error': 'That radio is not plugged in'}
            long_name, short = j.get('long_name') or '', j.get('short_name') or ''
            if not (isinstance(long_name, str) and isinstance(short, str) and len(long_name) <= 36 and len(short) <= 4):
                return {'ok': False, 'error': 'Name up to 36 characters, short name up to 4'}
            clean.append({'path': j['path'], 'long_name': long_name.strip(), 'short_name': short.strip()})
        if self.provisioner is None:
            self.provisioner = lora_admin.Provisioner(self.radio)
        try:
            self.provisioner.start(clean)
        except ValueError as e:
            return {'ok': False, 'error': str(e)}
        return {'ok': True}


class NoRadio:
    connected = False
    nodes = {}
    path = None

    def __init__(self, error='No LoRa radio plugged in'):
        self.error = error

    def status(self):
        return {'connected': False, 'error': self.error, 'heard': []}

    def send(self, *a, **k):
        raise OSError(self.error)

    set_lora = set_primary_channel = send

    def team_psk(self):
        return None

    def ping(self, timeout=None):
        return None


# ---- process plumbing ----
def usable(ip):
    """True when this address is configured on an interface right now."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.bind((ip, 0))
        return True
    except OSError:
        return False


def phone_address():
    """Where this node's phones are. On OpenMANET 1.8 the access point joins the mesh
    bridge (network.ahwlan, Node01 10.41.0.1) and network.lan is the WAN-side Ethernet;
    on other layouts it is network.lan. Same order as the init script."""
    for name in ('ahwlan', 'lan'):
        r = run(['uci', '-q', 'get', 'network.%s.ipaddr' % name])
        ip = (r.get('output') or '').strip().split('/')[0]
        if ip and usable(ip):
            return ip
    return None


class LocalPhones:
    """Addresses this node handed out by DHCP. On OpenMANET the phone Wi-Fi is bridged
    into the mesh, so ATAK traffic from other nodes' phones arrives here too; only our
    own phones are sent over LoRa (each node speaks for its own)."""

    def __init__(self, path='/tmp/dhcp.leases', clock=time.time):
        self.path, self.clock, self.at, self.ips = Path(path), clock, 0, None

    def __contains__(self, ip):
        if self.clock() - self.at > 30:
            self.at = self.clock()
            try:
                self.ips = {line.split()[2] for line in self.path.read_text().splitlines() if len(line.split()) > 2}
            except OSError:
                self.ips = None
        return self.ips is None or ip in self.ips


def udp_from_raw(packet, group, port):
    """Payload of a raw IPv4 packet if it is UDP to group:port, else None."""
    if len(packet) < 28 or packet[0] >> 4 != 4 or packet[9] != socket.IPPROTO_UDP:
        return None
    if packet[16:20] != socket.inet_aton(group):
        return None
    if int.from_bytes(packet[6:8], 'big') & 0x3FFF:
        return None  # a fragment; ATAK's positions fit in one packet
    ihl = (packet[0] & 0x0F) * 4
    udp = packet[ihl:]
    if len(udp) < 8 or int.from_bytes(udp[2:4], 'big') != port:
        return None
    return udp[8:int.from_bytes(udp[4:6], 'big')]


def multicast_listener(group, port, iface_ip, handler, stop, local=None, seen=None):
    membership = socket.inet_aton(group) + socket.inet_aton(iface_ip or '0.0.0.0')
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    raw = False
    try:
        s.bind(('', port))
        s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, membership)
    except OSError as e:
        s.close()
        # OpenMANET's own daemon holds the ATAK position port (6969) without sharing it.
        # A raw socket still receives a copy of every UDP packet delivered to this node.
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_UDP)
            s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, membership)
            raw = True
            print('LoRa: %s:%d is in use (%s); listening with a raw socket' % (group, port, e), flush=True)
        except OSError as e2:
            print('LoRa: cannot listen for phones on %s:%d: %s' % (group, port, e2), flush=True)
            return
    s.settimeout(1.0)
    own = {iface_ip}
    while not stop.is_set():
        try:
            data, addr = s.recvfrom(65535)
        except socket.timeout:
            continue
        if raw:
            data = udp_from_raw(data, group, port)
            if data is None:
                continue
        if addr[0] in own:
            continue  # our own re-broadcast of LoRa traffic
        if seen is not None:
            try:
                seen(data)
            except Exception:
                pass
        if local is not None and addr[0] not in local:
            continue  # another node's phone: that node speaks for it
        try:
            handler(data)
        except Exception:
            pass


def tagged(send):
    """Mark what we re-broadcast from LoRa, so other nodes do not take it as mesh traffic."""
    def out(group, port, data):
        b = data.encode() if isinstance(data, str) else data
        return send(group, port, b.replace(b'</detail>', b'<' + LORA_TAG + b'/></detail>', 1))
    return out


def multicast_sender(iface_ip):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 0)
    if iface_ip:
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(iface_ip))
    return lambda group, port, data: s.sendto(data, (group, port))


def control_server(bridge, stop, path=SOCKET):
    try:
        os.unlink(path)
    except OSError:
        pass
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    old = os.umask(0o077)
    try:
        s.bind(path)
    finally:
        os.umask(old)
    s.listen(4)
    s.settimeout(1.0)
    while not stop.is_set():
        try:
            conn, _ = s.accept()
        except socket.timeout:
            continue
        with conn:
            conn.settimeout(5)
            try:
                line = conn.makefile('rb').readline(8192)
                reply = bridge.command(json.loads(line))
            except Exception as e:
                reply = {'ok': False, 'error': 'LoRa service error: %s' % (str(e) or e.__class__.__name__)}
            conn.sendall(json.dumps(reply).encode() + b'\n')


def request(req, path=SOCKET, timeout=5.0):
    """Client side, used by the web server."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect(path)
        s.sendall(json.dumps(req).encode() + b'\n')
        return json.loads(s.makefile('rb').readline(1 << 20))
    finally:
        s.close()


def watchdog(radio, bridge, stop, device):
    """Ping the radio; if it stops answering, wake its API again, then reset its USB
    port. Also resets the port when the radio has been gone for a while but its device
    is still there (a hung USB link)."""
    gone_since, last_reset = None, 0.0
    usb = str(device).startswith('/dev/')   # a radio on Wi-Fi has no USB port to reset
    while not stop.wait(WATCH_S):
        now = time.time()
        if radio.port is None:
            gone_since = gone_since or now
            if now - gone_since > 90 and usb and os.path.exists(device) and now - last_reset > 300:
                last_reset = now
                fixed = lora_admin.usb_reset(device)
                bridge.health['repairs'].append({'time': int(now), 'action': 'USB port reset' if fixed else 'USB reset not possible'})
            continue
        gone_since = None
        if not radio.config_complete:
            continue
        ms = radio.ping()
        bridge.health.update({'ping_ms': ms, 'checked': int(now)})
        if ms is not None:
            bridge.health['fails'] = 0
            continue
        bridge.health['fails'] += 1
        if bridge.health['fails'] == 1:
            try:
                radio.request_config()
            except OSError:
                pass
            bridge.health['repairs'].append({'time': int(now), 'action': 'Asked the radio to restart its link'})
        elif bridge.health['fails'] >= 3 and usb and now - last_reset > 300:
            last_reset = now
            fixed = lora_admin.usb_reset(device)
            bridge.health['repairs'].append({'time': int(now), 'action': 'USB port reset' if fixed else 'USB reset not possible'})
            bridge.health['fails'] = 0
        del bridge.health['repairs'][:-5]


def main():
    stop = threading.Event()
    settings = load_settings()
    device, choices = pick_device(settings)
    if device is None:
        # Answer the web page ("no radio" or "choose one") and restart once that changes.
        idle = Bridge(NoRadio('Choose this node\'s radio' if choices else 'No LoRa radio plugged in'),
                      lambda *a: None, lambda: None, settings)
        idle.choices = choices
        idle.restart = stop.set
        threading.Thread(target=control_server, args=(idle, stop), daemon=True).start()
        while not stop.is_set() and len(lora_admin.list_devices()) == len(choices) and not wifi_radio():
            stop.wait(10)
        stop.set()
        time.sleep(1.5)
        return
    node = Node()
    iface_ip = phone_address()
    try:
        sender = tagged(multicast_sender(iface_ip))
    except OSError as e:
        # Keep radio control working even if the phone side cannot be reached.
        print('LoRa: cannot send to phones on %s: %s' % (iface_ip, e), flush=True)
        sender = lambda *a: None
    holder = {}
    radio = mt.Radio(device, on_packet=lambda *a: holder['bridge'].from_radio(*a))

    def mesh_up():
        n = radios.neighbors(run, node.halow_iface())
        return bool(n.get('count')) if n.get('available') else False

    bridge = Bridge(radio, sender, mesh_up, settings, pending_file=PENDING_FILE)
    if bridge.restore_pending():
        print('LoRa: re-applying a team change that a restart interrupted', flush=True)
    bridge.restart = stop.set
    holder['bridge'] = bridge
    local = LocalPhones()
    threads = [threading.Thread(target=radio.run_forever, daemon=True),
               threading.Thread(target=control_server, args=(bridge, stop), daemon=True),
               threading.Thread(target=watchdog, args=(radio, bridge, stop, device), daemon=True),
               threading.Thread(target=multicast_listener,
                                args=(tak.SA_GROUP, tak.SA_PORT, iface_ip, bridge.from_phones, stop, local,
                                      bridge.on_mesh), daemon=True),
               threading.Thread(target=multicast_listener,
                                args=(tak.CHAT_GROUP, tak.CHAT_PORT, iface_ip, bridge.from_phones, stop, local,
                                      bridge.on_mesh), daemon=True)]
    threads.append(threading.Thread(target=fleet_watch, args=(node, radio, bridge, stop), daemon=True))
    for t in threads:
        t.start()
    print('LoRa: radio %s, mode %s, phones on %s' % (device, bridge.mode, iface_ip), flush=True)
    last_check = 0
    while not stop.is_set():
        now = time.time()
        if now - last_check >= MESH_CHECK_S:
            last_check = now
            try:
                bridge.mesh = mesh_up()
            except Exception:
                bridge.mesh = None
        try:
            bridge.tick()
            bridge.pump()
        except OSError:
            pass
        stop.wait(0.5)
    radio.stop.set()
    radio.close()
    time.sleep(1.5)


if __name__ == '__main__':
    main()
