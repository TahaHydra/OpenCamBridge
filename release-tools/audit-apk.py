"""Check 64-bit native library ELF/ZIP alignment for 16 KiB-page devices."""
import struct
import sys
import zipfile
from pathlib import Path
path = Path(sys.argv[1])
failures = []
checked = 0
with zipfile.ZipFile(path) as apk, path.open("rb") as raw:
    for item in apk.infolist():
        if not item.filename.endswith(".so") or not item.filename.startswith(("lib/arm64-v8a/", "lib/x86_64/")):
            continue
        data = apk.read(item)
        if data[:5] != b"\x7fELF\x02" or data[5] != 1:
            failures.append(item.filename + ": unsupported ELF header")
            continue
        phoff = struct.unpack_from("<Q", data, 32)[0]
        phsize, count = struct.unpack_from("<HH", data, 54)
        for index in range(count):
            fields = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phsize)
            if fields[0] == 1 and fields[7] < 16384:
                failures.append(item.filename + ": ELF LOAD alignment below 16 KiB")
        if item.compress_type == zipfile.ZIP_STORED:
            raw.seek(item.header_offset + 26)
            name_size, extra_size = struct.unpack("<HH", raw.read(4))
            offset = item.header_offset + 30 + name_size + extra_size
            if offset % 16384:
                failures.append(item.filename + ": APK storage is not 16 KiB aligned")
        checked += 1
if failures:
    raise SystemExit("\n".join(failures))
print(f"APK_NATIVE_16K_CHECK=PASSED libraries={checked}")
