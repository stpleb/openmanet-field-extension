"""ATAK mesh traffic <-> Meshtastic TAKPacket, for carrying positions and chat over LoRa.

ATAK phones on a node's Wi-Fi announce themselves (SA) on 239.2.3.1:6969 and send
GeoChat to 224.10.10.1:17012, as CoT XML ("protocol 0") or TAK protocol 1
(0xbf 0x01 0xbf + a TakMessage protobuf). On LoRa the same information travels as the
Meshtastic TAKPacket (atak.proto, port ATAK_PLUGIN = 72), the format the official
Meshtastic ATAK plugin uses, so plain Meshtastic radios running that plugin can join in.

XML is read with small regexes: python3-light has no xml package, and ATAK's CoT
attributes are simple. Field numbers: ATAK takproto (cotevent, detail, contact, group,
status, track) and Meshtastic atak.proto.
"""
import os
import re
import time

from . import pb

SA_GROUP, SA_PORT = '239.2.3.1', 6969
CHAT_GROUP, CHAT_PORT = '224.10.10.1', 17012
ALL_CHAT = 'All Chat Rooms'
TAK1_MAGIC = b'\xbf\x01\xbf'
MAX_CHAT = 127  # keeps a chat with callsign and uid inside one LoRa packet

TEAMS = ['', 'White', 'Yellow', 'Orange', 'Magenta', 'Red', 'Maroon', 'Purple', 'Dark Blue',
         'Blue', 'Cyan', 'Teal', 'Green', 'Dark Green', 'Brown']
ROLES = ['', 'Team Member', 'Team Lead', 'HQ', 'Sniper', 'Medic', 'Forward Observer', 'RTO']

_ATTR = re.compile(r'([\w:.-]+)\s*=\s*(?:"([^"]*)"|\'([^\']*)\')')
_ENT = {'&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'", '&amp;': '&'}


def unescape(s):
    return re.sub(r'&(lt|gt|quot|apos|amp);', lambda m: _ENT[m.group(0)], s)


def escape(s):
    return (str(s).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
            .replace('"', '&quot;').replace("'", '&apos;'))


def tag_attrs(xml, tag):
    m = re.search(r'<%s\b([^>]*)>' % re.escape(tag), xml)
    if not m:
        return None
    return {k: unescape(a if a is not None else b) for k, a, b in _ATTR.findall(m.group(1))}


def _num(v, default=None):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def _from_detail(ev, detail):
    """Fill the parts of an event that live in <detail> (XML in both protocols)."""
    contact = tag_attrs(detail, 'contact') or {}
    group = tag_attrs(detail, '__group') or {}
    status = tag_attrs(detail, 'status') or {}
    track = tag_attrs(detail, 'track') or {}
    ev.setdefault('callsign', contact.get('callsign'))
    ev.setdefault('endpoint', contact.get('endpoint'))
    link = tag_attrs(detail, 'link') or {}
    ev.setdefault('link_uid', link.get('uid'))
    ev.setdefault('team', group.get('name'))
    ev.setdefault('role', group.get('role'))
    ev.setdefault('battery', _num(status.get('battery')))
    ev.setdefault('speed', _num(track.get('speed')))
    ev.setdefault('course', _num(track.get('course')))
    chat = tag_attrs(detail, '__chat')
    if chat is not None:
        remarks = re.search(r'<remarks\b[^>]*>(.*?)</remarks>', detail, re.S)
        link = tag_attrs(detail, 'link') or {}
        grp = tag_attrs(detail, 'chatgrp') or {}
        ev['chat'] = {'room': chat.get('chatroom') or ALL_CHAT,
                      'room_id': chat.get('id') or grp.get('uid1') or ALL_CHAT,
                      'sender_callsign': chat.get('senderCallsign') or ev.get('callsign'),
                      'sender_uid': grp.get('uid0') or link.get('uid'),
                      'text': unescape(remarks.group(1)) if remarks else ''}
    return ev


def parse_xml(data):
    text = data.decode('utf-8', 'replace') if isinstance(data, bytes) else data
    event = tag_attrs(text, 'event')
    if not event or not event.get('uid'):
        return None
    point = tag_attrs(text, 'point') or {}
    ev = {'uid': event['uid'], 'type': event.get('type', ''), 'how': event.get('how', ''),
          'lat': _num(point.get('lat')), 'lon': _num(point.get('lon')), 'hae': _num(point.get('hae'))}
    detail = re.search(r'<detail\b[^>]*>(.*)</detail>', text, re.S)
    return _from_detail(ev, detail.group(1) if detail else '')


def parse_tak1(data):
    m = pb.decode(data[3:])
    raw = pb.first(m, 2)
    if raw is None:
        return None  # TakControl only
    e = pb.decode(raw)
    ev = {'uid': pb.as_text(pb.first(e, 5)), 'type': pb.as_text(pb.first(e, 1)),
          'how': pb.as_text(pb.first(e, 9)), 'lat': pb.as_double(pb.first(e, 10)),
          'lon': pb.as_double(pb.first(e, 11)), 'hae': pb.as_double(pb.first(e, 12))}
    if not ev['uid']:
        return None
    d = pb.decode(pb.first(e, 15, b''))
    contact = pb.decode(pb.first(d, 2, b''))
    group = pb.decode(pb.first(d, 3, b''))
    track = pb.decode(pb.first(d, 7, b''))
    if pb.first(d, 2) is not None:
        ev['callsign'] = pb.as_text(pb.first(contact, 2)) or None
        ev['endpoint'] = pb.as_text(pb.first(contact, 1)) or None
    if pb.first(d, 3) is not None:
        ev['team'] = pb.as_text(pb.first(group, 1)) or None
        ev['role'] = pb.as_text(pb.first(group, 2)) or None
    if pb.first(d, 5) is not None:
        ev['battery'] = pb.first(pb.decode(pb.first(d, 5)), 1)
    if pb.first(d, 7) is not None:
        ev['speed'] = pb.as_double(pb.first(track, 1))
        ev['course'] = pb.as_double(pb.first(track, 2))
    return _from_detail(ev, pb.as_text(pb.first(d, 1)))


def parse(data):
    """CoT from either mesh protocol, as a flat dict; None if it is not usable."""
    try:
        if data[:3] == TAK1_MAGIC:
            return parse_tak1(data)
        if data[:1] == b'\xbf':
            return None  # a newer TAK protocol version we do not read
        return parse_xml(data)
    except (ValueError, IndexError, UnicodeError):
        return None


MARKER_TYPES = ('a-', 'b-m-p', 'u-d-')


def kind(ev):
    """chat, pli (a phone's own position), marker (a point someone placed), delete, or None."""
    t = ev['type']
    if ev.get('chat') and t.startswith('b-t-f'):
        return 'chat'
    if t == 't-x-d-d':
        return 'delete' if ev.get('link_uid') else None
    if ev.get('lat') is None or (ev['lat'] == 0 and ev['lon'] == 0):
        return None
    if t.startswith('a-') and ev.get('callsign') and (ev.get('endpoint') or ev.get('how', '').startswith('m-')):
        return 'pli'
    if t.startswith(MARKER_TYPES) and ev.get('how', '').startswith('h-'):
        return 'marker'
    return None


def to_takpacket(ev):
    """Meshtastic TAKPacket bytes for a position or chat, or None for anything else."""
    k = kind(ev)
    if k not in ('pli', 'chat'):
        return None
    uid = ev['uid']
    if k == 'chat':
        uid = ev['chat'].get('sender_uid') or uid
    contact = pb.text(1, ev.get('callsign') or ev.get('chat', {}).get('sender_callsign') or '') + pb.text(2, uid)
    out = pb.blob(2, contact)
    team = TEAMS.index(ev['team']) if ev.get('team') in TEAMS else 0
    role = ROLES.index(ev['role']) if ev.get('role') in ROLES else 0
    if team or role:
        out += pb.blob(3, pb.uint(1, role) + pb.uint(2, team))
    if ev.get('battery') is not None:
        out += pb.blob(4, pb.uint(1, max(0, min(100, int(ev['battery'])))))
    if k == 'pli':
        hae = ev.get('hae')
        pli = (pb.sfixed32(1, round(ev['lat'] * 1e7)) + pb.sfixed32(2, round(ev['lon'] * 1e7)) +
               (pb.uint(3, round(hae)) if hae is not None and abs(hae) < 9e5 else b'') +
               pb.uint(4, round(ev.get('speed') or 0)) + pb.uint(5, round(ev.get('course') or 0) % 360))
        return out + pb.blob(5, pli)
    chat = ev['chat']
    to = None if chat['room_id'] == ALL_CHAT else chat['room_id']
    body = pb.text(1, chat['text'][:MAX_CHAT]) + (pb.text(2, to) + pb.text(3, chat['room']) if to else b'')
    return out + pb.blob(6, body)


def from_takpacket(data):
    m = pb.decode(data)
    if pb.first(m, 1, 0):
        return None  # compressed strings (unishox2) are not supported yet
    contact = pb.decode(pb.first(m, 2, b''))
    group = pb.decode(pb.first(m, 3, b''))
    ev = {'callsign': pb.as_text(pb.first(contact, 1)) or None,
          'uid': pb.as_text(pb.first(contact, 2)) or None}
    if not ev['uid']:
        return None
    team, role = pb.first(group, 2, 0), pb.first(group, 1, 0)
    ev['team'] = TEAMS[team] if 0 < team < len(TEAMS) else None
    ev['role'] = ROLES[role] if 0 < role < len(ROLES) else None
    status = pb.first(m, 4)
    ev['battery'] = pb.first(pb.decode(status), 1, 0) if status is not None else None
    if pb.first(m, 5) is not None:
        p = pb.decode(pb.first(m, 5))
        ev.update({'kind': 'pli', 'lat': pb.as_s32(pb.first(p, 1, b'\0\0\0\0')) / 1e7,
                   'lon': pb.as_s32(pb.first(p, 2, b'\0\0\0\0')) / 1e7,
                   'hae': pb.as_int32(pb.first(p, 3, 0)) if pb.first(p, 3) is not None else None,
                   'speed': pb.first(p, 4, 0), 'course': pb.first(p, 5, 0)})
    elif pb.first(m, 6) is not None:
        c = pb.decode(pb.first(m, 6))
        to = pb.as_text(pb.first(c, 2)) or None
        ev.update({'kind': 'chat', 'chat': {'text': pb.as_text(pb.first(c, 1)), 'room_id': to or ALL_CHAT,
                                            'room': pb.as_text(pb.first(c, 3)) or (to or ALL_CHAT)}})
    else:
        return None
    return ev


def _iso(t):
    return time.strftime('%Y-%m-%dT%H:%M:%S', time.gmtime(t)) + '.%03dZ' % int(t % 1 * 1000)


def to_cot(ev, now=None, stale_s=600):
    """CoT XML (protocol 0, which every ATAK reads) for a packet heard on LoRa."""
    now = time.time() if now is None else now
    times = 'time="%s" start="%s" stale="%s"' % (_iso(now), _iso(now), _iso(now + stale_s))
    group = ('<__group name="%s" role="%s"/>' % (escape(ev.get('team') or 'Cyan'), escape(ev.get('role') or 'Team Member')))
    status = '<status battery="%d"/>' % ev['battery'] if ev.get('battery') else ''
    callsign = escape(ev.get('callsign') or ev['uid'])
    if ev['kind'] == 'pli':
        hae = ev['hae'] if ev.get('hae') is not None else 9999999.0
        return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                '<event version="2.0" uid="%s" type="a-f-G-U-C" how="m-g" %s>'
                '<point lat="%.7f" lon="%.7f" hae="%s" ce="9999999.0" le="9999999.0"/>'
                '<detail><contact callsign="%s" endpoint="*:-1:stcp"/>%s%s'
                '<track speed="%s" course="%s"/><uid Droid="%s"/></detail></event>'
                % (escape(ev['uid']), times, ev['lat'], ev['lon'], hae, callsign, group, status,
                   ev.get('speed') or 0, ev.get('course') or 0, callsign)).encode()
    chat = ev['chat']
    msg_id = os.urandom(8).hex()
    room, room_id = escape(chat['room']), escape(chat['room_id'])
    uid = escape(ev['uid'])
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<event version="2.0" uid="GeoChat.%s.%s.%s" type="b-t-f" how="h-g-i-g-o" %s>'
            '<point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>'
            '<detail><__chat parent="RootContactGroup" groupOwner="false" messageId="%s" chatroom="%s" '
            'id="%s" senderCallsign="%s"><chatgrp uid0="%s" uid1="%s" id="%s"/></__chat>'
            '<link uid="%s" type="a-f-G-U-C" relation="p-p"/>'
            '<remarks source="BAO.F.ATAK.%s" to="%s" time="%s">%s</remarks></detail></event>'
            % (uid, room_id, msg_id, times, msg_id, room, room_id, callsign, uid, room_id, room_id,
               uid, uid, room_id, _iso(now), escape(chat['text']))).encode()


def marker_cot(ev, now=None, stale_s=86400):
    """CoT for a marker or a delete heard on LoRa (from another Field Extension node)."""
    now = time.time() if now is None else now
    times = 'time="%s" start="%s" stale="%s"' % (_iso(now), _iso(now), _iso(now + stale_s))
    uid = escape(ev['uid'])
    if ev['kind'] == 'delete':
        return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                '<event version="2.0" uid="%s" type="t-x-d-d" how="h-g-i-g-o" %s>'
                '<point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>'
                '<detail><link uid="%s" relation="none" type="none"/><__forcedelete/></detail></event>'
                % (os.urandom(8).hex(), times, uid)).encode()
    hae = ev['hae'] if ev.get('hae') is not None else 9999999.0
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<event version="2.0" uid="%s" type="%s" how="h-g-i-g-o" %s>'
            '<point lat="%.7f" lon="%.7f" hae="%s" ce="9999999.0" le="9999999.0"/>'
            '<detail><contact callsign="%s"/><archive/><remarks>Sent over LoRa</remarks></detail></event>'
            % (uid, escape(ev['type']), times, ev['lat'], ev['lon'], hae, escape(ev.get('callsign') or ev['uid']))).encode()
