"""Battery size setting and a time-left estimate.

The PiSugar 3 gives voltage and a percent, no reliable current reading, so time left
comes from how fast the shown percent actually moves on this node (measured), and
until there is enough of that, from the pack size and a typical node load (typical).
"""
import json
import threading
import time
from pathlib import Path

STATE_DIR = Path('/etc/field-extensions')
SETTINGS_FILE = 'battery.json'
DEFAULT_MAH = 10000            # the pack both nodes have
SIZES = (5000, 8000, 10000)    # offered in the web UI; any 1000-50000 is accepted
PACK_VOLTS = 3.7
BOOST_EFFICIENCY = 0.85        # 3.7 V cell to the Pi's 5 V
TYPICAL_WATTS = 5.0            # Pi 4 + HaLow + LoRa + USB Wi-Fi, rough
WINDOW_S = 3600                # fit over the last hour
MIN_SPAN_S = 600               # at least 10 minutes of readings
MIN_CHANGE = 2                 # and at least 2 points of change
SAMPLE_S = 30


def load_mah(state_dir=STATE_DIR):
    try:
        mah = int(json.loads((Path(state_dir) / SETTINGS_FILE).read_text()).get('mah'))
        if 1000 <= mah <= 50000:
            return mah
    except (OSError, ValueError, TypeError, AttributeError):
        pass
    return DEFAULT_MAH


def slope_per_hour(points):
    """Least-squares slope of [(seconds, percent)] in percent per hour."""
    n = len(points)
    if n < 2:
        return None
    mt = sum(t for t, _ in points) / n
    mp = sum(p for _, p in points) / n
    den = sum((t - mt) ** 2 for t, _ in points)
    if not den:
        return None
    return sum((t - mt) * (p - mp) for t, p in points) / den * 3600


class Estimate:
    """Feed it battery status every SAMPLE_S; status() gives size and time left."""

    def __init__(self, battery, state_dir=STATE_DIR, clock=time.monotonic):
        self.battery, self.dir, self.clock = battery, Path(state_dir), clock
        self.mah = load_mah(state_dir)
        self.points = []           # (time, percent) since the plug state last changed
        self.plugged = None
        self.lock = threading.Lock()

    def set_mah(self, mah):
        try:
            mah = int(mah)
        except (TypeError, ValueError):
            return {'ok': False, 'error': 'Battery size must be a number of mAh'}
        if not 1000 <= mah <= 50000:
            return {'ok': False, 'error': 'Battery size must be between 1,000 and 50,000 mAh'}
        self.dir.mkdir(parents=True, exist_ok=True)
        tmp = self.dir / (SETTINGS_FILE + '.tmp')
        tmp.write_text(json.dumps({'mah': mah}) + '\n')
        tmp.replace(self.dir / SETTINGS_FILE)
        self.mah = mah
        return {'ok': True, 'note': 'Battery size set to {:,} mAh.'.format(mah)}

    def observe(self, status):
        pct, plugged = status.get('battery_percent'), status.get('external_power')
        if pct is None or plugged is None:
            return
        now = self.clock()
        with self.lock:
            if plugged != self.plugged:
                self.points, self.plugged = [], plugged
            self.points.append((now, pct))
            self.points = [p for p in self.points if now - p[0] <= WINDOW_S]

    def sample(self):
        self.observe(self.battery())

    def run(self, stop):
        while not stop.wait(SAMPLE_S):
            try:
                self.sample()
            except Exception:
                pass

    def measured_rate(self):
        with self.lock:
            pts = list(self.points)
        if len(pts) < 3 or pts[-1][0] - pts[0][0] < MIN_SPAN_S:
            return None
        if max(p for _, p in pts) - min(p for _, p in pts) < MIN_CHANGE:
            return None
        return slope_per_hour([(t - pts[0][0], p) for t, p in pts])

    def typical_hours(self, percent):
        wh = self.mah / 1000 * PACK_VOLTS * BOOST_EFFICIENCY
        return wh * percent / 100 / TYPICAL_WATTS

    def status(self, battery):
        out = {'mah': self.mah, 'sizes': list(SIZES), 'time_left_min': None,
               'time_to_full_min': None, 'method': None}
        pct, plugged = battery.get('battery_percent'), battery.get('external_power')
        if pct is None or plugged is None:
            return out
        rate = self.measured_rate()
        if plugged:
            if rate is not None and rate > 0.5 and pct < 100:
                out['time_to_full_min'] = round((100 - pct) / rate * 60)
                out['method'] = 'measured'
            return out
        if rate is not None and rate < -0.5:
            out['time_left_min'], out['method'] = round(pct / -rate * 60), 'measured'
        else:
            out['time_left_min'], out['method'] = round(self.typical_hours(pct) * 60), 'typical'
        return out
