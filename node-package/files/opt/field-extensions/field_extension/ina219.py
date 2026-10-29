"""INA219 battery monitor (Waveshare UPS HAT (D) layout), a fallback only.

Node02 and Node01 use a PiSugar 3 Plus (I2C 0x57/0x68; nothing answers at 0x43,
re-checked 2026-10-04). This stays for other nodes built with the UPS HAT.
Readings only; that board has no power-on or auto-power controls.
Percentage scale is NOT yet matched to the node: the audit described a linear
3.0-4.2 V scale, but Node02 showed 29% at 3.51 V, which that scale puts at 42%.
Confirm the real discharge curve before relying on it.
"""
from .i2c import read_registers

BUS = '/dev/i2c-1'
ADDRESS = 0x43
REG_BUS_VOLTAGE = 0x02
REG_CURRENT = 0x04
EMPTY_V, FULL_V = 3.0, 4.2


def i2c_read_word(register, bus=BUS, address=ADDRESS):
    """Read one big-endian 16-bit register, or None on any failure."""
    data = read_registers(bus, address, register, 2)
    return (data[0] << 8) | data[1] if data and len(data) == 2 else None


def percent(voltage):
    pct = (voltage - EMPTY_V) / (FULL_V - EMPTY_V) * 100
    return int(max(0, min(100, round(pct))))


class INA219:
    def __init__(self, read_word=i2c_read_word):
        self.read = read_word

    def status(self):
        raw = self.read(REG_BUS_VOLTAGE)
        if raw is None:
            return {'available': False, 'source': 'ina219',
                    'message': 'Battery monitor not responding'}
        voltage = round((raw >> 3) * 0.004, 3)  # bits 15..3, 4 mV per bit
        current_raw = self.read(REG_CURRENT)
        # The current register needs calibration and reads 0 on Node02, so a
        # zero is reported as unknown rather than as "not charging".
        current = None
        if current_raw:
            signed = current_raw - 0x10000 if current_raw & 0x8000 else current_raw
            current = signed  # uncalibrated LSB units until the shunt is confirmed
        return {
            'available': True,
            'source': 'ina219',
            'battery_voltage': voltage,
            'battery_percent': percent(voltage),
            'charging': None if current is None else current > 0,
            'external_power': None,
            'auto_power_on': None,
            'controls': False,
        }
