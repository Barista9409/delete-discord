#!/usr/bin/env python3
"""バイナリ AndroidManifest.xml の versionCode / versionName を書き換える。
使い方: python3 tools/patch_manifest_version.py <AndroidManifest.xml> <versionCode> <versionName>
versionName は元の文字列と同じ長さにすること(例: 1.2 -> 1.3)。文字列プールを再構築しないための制約。
"""
import struct, sys
path, vcode, vname = sys.argv[1], int(sys.argv[2]), sys.argv[3]
d = bytearray(open(path, 'rb').read())
sp = 8
n, ns, flags, ss, _ = struct.unpack_from('<IIIII', d, sp + 8)
utf8 = bool(flags & 0x100)
offs = struct.unpack_from('<%dI' % n, d, sp + 28)
base = sp + ss
def s(i):
    p = base + offs[i]
    if utf8:
        l = d[p]; p += 1 if l < 128 else 2
        l2 = d[p]; p += 1 if l2 < 128 else 2
        return p, bytes(d[p:p + l2]).decode()
    l = struct.unpack_from('<H', d, p)[0]
    return p + 2, bytes(d[p + 2:p + 2 + 2 * l]).decode('utf-16le')
strs = [s(i) for i in range(n)]
p = sp + struct.unpack_from('<I', d, sp + 4)[0]
while p < len(d):
    t, h, sz = struct.unpack_from('<HHI', d, p)
    if t == 0x102:
        _, name, astart, asz, ac = struct.unpack_from('<IIHHH', d, p + 16)
        a0 = p + 16 + astart
        for k in range(ac):
            q = a0 + k * 20
            _, nm, raw, sz2, res, typ, data = struct.unpack_from('<IIIHBBI', d, q)
            if strs[nm][1] == 'versionCode':
                struct.pack_into('<I', d, q + 16, vcode)
            elif strs[nm][1] == 'versionName':
                pos, old = strs[raw]
                assert len(old) == len(vname), f'versionName は {len(old)} 文字で指定: {old}'
                d[pos:pos + len(old) * (1 if utf8 else 2)] = vname.encode('utf-8' if utf8 else 'utf-16le')
        break
    p += sz
open(path, 'wb').write(d)
print('patched', vcode, vname)
