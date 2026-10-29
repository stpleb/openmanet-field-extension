"""Install an OpenMANET update from the web UI (any newer stable release).

The only firmware write Field Extension makes. It always runs in this order, and
stops at the first failure without touching the node:
  1. Eligibility: a newer stable release, an image for this
     hardware, published checksums, battery at least 40% or on external power.
     When the release has warnings (feature release, HaLow driver change,
     reset recommended), the operator must acknowledge them first.
  2. Download the image from the official OpenMANET GitHub release into RAM, with
     a size cap and a free-memory check.
  3. Verify the SHA-256 against the release's checksum file.
  4. `sysupgrade -T` checks the image is valid for this board.
  5. Save a config backup (`sysupgrade -b`) to the SD data partition when present.
  6. Make sure Field Extension's own files are kept, then `sysupgrade` with
     settings kept. The node reboots.

Settings are always kept; a release that recommends a reset may still need one
afterwards, which is why the warning must be acknowledged. Pi images have no
rollback: a failed install is fixed by re-flashing the SD card. Nothing here is built from request text except the
version string, which must equal the release the check already chose.
"""
import hashlib
import re
import threading
import time
import urllib.request
from pathlib import Path

from .updates import parse_version

DOWNLOAD_PREFIX = 'https://github.com/OpenMANET/firmware/releases/download/'
WORK = Path('/tmp/field-extensions/upgrade')
BACKUP_DIRS = (Path('/mnt/data'),)          # Node02's SD data partition; skipped if absent
KEEP_FILE = Path('/etc/sysupgrade.conf')
KEEP_PATHS = ('/etc/field-extensions/', str(Path(__file__).resolve().parent.parent) + '/',
              '/etc/init.d/field-extensions', '/etc/rc.d/S99field-extensions')
MAX_IMAGE = 256 * 1024 * 1024
MEM_MARGIN = 64 * 1024 * 1024
MIN_BATTERY = 40


def eligibility(report, battery):
    """Why the button is locked, or None when an install may start."""
    latest, current = report.get('latest'), parse_version(report['installed'].get('version') or '')
    if report.get('state') != 'UPDATE AVAILABLE' or not latest or not current:
        return 'No update to install.'
    if not latest.get('image'):
        return 'This release has no image for this hardware.'
    if not latest.get('checksums'):
        return 'This release has no checksums to verify the download.'
    if not str(latest['image'].get('url', '')).startswith(DOWNLOAD_PREFIX):
        return 'Image is not from the official OpenMANET release.'
    if battery.get('available') and battery.get('external_power') is not True:
        pct = battery.get('battery_percent')
        if not isinstance(pct, (int, float)) or pct < MIN_BATTERY:
            return f'Battery must be at least {MIN_BATTERY}% or on external power.'
    return None


def mem_available(meminfo_text):
    m = re.search(r'^MemAvailable:\s+(\d+)\s+kB', meminfo_text or '', re.M)
    return int(m.group(1)) * 1024 if m else 0


def expected_sha256(sums_text, name):
    for line in (sums_text or '').splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].lstrip('*') == name and re.fullmatch(r'[0-9a-f]{64}', parts[0].lower()):
            return parts[0].lower()
    return None


class Installer:
    def __init__(self, run, spawn, opener=None, work=WORK, meminfo=lambda: Path('/proc/meminfo').read_text(),
                 keep_file=KEEP_FILE, backup_dirs=BACKUP_DIRS, clock=time.time):
        self.run, self.spawn = run, spawn
        self.opener = opener or urllib.request.build_opener()
        self.work, self.meminfo, self.keep_file = Path(work), meminfo, Path(keep_file)
        self.backup_dirs, self.clock = backup_dirs, clock
        self.lock = threading.Lock()
        self.status = {'state': 'IDLE'}

    def _set(self, state, message, **extra):
        self.status = {'state': state, 'message': message, 'at': self.clock(), **extra}

    def _fetch(self, url, path=None, limit=MAX_IMAGE):
        req = urllib.request.Request(url, headers={'User-Agent': 'field-extensions'})
        digest, size = hashlib.sha256(), 0
        with self.opener.open(req, timeout=30) as response:
            out = open(path, 'wb') if path else None
            chunks = []
            try:
                while True:
                    chunk = response.read(1 << 20)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > limit:
                        raise ValueError('download larger than expected')
                    digest.update(chunk)
                    (out.write(chunk) if out else chunks.append(chunk))
            finally:
                if out:
                    out.close()
        return digest.hexdigest(), b''.join(chunks)

    def start(self, report, battery, version, acknowledged=False):
        """Validate synchronously, then download and install in the background."""
        reason = eligibility(report, battery)
        if reason:
            return 409, {'ok': False, 'error': reason}
        latest = report['latest']
        if version != latest['version']:
            return 409, {'ok': False, 'error': 'Version changed since the check. Check again.'}
        if latest.get('warnings') and acknowledged is not True:
            return 409, {'ok': False, 'error': 'Read and acknowledge the warnings first.',
                         'warnings': latest['warnings']}
        if not self.lock.acquire(blocking=False):
            return 409, {'ok': False, 'error': 'An update is already running.'}
        from . import maintenance
        maintenance.mark('update', 1800, None if self.work == WORK else self.work / 'busy')   # white light from the download on; the reboot clears it
        threading.Thread(target=self._install, args=(latest,), daemon=True).start()
        return 202, {'ok': True, 'message': f"Downloading OpenMANET {latest['version']}."}

    def _install(self, latest):
        image_path = None
        try:
            name, url = latest['image']['name'], latest['image']['url']
            self._set('DOWNLOADING', f'Downloading {name}')
            if mem_available(self.meminfo()) < MAX_IMAGE // 2 + MEM_MARGIN:
                raise RuntimeError('Not enough free memory to hold the image.')
            _, sums = self._fetch(latest['checksums']['url'], limit=1 << 20)
            want = expected_sha256(sums.decode(errors='replace'), name)
            if not want:
                raise RuntimeError('The checksum file does not list this image.')
            self.work.mkdir(parents=True, exist_ok=True)
            image_path = self.work / name
            got, _ = self._fetch(url, image_path)
            self._set('VERIFYING', 'Checking the download')
            if got != want:
                raise RuntimeError('Checksum mismatch. The download was discarded.')
            if not self.run(['sysupgrade', '-T', str(image_path)]).get('ok'):
                raise RuntimeError('sysupgrade says this image is not valid for this board.')
            backup = self._backup()
            self._keep_field_extensions()
            self._set('INSTALLING', 'Installing. The node reboots and is offline for a few minutes.',
                      backup=backup)
            self.spawn(['sysupgrade', '-k', str(image_path)])  # -k keeps a list of installed packages
        except Exception as e:  # report every failure; the node is unchanged at this point
            if image_path:
                image_path.unlink(missing_ok=True)
            self._set('FAILED', str(e) if isinstance(e, RuntimeError) else 'Download or install step failed.')
            from . import maintenance
            maintenance.clear('update', None if self.work == WORK else self.work / 'busy')
            self.lock.release()

    def _backup(self):
        for base in self.backup_dirs:
            if base.is_dir():
                dest = base / 'field-extensions-backups' / time.strftime('config-%Y%m%d-%H%M%S.tar.gz', time.gmtime(self.clock()))
                dest.parent.mkdir(parents=True, exist_ok=True)
                if self.run(['sysupgrade', '-b', str(dest)]).get('ok'):
                    return str(dest)
        return None

    def _keep_field_extensions(self):
        try:
            lines = self.keep_file.read_text().splitlines()
        except OSError:
            lines = []
        missing = [p for p in KEEP_PATHS if p not in lines]
        if missing:
            self.keep_file.write_text('\n'.join(lines + missing) + '\n')
