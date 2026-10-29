"""OpenMANET update and compatibility check (read-only).

Asks GitHub for OpenMANET firmware releases, picks the image that matches
this node's board, HaLow chip and bus, and says what an upgrade would
change for Field Extension. This module only reads; installing is upgrade.py,
which the web UI starts only after login and confirmation.

Image names look like openmanet-1.8.0-rpi4-mm6108-spi-squashfs-sysupgrade.img.gz.
The node's image key ("rpi4-mm6108-spi") is detected from /sys, and node.conf
OPENMANET_IMAGE overrides it when detection is wrong.

The answer is cached in RAM (/tmp) so phones refreshing the page do not use
up GitHub's 60-requests-per-hour limit or wear the SD card.
"""
import json
import re
import time
import urllib.request
from pathlib import Path

RELEASES_URL = 'https://api.github.com/repos/OpenMANET/firmware/releases?per_page=15'
RELEASES_PAGE = 'https://github.com/OpenMANET/firmware/releases'
CACHE = Path('/tmp/field-extensions/openmanet-releases.json')
MAX_AGE = 6 * 3600       # normal re-check interval
MIN_AGE = 10 * 60        # "Check now" cannot hit GitHub more often than this
MAX_BYTES = 2 * 1024 * 1024

COMPATIBILITY = Path(__file__).with_name('compatibility.json')


def load_tested(path=COMPATIBILITY):
    """What this Field Extension build has actually been run on (compatibility.json)."""
    try:
        versions = json.loads(Path(path).read_text()).get('versions', {})
        return {k: (v['status'], v['detail']) for k, v in versions.items()}
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return {}


TESTED = load_tested()


def rating(v, tested=None):
    """Exact entry, else 'likely' for a patch release in a tested series, else 'untested'."""
    tested = TESTED if tested is None else tested
    text = version_text(v)
    if text in tested:
        return tested[text]
    if v and v[3] == 9:
        for other, (status, _) in tested.items():
            o = parse_version(other)
            if status == 'tested' and o and o[:2] == v[:2] and o[3] == 9:
                return 'likely', f'Patch release in the tested {o[0]}.{o[1]} series; not run yet.'
    return 'untested', 'Not yet run with Field Extension. Try it on one node first.'

VERSION_RE = re.compile(r'(\d+)\.(\d+)\.(\d+)(?:-?(rc|beta|alpha)\.?(\d+))?', re.I)
IMAGE_RE = re.compile(r'^openmanet-(?P<version>[^-]+(?:-RC\d+)?)-(?P<key>[a-z0-9]+-mm\d{4}-(?:spi|sdio|usb))'
                      r'-squashfs-sysupgrade\.img\.gz$', re.I)
CLEAN_INSTALL_RE = re.compile(r'reset your device|fresh install|factory reset|clean install|re-?flash', re.I)
# Matches "Morse Micro Drivers 1.16" (1.6.0 notes) and "Morse Micro 2.0.x drivers" (1.8.0 notes).
DRIVER_RE = re.compile(r'Morse\s*Micro\s*(?:Drivers?\s*)?v?(\d+\.(?:\d+|x)(?:\.(?:\d+|x))?)', re.I)


def parse_version(text):
    """'1.8.0' -> (1, 8, 0, 9, 0); pre-releases sort below the final release."""
    m = VERSION_RE.search(text or '')
    if not m:
        return None
    major, minor, patch, kind, num = m.groups()
    rank = {'alpha': 0, 'beta': 1, 'rc': 2}.get((kind or '').lower(), 9)
    return int(major), int(minor), int(patch), rank, int(num or 0)


def version_text(v):
    if v is None:
        return None
    text = f'{v[0]}.{v[1]}.{v[2]}'
    return text if v[3] == 9 else text + '-' + ('alpha', 'beta', 'RC')[v[3]] + str(v[4])


def installed_version(release_text):
    """OpenMANET version from /etc/openwrt_release.

    Stock 1.8.0 (Node01, 2026-10-05): DISTRIB_ID='OpenMANET', DISTRIB_RELEASE='24.10'
    (the OpenWrt base), DISTRIB_DESCRIPTION='OpenMANET 24.10 1.8.0'.
    Derived builds name the base inside the description: "... (OpenWrt 24.10 / OpenMANET 1.6.5 base)".
    """
    fields = dict(re.findall(r"^(DISTRIB_\w+)='([^']*)'", release_text or '', re.M))
    desc = fields.get('DISTRIB_DESCRIPTION', '')
    m = re.search(r'OpenMANET\s+v?(\d+\.\d+\.\d+(?:-RC\d+)?)', desc, re.I)
    if m:
        return parse_version(m.group(1))
    if 'manet' in (fields.get('DISTRIB_ID', '') + desc).lower():
        # The OpenMANET version is the last x.y.z in the description; 24.10 is OpenWrt's.
        # OpenWrt bases are year-numbered (24.10.x), OpenMANET releases are not.
        found = [v for v in re.findall(r'\b(\d+\.\d+\.\d+(?:-RC\d+)?)\b', desc, re.I)
                 if int(v.split('.')[0]) < 20]
        if found:
            return parse_version(found[-1])
        release = parse_version(fields.get('DISTRIB_RELEASE', ''))
        return release if release and release[0] < 20 else None
    return None


class Probe:
    """Reads this node's board, HaLow chip, bus and driver version from /sys."""

    def __init__(self, root='/', halow_iface=None, run=None):
        self.root = Path(root)
        self.iface = halow_iface
        self.run = run

    def text(self, path):
        try:
            return (self.root / path.lstrip('/')).read_bytes().replace(b'\0', b' ').decode(errors='replace').strip()
        except OSError:
            return ''

    def board(self):
        model = self.text('/proc/device-tree/model')
        m = re.search(r'Raspberry Pi (\d)', model)
        if m:
            return 'rpi' + m.group(1)
        if 'venice' in model.lower():
            return 'venice'
        return None

    def bus(self):
        if not self.iface:
            return None
        try:
            sub = (self.root / f'sys/class/net/{self.iface}/device/subsystem').resolve().name
        except OSError:
            return None
        return {'spi': 'spi', 'sdio': 'sdio', 'usb': 'usb', 'mmc': 'sdio'}.get(sub)

    def chip(self):
        if not self.iface:
            return None
        dev = f'/sys/class/net/{self.iface}/device/'
        hay = ' '.join(self.text(dev + p) for p in ('modalias', 'of_node/compatible', 'uevent'))
        m = re.search(r'mm(\d{3})[0-9x]', hay, re.I)
        return f'mm{m.group(1)}8' if m else None

    def driver_version(self):
        v = self.text('/sys/module/morse/version')
        if not v and self.run:
            r = self.run(['morse_cli', '-i', self.iface or 'wlan0', 'version'])
            v = r.get('output', '') if r.get('ok') else ''
        # Plain "2.0.0", or morse_cli's "rel_1_16_4_2025_Sep_18" / "rel_mm8108_2_0_0".
        m = re.search(r'(\d+\.\d+\.\d+)', v) or re.search(r'rel_(?:mm\d+_)?(\d+)_(\d+)_(\d+)', v)
        if not m:
            return None
        return m.group(1) if m.lastindex == 1 else '.'.join(m.groups())

    def image_key(self, override=None):
        if override:
            return override.lower(), 'node.conf'
        parts = (self.board(), self.chip(), self.bus())
        if all(parts):
            return '-'.join(parts), 'detected'
        return None, 'unknown'


def fetch_releases(opener=None, url=RELEASES_URL, timeout=8):
    opener = opener or urllib.request.build_opener()
    req = urllib.request.Request(url, headers={'Accept': 'application/vnd.github+json',
                                               'User-Agent': 'field-extensions'})
    with opener.open(req, timeout=timeout) as response:
        data = response.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError('oversize response')
    releases = json.loads(data)
    if not isinstance(releases, list):
        raise ValueError('unexpected response')
    return releases


def slim(release):
    """Keep only what the check needs, so the cache stays small."""
    return {'tag': str(release.get('tag_name', '')), 'name': str(release.get('name') or ''),
            'prerelease': bool(release.get('prerelease')), 'draft': bool(release.get('draft')),
            'published_at': release.get('published_at'), 'url': release.get('html_url') or RELEASES_PAGE,
            'notes': str(release.get('body') or '')[:20000],
            'assets': [{'name': str(a.get('name', '')), 'url': a.get('browser_download_url')}
                       for a in release.get('assets') or []]}


class Releases:
    """Cached release list: fresh copy at most every MAX_AGE, or MIN_AGE on request."""

    def __init__(self, fetch=fetch_releases, cache=CACHE, clock=time.time):
        self.fetch, self.cache, self.clock = fetch, Path(cache), clock

    def load(self):
        try:
            return json.loads(self.cache.read_text())
        except (OSError, ValueError):
            return None

    def get(self, refresh=False):
        cached = self.load()
        age = self.clock() - cached['checked_at'] if cached else None
        if cached and age < (MIN_AGE if refresh else MAX_AGE):
            return cached, None
        try:
            releases = [slim(r) for r in self.fetch() if isinstance(r, dict) and not r.get('draft')]
        except Exception:
            return cached, 'Could not reach GitHub. This node may have no internet connection.'
        fresh = {'checked_at': self.clock(), 'releases': releases}
        try:
            self.cache.parent.mkdir(parents=True, exist_ok=True)
            self.cache.write_text(json.dumps(fresh))
        except OSError:
            pass
        return fresh, None


SOC = {'rpi3': 'bcm2710', 'rpi4': 'bcm2711'}


def pick_checksums(assets, image_key):
    """The checksum file that covers this image: the combined SHA256SUMS first, then the
    per-board file (sha256sums-ekh-bcm2711.txt for rpi4). Never another board's file."""
    files = [a for a in assets if 'sha256' in a['name'].lower()]
    combined = [a for a in files if a['name'].lower().startswith('sha256sums.')]
    board = (image_key or '').split('-')[0]
    soc = SOC.get(board, board)
    own = [a for a in files if soc and soc in a['name'].lower()]
    return combined + own


def assess(release, current, image_key, driver):
    """Compatibility notes for moving from `current` to `release`."""
    v = parse_version(release['tag'])
    notes = release['notes']
    image = None
    for asset in release['assets']:
        m = IMAGE_RE.match(asset['name'])
        if m and image_key and m.group('key').lower() == image_key:
            image = asset
    sums = pick_checksums(release['assets'], image_key)
    status, detail = rating(v)
    warnings = []
    if image_key and not image:
        warnings.append(f'No image for this hardware ({image_key}) in this release.')
    if CLEAN_INSTALL_RE.search(notes):
        warnings.append('Release notes recommend a reset or fresh install. Settings may not carry over, '
                        'so back up the SD card or use a spare one.')
    new_driver = DRIVER_RE.search(notes)
    if new_driver and driver and new_driver.group(1).split('.')[0] != driver.split('.')[0]:
        warnings.append(f'HaLow driver changes from {driver} to {new_driver.group(1)}. '
                        'Test HaLow links with nodes still on the old release before upgrading them all.')
    if current and v and (v[0], v[1]) != (current[0], current[1]):
        warnings.append('Feature release: re-check Field Extension readings after upgrading.')
    if release['prerelease']:
        warnings.append('Pre-release: for testing only.')
    return {'version': version_text(v) or release['tag'], 'name': release['name'],
            'published_at': release['published_at'], 'prerelease': release['prerelease'],
            'url': release['url'],
            'image': image, 'checksums': sums[0] if sums else None,
            'field_extensions': {'status': status, 'detail': detail},
            'warnings': warnings}


def report(releases, current, image_key, key_source, driver):
    rel = [r for r in releases if parse_version(r['tag'])]
    rel.sort(key=lambda r: parse_version(r['tag']), reverse=True)
    stable = next((r for r in rel if not r['prerelease']), None)
    pre = next((r for r in rel if r['prerelease']), None)
    if pre and stable and parse_version(pre['tag']) < parse_version(stable['tag']):
        pre = None
    latest = assess(stable, current, image_key, driver) if stable else None
    newer = bool(latest and current and parse_version(stable['tag']) > current)
    if current is None:
        state, message = 'UNKNOWN', 'Installed OpenMANET version could not be read.'
    elif latest is None:
        state, message = 'UNKNOWN', 'No release information yet.'
    elif newer:
        state, message = 'UPDATE AVAILABLE', f"OpenMANET {latest['version']} is available (installed {version_text(current)})."
    else:
        state, message = 'UP TO DATE', f'OpenMANET {version_text(current)} is the newest stable release.'
    return {
        'available': True, 'state': state, 'message': message,
        'installed': {'version': version_text(current), 'image': image_key, 'image_source': key_source,
                      'halow_driver': driver,
                      'field_extensions': dict(zip(('status', 'detail'), rating(current)))},
        'latest': latest,
        'prerelease': assess(pre, current, image_key, driver) if pre else None,
        'releases_page': RELEASES_PAGE,
        'install': 'Only when you press Install and confirm. Never automatic.',
    }
