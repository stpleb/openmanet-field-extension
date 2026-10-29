"""Node warnings as ATAK chat, so nobody has to open the web page to learn the node is
in trouble. Sent to All Chat Rooms on this node's own Wi-Fi, from the node's name.
OFF by default (team chat is for people); on with /etc/field-extensions/state/alerts-enabled = 1
or the web page's "Node alerts in ATAK chat" switch.
Each alert is sent when its condition starts (after a short hold, so a blip stays
quiet) and once more when it clears. Battery low clears only above CLEAR_PCT, and a
condition that starts again within COOLDOWN_S stays quiet, so a hovering reading or a
loose charger cannot fill the chat.
"""
import socket
import time

from . import tak

HOLD_S = {'mesh_lost': 30, 'battery_low': 60, 'battery_critical': 30, 'hot': 60}
LOW, CRITICAL = 20, 10
CLEAR_PCT = 25      # battery low/critical stay on until above this
COOLDOWN_S = 600    # a condition re-announced at most once per 10 min
HOT_C = 80.0

TEXT = {
    'mesh_lost': ('Mesh link lost. Phones on this node only reach the team over LoRa now.',
                  'Mesh link back.'),
    'battery_low': ('Battery low: {pct}% and not charging.', 'Battery OK: charging or above 25%.'),
    'battery_critical': ('Battery critical: {pct}%. Plug in now; the node shuts itself down soon.', None),
    'hot': ('Node running hot ({temp:.0f} C). Give it shade or air.', 'Node temperature back to normal.'),
    'shutdown': ('Battery empty: shutting down in {secs} s unless a charger is plugged in.', None),
}


class Alerts:
    def __init__(self, send, name=None, clock=time.monotonic, wall=time.time):
        self.send, self.clock, self.wall = send, clock, wall
        self.host = socket.gethostname()
        self.name = name or self.host   # the callsign; led_driver refreshes it
        self.since = {}       # condition -> time it started
        self.active = set()   # conditions already announced
        self.said = {}        # condition -> when it was last announced
        self.sent = []        # last messages, for tests and the web page

    def say(self, text):
        ev = {'kind': 'chat', 'uid': 'FE-NODE-' + self.host, 'callsign': self.name,
              'chat': {'text': text[:200], 'room': tak.ALL_CHAT, 'room_id': tak.ALL_CHAT}}
        self.sent.append(text)
        del self.sent[:-20]
        self.send(tak.CHAT_GROUP, tak.CHAT_PORT, tak.to_cot(ev, now=self.wall()))

    def update(self, conditions, values=None):
        """conditions: {name: bool}. values: numbers for the message text."""
        now, values = self.clock(), values or {}
        for name, on in conditions.items():
            if on:
                start = self.since.setdefault(name, now)
                if name not in self.active and now - start >= HOLD_S.get(name, 0):
                    self.active.add(name)
                    last = self.said.get(name)
                    if last is None or now - last >= COOLDOWN_S:
                        self.said[name] = now
                        self.say(TEXT[name][0].format(**values))
                    else:
                        self.active.add(name + ':quiet')
            else:
                self.since.pop(name, None)
                if name in self.active:
                    self.active.discard(name)
                    if name + ':quiet' in self.active:   # its start was never sent
                        self.active.discard(name + ':quiet')
                    elif TEXT[name][1]:
                        self.say(TEXT[name][1].format(**values))

    def observe(self, r):
        """r: the light driver's readings plus temperature_c. LoRa fallback is normal
        operation, not a warning, so it is never announced."""
        pct, plugged = r.get('battery_percent'), r.get('external_power')
        on_battery = plugged is False and pct is not None
        temp = r.get('temperature_c')
        def low(name, limit):   # once on, stays on until charging or above CLEAR_PCT
            return on_battery and pct <= (CLEAR_PCT if name in self.active else limit)
        self.update({
            'mesh_lost': r.get('mesh_neighbors', 0) == 0 and r.get('had_mesh', False),
            'battery_critical': low('battery_critical', CRITICAL),
            'battery_low': low('battery_low', LOW),
            'hot': temp is not None and temp >= HOT_C,
        }, {'pct': pct if pct is not None else 0, 'temp': temp or 0})

    def shutdown_warning(self, secs):
        if 'shutdown' not in self.active:
            self.active.add('shutdown')
            self.say(TEXT['shutdown'][0].format(secs=int(secs)))


def cpu_temp(path='/sys/class/thermal/thermal_zone0/temp'):
    try:
        with open(path) as f:
            return int(f.read().strip()) / 1000
    except (OSError, ValueError):
        return None
