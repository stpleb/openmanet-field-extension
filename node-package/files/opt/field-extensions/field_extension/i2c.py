"""Minimal I2C register access using one combined write-then-read
transaction (I2C_RDWR), plus a single-register write for the PiSugar toggle. The PiSugar 3 MCU returns garbage for a separate
write followed by a read, so a repeated-start transfer is required.
"""
import ctypes
import fcntl
import os

I2C_RDWR = 0x0707
I2C_M_RD = 0x0001


class _Msg(ctypes.Structure):
    _fields_ = [('addr', ctypes.c_uint16), ('flags', ctypes.c_uint16),
                ('len', ctypes.c_uint16), ('buf', ctypes.POINTER(ctypes.c_uint8))]


class _RdwrData(ctypes.Structure):
    _fields_ = [('msgs', ctypes.POINTER(_Msg)), ('nmsgs', ctypes.c_uint32)]


def read_registers(bus, address, register, length=1):
    """Return `length` bytes starting at `register`, or None on any failure."""
    out_buf = (ctypes.c_uint8 * 1)(register)
    in_buf = (ctypes.c_uint8 * length)()
    msgs = (_Msg * 2)(
        _Msg(address, 0, 1, ctypes.cast(out_buf, ctypes.POINTER(ctypes.c_uint8))),
        _Msg(address, I2C_M_RD, length, ctypes.cast(in_buf, ctypes.POINTER(ctypes.c_uint8))),
    )
    data = _RdwrData(ctypes.cast(msgs, ctypes.POINTER(_Msg)), 2)
    try:
        fd = os.open(bus, os.O_RDWR)
    except OSError:
        return None
    try:
        fcntl.ioctl(fd, I2C_RDWR, data)
        return bytes(in_buf)
    except OSError:
        return None
    finally:
        os.close(fd)


def write_register(bus, address, register, value):
    """Write one byte to `register`. Returns True on success."""
    buf = (ctypes.c_uint8 * 2)(register, value & 0xFF)
    msgs = (_Msg * 1)(_Msg(address, 0, 2, ctypes.cast(buf, ctypes.POINTER(ctypes.c_uint8))))
    data = _RdwrData(ctypes.cast(msgs, ctypes.POINTER(_Msg)), 1)
    try:
        fd = os.open(bus, os.O_RDWR)
    except OSError:
        return False
    try:
        fcntl.ioctl(fd, I2C_RDWR, data)
        return True
    except OSError:
        return False
    finally:
        os.close(fd)
