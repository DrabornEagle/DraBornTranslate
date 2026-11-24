"""Verify the ELF LOAD segments of the Android ARM64 package."""

import struct
import sys
from zipfile import ZipFile

PAGE = 16 * 1024
checked = 0
with ZipFile(sys.argv[1]) as apk:
    for entry in apk.namelist():
        if not entry.startswith("lib/arm64-v8a/") or not entry.endswith(".so"):
            continue
        binary = apk.read(entry)
        if binary[:6] != b"\x7fELF\x02\x01":
            raise ValueError(f"Unexpected ELF format: {entry}")
        table = struct.unpack_from("<Q", binary, 32)[0]
        stride, count = struct.unpack_from("<HH", binary, 54)
        segments = [table + stride * index for index in range(count)]
        loads = [offset for offset in segments if struct.unpack_from("<I", binary, offset)[0] == 1]
        if not loads or any(struct.unpack_from("<Q", binary, offset + 48)[0] < PAGE for offset in loads):
            raise ValueError(f"16 KB load alignment missing: {entry}")
        checked += 1
        print(f"16 KB: {entry}")
if checked == 0:
    raise ValueError("The APK contains no ARM64 libraries")
