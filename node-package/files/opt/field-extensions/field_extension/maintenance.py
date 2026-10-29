"""Maintenance (white light): "someone is working on this node, don't unplug it".

Active while any of these holds (Stephen approved the white light 2026-10-26):
- manual maintenance mode, switched on from the web page, app or API; it ends by
  itself at state/maintenance-until (epoch seconds), so it survives a reboot but
  can never be left on for good;
- a short-lived busy marker in /tmp written around updates, settings changes and
  diagnostics (cleared by a reboot, and each one expires on its own);
- an opkg, sysupgrade or self-test process is running, whoever started it.
Logins (web, SSH) never count.
"""
import time
from pathlib import Path

STATE_DIR = Path('/etc/field-extensions/state')
BUSY_DIR = Path('/tmp/field-extensions/busy')
MAX_MINUTES = 240
REASONS = ('manual', 'update', 'settings', 'diagnostics')   # shown in this order of preference
PROCESSES = {'opkg': 'update', 'sysupgrade': 'update', 'selftest.sh': 'diagnostics'}


def busy_dir_for(state_dir):
    """The real marker folder for the node's own state folder; a private one beside any
    other state folder (tests), so markers never leak between them."""
    return BUSY_DIR if Path(state_dir) == STATE_DIR else Path(state_dir) / 'busy'


def mark(reason, seconds, busy_dir=None, wall=time.time):
    """Hold the white light for `reason` for up to `seconds` (or until clear/reboot)."""
    busy_dir = Path(busy_dir or BUSY_DIR)
    try:
        busy_dir.mkdir(parents=True, exist_ok=True)
        (busy_dir / reason).write_text('%d\n' % (wall() + seconds))
    except OSError:
        pass


def clear(reason, busy_dir=None):
    busy_dir = Path(busy_dir or BUSY_DIR)
    try:
        (busy_dir / reason).unlink()
    except OSError:
        pass


def set_manual(enabled, minutes=30, state_dir=None, wall=time.time):
    path = Path(state_dir or STATE_DIR) / 'maintenance-until'
    if not enabled:
        try:
            path.unlink()
        except OSError:
            pass
        return None
    until = int(wall() + minutes * 60)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('%d\n' % until)
    return until


def manual_until(state_dir=None, wall=time.time):
    try:
        until = int((Path(state_dir or STATE_DIR) / 'maintenance-until').read_text().strip())
    except (OSError, ValueError):
        return None
    now = wall()
    # A clock that jumped (or a hand-edited file) must not keep the light white for good.
    if until <= now or until - now > MAX_MINUTES * 60 + 60:
        return None
    return until


def running(proc=Path('/proc')):
    """Reasons from processes running right now (opkg, sysupgrade, self-test)."""
    found = set()
    try:
        entries = [p for p in proc.iterdir() if p.name.isdigit()]
    except OSError:
        return found
    for p in entries:
        try:
            argv = (p / 'cmdline').read_bytes().split(b'\0')
        except OSError:
            continue
        for arg in argv[:2]:   # the program, or the script a shell is running
            name = arg.decode('utf-8', 'replace').rsplit('/', 1)[-1]
            if name in PROCESSES:
                found.add(PROCESSES[name])
    return found


def status(state_dir=None, busy_dir=None, proc=Path('/proc'), wall=time.time):
    """{'active', 'until', 'reason'}: until is the manual end time, or None."""
    state_dir, busy_dir = Path(state_dir or STATE_DIR), Path(busy_dir or BUSY_DIR)
    now = wall()
    reasons = set()
    until = manual_until(state_dir, wall)
    if until:
        reasons.add('manual')
    try:
        for f in busy_dir.iterdir():
            try:
                if int(f.read_text().strip()) > now:
                    reasons.add(f.name)
            except (OSError, ValueError):
                pass
    except OSError:
        pass
    reasons |= running(proc)
    reason = next((r for r in REASONS if r in reasons), None)
    return {'active': reason is not None, 'until': until, 'reason': reason}


def shutting_down(busy_dir=None, wall=time.time):
    """True for a few minutes after a reboot or shutdown was started from the web or API."""
    busy_dir = Path(busy_dir or BUSY_DIR)
    try:
        return int((busy_dir / 'shutting-down').read_text().strip()) > wall()
    except (OSError, ValueError):
        return False
