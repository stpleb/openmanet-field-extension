"""Self-recovery for OpenMANET nodes, written from the behavior notes in
audit 2026-10-04, part 6. One procd instance
("recovery") runs these on their own timers:

  reboot log      every boot: was the last shutdown clean, a watchdog, or unexpected?
  HaLow watchdog  every 5 min: the Morse chip must answer; 3 misses -> reboot (budgeted)
  peer watchdog   HaLow peers seen this boot, then none for 20 min: reboot (same budget).
                  A node that never had a peer is left alone.
  MCS latch       every 60 s: a strong HaLow peer stuck at the lowest rate is re-joined
  link check      per mesh link: 5 pings through batman; report-only unless allowed to kick
  link demote     every 2 s: a weak 2.4 GHz mesh link is made unattractive to batman
  mesh guard      mesh_fwding must stay 0 on every mesh interface
  traffic shaping AIMD ceiling on bat0 from neighbor round trips (off until tested)

Each part can be turned off with /etc/field-extensions/state/recovery-<name> = 0, and the
status is written to /tmp/field-recovery.json for the web page.
"""
import json
import os
import re
import subprocess
import time
from pathlib import Path

STATE = Path('/etc/field-extensions/state')
STATUS_FILE = Path('/tmp/field-recovery.json')
HEARTBEAT, CLEAN, REBOOT_LOG, BUDGET = 'heartbeat.json', 'clean-shutdown.json', 'reboot-log.txt', 'watchdog-reboots.json'
DEFAULTS = {'radio-watchdog': 1, 'peer-watchdog': 1, 'mcs-latch': 1, 'link-check': 1, 'link-kick': 0, 'link-demote': 1,
            'mesh-guard': 1, 'qos': 0}
SETUP_GRACE_S = 300
REBOOT_BUDGET = (2, 6 * 3600)          # at most 2 watchdog reboots in 6 hours


def run(argv, timeout=10):
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
        return p.returncode == 0, p.stdout
    except (OSError, subprocess.TimeoutExpired):
        return False, ''


def uptime():
    try:
        return float(Path('/proc/uptime').read_text().split()[0])
    except (OSError, ValueError, IndexError):
        return 0.0


def boot_id():
    try:
        return Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    except OSError:
        return 'unknown'


# ---- radio adapter: the few iw / morse_cli / batctl reads every part shares ----
def parse_stations(text):
    """`iw dev X station dump` -> [{mac, inactive_ms, signal_avg, expected_kbps, connected_s, estab}]."""
    out, cur = [], None
    for line in text.splitlines():
        m = re.match(r'^Station ([0-9a-f:]{17})', line.strip())
        if m:
            cur = {'mac': m.group(1), 'inactive_ms': None, 'signal_avg': None, 'expected_kbps': None,
                   'connected_s': None, 'estab': False}
            out.append(cur)
            continue
        if cur is None or ':' not in line:
            continue
        key, _, value = line.strip().partition(':')
        key, value = key.strip().lower(), value.strip()
        num = re.match(r'^(-?\d+(?:\.\d+)?)', value)
        if key == 'inactive time' and num:
            cur['inactive_ms'] = float(num.group(1))
        elif key == 'signal avg' and num:
            cur['signal_avg'] = float(num.group(1))
        elif key == 'expected throughput' and num:
            cur['expected_kbps'] = float(num.group(1)) * (1000 if 'mbps' in value.lower() else 1)
        elif key == 'connected time' and num:
            cur['connected_s'] = float(num.group(1))
        elif key == 'mesh plink':
            cur['estab'] = value.upper().startswith('ESTAB')
    return out


def halow_width(text):
    m = re.search(r'(?:Operating )?Bandwidth[^\d]*(\d+)\s*MHz', text, re.I)
    return int(m.group(1)) if m else None


class Adapter:
    def __init__(self, halow, runner=run):
        self.halow, self.run = halow, runner

    def channel(self):
        if not self.halow:
            return False, ''
        return self.run(['morse_cli', '-i', self.halow, 'channel'])

    def stations(self, iface):
        ok, text = self.run(['iw', 'dev', iface, 'station', 'dump'])
        return parse_stations(text) if ok else []

    def station_del(self, iface, mac):
        return self.run(['iw', 'dev', iface, 'station', 'del', mac])[0]

    def hardifs(self):
        ok, text = self.run(['batctl', 'if'])
        return [line.split(':')[0].strip() for line in text.splitlines() if 'active' in line] if ok else []

    def mesh_ifaces(self):
        ok, text = self.run(['iw', 'dev'])
        out, name = [], None
        for line in text.splitlines():
            m = re.match(r'^\s*Interface (\S+)', line)
            if m:
                name = m.group(1)
            elif name and re.match(r'^\s*type mesh point', line):
                out.append(name)
        return out if ok else []


# ---- reboot log ----
class RebootLog:
    def __init__(self, state=STATE, clock=time.time, up=uptime, boot=boot_id):
        self.dir, self.clock, self.up, self.boot = Path(state), clock, up, boot
        self.last = None

    def _read(self, name):
        try:
            return json.loads((self.dir / name).read_text())
        except (OSError, ValueError):
            return None

    def classify(self):
        """Once per boot: how did the previous one end?"""
        hb, clean = self._read(HEARTBEAT), self._read(CLEAN)
        if hb is None or hb.get('boot') == self.boot():
            return None    # first start ever, or only the service restarted
        if clean and clean.get('boot') == hb.get('boot') and hb.get('uptime', 0) <= clean.get('uptime', 0) + 120:
            kind = 'clean (%s)' % clean.get('why', 'shutdown')
        elif clean and clean.get('boot') == hb.get('boot') and clean.get('why', '').startswith('watchdog'):
            kind = clean['why']
        elif hb.get('uptime', 0) < 360:
            kind = 'probable watchdog or power loss (up only %d s)' % hb.get('uptime', 0)
        else:
            kind = 'unexpected (power loss or crash)'
        line = '%s %s up %ds' % (time.strftime('%Y-%m-%d %H:%M', time.localtime(hb.get('time', 0))), kind,
                                 hb.get('uptime', 0))
        try:
            lines = (self.dir / REBOOT_LOG).read_text().splitlines()
        except OSError:
            lines = []
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / REBOOT_LOG).write_text('\n'.join((lines + [line])[-100:]) + '\n')
        self.last = line
        return line

    def heartbeat(self):
        self.dir.mkdir(parents=True, exist_ok=True)
        tmp = self.dir / (HEARTBEAT + '.tmp')
        tmp.write_text(json.dumps({'boot': self.boot(), 'uptime': int(self.up()), 'time': int(self.clock())}))
        tmp.replace(self.dir / HEARTBEAT)

    def recent(self, n=5):
        try:
            return (self.dir / REBOOT_LOG).read_text().splitlines()[-n:]
        except OSError:
            return []


def mark_clean(why, state=STATE, overwrite=True):
    """Called before a planned shutdown or reboot (init stop, safe shutdown, watchdog).
    overwrite=False (the init stop) keeps a more specific reason already written this boot,
    such as "button long press", instead of replacing it with "shutdown or restart"."""
    try:
        Path(state).mkdir(parents=True, exist_ok=True)
        if not overwrite:
            try:
                if json.loads((Path(state) / CLEAN).read_text()).get('boot') == boot_id():
                    return
            except (OSError, ValueError, AttributeError):
                pass
        (Path(state) / CLEAN).write_text(json.dumps({'boot': boot_id(), 'uptime': int(uptime()), 'why': why}))
    except OSError:
        pass


# ---- HaLow watchdog ----
class RadioWatchdog:
    EVERY_S, RETRY_S, MISSES = 300, 60, 3

    def __init__(self, adapter, reboot, state=STATE, clock=time.time, up=uptime):
        self.a, self.reboot, self.dir, self.clock, self.up = adapter, reboot, Path(state), clock, up
        self.misses, self.next_at, self.note = 0, 0.0, None

    def busy(self):
        return any(Path(p).exists() for p in ('/tmp/sysupgrade.img', '/tmp/firmware.bin', '/tmp/field-upgrade.lock'))

    def budget_left(self):
        try:
            times = json.loads((self.dir / BUDGET).read_text())
        except (OSError, ValueError):
            times = []
        now = self.clock()
        times = [t for t in times if now - t < REBOOT_BUDGET[1]]
        return times, len(times) < REBOOT_BUDGET[0]

    def tick(self):
        now = self.clock()
        if now < self.next_at or not self.a.halow or self.up() < SETUP_GRACE_S or self.busy():
            return
        ok, text = self.a.channel()
        if ok and 'Operating Frequency' in text:
            self.misses, self.next_at, self.note = 0, now + self.EVERY_S, 'HaLow radio answering'
            return
        self.misses += 1
        self.next_at = now + self.RETRY_S
        self.note = 'HaLow radio not answering (%d of %d)' % (self.misses, self.MISSES)
        if self.misses < self.MISSES:
            return
        times, ok_to_reboot = self.budget_left()
        if not ok_to_reboot:
            self.note = 'HaLow radio not answering; reboot skipped (2 watchdog reboots in the last 6 hours)'
            self.next_at = now + self.EVERY_S
            return
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / BUDGET).write_text(json.dumps(times + [int(now)]))
        mark_clean('watchdog: HaLow radio stopped answering', self.dir)
        self.note = 'Rebooting: HaLow radio stopped answering'
        self.reboot()


# ---- HaLow peer watchdog ----
class PeerWatchdog:
    """The radio can answer while its mesh is dead (Node02 2026-10-13: after a Wi-Fi restart
    its HaLow never re-peered with Node01 and the chip watchdog saw nothing wrong).
    Recovery is a reboot, not a Wi-Fi reload: a remote Wi-Fi restart is what left Node02
    half-stuck (AP gone, clean shutdown hung)."""
    EVERY_S, LOST_S = 60, 1200

    def __init__(self, adapter, halow_peers, reboot, state=STATE, clock=time.time, up=uptime):
        self.a, self.peers, self.reboot, self.dir, self.clock, self.up = \
            adapter, halow_peers, reboot, Path(state), clock, up
        self.had_peer, self.lost_at, self.next_at, self.note = False, None, 0.0, None

    def tick(self):
        now = self.clock()
        if now < self.next_at or not self.a.halow or self.up() < SETUP_GRACE_S:
            return
        self.next_at = now + self.EVERY_S
        if self.peers():
            self.had_peer, self.lost_at = True, None
            self.note = 'HaLow peers present'
            return
        if not self.had_peer:
            self.note = 'No HaLow peer yet this boot (nothing to recover)'
            return
        if self.lost_at is None:
            self.lost_at = now
        if now - self.lost_at < self.LOST_S:
            self.note = 'HaLow peers lost %d min ago' % ((now - self.lost_at) // 60)
            return
        times, ok = RadioWatchdog(self.a, None, self.dir, self.clock).budget_left()
        if not ok:
            self.note = 'HaLow peers still lost; reboot skipped (2 watchdog reboots in the last 6 hours)'
            return
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / BUDGET).write_text(json.dumps(times + [int(now)]))
        mark_clean('watchdog: HaLow peers lost', self.dir)
        self.note = 'Rebooting: HaLow peers lost for 20 min'
        self.lost_at = now     # if the reboot does not happen, start over
        self.reboot()


# ---- MCS latch ----
class McsLatch:
    EVERY_S, SCANS, PEER_COOLDOWN_S, ANY_COOLDOWN_S = 60, 5, 1200, 300
    FLOOR_KBPS = {1: 350, 2: 1000, 4: 1600, 8: 1200}

    def __init__(self, adapter, clock=time.time, up=uptime):
        self.a, self.clock, self.up = adapter, clock, up
        self.count, self.kicked, self.last_kick, self.next_at, self.note = {}, {}, 0.0, 0.0, None

    def tick(self):
        now = self.clock()
        if now < self.next_at or not self.a.halow:
            return
        self.next_at = now + self.EVERY_S
        width = halow_width(self.a.channel()[1])
        floor = self.FLOOR_KBPS.get(width)
        stations = [s for s in self.a.stations(self.a.halow) if s['estab']]
        if floor is None:
            self.note = 'HaLow width unknown; not checking'
            return
        latched = set()
        for s in stations:
            if ((s['connected_s'] or 0) > 300 and s['signal_avg'] is not None and s['signal_avg'] > -68
                    and s['expected_kbps'] is not None and 0 < s['expected_kbps'] < floor):
                latched.add(s['mac'])
        self.count = {m: self.count.get(m, 0) + 1 for m in latched}
        self.note = ('%d strong peer(s) stuck at a low rate' % len(latched)) if latched else 'HaLow rates normal'
        for mac, n in self.count.items():
            if (n >= self.SCANS and len(stations) > 1 and self.up() > SETUP_GRACE_S
                    and now - self.last_kick >= self.ANY_COOLDOWN_S
                    and now - self.kicked.get(mac, 0) >= self.PEER_COOLDOWN_S):
                if self.a.station_del(self.a.halow, mac):
                    self.kicked[mac], self.last_kick, self.count[mac] = now, now, 0
                    self.note = 'Re-joined %s (stuck at a low rate)' % mac
                break


# ---- link check (report-only unless kicks are allowed) ----
TIMING = {1: (60, 20, 3, 500), 2: (45, 15, 3, 1000), 4: (30, 10, 2, 1000), 8: (30, 10, 2, 1000)}


def ping_loss(text):
    m = re.search(r'(\d+(?:\.\d+)?)% packet loss', text)
    return float(m.group(1)) if m else None


_ORIG = re.compile(r'^\s*\*\s+([0-9a-f:]{17})\s+[\d.]+s\s+\(\s*[\d.]+\)\s+([0-9a-f:]{17})\s+\[\s*([^\]\s]+)\s*\]')


def selected_routes(text):
    """`batctl o` -> {(nexthop, iface): [originators]} for the selected (*) routes."""
    groups = {}
    for line in text.splitlines():
        m = _ORIG.match(line)
        if m:
            groups.setdefault((m.group(2), m.group(3)), []).append(m.group(1))
    return groups


class LinkCheck:
    """One link = (next hop, interface). Every originator routed over it is pinged through
    batman (data frames, not just OGMs); the link fails only if all of them fail."""

    def __init__(self, adapter, kick_allowed, clock=time.time):
        self.a, self.kick_allowed, self.clock = adapter, kick_allowed, clock
        self.links, self.next_at, self.last_kick = {}, {}, 0.0

    def tick(self):
        now = self.clock()
        width = halow_width(self.a.channel()[1]) if self.a.halow else None
        every, suspect_every, timeout, _size = TIMING.get(width, (60, 15, 3, 500))
        ok, text = self.a.run(['batctl', 'o', '-n'])
        groups = selected_routes(text) if ok else {}
        for key in list(self.links):
            if key not in groups:
                self.links.pop(key)
        for key, targets in groups.items():
            link = self.links.setdefault(key, {'state': 'ok', 'fails': 0, 'loss': None})
            if now < self.next_at.get(key, 0):
                continue
            losses = []
            for orig in targets[:3]:
                _, out = self.a.run(['batctl', 'ping', '-c', '5', '-t', str(timeout), orig], timeout=5 * timeout + 5)
                losses.append(ping_loss(out))
            known = [x for x in losses if x is not None]
            loss = min(known) if known else None    # condemned only if every target fails
            link['loss'] = loss
            if loss is None:
                link['state'] = 'unknown'
            elif loss >= 90:
                link['state'], link['fails'] = 'fail', link['fails'] + 1
            elif loss >= 70:
                link['state'], link['fails'] = 'suspect', 0
            else:
                link['state'], link['fails'] = 'ok', 0
            self.next_at[key] = now + (suspect_every if link['state'] in ('suspect', 'fail') else every)
        failing = [k for k, l in self.links.items() if l['state'] == 'fail']
        local_fault = len(self.links) >= 3 and len(failing) >= len(self.links) / 2
        for key in failing:
            link = self.links[key]
            if self.kick_allowed() and not local_fault and link['fails'] >= 3 and now - self.last_kick >= 120:
                if self.a.station_del(key[1], key[0]):
                    self.last_kick, link['fails'], link['kicked'] = now, 0, int(now)
        return {'links': [{'next_hop': k[0], 'iface': k[1], **v} for k, v in self.links.items()],
                'local_fault': local_fault}


# ---- link demote (2.4 GHz mesh weaker than HaLow) ----
class LinkDemote:
    WEAK, GOOD, WEAK_POLLS, GOOD_POLLS, DWELL_S = -82, -75, 3, 5, 30

    def __init__(self, adapter, clock=time.time):
        self.a, self.clock = adapter, clock
        self.state = {}   # iface -> {'demoted', 'weak', 'good', 'since'}

    def tick(self):
        now = self.clock()
        for iface in self.a.hardifs():
            if iface == self.a.halow:
                continue
            st = self.state.setdefault(iface, {'demoted': self._current(iface), 'weak': 0, 'good': 0, 'since': 0.0})
            peers = [s for s in self.a.stations(iface) if s['estab'] and (s['inactive_ms'] or 0) <= 15000
                     and s['signal_avg'] is not None]
            best = max((s['signal_avg'] for s in peers), default=None)
            if best is None:
                continue
            st['weak'] = st['weak'] + 1 if best < self.WEAK else 0
            st['good'] = st['good'] + 1 if best >= self.GOOD else 0
            if now - st['since'] < self.DWELL_S:
                continue
            if not st['demoted'] and st['weak'] >= self.WEAK_POLLS:
                if self.a.run(['batctl', 'hardif', iface, 'throughput_override', '1mbit'])[0]:
                    st.update(demoted=True, since=now)
            elif st['demoted'] and st['good'] >= self.GOOD_POLLS:
                if self.a.run(['batctl', 'hardif', iface, 'throughput_override', '0'])[0]:
                    st.update(demoted=False, since=now)
        return {k: v['demoted'] for k, v in self.state.items()}

    def _current(self, iface):
        ok, text = self.a.run(['batctl', 'hardif', iface, 'throughput_override'])
        return ok and text.strip() not in ('', '0', '0.0 Mbit', '0 Mbit')


# ---- mesh guard ----
def mesh_guard(adapter):
    fixed = []
    for iface in adapter.mesh_ifaces():
        ok, text = adapter.run(['iw', 'dev', iface, 'get', 'mesh_param', 'mesh_fwding'])
        if ok and re.search(r'\b1\b', text):
            if adapter.run(['iw', 'dev', iface, 'set', 'mesh_param', 'mesh_fwding', '0'])[0]:
                fixed.append(iface)
    return fixed


# ---- traffic shaping (AIMD ceiling on bat0) ----
class Shaper:
    MAX_KBPS = {1: 1200, 2: 2500, 4: 5000, 8: 9500}
    WIFI_MAX = 60000
    HIGH_MS, LOW_MS, STEP_UP, HYST = 150, 40, 400, 150

    def __init__(self, adapter, neighbors):
        self.a, self.neighbors = adapter, neighbors
        self.ceiling, self.applied, self.ready, self.note = None, None, False, None

    def limits(self):
        width = halow_width(self.a.channel()[1]) if self.a.halow else None
        top = self.MAX_KBPS.get(width, 2500)
        if any(i != self.a.halow for i in self.a.hardifs()):
            top = self.WIFI_MAX
        return top, top // 10

    def setup(self, top):
        cmds = [['tc', 'qdisc', 'replace', 'dev', 'bat0', 'root', 'handle', '1:', 'htb', 'default', '20'],
                ['tc', 'class', 'replace', 'dev', 'bat0', 'parent', '1:', 'classid', '1:1', 'htb',
                 'rate', '%dkbit' % top, 'ceil', '%dkbit' % top],
                # 1:10 = ATAK positions and chat (small, urgent); 1:20 = everything else
                ['tc', 'class', 'replace', 'dev', 'bat0', 'parent', '1:1', 'classid', '1:10', 'htb',
                 'rate', '%dkbit' % max(64, top // 5), 'ceil', '%dkbit' % top, 'prio', '0'],
                ['tc', 'class', 'replace', 'dev', 'bat0', 'parent', '1:1', 'classid', '1:20', 'htb',
                 'rate', '%dkbit' % max(64, top // 5), 'ceil', '%dkbit' % top, 'prio', '1']]
        for port in ('6969', '17012', '4242'):
            cmds.append(['tc', 'filter', 'replace', 'dev', 'bat0', 'parent', '1:', 'protocol', 'ip', 'prio', '1',
                         'u32', 'match', 'ip', 'dport', port, '0xffff', 'flowid', '1:10'])
        self.ready = all(self.a.run(c)[0] for c in cmds)
        return self.ready

    def tick(self):
        top, bottom = self.limits()
        if not self.ready:
            if not self.setup(top):
                self.note = 'Traffic shaping unavailable (tc/HTB missing on this node?)'
                return
            self.ceiling = top // 4
        rtts = []
        for n in self.neighbors():
            ok, text = self.a.run(['batctl', 'ping', '-c', '1', '-t', '1', n['mac']], timeout=3)
            m = re.search(r'time=(\d+(?:\.\d+)?)', text)
            if ok and m:
                rtts.append(float(m.group(1)))
        peers = self.neighbors()
        if peers and not rtts:
            self.ceiling = self.ceiling * 3 // 5
        elif rtts:
            mid = sorted(rtts)[len(rtts) // 2]
            if mid > self.HIGH_MS:
                self.ceiling = self.ceiling * 3 // 5
            elif mid < self.LOW_MS:
                self.ceiling += self.STEP_UP
        self.ceiling = max(bottom, min(top, self.ceiling))
        if self.applied is None or abs(self.ceiling - self.applied) >= self.HYST:
            for cid in ('1:1', '1:10', '1:20'):
                parent = '1:' if cid == '1:1' else '1:1'
                self.a.run(['tc', 'class', 'change', 'dev', 'bat0', 'parent', parent, 'classid', cid, 'htb',
                            'rate', '%dkbit' % (self.ceiling if cid == '1:1' else max(64, self.ceiling // 5)),
                            'ceil', '%dkbit' % self.ceiling])
            self.applied = self.ceiling
        self.note = 'Mesh ceiling %d kbit/s' % self.ceiling


# ---- service ----
def setting(name, state=STATE):
    try:
        return int((Path(state) / ('recovery-' + name)).read_text().strip()) == 1
    except (OSError, ValueError):
        return DEFAULTS[name] == 1


def main():
    from . import radios
    from .core import Node, run as core_run
    node = Node()
    a = Adapter(node.halow_iface())
    log = RebootLog()
    print('Recovery: previous shutdown: %s' % (log.classify() or 'n/a'), flush=True)

    def live():
        return [n for n in radios.neighbors(core_run, a.halow).get('neighbors', []) if n.get('mac')]

    def reboot():
        subprocess.run(['sync'], timeout=30, check=False)
        subprocess.run(['reboot'], timeout=30, check=False)

    def halow_peers():
        return [n for n in live() if n.get('interface') == a.halow]

    watchdog, latch = RadioWatchdog(a, reboot), McsLatch(a)
    peerdog = PeerWatchdog(a, halow_peers, reboot)
    links = LinkCheck(a, lambda: setting('link-kick'))
    demote, shaper = LinkDemote(a), Shaper(a, live)
    status, last = {}, {}

    def every(name, seconds):
        now = time.monotonic()
        if now - last.get(name, -1e9) >= seconds:
            last[name] = now
            return True
        return False

    while True:
        try:
            if every('heartbeat', 60):
                log.heartbeat()
            if every('mesh-join', 30):
                from . import mesh_join
                if mesh_join.tick(reboot=reboot):
                    print('Recovery: temporary mesh join ran out; old mesh settings restored, rebooting', flush=True)
            if setting('radio-watchdog'):
                watchdog.tick()
                status['radio_watchdog'] = watchdog.note
            if setting('peer-watchdog'):
                peerdog.tick()
                status['peer_watchdog'] = peerdog.note
            if setting('mcs-latch'):
                latch.tick()
                status['mcs_latch'] = latch.note
            if setting('link-check') and every('links', 10):
                status['links'] = links.tick()
            if setting('link-demote') and every('demote', 2):
                status['demoted'] = demote.tick()
            if setting('mesh-guard') and every('guard', 600):
                fixed = mesh_guard(a)
                status['mesh_guard'] = ('Set mesh forwarding off on ' + ', '.join(fixed)) if fixed else 'Mesh forwarding off'
            if setting('qos') and every('qos', 3):
                shaper.tick()
                status['qos'] = shaper.note
            if every('status', 10):
                status['reboots'] = log.recent()
                status['settings'] = {k: setting(k) for k in DEFAULTS}
                tmp = STATUS_FILE.with_suffix('.tmp')
                tmp.write_text(json.dumps(status))
                os.replace(tmp, STATUS_FILE)
        except Exception as e:
            print('Recovery: %s' % e, flush=True)
        time.sleep(1)


if __name__ == '__main__':
    main()
