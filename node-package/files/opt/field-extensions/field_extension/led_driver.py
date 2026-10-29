"""Status LED driver: the one owner of the RGB LED (spec 20-22).

Runs as its own small process. Once a second it gathers readings, resolves the
logical state with led.py, and only when the pattern changes does it restart
`gpioset` (libgpiod 2.x, present on stock OpenMANET 1.8).
- One gpioset holds the "off" pins low.
- Another drives the "on" pins: held high at 100%, or toggled with a 10 ms
  software-PWM cycle for lower brightness, and for blinks. RC1 also used
  gpioset with software PWM.
Pins come from node.conf (LED_RED/LED_GREEN/LED_BLUE) and are validated against
the forbidden list; reference wiring is R27 G22 B26, common cathode, active-high.
"""
import signal
import subprocess
import threading
import time
from pathlib import Path

from . import led, maintenance, radios
from .capabilities import Detector
from .core import Node, read

STATE_DIR = Path('/etc/field-extensions/state')
COLORS = {'off': (), 'red': ('red',), 'green': ('green',), 'blue': ('blue',),
          'magenta': ('red', 'blue'), 'cyan': ('green', 'blue'), 'amber': ('red', 'green'),
          'white': ('red', 'green', 'blue')}
# Per-channel share of the brightness, for mixed colours. With 330 ohm on every leg the
# green die is far brighter than red, so full red + full green reads yellow-green.
# RC1 drove amber as red solid + green at about 25% PWM (Node02 audit 2026-10-04).
MIX = {'amber': {'green': 25}}
PWM_MS = 10


def toggle_periods(brightness, blink=None):
    """gpioset -t periods (ms) for the "on" pins, or None to hold them high.

    Lines start high; each period ends with a toggle, so the list alternates on/off.
    """
    on = max(1, round(PWM_MS * brightness / 100))
    pwm = [] if brightness >= 100 else [on, PWM_MS - on]
    if not blink:
        return pwm or None
    on_ms, off_ms = (int(x * 1000) for x in blink)
    if not pwm:
        return [on_ms, off_ms]
    cycles = max(1, on_ms // PWM_MS)
    # PWM during the on-phase; the last toggle of the on-phase leaves the line low for the off-phase.
    return pwm * (cycles - 1) + [on, PWM_MS - on + off_ms]


GAUGE_ON_MS, GAUGE_OFF_MS, GAUGE_GROUP_MS = 250, 250, 900
BATTERY_AVG_S = 30


def gauge_blinks(percent):
    """One blink per 10%, rounded to the nearest 10 (46% -> 5, 45% -> 5, 44% -> 4),
    at least 1 so a near-empty battery still answers (Stephen, 2026-10-06)."""
    if not isinstance(percent, (int, float)):
        return 0
    return min(10, max(1, int(percent + 5) // 10))


def gauge_pattern(percent):
    n = gauge_blinks(percent)
    if not n:
        return None
    # A finite toggle list ending in 0 makes gpioset stop after the last blink.
    # Blinks come in groups of five with a longer gap, so 7 reads as "5, 2".
    seq = []
    for i in range(n):
        seq += [GAUGE_ON_MS, GAUGE_GROUP_MS if (i + 1) % 5 == 0 else GAUGE_OFF_MS]
    seq[-1] = 0
    return {'color': 'green', 'sequence': seq, 'seconds': sum(seq) / 1000 + 0.6}


class ButtonWatcher:
    """Detects a single short press of the PiSugar button (CTR1 bit 0), read over I2C.

    Verified on Node01 2026-10-06: CTR1 reads 0x7c idle and 0x7d while pressed; the
    tap register 0x08 stays 0 on this firmware. One sample counts (each read is
    already a matched double read), since a quick tap lasts about 0.1-0.2 s.
    Holds longer than LONG_S are left alone (the PiSugar's own power-off gesture),
    and a second press within GAP_S (a double tap) cancels the first.
    Click, then press again and hold for HOLD_OFF_S: a clean shutdown (sets power_off),
    instead of the PiSugar's plain long press, which cuts the power without one.
    """
    BIT, LONG_S, GAP_S, HOLD_OFF_S = 0x01, 1.5, 1.0, 2.0

    def __init__(self, read_ctr1, clock=time.monotonic):
        self.read, self.clock = read_ctr1, clock
        self.down_since = None
        self.pending = None      # release time of a short press, waiting out GAP_S
        self.second_since = None  # click-then-hold: when the second press started
        self.power_off = False

    def poll(self):
        v = self.read()
        now = self.clock()
        if v is not None and v & self.BIT:
            if self.down_since is None:
                self.down_since = now
                if self.pending is not None:      # second press: a double tap, or click-then-hold
                    self.pending, self.down_since, self.second_since = None, float('-inf'), now
            if self.second_since is not None and now - self.second_since >= self.HOLD_OFF_S:
                self.power_off, self.second_since = True, None
            return False
        if v is not None:
            self.second_since = None
        if v is not None and self.down_since is not None:
            held = now - self.down_since
            self.down_since = None
            if held <= self.LONG_S:
                self.pending = now
        if self.pending is not None and now - self.pending >= self.GAP_S:
            self.pending = None
            return True
        return False


CHARGE_CYCLE_S = 10.0         # charging: the status colour holds steady, green blinks every 10 s
# (state file charge-blink-seconds overrides it, 4-60 s)
CHARGE_BLINK_MS = (250, 250)   # one green blink (on, off) per 20% of charge
CHARGE_GAP_MS = 500           # dark pause before and after the blinks, so they stand apart


def charge_blinks(percent):
    """1 blink for 0-20%, 2 for 20-40% ... 5 for 80-100% (Stephen, 2026-10-23)."""
    if not isinstance(percent, (int, float)):
        return 1
    return min(5, max(1, int(percent // 20) + 1))


def runs_to_periods(runs):
    """[(state, ms), ...] -> (start state, gpioset toggle periods), equal states merged."""
    merged = []
    for state, ms in runs:
        if ms <= 0:
            continue
        if merged and merged[-1][0] == state:
            merged[-1][1] += ms
        else:
            merged.append([state, ms])
    if len(merged) > 1 and merged[0][0] == merged[-1][0] and merged[-1][1] > 1:
        # gpioset toggles at every period end, so the list must end in the opposite state
        # to the one it starts in. Joining the ends would shift this pin's timeline against
        # the others (the 1.0.4 cyan blinks); a 1 ms toggle at the very end keeps it aligned.
        merged[-1][1] -= 1
        merged.append([1 - merged[0][0], 1])
    return (merged[0][0] if merged else 0), [ms for _, ms in merged]


def pwm_runs(level, ms):
    """Hold a line at `level`% for `ms` with 10 ms software PWM."""
    on = round(PWM_MS * level / 100)
    return [(1, on), (0, PWM_MS - on)] * int(ms // PWM_MS)


def breath_runs(level, blink_level, blinks, cycle=CHARGE_CYCLE_S):
    """One charging cycle for one pin: a dark pause, `blinks` blinks at blink_level, a dark
    pause, then the status colour (level) held steady. The steady part runs to twice the
    cycle: the driver restarts the pattern every cycle, so the loop is only a fallback."""
    on_ms, off_ms = CHARGE_BLINK_MS
    runs = [(0, CHARGE_GAP_MS)]
    for _ in range(blinks):
        runs += pwm_runs(blink_level, on_ms) + [(0, off_ms)]
    runs += [(0, CHARGE_GAP_MS)]
    used = sum(ms for _, ms in runs)
    return runs + pwm_runs(level, max(PWM_MS, 2 * cycle * 1000 - used))


def breath_commands(pins, color, brightness, blinks=1, chip='gpiochip0', cycle=CHARGE_CYCLE_S):
    """Charging (Stephen, 2026-10-24): the status colour stays steady; every 10 s the light
    goes dark briefly and blinks green once per 20% of charge, then returns to the colour."""
    lit = COLORS.get(color, ())
    groups = {}   # pins with the same waveform share one gpioset, so they stay in step
    for c in ('red', 'green', 'blue'):
        share = MIX.get(color, {}).get(c, 100) if c in lit else 0
        level = brightness * share / 100
        runs = breath_runs(level, brightness if c == 'green' else 0, blinks, cycle)
        start, periods = runs_to_periods(runs)
        groups.setdefault((start, tuple(periods) if len(periods) > 1 else ()), []).append(pins[c])
    cmds = []
    for (start, periods), group in groups.items():
        cmd = ['gpioset', '-c', chip]
        if periods:
            cmd += ['-t', ','.join(str(p) for p in periods)]
        cmds.append(cmd + [f'{p}={start}' for p in group])
    return cmds


def commands(pins, pattern, brightness, chip='gpiochip0'):
    color = pattern.get('color', 'off')
    if pattern.get('breath'):
        return breath_commands(pins, color, brightness, pattern.get('blinks', 1), chip,
                               pattern.get('cycle', CHARGE_CYCLE_S))
    lit = COLORS.get(color, ())
    off = [pins[c] for c in ('red', 'green', 'blue') if c not in lit]
    cmds = []
    if off:
        cmds.append(['gpioset', '-c', chip] + [f'{p}=0' for p in off])
    # One gpioset per distinct duty level, so a mixed colour can dim one channel.
    # Software PWM has 1 ms steps in a 10 ms cycle, so at low brightness the dimmed
    # channel would floor at 1 ms and match the others; scale everything up instead
    # so the mix (the hue) survives, at the cost of a slightly brighter light.
    shares = {c: MIX.get(color, {}).get(c, 100) for c in lit}
    shortest_ms = min([PWM_MS * brightness * s / 10000 for s in shares.values()] or [1])
    scale = max(1.0, 1 / shortest_ms)
    levels = {}
    for c, share in shares.items():
        level = min(100, max(1, round(brightness * share / 100 * scale)))
        levels.setdefault(level, []).append(pins[c])
    for level, on in sorted(levels.items(), reverse=True):
        periods = pattern.get('sequence') or toggle_periods(level, pattern.get('blink'))
        cmd = ['gpioset', '-c', chip]
        if periods:
            cmd += ['-t', ','.join(str(p) for p in periods)]
        cmds.append(cmd + [f'{p}=1' for p in on])
    return cmds


class Driver:
    def __init__(self, node, pins, spawn=None, state_dir=STATE_DIR):
        self.node, self.pins, self.state = node, pins, Path(state_dir)
        self.spawn = spawn or (lambda argv: subprocess.Popen(argv, stdin=subprocess.DEVNULL,
                                                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        self.procs, self.current, self.loop, self.started = [], None, 0, 0.0
        self.override, self.override_until = None, 0.0
        self.battery_seen = []   # (time, percent) from recent ticks, for a steady gauge
        self.last_readings = {}

    def setting(self, name, default):
        try:
            return int((self.state / name).read_text().strip())
        except (OSError, ValueError):
            return default

    def readings(self):
        caps = self.node.capabilities()
        battery = self.node.pisugar.status()
        neigh = radios.neighbors(self.node.run, self.node.halow_iface())
        uptime = (read(self.node.file('/proc/uptime')) or '0').split()[0]
        return {
            'enabled': self.setting('led-enabled', 1) == 1,
            'booting': float(uptime) < 60,
            'battery_percent': battery.get('battery_percent') if battery.get('available') else None,
            'external_power': battery.get('external_power') if battery.get('available') else None,
            'charging': battery.get('charging') if battery.get('available') else None,
            'mesh_neighbors': neigh.get('count', 0),
            'internet': caps.get('internet_gateway', False),
            'lora_available': caps.get('meshtastic', False),
            'gps_fix': radios.gps(timeout=1.0).get('state') == 'FIX',
            'admin': maintenance.status()['active'],
            'shutting_down': maintenance.shutting_down(),
        }

    def charge_cycle(self):
        """Seconds between charge-level blink bursts (state file charge-blink-seconds)."""
        return float(min(60, max(4, self.setting('charge-blink-seconds', int(CHARGE_CYCLE_S)))))

    def frame(self, resolved):
        """The pattern to show this second, including RC1's periodic pulses."""
        self.loop = (self.loop + 1) % 10
        if resolved.get('pattern') == 'BATTERY_LOW':
            return led.PATTERNS['BATTERY_LOW'] if self.loop == 0 else led.PATTERNS[resolved['base_pattern']]
        if resolved.get('charge_overlay') and resolved.get('color') not in (None, 'off'):
            # Charging: one long-running breathing pattern (it carries its own green flash),
            # so it is not restarted every second; the GPS pulse waits until charging ends.
            return {'color': resolved['color'], 'breath': True, 'blinks': self.charge_level_blinks(),
                    'cycle': self.charge_cycle()}
        if resolved.get('gps_overlay') and self.loop in (5, 6):   # 2 s blue every 10 s
            return led.GPS_OVERLAY
        return resolved

    def charge_level_blinks(self):
        """Blinks for the 30 s average percent, held until it is 2 points past a band edge,
        so a reading on a boundary cannot make the pattern restart back and forth."""
        seen = [p for _, p in self.battery_seen]
        pct = sum(seen) / len(seen) if seen else (self.last_readings or {}).get('battery_percent')
        n = charge_blinks(pct)
        held = getattr(self, 'charge_n', None)
        if held and isinstance(pct, (int, float)) and n != held and held * 20 - 22 < pct < held * 20 + 2:
            n = held
        self.charge_n = n
        return n

    def note_battery(self, percent, now):
        if isinstance(percent, (int, float)):
            self.battery_seen.append((now, percent))
        self.battery_seen = [(t, p) for t, p in self.battery_seen if now - t <= BATTERY_AVG_S]

    def steady_battery(self, now):
        """Average of the last BATTERY_AVG_S of readings: the PiSugar's voltage-based
        percent swings several points second to second on battery (46/52/50 on Node01)."""
        status = self.node.pisugar.status()
        self.note_battery(status.get('battery_percent') if status.get('available') else None, now)
        if not self.battery_seen:
            return None
        return sum(p for _, p in self.battery_seen) / len(self.battery_seen)

    def show_battery(self, now=None):
        """Button pressed: blink the battery level in green, then go back to normal."""
        now = time.monotonic() if now is None else now
        pattern = gauge_pattern(self.steady_battery(now))
        if pattern:
            self.override, self.override_until = pattern, now + pattern['seconds']
            self.current = None
            self.apply(pattern, 100)

    def apply(self, pattern, brightness, now=None):
        now = time.monotonic() if now is None else now
        key = (pattern.get('color'), tuple(pattern.get('blink') or pattern.get('sequence') or ()),
               bool(pattern.get('breath')), pattern.get('blinks'), pattern.get('cycle'), brightness)
        if key == self.current:
            # Charging runs one gpioset per waveform, and separate processes drift apart
            # (the blue came on during the green blinks, 2026-10-24). Restart them together
            # every cycle, so the blinks always come right after a fresh, in-step start.
            if not pattern.get('breath') or now - self.started < pattern.get('cycle', CHARGE_CYCLE_S) - 0.5:
                return
        self.stop()
        self.procs = [self.spawn(c) for c in commands(self.pins, pattern, brightness)]
        self.current, self.started = key, now

    def stop(self):
        for p in self.procs:   # only our own gpioset processes, never killall
            try:
                p.terminate()
                p.wait(timeout=2)
            except Exception:
                pass
        self.procs, self.current = [], None

    def tick(self, now=None):
        now = time.monotonic() if now is None else now
        if self.override and now < self.override_until:
            return {'state': 'BATTERY_GAUGE'}
        self.override = None
        try:
            r = self.readings()
            self.last_readings = r
            self.note_battery(r.get('battery_percent'), now)
            resolved = led.resolve(r)
        except Exception:
            resolved = {'state': 'FAULT', **led.PATTERNS['FAULT']}
        brightness = self.setting('led-brightness', 100)
        if brightness not in (10, 25, 50, 75, 100):
            brightness = 100
        self.apply(self.frame(resolved), brightness, now)
        return resolved


def pins_from_conf(conf):
    try:
        pins = {c: int(conf['LED_' + c.upper()]) for c in ('red', 'green', 'blue')}
    except (KeyError, ValueError):
        return None
    return pins if led.validate_pins(pins) else None


def main():
    detector = Detector()
    pins = pins_from_conf(detector.node_conf()) or dict(led.REFERENCE_PINS)
    if not led.validate_pins(pins):
        print('LED pins invalid or forbidden; not starting.', flush=True)
        return
    node = Node()
    driver = Driver(node, pins)
    direct = getattr(node.pisugar, 'direct', None)
    button = ButtonWatcher(direct.ctr1) if direct is not None else None
    if direct is not None:
        direct.start_sampler()   # steadier battery % for the light's low-battery warning
    running = [True]
    signal.signal(signal.SIGTERM, lambda *a: running.__setitem__(0, False))
    # The button is sampled on its own thread: a tick can take ~1 s (GPS, mesh, battery
    # reads), and a quick second tap of a double tap would be missed in that time.
    tapped = threading.Event()

    long_pressed = threading.Event()
    soft = direct is not None
    # Set once a clean shutdown has started: the light then keeps blinking red (the normal
    # tick would put the status colour back while poweroff is still stopping services), and
    # the PiSugar stays in soft-shutdown mode so another long press cannot cut the power.
    shutting = [False]
    shutting_since = [0.0]

    def watch_button():
        n = 0
        while running[0]:
            if button.poll():
                tapped.set()
            if soft and not shutting[0]:
                try:
                    if n % 200 == 0:   # every 10 s: keep the PiSugar mode as set
                        direct.set_soft_shutdown(driver.setting('long-press-shutdown', 1) == 1)
                    if n % 5 == 0 and direct.long_pressed():
                        long_pressed.set()
                except Exception:
                    pass
            n += 1
            time.sleep(0.05)

    if button:
        threading.Thread(target=watch_button, daemon=True).start()
    if direct is not None:
        from .clock_sync import ClockSync
        clock = ClockSync(internet=lambda: node.capabilities().get('internet_gateway', False))

        def keep_time():
            try:
                clock.at_boot()
            except Exception:
                pass
            while running[0]:
                try:
                    clock.tick()
                except Exception:
                    pass
                time.sleep(300)

        threading.Thread(target=keep_time, daemon=True).start()
    def power_off(why):
        if shutting[0]:
            return
        shutting[0], shutting_since[0] = True, time.monotonic()
        print('%s: shutting down cleanly.' % why, flush=True)
        try:
            from .recovery import mark_clean
            mark_clean(why)
        except Exception:
            pass
        driver.apply(led.PATTERNS['SHUTTING_DOWN'], 100)
        if direct is not None:
            from .pisugar_i2c import arm_power_cut
            arm_power_cut()
        subprocess.run(['poweroff'], timeout=30, check=False)

    guard = None
    if direct is not None:
        from .power_guard import LowBatteryGuard
        guard = LowBatteryGuard(lambda: power_off('safe shutdown'))
    alerts, had_mesh, lora_sending = None, [False], [False]
    try:
        from . import lora as lora_svc
        from .node_alerts import Alerts, cpu_temp
        alerts = Alerts(lora_svc.multicast_sender(lora_svc.phone_address()))
    except Exception as e:
        print('Alerts to ATAK off: %s' % e, flush=True)
    try:
        step = 0
        while running[0]:
            if shutting[0]:
                if time.monotonic() - shutting_since[0] < 180:
                    time.sleep(0.1)
                    continue
                print('Still running 3 minutes after poweroff: back to normal.', flush=True)
                shutting[0] = False
            if button is not None and button.power_off:
                button.power_off = False
                power_off('button shutdown')
            if long_pressed.is_set():
                long_pressed.clear()
                try:
                    direct.clear_long_press()
                except Exception:
                    pass
                power_off('button long press')
            if tapped.is_set():
                tapped.clear()
                if driver.setting('led-enabled', 1) == 1:
                    driver.show_battery()
            if step % 10 == 0:
                driver.tick()
                if guard is not None:
                    try:
                        guard.check(node.pisugar.status())
                        if (alerts is not None and guard.low_since is not None and not guard.done
                                and driver.setting('alerts-enabled', 0) == 1):
                            alerts.shutdown_warning(max(0, guard.HOLD_S - (time.monotonic() - guard.low_since)))
                        elif alerts is not None and guard.low_since is None:
                            alerts.active.discard('shutdown')
                    except Exception:
                        pass
                if alerts is not None and driver.setting('alerts-enabled', 0) == 1:
                    try:
                        if step % 100 == 0:
                            from . import identity
                            alerts.name = identity.load()['callsign']
                        r = dict(driver.last_readings)
                        had_mesh[0] = had_mesh[0] or r.get('mesh_neighbors', 0) > 0
                        if step % 100 == 0:
                            try:
                                lora_sending[0] = bool(lora_svc.request({'cmd': 'status'}).get('sending_over_lora'))
                            except Exception:
                                lora_sending[0] = False
                        alerts.observe({**r, 'had_mesh': had_mesh[0], 'lora_sending': lora_sending[0],
                                        'temperature_c': cpu_temp()})
                    except Exception:
                        pass
            step += 1
            time.sleep(0.1)
    finally:
        driver.stop()
        # With Field Extension stopped nobody reads the long-press flag, so give the
        # PiSugar its own hard power-off back (a running driver turns soft shutdown on again).
        if soft and not shutting[0]:
            try:
                direct.set_soft_shutdown(False)
            except Exception:
                pass
        # Leave the LED dark: a short-lived gpioset that sets all pins low and exits.
        subprocess.run(['gpioset', '-c', 'gpiochip0', '-t', '0'] + [f'{p}=0' for p in pins.values()],
                       timeout=3, check=False)


if __name__ == '__main__':
    main()
