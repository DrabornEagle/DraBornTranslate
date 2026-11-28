"""Update and validate the Android binary manifest version fields."""

import struct


def chunks(binary, start):
    while start < len(binary):
        code, header, size = struct.unpack_from("<HHI", binary, start)
        if header < 8 or size < header or start + size > len(binary):
            raise ValueError("Malformed Android XML chunk")
        yield code, header, binary[start:start + size]
        start += size


def string_length(binary, start, utf8):
    if utf8:
        first = binary[start]
        return (((first & 127) << 8) | binary[start + 1], start + 2) if first & 128 else (first, start + 1)
    first = struct.unpack_from("<H", binary, start)[0]
    if first & 32768:
        second = struct.unpack_from("<H", binary, start + 2)[0]
        return ((first & 32767) << 16) | second, start + 4
    return first, start + 2


def read_pool_string(pool, start, utf8):
    units, position = string_length(pool, start, utf8)
    if utf8:
        count, position = string_length(pool, position, True)
        return pool[position:position + count].decode("utf-8"), position + count + 1
    return pool[position:position + units * 2].decode("utf-16le"), position + units * 2 + 2


def patched_string_pool(pool, header, before, after):
    string_count, style_count, flags, strings_start, styles_start = struct.unpack_from("<IIIII", pool, 8)
    utf8 = bool(flags & 0x100)
    offsets = [o[0] for o in struct.iter_unpack("<I", pool[header:header + 4 * string_count])]
    decoded = [read_pool_string(pool, strings_start + offset, utf8) for offset in offsets]
    hits = [i for i, (text, _) in enumerate(decoded) if text == before]
    if not hits:
        return pool, set()
    if len(hits) != 1 or style_count or styles_start or len(after) >= 128:
        raise ValueError("Unexpected Android manifest version string pool")
    index = hits[0]
    start = strings_start + offsets[index]
    stop = decoded[index][1]
    end = max(end for _, end in decoded)
    replacement = (bytes([len(after), len(after)]) + after.encode("ascii") + b"\0" if utf8
                   else struct.pack("<H", len(after)) + after.encode("utf-16le") + b"\0\0")
    delta = len(replacement) - (stop - start)
    prefix = bytearray(pool[:strings_start])
    for i, offset in enumerate(offsets):
        if offset > offsets[index]:
            struct.pack_into("<I", prefix, header + i * 4, offset + delta)
    raw = prefix + pool[strings_start:start] + replacement + pool[stop:end]
    raw += b"\0" * (-len(raw) % 4)
    struct.pack_into("<I", raw, 4, len(raw))
    return bytes(raw), {index}


def manifest_version(binary, before="0.7.10", after="0.7.11", before_code=28, after_code=29):
    code, header, size = struct.unpack_from("<HHI", binary)
    if code != 3 or size != len(binary):
        raise ValueError("Invalid binary Android manifest")
    result = bytearray(binary[:header])
    resource = []
    pool_indices = set()
    changed = changed_name = 0
    for kind, head, chunk in chunks(binary, header):
        if kind == 1:
            chunk, pool_indices = patched_string_pool(chunk, head, before, after)
        elif kind == 0x0180:
            resource = [item[0] for item in struct.iter_unpack("<I", chunk[head:])]
        elif kind == 0x0102:
            chunk = bytearray(chunk)
            offset, width, count = struct.unpack_from("<HHH", chunk, 24)
            for index in range(count):
                attribute = 16 + offset + index * width
                _, name, raw = struct.unpack_from("<III", chunk, attribute)
                kind_value = chunk[attribute + 15]
                value = struct.unpack_from("<I", chunk, attribute + 16)[0]
                res = resource[name] if name < len(resource) else 0
                if res == 0x0101021B:
                    if kind_value != 0x10 or value != before_code:
                        raise ValueError("Unexpected base versionCode")
                    struct.pack_into("<I", chunk, attribute + 16, after_code)
                    changed += 1
                elif res == 0x0101021C and kind_value == 3 and value in pool_indices and raw in pool_indices | {0xFFFFFFFF}:
                    changed_name += 1
        result.extend(chunk)
    if changed != 1 or changed_name != 1:
        raise ValueError("Could not verify and update the version in the Android manifest")
    struct.pack_into("<I", result, 4, len(result))
    return bytes(result)


