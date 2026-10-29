"""Field Extension HTTP service: web UI plus the /api/field/* API.

Binds to loopback by default. On a node it binds to the EUD access-point
address (br-lan) so phones and the ATAK plugin can reach it; never to the
mesh or WAN. Reads are open on that network; writes need an rpcd session.
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from .core import Node, run
import subprocess
from .actions import Actions, Login, session_valid
from .upgrade import Installer

WEB = Path(__file__).resolve().parent.parent / 'web'
ENDPOINTS = {'capabilities', 'summary', 'system', 'mesh', 'neighbors', 'halow', 'gps', 'comms',
             'services', 'power', 'network', 'updates', 'led', 'eud', 'nodes', 'hello', 'lora', 'card', 'diagnostics'}
# Read-only node discovery that launcher apps and other nodes' pages may fetch cross-origin.
PUBLIC = {'/api/field/hello', '/api/field/nodes', '/api/field/card', '/api/field/package'}
MAX_BODY = 4096
ASSETS = {'/': ('index.html', 'text/html'), '/app.js': ('app.js', 'text/javascript'),
          '/style.css': ('style.css', 'text/css'),
          '/qrcode.js': ('qrcode.js', 'text/javascript'),
          '/manifest.webmanifest': ('manifest.webmanifest', 'application/manifest+json'),
          '/icon.svg': ('icon.svg', 'image/svg+xml'), '/logo.svg': ('logo.svg', 'image/svg+xml'), '/icon-180.png': ('icon-180.png', 'image/png'),
          '/icon-192.png': ('icon-192.png', 'image/png'), '/icon-512.png': ('icon-512.png', 'image/png'),
          '/nodes': ('nodes.html', 'text/html'), '/nodes.js': ('nodes.js', 'text/javascript'),
          '/nodes.webmanifest': ('nodes.webmanifest', 'application/manifest+json')}

class Handler(BaseHTTPRequestHandler):
    def send(self, code, body, content_type='application/json'):
        self.send_response(code)
        self.send_header('Content-Type', content_type if content_type in ('image/png', 'application/octet-stream') else content_type + '; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        if self.command == 'GET' and urlsplit(self.path).path in PUBLIC:
            self.send_header('Access-Control-Allow-Origin', '*')
        # The node list page also checks the other nodes' addresses on the mesh.
        connect = " connect-src 'self' http:;" if urlsplit(self.path).path == '/nodes' else ''
        self.send_header('Content-Security-Policy', "default-src 'self';" + connect + " frame-ancestors 'none'; object-src 'none'; base-uri 'none'")
        self.end_headers()
        self.wfile.write(body)

    def allowed_hosts(self):
        host, port = self.server.server_address[:2]
        names = {'127.0.0.1', 'localhost', host}
        return {f'{n}:{port}' for n in names} | ({host} if port == 80 else set())

    def check_origin(self):
        # Reject DNS rebinding and cross-origin browser access.
        hosts = self.allowed_hosts()
        if self.headers.get('Host') not in hosts:
            self.send(403, b'{"error":"Unknown host"}')
            return False
        origin = self.headers.get('Origin')
        if self.command == 'GET' and urlsplit(self.path).path in PUBLIC:
            return True
        if origin and origin not in {'http://' + x for x in hosts}:
            self.send(403, b'{"error":"Origin rejected"}')
            return False
        return True

    def do_GET(self):
        if not self.check_origin():
            return
        url = urlsplit(self.path)
        path = url.path
        if path in ASSETS:
            name, content_type = ASSETS[path]
            return self.send(200, (WEB / name).read_bytes(), content_type)
        prefix = '/api/field/'
        if path == prefix + 'package':
            # This node's own Field Extension package, for other nodes' mesh updates.
            from .fleet_update import FE_DIR, PACKAGE, package_info
            if package_info(FE_DIR) is None:
                return self.send(404, b'{"error":"No package kept on this node"}')
            return self.send(200, (FE_DIR / PACKAGE).read_bytes(), 'application/octet-stream')
        if path.startswith(prefix) and path[len(prefix):] in ENDPOINTS:
            try:
                name = path[len(prefix):]
                if name == 'updates':
                    # ?refresh=1 asks GitHub again, but never more than every 10 minutes.
                    data = self.server.node.updates(refresh=parse_qs(url.query).get('refresh') == ['1'])
                else:
                    data = self.server.node.get(name)
                return self.send(200, json.dumps(data).encode())
            except Exception:
                return self.send(503, b'{"error":"Collector unavailable"}')
        self.send(404, b'{"error":"Not found"}')

    def do_POST(self):
        if not self.check_origin():
            return
        path = urlsplit(self.path).path
        prefix = '/api/field/'
        if not path.startswith(prefix) or self.server.actions is None:
            return self.send(405, b'{"error":"Writes are not enabled"}')
        name = path[len(prefix):]
        auth = self.headers.get('Authorization', '')
        token = auth[7:] if auth.startswith('Bearer ') else None
        if name != 'login' and not session_valid(self.server.run, token, self.server.devices):
            return self.send(401, b'{"error":"Sign in to the node first"}')
        try:
            length = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            length = -1
        if not 0 <= length <= MAX_BODY:
            return self.send(413, b'{"error":"Request too large"}')
        try:
            body = json.loads(self.rfile.read(length) or b'{}')
        except ValueError:
            return self.send(400, b'{"error":"Invalid JSON"}')
        if not isinstance(body, dict):
            return self.send(400, b'{"error":"JSON object expected"}')
        if name == 'login':
            code, result = self.server.login(body)
        elif name == 'logout':
            if self.server.devices is not None:
                self.server.devices.forget(None if body.get('all') else token)
            code, result = 200, {'ok': True, 'note': 'Signed out on every device.' if body.get('all') else 'Signed out.'}
        else:
            code, result = self.server.actions.dispatch(name, body)
        self.send(code, json.dumps(result).encode())

    def log_message(self, *_):
        pass

def make_server(port=8787, node=None, host='127.0.0.1', actions=None, runner=run):
    server = ThreadingHTTPServer((host, port), Handler)
    server.node = node or Node()
    server.run = runner
    server.actions = actions
    from .actions import Devices
    server.devices = Devices() if actions is not None else None
    server.login = Login(devices=server.devices)
    return server

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8787)
    parser.add_argument('--host', default='127.0.0.1',
                        help='Address to bind; on a node use the br-lan (access point) address')
    parser.add_argument('--enable-writes', action='store_true')
    args = parser.parse_args()
    node = Node()
    actions = None
    if args.enable_writes:
        # sysupgrade outlives this process (it reboots the node), so start it detached.
        spawn = lambda argv: subprocess.Popen(argv, start_new_session=True, stdin=subprocess.DEVNULL,
                                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        node.installer = Installer(run, spawn)
        actions = Actions(run, node.pisugar, capabilities=node.capabilities, node=node)
        if getattr(node.pisugar, 'direct', None) is not None:
            node.pisugar.direct.start_sampler()
        import threading
        from .battery_curve import Calibration
        node.calibration = Calibration(lambda: node.pisugar.direct.status())
        threading.Thread(target=node.calibration.run, args=(threading.Event(),), daemon=True).start()
        from .power_guard import LowBatteryGuard
        node.guard = LowBatteryGuard(lambda: None)   # settings and status only; the light service acts
        from .fleet_update import Updater
        def installed():
            try:
                return Path('/opt/field-extensions/VERSION').read_text().strip()
            except OSError:
                return None
        node.updater = Updater(installed, lambda: [n['ip'] for n in node.mesh_nodes().get('nodes', [])
                                                   if not n.get('self') and n.get('ip')], spawn)
        threading.Thread(target=node.updater.run, args=(threading.Event(),), daemon=True).start()
        from .actions import clear_lora_log
        from .fleet_clear import Spreader
        from .fleet_update import http_json
        spreader = Spreader(lambda: [n['ip'] for n in node.mesh_nodes().get('nodes', [])
                                     if not n.get('self') and n.get('ip')], http_json, clear_lora_log)
        threading.Thread(target=spreader.run, args=(threading.Event(),), daemon=True).start()
        from .battery_estimate import Estimate
        node.estimate = Estimate(lambda: node.pisugar.direct.status())
        threading.Thread(target=node.estimate.run, args=(threading.Event(),), daemon=True).start()
    with make_server(args.port, node, args.host, actions) as server:
        print(f'Field Extension: http://{args.host}:{server.server_port}', flush=True)
        server.serve_forever()

if __name__ == '__main__':
    main()
