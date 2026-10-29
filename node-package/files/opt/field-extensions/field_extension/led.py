"""Status LED state model (spec 20-22).

Logical states are resolved here from normalized readings; only the driver
turns a pattern into GPIO. Colors and timings follow the installed RC1
field-led.sh so nothing visible changes for the operator:

  red solid        platform starting/unhealthy (RC1 boot state)
  red fast flash   battery < 10%
  red pulse        battery < 20% (every 10th loop)
  blue 2 s pulse   GPS fix (every 10th loop, overlay)
  breathing        charging: color steady, green blinks (1 per 20%) every 10 s
                   (a dark flash when the color is green already); replaces the GPS pulse meanwhile
  magenta          mesh neighbor + internet
  green            mesh neighbor only
  cyan             internet only
  amber            neither

States RC1 never had, all approved by Stephen (2026-10-26):
  red short blink  SHUTTING_DOWN: a clean shutdown or reboot has started
  white            ADMIN (maintenance): the node is being updated or worked on, don't unplug
  blue slow blink  LORA_ONLY: no mesh neighbour, LoRa radio still there
"""

# Reference hardware, verified from the installed RC1 code and node.conf.
REFERENCE_PINS = {'red': 27, 'green': 22, 'blue': 26}
FORBIDDEN_PINS = {12, 17, 25}  # RC1 rejects these; GPIO17 showed alternate pinmux.

# Highest priority first (spec 21).
PRIORITY = ['FAULT', 'SHUTTING_DOWN', 'WARNING', 'ADMIN', 'LORA_ONLY',
            'MESH_CONNECTED', 'READY', 'INITIALIZING', 'BOOTING']

PATTERNS = {
    'OFF':              {'color': 'off'},
    'BOOTING':          {'color': 'red'},
    'INITIALIZING':     {'color': 'red'},
    'FAULT':            {'color': 'red'},
    'BATTERY_CRITICAL': {'color': 'red', 'blink': [0.5, 0.5]},
    'BATTERY_LOW':      {'color': 'red', 'pulse_every': 10},
    'SHUTTING_DOWN':    {'color': 'red', 'blink': [0.2, 0.8]},
    'ADMIN':            {'color': 'white'},
    'LORA_ONLY':        {'color': 'blue', 'blink': [1.0, 1.0]},
    'MESH_AND_INTERNET': {'color': 'magenta'},
    'MESH_ONLY':        {'color': 'green'},
    'INTERNET_ONLY':    {'color': 'cyan'},
    'NO_LINKS':         {'color': 'amber'},
}
GPS_OVERLAY = {'color': 'blue', 'seconds': 2, 'every': 10}


def validate_pins(pins):
    values = [pins.get(k) for k in ('red', 'green', 'blue')]
    if any(not isinstance(v, int) or not 0 <= v <= 57 for v in values):
        return False
    if len(set(values)) != 3 or FORBIDDEN_PINS & set(values):
        return False
    return True


def resolve(r):
    """Pick the one logical state and pattern for a dict of normalized readings.

    Keys used: enabled, platform_healthy, shutting_down, admin, battery_percent, external_power, charging,
    mesh_neighbors, internet, lora_available, booting, gps_fix.
    Missing readings never raise; they just do not trigger their state.
    """
    if r.get('enabled') is False:
        return {'state': 'OFF', **PATTERNS['OFF'], 'gps_overlay': False, 'charge_overlay': False}
    active = {}
    if r.get('booting'):
        active['BOOTING'] = 'BOOTING'
    if r.get('platform_healthy') is False:
        active['FAULT'] = 'FAULT'
    if r.get('shutting_down'):
        active['SHUTTING_DOWN'] = 'SHUTTING_DOWN'
    pct = r.get('battery_percent')
    if isinstance(pct, (int, float)) and pct < 20:
        # RC1 uses strict < 10 and < 20, so exactly 20% is normal. While external power
        # is plugged in the battery is charging, so only a critical level still warns.
        if pct < 10:
            active['WARNING'] = 'BATTERY_CRITICAL'
        elif r.get('external_power') is not True:
            active['WARNING'] = 'BATTERY_LOW'
    if r.get('admin'):
        active['ADMIN'] = 'ADMIN'
    neighbors = r.get('mesh_neighbors') or 0
    internet = r.get('internet') is True
    if neighbors > 0:
        active['MESH_CONNECTED'] = 'MESH_AND_INTERNET' if internet else 'MESH_ONLY'
    elif r.get('lora_available'):
        active['LORA_ONLY'] = 'LORA_ONLY'
    active['READY'] = 'INTERNET_ONLY' if internet else 'NO_LINKS'
    for state in PRIORITY:
        if state in active:
            pattern = active[state]
            # Battery-low pulses ride on top of the network color, as in RC1.
            base = None
            if pattern == 'BATTERY_LOW':
                base = next(active[s] for s in PRIORITY[PRIORITY.index('WARNING') + 1:] if s in active)
            overlay = r.get('gps_fix') is True and state not in ('FAULT', 'SHUTTING_DOWN') \
                and pattern != 'BATTERY_CRITICAL'
            charge = r.get('charging') is True and state not in ('FAULT', 'SHUTTING_DOWN', 'BOOTING') \
                and pattern != 'BATTERY_CRITICAL'
            out = {'state': state, 'pattern': pattern, **PATTERNS[pattern], 'gps_overlay': overlay,
                   'charge_overlay': charge}
            if base:
                out['base_pattern'] = base
            return out
