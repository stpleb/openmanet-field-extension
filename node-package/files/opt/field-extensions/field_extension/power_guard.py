"""Clean shutdown before the battery runs flat.

Left alone, the PiSugar cuts power when the cell is empty, which can corrupt the SD
card mid-write. On battery and low for HOLD_S in a row, this powers the Pi off itself.
Auto power-on (PiSugar CTR1 bit 4) brings the node back when a charger is plugged in.
The threshold lives in /etc/field-extensions/safe-shutdown-percent (0 turns it off).
"""
import json
import time
from pathlib import Path

STATE_DIR = Path('/etc/field-extensions')
SETTING = 'safe-shutdown-percent'
RECORD = 'last-safe-shutdown.json'
DEFAULT_PERCENT = 5
LOW_VOLTS = 3.30          # backstop when the percent is off (a full-load sag reads low too, hence the hold)
HOLD_S = 120
BOOT_GRACE_S = 300        # never right after a start: give a charger the chance to be noticed


class LowBatteryGuard:
    HOLD_S = HOLD_S

    def __init__(self, poweroff, state_dir=STATE_DIR, clock=time.monotonic, wall=time.time, uptime=None):
        self.poweroff, self.dir, self.clock, self.wall = poweroff, Path(state_dir), clock, wall
        self.uptime = uptime or _uptime
        self.low_since = None
        self.done = False

    def percent(self):
        try:
            p = int((self.dir / SETTING).read_text().strip())
            return p if 0 <= p <= 30 else DEFAULT_PERCENT
        except (OSError, ValueError):
            return DEFAULT_PERCENT

    def set_percent(self, p):
        if not isinstance(p, int) or isinstance(p, bool) or not 0 <= p <= 30:
            return {'ok': False, 'error': 'Shutdown level must be 0 (off) to 30 percent'}
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / SETTING).write_text('%d\n' % p)
        return {'ok': True, 'note': 'Safe shutdown off.' if p == 0 else 'The node shuts itself down at %d%% on battery.' % p}

    def is_low(self, battery):
        limit = self.percent()
        if not limit or not battery.get('available') or battery.get('external_power') is not False:
            return False
        pct, volts = battery.get('battery_percent'), battery.get('battery_voltage')
        return (pct is not None and pct <= limit) or (volts is not None and 2.5 < volts <= LOW_VOLTS)

    def check(self, battery):
        """Call about once a second with PiSugar status. Returns True when it shut down."""
        if self.done:
            return False
        now = self.clock()
        if not self.is_low(battery):
            self.low_since = None
            return False
        if self.low_since is None:
            self.low_since = now
        if now - self.low_since < HOLD_S or self.uptime() < BOOT_GRACE_S:
            return False
        self.done = True
        record = {'time': int(self.wall()), 'percent': battery.get('battery_percent'),
                  'voltage': battery.get('battery_voltage'), 'auto_power_on': battery.get('auto_power_on')}
        try:
            self.dir.mkdir(parents=True, exist_ok=True)
            (self.dir / RECORD).write_text(json.dumps(record) + '\n')
        except OSError:
            pass
        try:
            from .recovery import mark_clean
            mark_clean('safe shutdown at %s%%' % record['percent'], self.dir / 'state')
        except Exception:
            pass
        print('Battery at %s%% (%s V) on battery: shutting down safely.' % (record['percent'], record['voltage']), flush=True)
        self.poweroff()
        return True

    def status(self, battery=None):
        out = {'percent': self.percent(), 'low_for_s': None, 'last': None}
        if self.low_since is not None:
            out['low_for_s'] = round(self.clock() - self.low_since)
        try:
            out['last'] = json.loads((self.dir / RECORD).read_text())
        except (OSError, ValueError):
            pass
        return out


def _uptime():
    try:
        return float(Path('/proc/uptime').read_text().split()[0])
    except (OSError, ValueError, IndexError):
        return 0.0
