"""Field Extension updates from other nodes over the mesh.

Each node keeps a copy of the package it was installed from (/opt/field-extensions/
package.ipk, with package.json holding its version and SHA-256) and serves it at
GET /api/field/package. A node that finds a newer Field Extension on another node
offers it on its web page and in the plugin; with "Update automatically" on it installs
it by itself. The download must match the size and SHA-256 the other node announces,
or nothing is installed. So one node updated by hand (or by the Mac) updates the rest.
"""
import hashlib
import json
import re
import threading
import time
import urllib.request
from pathlib import Path

FE_DIR = Path('/opt/field-extensions')
PACKAGE, PACKAGE_INFO = 'package.ipk', 'package.json'
AUTO_SETTING = 'auto-update-from-mesh'
CHECK_S = 600
MAX_SIZE = 32 * 1024 * 1024
PORT = 8787


def version_key(v):
    """'2.0.0~dev19' < '2.0.0~dev20' < '2.0.0' < '2.0.1~dev1' < '1:1.0.0' (opkg: '~' sorts
    first; an 'N:' epoch outranks everything without one)."""
    m = re.match(r'^(?:(\d+):)?(\d+(?:\.\d+)*)(?:~dev(\d+))?$', (v or '').strip())
    if not m:
        return None
    base = tuple(int(x) for x in m.group(2).split('.'))
    return (int(m.group(1) or 0), base, (0, int(m.group(3))) if m.group(3) is not None else (1, 0))


def file_version(v):
    """The version as it appears in .ipk file names (opkg-build leaves the epoch out)."""
    return re.sub(r'^\d+:', '', v)


def newer(a, b):
    ka, kb = version_key(a), version_key(b)
    return ka is not None and kb is not None and ka > kb


def package_info(fe_dir=FE_DIR, installed=None):
    """The package this node can hand out, if it matches what is installed."""
    try:
        info = json.loads((Path(fe_dir) / PACKAGE_INFO).read_text())
        size = (Path(fe_dir) / PACKAGE).stat().st_size
    except (OSError, ValueError):
        return None
    if not isinstance(info, dict) or info.get('size') != size:
        return None
    if installed and info.get('version') != installed:
        return None
    return {k: info.get(k) for k in ('version', 'sha256', 'size')}


def http_json(url, timeout=3.0):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(url, timeout=timeout) as r:
        return json.loads(r.read(65536))


def http_bytes(url, limit, timeout=60.0):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(url, timeout=timeout) as r:
        data = r.read(limit + 1)
    if len(data) > limit:
        raise ValueError('package too large')
    return data


class Updater:
    def __init__(self, installed, peers, spawn, state_dir=Path('/etc/field-extensions/state'),
                 get_json=http_json, get_bytes=http_bytes, work=Path('/tmp'), clock=time.time):
        self.installed, self.peers, self.spawn = installed, peers, spawn
        self.state, self.get_json, self.get_bytes, self.work, self.clock = Path(state_dir), get_json, get_bytes, Path(work), clock
        self.lock = threading.Lock()
        self.offer, self.checked, self.state_text, self.error = None, None, None, None

    def auto(self):
        try:
            return (self.state / AUTO_SETTING).read_text().strip() == '1'
        except OSError:
            return False

    def set_auto(self, on):
        self.state.mkdir(parents=True, exist_ok=True)
        (self.state / AUTO_SETTING).write_text('1\n' if on else '0\n')
        return {'ok': True, 'note': 'This node updates itself from the mesh.' if on else
                'Updates from the mesh are offered, not installed by themselves.'}

    def check(self):
        """Ask every other node what it runs; remember the newest one that can hand it out."""
        best = None
        for ip in self.peers():
            try:
                hello = self.get_json('http://%s:%d/api/field/hello' % (ip, PORT))
            except Exception:
                continue
            pkg = hello.get('package') if isinstance(hello, dict) else None
            if not isinstance(pkg, dict) or not newer(pkg.get('version'), self.installed()):
                continue
            if best is None or newer(pkg['version'], best['version']):
                best = {'ip': ip, 'node': hello.get('node'), 'version': pkg['version'],
                        'sha256': pkg.get('sha256'), 'size': pkg.get('size')}
        with self.lock:
            self.offer, self.checked = best, int(self.clock())
        return best

    def install(self, ip=None):
        with self.lock:
            offer = self.offer
        if offer is None or (ip and offer['ip'] != ip):
            return {'ok': False, 'error': 'No newer Field Extension found on the mesh. Check again first.'}
        size, sha = offer.get('size'), offer.get('sha256')
        if not (isinstance(size, int) and 0 < size <= MAX_SIZE and isinstance(sha, str) and re.match(r'^[0-9a-f]{64}$', sha)):
            return {'ok': False, 'error': 'The other node did not announce a usable checksum'}
        self.state_text, self.error = 'downloading from %s' % (offer['node'] or offer['ip']), None
        try:
            data = self.get_bytes('http://%s:%d/api/field/package' % (offer['ip'], PORT), MAX_SIZE)
        except Exception as e:
            self.state_text, self.error = None, 'Download failed: %s' % e
            return {'ok': False, 'error': self.error}
        if len(data) != size or hashlib.sha256(data).hexdigest() != sha:
            self.state_text, self.error = None, 'The download did not match its checksum; nothing was installed'
            return {'ok': False, 'error': self.error}
        if not re.match(r'^(?:\d+:)?[0-9A-Za-z.~+-]+$', offer['version']):
            return {'ok': False, 'error': 'Unexpected version text'}
        # postinst keeps this file as the node's own package (fe-setup.sh keep-package).
        path = self.work / ('field-extensions_%s_all.ipk' % file_version(offer['version']))
        path.write_bytes(data)
        self.state_text = 'installing %s' % offer['version']
        from . import maintenance
        maintenance.mark('update', 900, maintenance.busy_dir_for(self.state))   # white light; opkg itself also counts while it runs
        # opkg restarts this service, so it runs detached from it.
        self.spawn(['sh', '-c', 'sleep 2; opkg install --force-reinstall "$0" > /tmp/fe-mesh-update.log 2>&1',
                    str(path)])
        return {'ok': True, 'note': 'Installing %s from %s. The page reconnects in about a minute.'
                % (offer['version'], offer['node'] or offer['ip'])}

    def run(self, stop):
        while not stop.wait(CHECK_S):
            try:
                if self.check() and self.auto():
                    self.install()
            except Exception:
                pass

    def status(self):
        return {'installed': self.installed(), 'offer': self.offer, 'checked': self.checked,
                'auto': self.auto(), 'state': self.state_text, 'error': self.error}
