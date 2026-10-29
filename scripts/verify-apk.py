#!/usr/bin/env python3
"""Static APK and ELF packaging checks. Does not claim device installation."""
import hashlib
import json
import pathlib
import re
import struct
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
version = re.search(r"versionName '([^']+)'", (root / 'android/app/build.gradle').read_text()).group(1)
apk = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / f'releases/inkstone-{version}-debug.apk'
required = ['create', 'destroy', 'command', 'stroke', 'render', 'renderPatch', 'preview', 'save', 'load', 'importPixels', 'thumbnails', 'writePsd', 'transformSnapshot', 'importPsd']
result = {'apk': apk.name, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'bytes': apk.stat().st_size, 'abis': {}}
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None, 'ZIP CRC check failed'
    assert archive.read('AndroidManifest.xml')
    assert archive.read('classes.dex').startswith(b'dex\n')
    for abi, machine in [('arm64-v8a', 183), ('x86_64', 62)]:
        data = archive.read(f'lib/{abi}/libinkstone.so')
        assert data[:6] == b'\x7fELF\x02\x01', 'Expected little-endian ELF64'
        assert struct.unpack_from('<H', data, 18)[0] == machine
        phoff = struct.unpack_from('<Q', data, 32)[0]
        phsize, phnum = struct.unpack_from('<HH', data, 54)
        loads = []
        for i in range(phnum):
            kind, flags, offset, vaddr, paddr, filesz, memsz, align = struct.unpack_from('<IIQQQQQQ', data, phoff + i * phsize)
            if kind == 1:
                assert align >= 16384 and (vaddr - offset) % 16384 == 0, 'Missing 16 KB page alignment'
                loads.append({'offset': offset, 'alignment': align})
        for method in required:
            assert f'Java_art_inkstone_NativeBridge_{method}\0'.encode() in data, f'Missing JNI method {method}'
        result['abis'][abi] = {'bytes': len(data), 'jni_methods': len(required), 'load_segments': loads}
result['static_checks_passed'] = True
target = root / 'artifacts/apk-verification.json'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
