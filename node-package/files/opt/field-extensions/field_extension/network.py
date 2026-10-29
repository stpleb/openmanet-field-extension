"""Turn raw `ip -j addr`, `ip -j route` and `iw dev` output into plain fields.

The HaLow interface (Node01: wlh0) is listed, but never with iw's channel or
frequency: the Morse driver reports a fake 5 GHz channel there. HaLow values
come from radios.halow (morse_cli) instead. Nothing here reads or returns
Wi-Fi keys; iw dev does not print them and only whitelisted fields are kept.
"""
import json
import re

CHANNEL_RE = re.compile(r'channel (\d+) \((\d+) MHz\)(?:, width: (\d+) MHz)?')


def _json(text):
    try:
        data = json.loads(text or '[]')
    except ValueError:
        return []
    return data if isinstance(data, list) else []


def interfaces(addr_json):
    out = []
    for i in _json(addr_json):
        if not isinstance(i, dict) or i.get('ifname') in (None, 'lo'):
            continue
        addrs = []
        for a in i.get('addr_info') or []:
            if not isinstance(a, dict) or a.get('family') not in ('inet', 'inet6'):
                continue
            if a.get('scope') == 'link' or str(a.get('local', '')).startswith('fe80'):
                continue
            if a.get('local') is not None:
                addrs.append(f"{a['local']}/{a.get('prefixlen')}")
        flags = i.get('flags') or []
        state = i.get('operstate')
        if state in (None, 'UNKNOWN'):
            state = 'UP' if 'UP' in flags else 'DOWN'
        out.append({'name': i['ifname'], 'state': state, 'addresses': addrs})
    return out


def default_route(route_json):
    for r in _json(route_json):
        if isinstance(r, dict) and r.get('dst') == 'default':
            return {'gateway': r.get('gateway'), 'interface': r.get('dev')}
    return None


def wireless(iw_text, halow_iface=None):
    """One entry per wireless interface: name, type, ssid, channel, width."""
    out, cur = [], None
    for raw in (iw_text or '').splitlines():
        line = raw.strip()
        if line.startswith('Interface '):
            cur = {'name': line.split(None, 1)[1], 'type': None, 'ssid': None,
                   'channel': None, 'frequency_mhz': None, 'width_mhz': None}
            out.append(cur)
        elif cur is None:
            continue
        elif line.startswith('type '):
            cur['type'] = line[5:]
        elif line.startswith('ssid '):
            cur['ssid'] = line[5:]
        elif line.startswith('channel '):
            m = CHANNEL_RE.match(line)
            if m:
                cur['channel'], cur['frequency_mhz'] = int(m.group(1)), int(m.group(2))
                cur['width_mhz'] = int(m.group(3)) if m.group(3) else None
    for w in out:
        w['halow'] = bool(halow_iface) and w['name'] == halow_iface
        if w['halow']:
            # Fake 5 GHz values from the Morse driver: drop them, see summary.halow.
            w['channel'] = w['frequency_mhz'] = w['width_mhz'] = None
    return out


def parse(addr_json, route_json, iw_text, halow_iface=None):
    radios = wireless(iw_text, halow_iface)
    ap = next((w for w in radios if w['type'] == 'AP' and not w['halow']), None)
    return {
        'interfaces': interfaces(addr_json),
        'default_route': default_route(route_json),
        'wireless': radios,
        'access_point': {k: ap[k] for k in ('name', 'ssid', 'channel', 'frequency_mhz', 'width_mhz')}
        if ap else None,
    }
