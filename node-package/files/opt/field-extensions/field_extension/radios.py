"""Read-only radio, mesh and GPS readings for the normalized API (spec 14, 15, 28).

Every function takes a `run(argv)` callable (core.run) so tests can feed
captured command output. Formats were checked against Node02's audit output
(morse_cli rel_1_16_4, batctl 2025.4, gpsd 3.25).
"""
import ipaddress
import json
import re
from pathlib import Path
import socket


def halow(run, iface):
    """Frequency and bandwidth straight from the Morse chip, not hard-coded."""
    if not iface:
        return {'available': False, 'message': 'No HaLow radio detected'}
    r = run(['morse_cli', '-i', iface, 'channel'])
    text = r.get('output', '') if r.get('ok') else ''
    freq = re.search(r'Operating Frequency:\s*(\d+)\s*kHz', text)
    bw = re.search(r'Operating BW:\s*(\d+)\s*MHz', text)
    prim = re.search(r'Primary BW:\s*(\d+)\s*MHz', text)
    if not freq:
        return {'available': True, 'interface': iface, 'state': 'NOT RESPONDING',
                'frequency_mhz': None, 'bandwidth_mhz': None}
    return {'available': True, 'interface': iface, 'state': 'ONLINE',
            'frequency_mhz': int(freq.group(1)) / 1000,
            'bandwidth_mhz': int(bw.group(1)) if bw else None,
            'primary_bandwidth_mhz': int(prim.group(1)) if prim else None}


_MAC = re.compile(r'\b([0-9a-f]{2}(?::[0-9a-f]{2}){5})\b', re.I)
_SEEN = re.compile(r'\b([\d.]+)s\b')
_SPEED = re.compile(r'\(\s*([\d.]+)\s*\)')


FRESH_S = 10.0


def neighbors(run, halow_iface=None):
    """Parse `batctl n` (BATMAN_V: MAC, last-seen, throughput Mbit/s, iface).

    `-n` keeps batctl from swapping MACs for names from /etc/bat-hosts, which OpenMANET
    fills in (seen on Node01 and Node02 2026-10-13: no MAC, so no neighbor was counted)."""
    r = run(['batctl', 'n', '-n'])
    if not r.get('ok'):
        return {'available': False, 'neighbors': []}
    out = []
    # Column order differs between batctl versions, so match by shape, not position.
    for line in r.get('output', '').splitlines():
        if line.lstrip().startswith('[') and 'B.A.T.M.A.N' in line:
            continue
        mac, seen, speed = _MAC.search(line), _SEEN.search(line), _SPEED.search(line)
        if not (mac and seen):
            continue
        rest = _MAC.sub(' ', _SPEED.sub(' ', _SEEN.sub(' ', line)))
        iface = next((t.strip('[]') for t in rest.split() if t.strip('[]')), None)
        out.append({'mac': mac.group(1).lower(), 'last_seen_s': float(seen.group(1)),
                    'throughput_mbps': float(speed.group(1)) if speed else None,
                    'interface': iface,
                    'transport': 'HaLow' if iface == halow_iface else 'Wi-Fi'})
    # batman-adv keeps a vanished neighbor listed until its ~200 s purge timeout, so a node
    # that was switched off would still count. Live neighbors are heard every second or so.
    live = [n for n in out if n['last_seen_s'] <= FRESH_S]
    return {'available': True, 'count': len(live), 'nodes': _live_originators(run, len(live)),
            'neighbors': live, 'stale': [n for n in out if n['last_seen_s'] > FRESH_S]}


def mesh_interfaces(run, halow_iface=None, sysfs='/sys/class/net'):
    """This node's own batman hard interfaces with their MACs (`batctl if`: "wlh0: active"),
    so a viewer can match another node's neighbor MACs to this node and draw the links."""
    r = run(['batctl', 'if'])
    if not r.get('ok'):
        return []
    out = []
    for line in r.get('output', '').splitlines():
        name, _, state = line.partition(':')
        name = name.strip()
        if not name or 'active' not in state or 'inactive' in state:
            continue
        try:
            mac = (Path(sysfs) / name / 'address').read_text().strip().lower()
        except OSError:
            mac = None
        out.append({'interface': name, 'mac': mac,
                    'transport': 'HaLow' if name == halow_iface else 'Wi-Fi'})
    return out


def _live_originators(run, fallback):
    """Other nodes on the mesh. A node heard over HaLow and 2.4 GHz is two neighbors (one
    per radio) but one originator, so count originators from `batctl o`."""
    r = run(['batctl', 'o', '-n'])
    if not r.get('ok'):
        return fallback
    seen = set()
    for line in r.get('output', '').splitlines():
        mac, last = _MAC.search(line), _SEEN.search(line)
        if mac and last and not line.lstrip().startswith('[') and float(last.group(1)) <= FRESH_S:
            seen.add(mac.group(1).lower())
    return len(seen)


_IPV4 = re.compile(r'\b(\d{1,3}(?:\.\d{1,3}){3})\b')


def mesh_nodes(run, subnet=None, self_ip=None, is_node=None):
    """Mesh node addresses from batman-adv's distributed ARP table (`batctl dc`), so a
    launcher app can find every node's web pages without scanning the whole subnet.
    The table also holds phones and laptops on the nodes' Wi-Fi; `is_node(ips)` returns
    the ones that really are nodes (Mac listed as a node on 2026-10-13)."""
    r = run(['batctl', 'dc', '-n'])
    seen, nodes = set(), []

    def add(ip, mac, me=False):
        if ip in seen:
            return
        try:
            if subnet is not None and ipaddress.ip_address(ip) not in subnet:
                return
        except ValueError:
            return
        seen.add(ip)
        nodes.append({'ip': ip, 'mac': mac, 'self': me,
                      'field_extension': 'http://%s:8787/' % ip,
                      'openmanet': 'http://%s:8080/' % ip})

    if self_ip:
        add(self_ip, None, True)
    for line in (r.get('output', '') if r.get('ok') else '').splitlines():
        if line.lstrip().startswith('['):
            continue
        ip, mac = _IPV4.search(line), _MAC.search(line)
        if ip and mac:
            add(ip.group(1), mac.group(1).lower())
    if is_node is not None:
        others = [n['ip'] for n in nodes if not n['self']]
        keep = set(is_node(others)) if others else set()
        nodes = [n for n in nodes if n['self'] or n['ip'] in keep]
    return {'available': bool(r.get('ok')) or bool(self_ip), 'count': len(nodes), 'nodes': nodes}


def answers_as_node(ips, ports=(8787, 8080), timeout=0.8, connect=socket.create_connection):
    """The addresses with a node's web page (Field Extension or OpenMANET) listening."""
    import threading
    found = set()

    def check(ip):
        for port in ports:
            try:
                connect((ip, port), timeout=timeout).close()
                found.add(ip)
                return
            except OSError:
                pass
    threads = [threading.Thread(target=check, args=(ip,), daemon=True) for ip in ips[:32]]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout * len(ports) + 0.5)
    return found


def gps(host='127.0.0.1', port=2947, timeout=2.0, connect=socket.create_connection):
    """Absent / present-no-fix / fix, from gpsd's JSON stream (spec 28)."""
    try:
        with connect((host, port), timeout=timeout) as s:
            s.sendall(b'?WATCH={"enable":true,"json":true};\n')
            buf, tpv, devices = b'', None, None
            while len(buf) < 65536 and tpv is None:
                chunk = s.recv(4096)
                if not chunk:
                    break
                buf += chunk
                for line in buf.split(b'\n')[:-1]:
                    try:
                        msg = json.loads(line)
                    except ValueError:
                        continue
                    if msg.get('class') == 'DEVICES':
                        devices = msg.get('devices', [])
                    elif msg.get('class') == 'TPV':
                        tpv = msg
    except OSError:
        return {'available': False, 'state': 'GPS ABSENT', 'message': 'GPS service not running'}
    if devices == [] and tpv is None:
        return {'available': True, 'state': 'GPS ABSENT', 'message': 'No GPS device attached'}
    mode = (tpv or {}).get('mode', 0)
    if mode >= 2:
        return {'available': True, 'state': 'FIX', 'mode': mode,
                'lat': tpv.get('lat'), 'lon': tpv.get('lon'), 'alt': tpv.get('altMSL', tpv.get('alt')),
                'message': '3D fix' if mode == 3 else '2D fix'}
    return {'available': True, 'state': 'NO FIX', 'mode': mode,
            'message': 'GPS detected, waiting for position fix'}
