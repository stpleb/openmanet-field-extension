"""Clear fleet data: wipe team chat (and photos, where an app keeps them) everywhere at once.

A clear is a small record {id, at, what}. The node it starts on keeps it, shows it in
/api/field/hello and the summary, and every node copies a newer one from its mesh peers
every 30 s, so it spreads hop by hop and a node that was off reaches it when it rejoins.
Phones read summary.fleet_clear and delete what they hold from before `at`; a node empties
its LoRa message log. Nodes keep no photos, so 'photos' only tells the phones.
"""
import json
import os
import re
import time
from pathlib import Path

STATE = Path('/etc/field-extensions/state')
WHAT = ('chat', 'photos', 'both')
CHECK_S = 30
FUTURE_S = 300            # a record dated further ahead than this is refused (bad clock)
ID_RE = re.compile(r'^[0-9a-f]{16}$')


def _file(state):
    return Path(state) / 'fleet-clear.json'


def valid(rec, now):
    return (isinstance(rec, dict) and isinstance(rec.get('id'), str) and ID_RE.match(rec['id'])
            and rec.get('what') in WHAT and isinstance(rec.get('at'), int)
            and not isinstance(rec.get('at'), bool) and 0 < rec['at'] <= now + FUTURE_S)


def current(state=STATE, clock=time.time):
    try:
        rec = json.loads(_file(state).read_text())
    except (OSError, ValueError):
        return None
    if not valid(rec, clock()):
        return None
    return {k: rec[k] for k in ('id', 'at', 'what')} | {'by': str(rec.get('by') or '')[:40]}


def _save(state, rec):
    path = _file(state)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + '.tmp')
    tmp.write_text(json.dumps(rec))
    tmp.replace(path)


def start(what, by, state=STATE, clock=time.time):
    if what not in WHAT:
        return {'ok': False, 'error': 'what must be chat, photos or both'}
    rec = {'id': os.urandom(8).hex(), 'at': int(clock()), 'what': what, 'by': str(by or '')[:40]}
    _save(state, rec)
    return {'ok': True, 'clear': rec,
            'note': 'Clearing %s on every node and phone. Nodes that are off now clear when they rejoin.'
                    % {'chat': 'chat', 'photos': 'photos', 'both': 'chat and photos'}[what]}


def adopt(rec, state=STATE, clock=time.time):
    """Take a peer's clear if it is newer than ours. True when it was taken."""
    if not valid(rec, clock()):
        return False
    mine = current(state, clock)
    if mine is not None and (mine['id'] == rec['id'] or mine['at'] >= rec['at']):
        return False
    _save(state, {k: rec[k] for k in ('id', 'at', 'what')} | {'by': str(rec.get('by') or '')[:40]})
    return True


class Spreader:
    """Polls peers' hello for a newer clear; on_clear runs whenever one is taken or started."""
    def __init__(self, peers, get_json, on_clear, state=STATE, port=8787, clock=time.time):
        self.peers, self.get_json, self.on_clear = peers, get_json, on_clear
        self.state, self.port, self.clock = state, port, clock

    def check(self):
        taken = False
        for ip in self.peers():
            try:
                hello = self.get_json('http://%s:%d/api/field/hello' % (ip, self.port))
            except Exception:
                continue
            if isinstance(hello, dict) and adopt(hello.get('fleet_clear'), self.state, self.clock):
                taken = True
        if taken:
            self.on_clear(current(self.state, self.clock))
        return taken

    def run(self, stop):
        while not stop.wait(CHECK_S):
            try:
                self.check()
            except Exception:
                pass
