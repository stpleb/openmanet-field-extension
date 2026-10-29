"""Is there real internet behind this node's default route?

A Mesh Point always has a default route to its Mesh Gate, even when the gate has no
uplink, so a route alone is not internet. online() opens a TCP connection to a few
well-known anycast addresses (no DNS needed). The answer is shared between the FE
processes through a small cache file for CACHE_S seconds, and a node only goes
offline after FAILS_TO_DROP checks in a row fail, so one lost probe does not flip the
light or the phones' internet mode. Checks run in the background; callers get the
last known answer at once.
"""
import json
import socket
import threading
import time
from pathlib import Path

CACHE = Path('/var/run/field-internet.json')
CACHE_S = 15
FAILS_TO_DROP = 2
TARGETS = (('1.1.1.1', 443), ('8.8.8.8', 443), ('9.9.9.9', 443), ('1.1.1.1', 53))
TIMEOUT_S = 2.0


def probe(targets=TARGETS, timeout=TIMEOUT_S, connect=socket.create_connection):
    for host, port in targets:
        try:
            connect((host, port), timeout=timeout).close()
            return True
        except OSError:
            continue
    return False


_busy = threading.Lock()


def _refresh(cache, now, saved, check):
    try:
        ok = check()
        fails = 0 if ok else int(saved.get('fails') or 0) + 1
        result = ok or (saved.get('online') is True and fails < FAILS_TO_DROP)
        try:
            tmp = cache.with_name(cache.name + '.tmp')
            tmp.write_text(json.dumps({'at': now, 'online': result, 'fails': fails}))
            tmp.replace(cache)
        except OSError:
            pass
        return result
    finally:
        _busy.release()


def online(cache=None, clock=time.time, check=probe, wait=False):
    """Last known answer. A stale one starts a fresh check in the background (so the
    status light loop and web requests never block on a dead uplink); wait=True runs it
    inline and returns its result."""
    cache = Path(cache or CACHE)
    now = clock()
    try:
        saved = json.loads(cache.read_text())
    except (OSError, ValueError):
        saved = {}
    if not isinstance(saved, dict):
        saved = {}
    if isinstance(saved.get('at'), (int, float)) and 0 <= now - saved['at'] < CACHE_S:
        return saved.get('online') is True
    if _busy.acquire(blocking=False):
        if wait:
            return _refresh(cache, now, saved, check)
        threading.Thread(target=_refresh, args=(cache, now, saved, check), daemon=True).start()
    return saved.get('online') is True
