"""Platform and hardware capability detection (spec 6, 52, 54).

Detection is by what exists on the node, never by hostname. Every probe is
read-only and failure means "not detected", never an exception.
"""
import re
from pathlib import Path

from . import __version__

NODE_CONF = '/etc/field-extensions/node.conf'


class Detector:
    def __init__(self, root='/', pisugar=None, routes=None, ina219=None, reach=None):
        self.root = Path(root)
        self.pisugar = pisugar          # callable -> bool, injected for tests
        self.routes = routes            # callable -> text of `ip route`
        self.ina219 = ina219            # callable -> bool
        self.reach = reach              # callable -> bool: real internet behind the default route

    def p(self, path):
        return self.root / path.lstrip('/')

    def text(self, path):
        try:
            return self.p(path).read_text().strip()
        except (OSError, UnicodeError):
            return None

    def wiphys(self):
        """Map each wireless phy to its driver name."""
        out = {}
        base = self.p('/sys/class/ieee80211')
        try:
            for phy in sorted(base.iterdir()):
                drv = phy / 'device' / 'driver'
                out[phy.name] = drv.resolve().name if drv.exists() else 'unknown'
        except OSError:
            pass
        return out

    def node_conf(self):
        cfg = {}
        for line in (self.text(NODE_CONF) or '').splitlines():
            key, sep, value = line.partition('=')
            if sep and not key.startswith('#'):
                cfg[key.strip()] = value.strip().strip('"\'')
        return cfg

    def openmanet_version(self):
        release = self.text('/etc/openwrt_release') or ''
        m = re.search(r"DISTRIB_DESCRIPTION='([^']*)'", release)
        if m and 'manet' in m.group(1).lower():
            return m.group(1)
        if self.p('/etc/init.d/openmanetd').exists():
            return 'present (version unknown)'
        return None

    def detect(self):
        phys = self.wiphys()
        halow = [p for p, d in phys.items() if 'morse' in d.lower()]
        short = [p for p, d in phys.items() if 'morse' not in d.lower()]
        conf = self.node_conf()
        # The LED driver falls back to the reference pins when node.conf has none, so only a
        # node.conf that names pins badly counts as "no LED".
        led_keys = [conf.get(k) for k in ('LED_RED', 'LED_GREEN', 'LED_BLUE')]
        led_pins = all(v is None for v in led_keys) or all((v or '').isdigit() for v in led_keys)
        routes = self.routes() if self.routes else ''
        caps = {
            'openmanet': self.openmanet_version() is not None,
            'openmanet_version': self.openmanet_version(),
            'field_extensions_version': self.text('/etc/field-extensions/version') or __version__,
            'batman': self.p('/sys/module/batman_adv').exists(),
            'halow': bool(halow),
            'halow_phys': halow,
            'short_range_wifi': bool(short),
            'short_range_phys': short,
            # USB Meshtastic radios enumerate as ttyACM (Node02: Heltec on ttyACM0).
            'meshtastic': any(h in d.lower() for h in ('acm', 'ttyusb', 'heltec', 'meshtastic', 'espressif')
                              for d in self.serial_devices()),
            'gps': self.p('/etc/init.d/gpsd').exists(),
            'pisugar': bool(self.pisugar()) if self.pisugar else self.p('/etc/init.d/pisugar-server').exists(),
            'battery_monitor': bool(self.ina219()) if self.ina219 else False,
            'status_led': led_pins and self.p('/dev/gpiochip0').exists(),
            'internet_gateway': (routes.startswith('default ') or '\ndefault ' in routes)
                                and (self.reach() if self.reach else True),
        }
        caps['platform'] = 'openmanet' if caps['openmanet'] else 'unknown'
        return caps

    def serial_devices(self):
        # OpenWrt has no udev, so /dev/serial/by-id is usually missing; the radio then shows
        # only as /dev/ttyACM* (Node02 nRF52840) or /dev/ttyUSB* (Node01 ESP32-S3 bridge chips).
        names = []
        try:
            names += [p.name for p in self.p('/dev/serial/by-id').iterdir()]
        except OSError:
            pass
        try:
            names += [p.name for p in self.p('/dev').iterdir() if p.name.startswith(('ttyACM', 'ttyUSB'))]
        except OSError:
            pass
        return names
