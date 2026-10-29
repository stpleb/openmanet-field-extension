"""Node identity: callsign, colour, node type (role) and ATAK team role.

Kept in /etc/field-extensions/identity.json so it survives updates and a re-flash (the whole
/etc/field-extensions directory is kept). The callsign is what ATAK users see: the sender of
node alerts, the plugin's map marker and the web page title. The hostname is untouched.
"""
import json
import re
import socket
from pathlib import Path

FILE = Path('/etc/field-extensions/identity.json')
# ATAK team colours, so a node can match the team it belongs to.
COLORS = ['White', 'Yellow', 'Orange', 'Magenta', 'Red', 'Maroon', 'Purple', 'Dark Blue',
          'Blue', 'Cyan', 'Teal', 'Green', 'Dark Green', 'Brown']
ROLES = ['Worn', 'Vehicle', 'Fixed', 'Unattended']     # what kind of node this is
# ATAK's own team roles, so a worn node shows the role its wearer has.
TEAM_ROLES = ['Team Member', 'Team Lead', 'HQ', 'Sniper', 'Medic', 'Forward Observer', 'RTO', 'K9']
DEFAULT_COLOR, DEFAULT_ROLE, DEFAULT_TEAM_ROLE = 'Cyan', 'Fixed', 'Team Member'
KEYS = ('callsign', 'color', 'role', 'team_role')
CALLSIGN = re.compile(r'^[A-Za-z0-9][A-Za-z0-9 ._-]{0,39}$')


def load(path=FILE, hostname=None):
    try:
        saved = json.loads(Path(path).read_text())
        if not isinstance(saved, dict):
            saved = {}
    except (OSError, ValueError):
        saved = {}
    callsign = saved.get('callsign')
    out = {
        'callsign': callsign if isinstance(callsign, str) and CALLSIGN.match(callsign) else (hostname or socket.gethostname()),
        'color': saved.get('color') if saved.get('color') in COLORS else DEFAULT_COLOR,
        'role': saved.get('role') if saved.get('role') in ROLES else DEFAULT_ROLE,
        'team_role': saved.get('team_role') if saved.get('team_role') in TEAM_ROLES else DEFAULT_TEAM_ROLE,
    }
    out['custom_callsign'] = out['callsign'] == callsign
    return out


def save(body, path=FILE, hostname=None):
    """Change any of callsign / color / role / team_role. Returns (ok, result)."""
    if not isinstance(body, dict) or not any(k in body for k in KEYS):
        return False, {'ok': False, 'error': 'Send callsign, color, role or team_role'}
    cur = load(path, hostname)
    new = {'callsign': cur['callsign'] if cur['custom_callsign'] else None,
           'color': cur['color'], 'role': cur['role'], 'team_role': cur['team_role']}
    if 'callsign' in body:
        c = body['callsign']
        if c in (None, ''):
            new['callsign'] = None   # back to the hostname
        elif not isinstance(c, str) or not CALLSIGN.match(c.strip()):
            return False, {'ok': False, 'error': 'Callsign: 1 to 40 letters, numbers, spaces, dots, dashes or underscores'}
        else:
            new['callsign'] = c.strip()
    if 'color' in body:
        if body['color'] not in COLORS:
            return False, {'ok': False, 'error': 'Color must be one of: ' + ', '.join(COLORS)}
        new['color'] = body['color']
    if 'role' in body:
        if body['role'] not in ROLES:
            return False, {'ok': False, 'error': 'Role must be one of: ' + ', '.join(ROLES)}
        new['role'] = body['role']
    if 'team_role' in body:
        if body['team_role'] not in TEAM_ROLES:
            return False, {'ok': False, 'error': 'Team role must be one of: ' + ', '.join(TEAM_ROLES)}
        new['team_role'] = body['team_role']
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    tmp = Path(str(path) + '.tmp')
    tmp.write_text(json.dumps({k: v for k, v in new.items() if v is not None}) + '\n')
    tmp.replace(path)
    out = load(path, hostname)
    return True, {'ok': True, **out, 'note': 'This node is now %s (%s, %s).' % (out['callsign'], out['color'], out['role'])}
