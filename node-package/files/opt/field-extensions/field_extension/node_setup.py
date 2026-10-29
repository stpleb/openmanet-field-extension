"""One code (shown as a QR) that sets up a new node like the one that made it.

make() packs this node's mesh code (HaLow and, if set up, the 2.4 GHz mesh) and its
LoRa team channel link into "FENODE1:" plus base64url JSON. It holds the mesh password
and the team key, so it is only handed out to a signed-in caller. On the new node,
read() checks both parts before anything changes; the caller then sets the LoRa team
(no restart), the callsign if one was given, and joins the mesh for good (reboot).
"""
import base64
import json

from . import mesh_join
from . import meshtastic as mt

PREFIX = 'FENODE1:'


def make(mesh_code, lora_url=None):
    blob = json.dumps({'v': 1, 'mesh': mesh_code, 'lora': lora_url}, separators=(',', ':')).encode()
    return PREFIX + base64.urlsafe_b64encode(blob).decode().rstrip('=')


def read(code):
    """(mesh_code, lora_url or None), or raises ValueError with a message for the user."""
    if not isinstance(code, str) or not code.strip().startswith(PREFIX) or len(code) > 8192:
        raise ValueError('Not a node setup code')
    raw = code.strip()[len(PREFIX):]
    try:
        data = json.loads(base64.urlsafe_b64decode(raw + '=' * (-len(raw) % 4)))
    except (ValueError, TypeError):
        raise ValueError('The setup code is damaged')
    if not isinstance(data, dict) or data.get('v') != 1:
        raise ValueError('Unknown setup code version')
    mesh_join.decode(data.get('mesh'))   # raises with its own message
    lora = data.get('lora')
    if lora is not None:
        mt.team_from_url(lora)
    return data['mesh'], lora
