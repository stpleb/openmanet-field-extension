"""PiSugar 3 read directly over I2C, for nodes without pisugar-server.

Stock OpenMANET 1.8 has no pisugar-server package (Node01 audit 2026-10-05), so
this talks to the PiSugar 3 MCU at 0x57 itself. Register map checked on Node02
(firmware 1.3.4), behavior only:
  CTR1 0x02: bit 7 external power plugged, bit 6 charging allowed,
             bit 5 5 V output on, bit 4 power on automatically with external power
  0x0B write protect: write 0x29 to unlock, 0x00 to lock again
  0x22/0x23 battery voltage in mV (high byte, low byte); 0x2A battery percent
  CTR1 bit 0 is the button, live while pressed (0x7c idle, 0x7d pressed on Node01,
  2026-10-06); the tap register 0x08 stayed 0 on that firmware.
  CTR2 0x03: bit 4 soft shutdown (a long press raises bit 3 instead of cutting the
  power, so the Pi can shut down cleanly first). Read 0x40 on Node01 2026-10-12 (off).
"Charging" is not a register: it is reported as plugged-in, charging allowed,
and below 100%, and marked as inferred.
"""
import threading
import time

from . import i2c
from .battery_curve import SavedCurve, percent_from_curve
from .pisugar import describe

BUS = '/dev/i2c-1'
ADDRESS = 0x57
CTR1, CTR2, WRITE_PROTECT, VOLTAGE, PERCENT = 0x02, 0x03, 0x0B, 0x22, 0x2A
UNLOCK, LOCK = 0x29, 0x00
PLUGGED, CHARGE_ALLOWED, OUTPUT_ON, AUTO_ON = 0x80, 0x40, 0x20, 0x10
SOFT_SHUTDOWN, LONG_PRESS = 0x10, 0x08   # CTR2
# Set before a shutdown Field Extension starts; /etc/init.d/field-power-off then turns the
# PiSugar output off once the file systems are read-only (a halted Pi still drains the pack).
POWER_CUT_FLAG = '/tmp/fe-power-off'
# The MCU works the percent out from the voltage at that instant. On Node01 (2026-10-07,
# plugged in, low battery charging under a changing load) 13 readings in 2 minutes
# swung 3.31-3.59 V and 4-50%, so report the median of the last minute instead.
SMOOTH_S = 60
# On the charger the reading is worse (Node01 2026-10-08: 14-38% within minutes), so use a
# longer window there, and let the shown percent move at most 1 point per STEP_S toward
# the median: a real change still shows within minutes, a spike barely moves it.
SMOOTH_PLUGGED_S = 300
STEP_S = 20           # toward the expected direction (up on the charger, down on battery)
STEP_BACK_S = 120     # against it: still possible (a charger that cannot keep up), but slow
SAMPLE_S = 2          # background sampling, so the median has enough readings



def arm_power_cut(flag=POWER_CUT_FLAG):
    try:
        with open(flag, 'w') as f:
            f.write('1\n')
    except OSError:
        pass


def _median(xs):
    """statistics.median without the statistics module (it needs decimal, which
    OpenWrt's python3-light leaves out)."""
    if not xs:
        return None
    xs = sorted(xs)
    mid = len(xs) // 2
    return xs[mid] if len(xs) % 2 else (xs[mid - 1] + xs[mid]) / 2

class PiSugarI2C:
    def __init__(self, read=i2c.read_registers, write=i2c.write_register, bus=BUS, address=ADDRESS,
                 clock=time.monotonic, curve=None):
        self._read, self._write, self.bus, self.address = read, write, bus, address
        self.clock = clock
        self.curve = curve or SavedCurve()
        self.recent = []   # (time, percent, voltage)
        self.shown = self.shown_at = self.shown_source = None
        self.lock = threading.Lock()

    def sample(self):
        """One background reading into the median window (Node01 on a charger, 2026-10-08:
        the chip gave 13-50% within one minute, so a few readings are not enough)."""
        v, pct = self.read(VOLTAGE, 2), self.read(PERCENT)
        raw_percent = pct[0] if pct and pct[0] <= 100 else None
        raw_voltage = round(((v[0] << 8) | v[1]) / 1000, 3) if v else None
        if raw_percent is not None or raw_voltage is not None:
            with self.lock:
                self.recent.append((self.clock(), raw_percent, raw_voltage))
                self._trim()

    def start_sampler(self, interval=SAMPLE_S):
        def loop():
            while True:
                try:
                    self.sample()
                except Exception:
                    pass
                time.sleep(interval)
        threading.Thread(target=loop, daemon=True).start()

    def _trim(self):
        now = self.clock()
        self.recent = [r for r in self.recent if now - r[0] <= SMOOTH_PLUGGED_S]

    def smoothed(self, percent, voltage, window=SMOOTH_S):
        now = self.clock()
        with self.lock:
            if percent is not None or voltage is not None:
                self.recent.append((now, percent, voltage))
            self._trim()
            recent = [r for r in self.recent if now - r[0] <= window]
        return (_median([r[1] for r in recent if r[1] is not None]),
                _median([r[2] for r in recent if r[2] is not None]))

    def steady(self, target, source, plugged=False):
        """Move the shown percent toward target: 1 point per STEP_S in the direction the
        battery should be going, 1 point per STEP_BACK_S against it."""
        now = self.clock()
        if target is None:
            return None
        if self.shown is None or source != self.shown_source:
            self.shown = float(target)
        else:
            dt = max(0.0, now - self.shown_at)
            up, down = (dt / STEP_S, dt / STEP_BACK_S) if plugged else (dt / STEP_BACK_S, dt / STEP_S)
            self.shown += max(-down, min(up, target - self.shown))
        self.shown_at, self.shown_source = now, source
        return round(self.shown)

    def read(self, register, length=1):
        """Read twice and accept only matching answers, as the MCU can return noise."""
        a = self._read(self.bus, self.address, register, length)
        b = self._read(self.bus, self.address, register, length)
        return a if a and a == b else None

    def ctr1(self):
        r = self.read(CTR1)
        return r[0] if r else None

    def ctr2(self):
        r = self.read(CTR2)
        return r[0] if r else None

    def _change(self, register, set_bits=0, clear_bits=0):
        """Set/clear bits of one control register under the write-protect unlock.
        Returns the value read back, or None."""
        before = self.read(register)
        if not before:
            return None
        wanted = (before[0] | set_bits) & ~clear_bits & 0xFF
        if wanted != before[0]:
            ok = self._write(self.bus, self.address, WRITE_PROTECT, UNLOCK)
            try:
                ok and self._write(self.bus, self.address, register, wanted)
            finally:
                self._write(self.bus, self.address, WRITE_PROTECT, LOCK)   # always relock
        after = self.read(register)
        return after[0] if after else None

    def soft_shutdown(self):
        v = self.ctr2()
        return None if v is None else bool(v & SOFT_SHUTDOWN)

    def set_soft_shutdown(self, enabled):
        """On: a long press asks the Pi to shut down (CTR2 bit 3) instead of cutting power."""
        v = self._change(CTR2, SOFT_SHUTDOWN, 0) if enabled else self._change(CTR2, 0, SOFT_SHUTDOWN | LONG_PRESS)
        return None if v is None else bool(v & SOFT_SHUTDOWN)

    def long_pressed(self):
        v = self.ctr2()
        return v is not None and bool(v & SOFT_SHUTDOWN) and bool(v & LONG_PRESS)

    def clear_long_press(self):
        return self._change(CTR2, 0, LONG_PRESS)

    def cut_power(self):
        """Turn the PiSugar's 5 V output off: the Pi loses power at once. Only for the very
        end of a shutdown, after the file systems are read-only."""
        return self._change(CTR1, 0, OUTPUT_ON)

    def status(self):
        ctr1 = self.ctr1()
        if ctr1 is None:
            return {'available': False, 'source': 'pisugar-i2c', 'message': 'PiSugar not responding'}
        v, pct = self.read(VOLTAGE, 2), self.read(PERCENT)
        raw_percent = pct[0] if pct and pct[0] <= 100 else None
        raw_voltage = round(((v[0] << 8) | v[1]) / 1000, 3) if v else None
        plugged, allowed = bool(ctr1 & PLUGGED), bool(ctr1 & CHARGE_ALLOWED)
        window = SMOOTH_PLUGGED_S if plugged else SMOOTH_S
        percent, voltage = self.smoothed(raw_percent, raw_voltage, window)
        chip = None if percent is None else round(percent)
        saved = self.curve.get()
        # A calibrated curve only holds on battery: a charger lifts the voltage.
        if saved and not plugged and voltage is not None:
            percent, percent_source = round(percent_from_curve(voltage, saved['curve'])), 'calibrated'
        else:
            percent, percent_source = chip, 'chip'
        target, percent = percent, self.steady(percent, percent_source, plugged)
        out = {
            'available': True,
            'source': 'pisugar-i2c',
            'model': 'PiSugar 3',
            'battery_percent': percent,
            'battery_voltage': None if voltage is None else round(voltage, 3),
            'battery_percent_now': raw_percent,
            'battery_percent_chip': chip,
            'percent_source': percent_source,
            'battery_percent_target': target,
            'smoothed_over_s': window,
            'battery_current': None,
            'external_power': plugged,
            'charging_allowed': allowed,
            'charging': (plugged and allowed and percent is not None and percent < 100) if percent is not None else None,
            'charging_inferred': True,
            'auto_power_on': bool(ctr1 & AUTO_ON),
            'auto_power_on_config': None,   # no service config: the hardware is the setting
        }
        out['state'] = describe(out)
        return out

    def set_auto_power_on(self, enabled):
        """Change CTR1 bit 4 under the write-protect unlock, then read it back."""
        if not isinstance(enabled, bool):
            raise ValueError('enabled must be true or false')
        before = self.ctr1()
        if before is None:
            return {'requested': enabled, 'config': None, 'hardware': None, 'verified': False,
                    'reply': 'PiSugar not responding'}
        wanted = (before | AUTO_ON) if enabled else (before & ~AUTO_ON & 0xFF)
        ok = True
        if wanted != before:
            ok = self._write(self.bus, self.address, WRITE_PROTECT, UNLOCK)
            try:
                ok = ok and self._write(self.bus, self.address, CTR1, wanted)
            finally:
                self._write(self.bus, self.address, WRITE_PROTECT, LOCK)   # always relock
        after = self.ctr1()
        hardware = None if after is None else bool(after & AUTO_ON)
        return {'requested': enabled, 'config': hardware, 'hardware': hardware,
                'verified': ok and hardware is enabled, 'reply': None if ok else 'I2C write failed'}


class AutoPiSugar:
    """pisugar-server when it answers (e.g. Raspberry Pi OS builds), else direct I2C."""

    def __init__(self, server, direct):
        self.server, self.direct = server, direct

    def active(self):
        return self.server if self.server.status().get('available') else self.direct

    def status(self):
        s = self.server.status()
        return s if s.get('available') else self.direct.status()

    def set_auto_power_on(self, enabled):
        return self.active().set_auto_power_on(enabled)


def main(argv=None):
    """`python3 -m field_extension.pisugar_i2c cut-power`: used by /etc/init.d/field-power-off."""
    import sys
    args = sys.argv[1:] if argv is None else argv
    if args == ['cut-power']:
        PiSugarI2C().cut_power()
        return 0
    if args == ['soft-shutdown']:
        print({None: 'unknown', True: 'on', False: 'off'}[PiSugarI2C().soft_shutdown()])
        return 0
    print('usage: python3 -m field_extension.pisugar_i2c cut-power|soft-shutdown')
    return 2


if __name__ == '__main__':
    raise SystemExit(main())
