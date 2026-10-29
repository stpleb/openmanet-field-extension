"""A position for a node with no GPS fix.

Two kinds: one set by hand ("set node position here" in the app or plugin, kept until
cleared), and one fed in by a connected phone (source "eud": the app sends its own
location every so often while the node has no fix). A phone's position is only used
while it is fresh (EUD_MAX_AGE_S), since the phone may walk away with it. Order: GPS
fix, then the hand-set position, then a fresh phone position. The position source setting
(position-source) changes that: "node" never uses a phone's position, "phone" puts a
fresh phone position first (for a node whose GPS is misbehaving)."""
import json
import time
from pathlib import Path

FILE = Path('/etc/field-extensions/position.json')
EUD_MAX_AGE_S = 15 * 60


def eud_file(path):
    return Path(path).with_name('position-eud.json')


def load(path=FILE, source='set'):
    try:
        d = json.loads(Path(path).read_text())
        lat, lon = float(d['lat']), float(d['lon'])
        if -90 <= lat <= 90 and -180 <= lon <= 180:
            alt = d.get('alt')
            return {'lat': lat, 'lon': lon, 'alt': float(alt) if alt is not None else None,
                    'set_at': d.get('set_at'), 'source': source}
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return None


def save(body, path=FILE, clock=time.time):
    """{lat, lon, alt?} sets the position; {clear: true} forgets it. With source "eud" it is
    the connected phone's position instead (kept apart, used only while fresh). Returns (ok, result)."""
    if not isinstance(body, dict):
        return False, {'ok': False, 'error': 'Send lat and lon, or clear'}
    eud = body.get('source') == 'eud'
    path = eud_file(path) if eud else Path(path)
    if body.get('clear'):
        try:
            path.unlink()
        except FileNotFoundError:
            pass
        return True, {'ok': True, 'position': None, 'note': 'Node position cleared.'}
    try:
        lat, lon = float(body['lat']), float(body['lon'])
        alt = float(body['alt']) if body.get('alt') is not None else None
    except (KeyError, TypeError, ValueError):
        return False, {'ok': False, 'error': 'Send lat and lon as numbers'}
    if not (-90 <= lat <= 90 and -180 <= lon <= 180) or (lat == 0 and lon == 0):
        return False, {'ok': False, 'error': 'That is not a valid position'}
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = Path(str(path) + '.tmp')
    tmp.write_text(json.dumps({'lat': round(lat, 7), 'lon': round(lon, 7),
                               'alt': round(alt, 1) if alt is not None else None,
                               'set_at': int(clock())}) + '\n')
    tmp.replace(path)
    if eud:
        return True, {'ok': True, 'position': load(path, 'eud'),
                      'note': "Using this phone's position while the node has no GPS fix."}
    return True, {'ok': True, 'position': load(path),
                  'note': 'Node position set. It is used while the node has no GPS fix.'}


SOURCES = ('auto', 'node', 'phone')


def source_file(path):
    return Path(path).with_name('position-source')


def get_source(path=FILE):
    try:
        v = source_file(path).read_text().strip()
    except OSError:
        return 'auto'
    return v if v in SOURCES else 'auto'


def set_source(source, path=FILE):
    if source not in SOURCES:
        return False, {'ok': False, 'error': 'source must be auto, node or phone'}
    f = source_file(path)
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(source + '\n')
    return True, {'ok': True, 'source': source, 'note': {
        'auto': 'Node GPS first, then a hand-set position, then a connected phone.',
        'node': 'Node GPS or a hand-set position only; phones are not used.',
        'phone': 'A connected phone first; the node GPS only when no phone has sent one lately.'}[source]}


def pick(gps, path=FILE, clock=time.time):
    """The position this node reports, by its position source setting (see the module
    docstring), or None."""
    gps = gps or {}
    source = get_source(path)
    phone = load(eud_file(path), 'eud')
    if not (phone and isinstance(phone.get('set_at'), (int, float)) and 0 <= clock() - phone['set_at'] <= EUD_MAX_AGE_S):
        phone = None
    if source == 'phone' and phone:
        return phone
    if gps.get('state') == 'FIX' and gps.get('lat') is not None:
        return {'lat': gps.get('lat'), 'lon': gps.get('lon'), 'alt': gps.get('alt'),
                'mode': gps.get('mode'), 'source': 'gps'}
    hand = load(path)
    if hand:
        return hand
    return phone if source != 'node' else None
