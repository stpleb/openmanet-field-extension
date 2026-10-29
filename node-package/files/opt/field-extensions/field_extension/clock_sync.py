"""Keep the system clock right with no internet.

The Pi has no clock of its own; OpenWrt starts from the newest file time it can find,
so after a power cut the clock is hours or days behind. ATAK treats positions with a
wrong time as stale. Sources, best first: GPS time (gpsd), then the PiSugar 3's own
clock (an RTC kept by its battery). Once the time is known good (GPS, or an answer from
an NTP server), it is written back to the PiSugar clock, so that clock only ever holds
verified time.

Node02 2026-10-13: after a power pull the PiSugar clock held a garbage 2041 time; boot
trusted it, and the wrong time was later treated as internet time and written back. So:
a PiSugar time far ahead of the boot clock is ignored, "internet time" means an NTP
server actually answered, and files left with future times (OpenWrt sets the boot clock
from the newest file in /etc) are pulled back once the time is verified.

PiSugar 3 clock: registers 0x31-0x37 = year (from 2000), month, day, weekday, hour,
minute, second, all BCD; written under the 0x0B write-protect unlock. Years outside
2026-2046 are ignored as unset.
"""
import json
import os
import socket
import struct
import subprocess
import time

from . import i2c

BUS, ADDRESS = '/dev/i2c-1', 0x57
RTC = 0x31
WRITE_PROTECT, UNLOCK, LOCK = 0x0B, 0x29, 0x00
MIN_YEAR, MAX_YEAR = 2026, 2046
GPS_STEP_S = 2          # correct the clock from GPS when it is off by more than this
RTC_STEP_S = 5          # rewrite the PiSugar clock when it is off by more than this
MAX_AHEAD_S = 400 * 86400   # a PiSugar time further ahead of the boot clock is garbage
NTP_EPOCH = 2208988800      # 1900-01-01 -> 1970-01-01
NTP_FALLBACK = ('pool.ntp.org',)


def bcd(b):
    return (b >> 4) * 10 + (b & 0x0F)


def to_bcd(n):
    return ((n // 10) << 4) | (n % 10)


def days_from_civil(y, m, d):
    """Days since 1970-01-01 (proleptic Gregorian); avoids calendar/timezone modules."""
    y -= m <= 2
    era = y // 400
    yoe = y - era * 400
    doy = (153 * (m + (-3 if m > 2 else 9)) + 2) // 5 + d - 1
    doe = yoe * 365 + yoe // 4 - yoe // 100 + doy
    return era * 146097 + doe - 719468


def rtc_epoch(raw):
    """Seven BCD bytes -> UTC seconds, or None if the clock is unset or garbled."""
    if not raw or len(raw) != 7:
        return None
    year, month, day = 2000 + bcd(raw[0]), bcd(raw[1] & 0x1F), bcd(raw[2] & 0x3F)
    hour, minute, sec = bcd(raw[4] & 0x3F), bcd(raw[5] & 0x7F), bcd(raw[6] & 0x7F)
    if not (MIN_YEAR <= year <= MAX_YEAR and 1 <= month <= 12 and 1 <= day <= 31
            and hour < 24 and minute < 60 and sec < 60):
        return None
    return days_from_civil(year, month, day) * 86400 + hour * 3600 + minute * 60 + sec


def rtc_bytes(epoch):
    t = time.gmtime(int(epoch))
    weekday = (t.tm_wday + 1) % 7   # 0 = Sunday
    return [to_bcd(t.tm_year - 2000), to_bcd(t.tm_mon), to_bcd(t.tm_mday), weekday,
            to_bcd(t.tm_hour), to_bcd(t.tm_min), to_bcd(t.tm_sec)]


def gps_epoch(host='127.0.0.1', port=2947, timeout=3.0, connect=socket.create_connection):
    """UTC seconds from gpsd's TPV time when it has a fix, else None."""
    try:
        with connect((host, port), timeout=timeout) as s:
            s.sendall(b'?WATCH={"enable":true,"json":true};\n')
            buf, end = b'', time.monotonic() + timeout
            while time.monotonic() < end and len(buf) < 65536:
                chunk = s.recv(4096)
                if not chunk:
                    break
                buf += chunk
                for line in buf.split(b'\n')[:-1]:
                    try:
                        msg = json.loads(line)
                    except ValueError:
                        continue
                    if msg.get('class') == 'TPV' and msg.get('mode', 0) >= 2 and msg.get('time'):
                        return parse_iso(msg['time'])
    except OSError:
        pass
    return None


def parse_iso(text):
    """'2026-10-08T04:31:07.000Z' -> UTC seconds."""
    try:
        date, clock = text.rstrip('Z').split('T')
        y, m, d = (int(x) for x in date.split('-'))
        hh, mm, ss = clock.split(':')
        return days_from_civil(y, m, d) * 86400 + int(hh) * 3600 + int(mm) * 60 + int(float(ss))
    except (ValueError, AttributeError):
        return None


def ntp_servers(run=subprocess.run):
    try:
        out = run(['uci', '-q', 'get', 'system.ntp.server'], capture_output=True, text=True,
                  timeout=5).stdout
    except (OSError, subprocess.SubprocessError):
        out = ''
    return out.split() or list(NTP_FALLBACK)


def sntp_epoch(servers=None, timeout=2.0, sock=None):
    """UTC seconds from the first NTP server that answers (one SNTP query each), else None."""
    for host in servers if servers is not None else ntp_servers():
        try:
            s = sock() if sock else socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            with s:
                s.settimeout(timeout)
                s.sendto(b'\x1b' + 47 * b'\0', (host, 123))
                data = s.recv(512)
        except OSError:
            continue
        if len(data) >= 48 and data[1] and data[1] < 16:      # stratum 1-15: a synced server
            secs, frac = struct.unpack('!II', data[40:48])
            if secs:
                return secs - NTP_EPOCH + frac / 2 ** 32
    return None


def fix_future_files(now, root='/etc', slack=86400):
    """Pull back file times left in the future by a wrong clock; returns how many."""
    fixed = 0
    for d, _, names in os.walk(root):
        for n in names:
            path = os.path.join(d, n)
            try:
                if not os.path.islink(path) and os.stat(path).st_mtime > now + slack:
                    os.utime(path, (now, now))
                    fixed += 1
            except OSError:
                pass
    return fixed


class ClockSync:
    def __init__(self, read=i2c.read_registers, write=i2c.write_register, gps=gps_epoch,
                 now=time.time, set_clock=None, uptime=None, internet=lambda: False,
                 ntp=sntp_epoch, fix_files=fix_future_files):
        self.read, self.write, self.gps, self.now = read, write, gps, now
        self.set_clock = set_clock or (lambda e: subprocess.run(['date', '-u', '-s', '@%d' % e],
                                                                timeout=10, check=False,
                                                                stdout=subprocess.DEVNULL))
        self.uptime = uptime or _uptime
        self.internet, self.ntp, self.fix_files = internet, ntp, fix_files
        self.source = None      # 'gps', 'pisugar', 'ntp'
        self.last = None
        self.files_checked = False

    def read_rtc(self):
        a = self.read(BUS, ADDRESS, RTC, 7)
        b = self.read(BUS, ADDRESS, RTC, 7)
        if not a or not b or bytes(a)[:6] != bytes(b)[:6]:   # the seconds may tick between reads
            return None
        return rtc_epoch(bytes(b))

    def write_rtc(self, epoch):
        ok = self.write(BUS, ADDRESS, WRITE_PROTECT, UNLOCK)
        try:
            for i, v in enumerate(rtc_bytes(epoch)):
                ok = ok and self.write(BUS, ADDRESS, RTC + i, v)
        finally:
            self.write(BUS, ADDRESS, WRITE_PROTECT, LOCK)
        return ok

    def at_boot(self):
        """PiSugar clock -> system clock. The PiSugar clock only holds verified time, so it
        wins over the boot clock (a guess from file times), unless it is implausibly far
        ahead, which means it is garbage."""
        rtc, now = self.read_rtc(), self.now()
        if rtc is None:
            return self.source
        if rtc > now + MAX_AHEAD_S:
            self.last = {'action': 'PiSugar clock ignored (far in the future)', 'at': int(rtc)}
        elif abs(rtc - now) > 60:
            self.set_clock(rtc)
            self.source, self.last = 'pisugar', {'action': 'set from PiSugar clock', 'at': int(rtc)}
        return self.source

    def tick(self):
        """Every few minutes: GPS time beats everything, then an NTP server's answer; once
        verified, keep the PiSugar clock right."""
        g, verified = self.gps(), False
        if g is not None:
            if abs(g - self.now()) > GPS_STEP_S:
                self.set_clock(g)
                self.last = {'action': 'set from GPS', 'at': int(g)}
            self.source, verified = 'gps', True
        elif self.internet():
            n = self.ntp()
            if n is not None:
                if abs(n - self.now()) > GPS_STEP_S:
                    self.set_clock(n)
                    self.last = {'action': 'set from internet time', 'at': int(n)}
                self.source, verified = 'ntp', True
        if verified:
            if not self.files_checked:
                self.files_checked = True
                self.fix_files(self.now())
            rtc, now = self.read_rtc(), self.now()
            if rtc is None or abs(rtc - now) > RTC_STEP_S:
                if self.write_rtc(now):
                    self.last = {'action': 'PiSugar clock updated', 'at': int(now)}

    def status(self):
        return {'source': self.source, 'last': self.last}


def _uptime():
    try:
        with open('/proc/uptime') as f:
            return float(f.read().split()[0])
    except (OSError, ValueError, IndexError):
        return 0.0
