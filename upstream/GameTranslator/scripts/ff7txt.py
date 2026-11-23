"""FF7 Remake *_TxtRes.uexp okuyucu: [(kimlik, metin, konuşmacı)] (dosyadaki sırayla).
Biçim: FString kimlik ("$..."), FString metin, int32 n, n × (int32 tür, int32 ?, FString değer) — konuşmacı adı bunlardadır."""
import struct, sys, os, glob


def fstring(b, o):
    n = struct.unpack_from('<i', b, o)[0]
    o += 4
    if n == 0:
        return '', o
    if n < 0:
        s = b[o:o - n * 2].decode('utf-16-le').rstrip('\0')
        return s, o - n * 2
    return b[o:o + n].decode('utf-8', 'replace').rstrip('\0'), o + n


def read(path):
    b = open(path, 'rb').read()
    out = []
    o = b.find(b'$') - 4
    while 0 <= o < len(b) - 8:
        try:
            n = struct.unpack_from('<i', b, o)[0]
            if not (2 <= n <= 200) or b[o + 4:o + 5] != b'$':
                break
            key, o = fstring(b, o)
            text, o = fstring(b, o)
            count = struct.unpack_from('<i', b, o)[0]
            o += 4
            extra = []
            for _ in range(count):
                o += 8
                value, o = fstring(b, o)
                extra.append(value)
            out.append((key, text, extra[0] if extra else ''))
        except (struct.error, UnicodeDecodeError):
            break
    return out, o, len(b)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    total = 0
    for path in sorted(glob.glob(os.path.join(sys.argv[1], '*_TxtRes.uexp'))):
        entries, end, size = read(path)
        total += len(entries)
        print(os.path.basename(path), len(entries), 'son', end, '/', size)
        for e in entries[:3]:
            print('   ', e[0], '|', e[2], '|', repr(e[1][:70]))
    print('toplam', total)
