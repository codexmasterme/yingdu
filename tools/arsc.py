"""最小的 resources.arsc：只有一个 drawable 资源 ic_launcher（0x7f010000）。"""
import struct

def _pad4(b):
    return b + b'\x00' * ((4 - len(b) % 4) % 4)

def string_pool(strings, utf8=True):
    data = b''
    offsets = []
    for s in strings:
        offsets.append(len(data))
        if utf8:
            enc = s.encode('utf-8')
            assert len(s) < 0x80 and len(enc) < 0x80
            data += bytes([len(s), len(enc)]) + enc + b'\x00'
        else:
            enc = s.encode('utf-16-le')
            data += struct.pack('<H', len(s)) + enc + b'\x00\x00'
    data = _pad4(data)
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    body = b''.join(struct.pack('<I', o) for o in offsets) + data
    size = header_size + len(body)
    flags = 0x100 if utf8 else 0
    return struct.pack('<HHIIIIII', 0x0001, header_size, size, len(strings), 0, flags, strings_start, 0) + body

def build(package_name, file_path, key='ic_launcher', type_name='drawable', density=640):
    gpool = string_pool([file_path])
    type_pool = string_pool([type_name])
    key_pool = string_pool([key])

    spec = struct.pack('<HHIBBHI', 0x0202, 16, 16 + 4, 1, 0, 0, 1) + struct.pack('<I', 0)

    config = bytearray(64)
    struct.pack_into('<I', config, 0, 64)
    struct.pack_into('<H', config, 14, density)      # density
    struct.pack_into('<H', config, 24, 4)            # sdkVersion（-v4 限定符）
    header_size = 20 + len(config)
    offsets = struct.pack('<I', 0)
    entries = struct.pack('<HHI', 8, 0, 0) + struct.pack('<HBBI', 8, 0, 0x03, 0)  # 字符串值 → 全局池第 0 项
    entries_start = header_size + len(offsets)
    tsize = entries_start + len(entries)
    ttype = struct.pack('<HHIBBHII', 0x0201, header_size, tsize, 1, 0, 0, 1, entries_start) + bytes(config) + offsets + entries

    name = package_name.encode('utf-16-le')[:254]
    name += b'\x00' * (256 - len(name))
    pkg_header_size = 288
    type_strings_off = pkg_header_size
    key_strings_off = type_strings_off + len(type_pool)
    body = type_pool + key_pool + spec + ttype
    pkg = struct.pack('<HHII', 0x0200, pkg_header_size, pkg_header_size + len(body), 0x7f) + name + \
        struct.pack('<IIIII', type_strings_off, 0, key_strings_off, 0, 0) + body

    table_body = gpool + pkg
    return struct.pack('<HHII', 0x0002, 12, 12 + len(table_body), 1) + table_body
