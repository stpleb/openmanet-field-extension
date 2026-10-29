"""Battery percent from voltage with a curve calibrated on this node's own battery.

Same idea as pisugar-server's battery_curve setting ([voltage, percent] points), but
our nodes talk to the PiSugar directly, so Field Extension keeps the curve itself.
Without a saved curve the PiSugar chip's own estimate is used, as before.

Calibration: start it at full charge, unplug, and leave the node running until the
PiSugar cuts power. Voltage is logged every 30 s (synced to flash, because the run
ends in a power cut); the curve is built from the log at the next start. Plugging in
during the run cancels it, since a charger lifts the voltage.
"""
import json
import os
import threading
import time
from pathlib import Path

STATE_DIR = Path('/etc/field-extensions')
CURVE_FILE = 'battery_curve.json'
LOG_FILE = 'battery-calibration.log'
SAMPLE_S = 30
MIN_RUN_S = 3600
MIN_SAMPLES = 60
START_PERCENT = 90   # the chip's own estimate tops out near 92% on some cells (Node02)


def boot_id():
    try:
        return Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    except OSError:
        return 'unknown'


class SavedCurve:
    """The saved curve, re-read when the file changes (the web server writes it; the
    status-light driver reads it too)."""

    def __init__(self, state_dir=STATE_DIR):
        self.path, self.mtime, self.data = Path(state_dir) / CURVE_FILE, None, None

    def get(self):
        try:
            mtime = self.path.stat().st_mtime
        except OSError:
            self.mtime, self.data = None, None
            return None
        if mtime != self.mtime:
            self.mtime, self.data = mtime, load_curve(self.path)
        return self.data


def load_curve(path):
    try:
        data = json.loads(Path(path).read_text())
        curve = data.get('curve')
        if isinstance(curve, list) and len(curve) >= 2 and all(
                isinstance(p, list) and len(p) == 2 and all(isinstance(x, (int, float)) for x in p) for p in curve):
            return data
    except (OSError, ValueError, AttributeError):
        pass
    return None


def percent_from_curve(voltage, curve):
    """Linear between points; curve is [[volts, percent], ...] in any order."""
    if voltage is None or not curve:
        return None
    pts = sorted(curve, reverse=True)
    if voltage >= pts[0][0]:
        return float(pts[0][1])
    for (v1, p1), (v2, p2) in zip(pts, pts[1:]):
        if v2 <= voltage <= v1:
            return p2 + (p1 - p2) * (voltage - v2) / (v1 - v2) if v1 != v2 else float(p1)
    return float(pts[-1][1])


def _median(xs):
    xs = sorted(xs)
    mid = len(xs) // 2
    return xs[mid] if len(xs) % 2 else (xs[mid - 1] + xs[mid]) / 2


def build_curve(samples):
    """[(seconds, volts)] from one full discharge -> curve, or None if the run is unusable.
    Percent is the share of run time left, so it follows this node's real load."""
    samples = sorted(s for s in samples if s[1] and 2.5 < s[1] < 4.5)
    if len(samples) < MIN_SAMPLES:
        return None
    t0, t1 = samples[0][0], samples[-1][0]
    span = t1 - t0
    if span < MIN_RUN_S:
        return None
    curve, last_v = [], None
    for pct in range(100, -1, -10):
        centre = t0 + span * (100 - pct) / 100
        near = [v for t, v in samples if abs(t - centre) <= span * 0.03]
        if pct == 0:
            near = [v for _, v in samples[-3:]]
        if not near:
            continue
        v = round(_median(near), 3)
        if last_v is not None and v > last_v:
            v = last_v  # noise near a flat stretch; the curve must only fall
        curve.append([v, pct])
        last_v = v
    return curve if len(curve) >= 5 else None


class Calibration:
    """Owns the saved curve and a calibration run. `battery()` returns the PiSugar's raw
    status ({'external_power', 'battery_voltage', 'battery_percent_chip'})."""

    def __init__(self, battery, state_dir=STATE_DIR, clock=time.monotonic, wall=time.time, boot=boot_id):
        self.battery, self.dir, self.clock, self.wall, self.boot = battery, Path(state_dir), clock, wall, boot
        self.lock = threading.Lock()
        self.state = None          # None, 'waiting_unplug', 'running'
        self.message = None
        self.started = None
        self.count = 0
        self.curve_data = load_curve(self.dir / CURVE_FILE)
        self.finish_previous()

    @property
    def curve(self):
        return self.curve_data['curve'] if self.curve_data else None

    def save_curve(self, curve, how):
        data = {'curve': curve, 'made': time.strftime('%Y-%m-%d %H:%M', time.localtime(self.wall())), 'how': how}
        self.dir.mkdir(parents=True, exist_ok=True)
        tmp = self.dir / (CURVE_FILE + '.tmp')
        tmp.write_text(json.dumps(data) + '\n')
        tmp.replace(self.dir / CURVE_FILE)
        self.curve_data = data

    def reset(self):
        try:
            (self.dir / CURVE_FILE).unlink()
        except OSError:
            pass
        self.curve_data = None

    # ---- calibration run ----
    def finish_previous(self):
        """A run that ended in a power cut left its log behind: turn it into a curve."""
        log = self.dir / LOG_FILE
        if not log.exists():
            return
        samples, header = [], None
        try:
            lines = log.read_text().splitlines()
            header = lines[0].split() if lines else None
            for line in lines[1:]:
                parts = line.split()
                if len(parts) == 2:
                    samples.append((float(parts[0]), float(parts[1])))
        except (OSError, ValueError):
            samples = []
        if header and len(header) == 3 and header[0] == 'run' and header[1] == self.boot():
            # Same boot: only the service restarted (an update), so carry on recording.
            self.state, self.started, self.count = 'running', float(header[2]), len(samples)
            return
        curve = build_curve(samples)
        if curve:
            self.save_curve(curve, 'calibrated over %.1f h' % ((samples[-1][0] - samples[0][0]) / 3600))
            self.message = 'Calibration finished: the battery curve is now in use.'
        else:
            self.message = 'The last calibration run was too short to use (it needs at least an hour on battery).'
        try:
            log.unlink()
        except OSError:
            pass

    def start(self):
        b = self.battery()
        pct = b.get('battery_percent_chip')
        with self.lock:
            if self.state:
                return {'ok': False, 'error': 'Calibration is already running'}
            if pct is None or pct < START_PERCENT:   # not voltage: a charger lifts it at any charge
                return {'ok': False, 'error': 'Charge the battery to full first (now %s%%)' % (pct if pct is not None else '?')}
            self.state = 'running' if not b.get('external_power') else 'waiting_unplug'
            self.started, self.count, self.message = None, 0, None
        return {'ok': True, 'note': 'Unplug the node and leave it running until it switches itself off.'
                if self.state == 'waiting_unplug' else 'Recording. Leave the node running until it switches itself off.'}

    def cancel(self, why='Calibration cancelled.'):
        with self.lock:
            self.state, self.message = None, why
        try:
            (self.dir / LOG_FILE).unlink()
        except OSError:
            pass
        return {'ok': True, 'note': why}

    def sample(self):
        """One tick (every SAMPLE_S)."""
        if not self.state:
            return
        b = self.battery()
        plugged, volts = b.get('external_power'), b.get('battery_voltage')
        if self.state == 'waiting_unplug':
            if plugged is False:
                self._begin()
            return
        if plugged:
            self.cancel('Calibration cancelled: power was plugged in. Start again from full.')
            return
        if self.started is None:
            self._begin()
        if volts is None:
            return
        with open(self.dir / LOG_FILE, 'a') as f:
            f.write('%.0f %.3f\n' % (self.clock() - self.started, volts))
            f.flush()
            os.fsync(f.fileno())  # the run ends in a power cut
        self.count += 1

    def _begin(self):
        self.state, self.started = 'running', self.clock()
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / LOG_FILE).write_text('run %s %.0f\n' % (self.boot(), self.started))

    def run(self, stop):
        while not stop.wait(SAMPLE_S):
            try:
                self.sample()
            except Exception:
                pass

    def status(self):
        return {'state': self.state, 'message': self.message, 'samples': self.count,
                'running_s': round(self.clock() - self.started) if self.state == 'running' and self.started else None,
                'curve': self.curve, 'made': (self.curve_data or {}).get('made'),
                'how': (self.curve_data or {}).get('how')}
