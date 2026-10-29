"""Just enough Protocol Buffers for the Meshtastic radio link and ATAK's mesh format.

The node's Python (OpenWrt python3-light) has no protobuf library, and the messages
we need are small, so fields are written and read by number. Field numbers come from
the public Meshtastic protobufs (mesh, config, channel, admin, atak) and ATAK's
takproto definitions; each caller names them where it uses them.
"""
import struct

VARINT, I64, LEN, I32 = 0, 1, 2, 5


def varint(n):
    if n < 0:
        n += 1 << 64  # int32/int64 negatives are ten bytes on the wire
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def key(field, wire):
    return varint(field << 3 | wire)


def uint(field, n):
    return key(field, VARINT) + varint(int(n)) if n else b''


def boolean(field, value):
    return key(field, VARINT) + b'\x01' if value else b''


def blob(field, data):
    if data is None:
        return b''
    if isinstance(data, str):
        data = data.encode()
    return key(field, LEN) + varint(len(data)) + bytes(data)


def text(field, s):
    return blob(field, s) if s else b''


def fixed32(field, n):
    return key(field, I32) + struct.pack('<I', int(n) & 0xFFFFFFFF)


def sfixed32(field, n):
    return key(field, I32) + struct.pack('<i', int(n))


def double(field, x):
    return key(field, I64) + struct.pack('<d', float(x))


def _read_varint(buf, i):
    n = shift = 0
    while True:
        if i >= len(buf) or shift > 63:
            raise ValueError('truncated varint')
        b = buf[i]
        i += 1
        n |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            return n, i


def fields(buf):
    """Yield (field, wire, value). LEN values are bytes, I32/I64 raw little-endian bytes."""
    buf = bytes(buf)
    i = 0
    while i < len(buf):
        k, i = _read_varint(buf, i)
        field, wire = k >> 3, k & 7
        if wire == VARINT:
            value, i = _read_varint(buf, i)
        elif wire == LEN:
            n, i = _read_varint(buf, i)
            value = buf[i:i + n]
            if len(value) != n:
                raise ValueError('truncated field')
            i += n
        elif wire == I32:
            value = buf[i:i + 4]
            i += 4
        elif wire == I64:
            value = buf[i:i + 8]
            i += 8
        else:
            raise ValueError('unsupported wire type %d' % wire)
        if wire in (I32, I64) and len(value) != (4 if wire == I32 else 8):
            raise ValueError('truncated field')
        yield field, wire, value


def decode(buf):
    """{field: [values...]} for random access; repeated fields keep their order."""
    out = {}
    for field, wire, value in fields(buf):
        out.setdefault(field, []).append((wire, value))
    return out


def first(msg, field, default=None):
    v = msg.get(field)
    return v[0][1] if v else default


def as_int32(n):
    return n - (1 << 64) if n >= 1 << 63 else n


def as_text(b, default=''):
    if b is None:
        return default
    return bytes(b).decode('utf-8', 'replace')


def as_u32(b):
    return struct.unpack('<I', b)[0] if b is not None else None


def as_s32(b):
    return struct.unpack('<i', b)[0] if b is not None else None


def as_float(b):
    return struct.unpack('<f', b)[0] if b is not None else None


def as_double(b):
    return struct.unpack('<d', b)[0] if b is not None else None
