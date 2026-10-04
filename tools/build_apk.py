import struct, zipfile, io, hashlib, datetime, sys, os

# 路径都相对于仓库根目录；签名密钥放在仓库外（默认 ~/.yingdu/），不要提交到 git
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEYDIR = os.environ.get('YINGDU_KEYDIR', os.path.expanduser('~/.yingdu'))
os.makedirs(KEYDIR, exist_ok=True)
import pyaxml
from lxml import etree
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa, padding

MANIFEST_SRC = os.path.join(ROOT, 'app/src/main/AndroidManifest.xml')
DEX = os.environ.get('YINGDU_DEX', '/tmp/dexout/classes.dex')   # 由 kotlinc + dx 生成，见 README
OUT = sys.argv[1]
os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)

# ---------- 1. 二进制清单 ----------
# 版本号统一从 app/build.gradle.kts 读取，和 Android Studio 构建保持一致
import re
_gradle = open(os.path.join(ROOT, 'app/build.gradle.kts'), encoding='utf-8').read()
VERSION_CODE = re.search(r'versionCode\s*=\s*(\d+)', _gradle).group(1)
VERSION_NAME = re.search(r'versionName\s*=\s*"([^"]+)"', _gradle).group(1)
xml = open(MANIFEST_SRC, 'rb').read()
xml = xml.replace(b'<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.yingdu" '
    + f'android:versionCode="{VERSION_CODE}" android:versionName="{VERSION_NAME}">'.encode()
    + b'<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>')
xml = xml.replace(b'android:launchMode="singleTask"', b'android:launchMode="2"')
# foregroundServiceType 是标志位：location = 0x08，connectedDevice = 0x10，microphone = 0x80
xml = xml.replace(b'android:foregroundServiceType="connectedDevice|location"', b'android:foregroundServiceType="24"')
xml = xml.replace(b'android:foregroundServiceType="connectedDevice|microphone|location"', b'android:foregroundServiceType="152"')
xml = xml.replace(b'android:foregroundServiceType="connectedDevice|microphone"', b'android:foregroundServiceType="144"')
xml = xml.replace(b'android:foregroundServiceType="connectedDevice"', b'android:foregroundServiceType="16"')
assert b'foregroundServiceType="c' not in xml, "foregroundServiceType 有没换成数字的写法"

# 属性必须按资源 ID 升序排列（aapt 也是这样做的）。系统读取 application 等标签时
# 用的是"只向前查找"的方式，顺序乱了会漏读 label 和 icon —— 上一版图标和名字不生效就是这个原因。
ANDROID_NS = '{http://schemas.android.com/apk/res/android}'
ATTR_ID = {'theme': 0x01010000, 'label': 0x01010001, 'icon': 0x01010002, 'name': 0x01010003,
           'permission': 0x01010006, 'targetActivity': 0x01010202,
           'exported': 0x01010010, 'launchMode': 0x0101001d, 'minSdkVersion': 0x0101020c,
           'versionCode': 0x0101021b, 'versionName': 0x0101021c, 'targetSdkVersion': 0x01010270,
           'maxSdkVersion': 0x01010271, 'allowBackup': 0x0101027f, 'usesCleartextTraffic': 0x010104ec,
           'foregroundServiceType': 0x01010599}
root = etree.fromstring(xml)
for el in root.iter():
    if not isinstance(el.tag, str):
        continue
    items = list(el.attrib.items())
    def order(kv):
        k = kv[0]
        if not k.startswith(ANDROID_NS):
            return (0, 0)
        local = k[len(ANDROID_NS):]
        assert local in ATTR_ID, 'unknown attr ' + local
        return (1, ATTR_ID[local])
    items.sort(key=order)
    el.attrib.clear()
    for k, v in items:
        el.set(k, v)
a = pyaxml.AXML(); a.from_xml(root); a.compute()
axml = bytearray(a.pack())

# 把 icon / theme 从字符串改成框架资源引用（pyaxml 不支持 @android: 引用）
REFS = {0x01010002: 0x7F010000,   # android:icon  -> @drawable/ic_launcher（本应用自己的图标）
        0x01010000: 0x01030241}   # android:theme -> @android:style/Theme.Material.Light.NoActionBar
def u16(b, o): return struct.unpack_from('<H', b, o)[0]
def u32(b, o): return struct.unpack_from('<I', b, o)[0]
off = 8
resmap = []
patched = 0
while off < len(axml):
    ctype, hsize, csize = u16(axml, off), u16(axml, off + 2), u32(axml, off + 4)
    if ctype == 0x0180:
        resmap = [u32(axml, off + 8 + 4 * i) for i in range((csize - 8) // 4)]
    elif ctype == 0x0102:
        attr_start, attr_size, attr_count = u16(axml, off + 24), u16(axml, off + 26), u16(axml, off + 28)
        base = off + 16 + attr_start
        for i in range(attr_count):
            ao = base + i * attr_size
            name_idx = u32(axml, ao + 4)
            rid = resmap[name_idx] if name_idx < len(resmap) else None
            if rid in REFS:
                struct.pack_into('<I', axml, ao + 8, 0xFFFFFFFF)       # rawValue
                struct.pack_into('<HBB', axml, ao + 12, 8, 0, 0x01)    # TYPE_REFERENCE
                struct.pack_into('<I', axml, ao + 16, REFS[rid])
                patched += 1
    off += csize
assert patched == 2, patched

# 核对：每个标签里带资源 ID 的属性都是升序
off = 8
while off < len(axml):
    ctype, csize = u16(axml, off), u32(axml, off + 4)
    if ctype == 0x0102:
        attr_start, attr_size, attr_count = u16(axml, off + 24), u16(axml, off + 26), u16(axml, off + 28)
        ids = [resmap[u32(axml, off + 16 + attr_start + i * attr_size + 4)] if u32(axml, off + 16 + attr_start + i * attr_size + 4) < len(resmap) else 0
               for i in range(attr_count)]
        ids = [x for x in ids if x]
        assert ids == sorted(ids), [hex(x) for x in ids]
    off += csize

# ---------- 2. 未签名的 zip ----------
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import arsc
ICON_PATH = 'res/drawable-xxxhdpi-v4/ic_launcher.png'
arsc_bytes = arsc.build('io.github.yingdu', ICON_PATH)

def aligned_info(name, offset):
    """未压缩条目的数据要 4 字节对齐（targetSdk 30+ 对 resources.arsc 的硬性要求）。"""
    zi = zipfile.ZipInfo(name, (2026, 9, 26, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED
    base = offset + 30 + len(name.encode())
    pad = 6
    while (base + pad) % 4: pad += 1
    zi.extra = struct.pack('<HHH', 0xD935, pad - 4, 4) + b'\x00' * (pad - 6)
    return zi

buf = io.BytesIO()
with zipfile.ZipFile(buf, 'w') as z:
    z.writestr(zipfile.ZipInfo('AndroidManifest.xml', (2026, 9, 26, 0, 0, 0)), bytes(axml), zipfile.ZIP_DEFLATED)
    z.writestr(aligned_info('resources.arsc', buf.tell()), arsc_bytes)
    z.writestr(aligned_info(ICON_PATH, buf.tell()), open(os.path.join(ROOT, 'app/src/main/res/drawable-xxxhdpi/ic_launcher.png'), 'rb').read())
    z.writestr(zipfile.ZipInfo('classes.dex', (2026, 9, 26, 0, 0, 0)), open(DEX, 'rb').read(), zipfile.ZIP_DEFLATED)
    adir = os.path.join(ROOT, 'app/src/main/assets')
    for root_, _, files in os.walk(adir):
        for fn in sorted(files):
            full = os.path.join(root_, fn)
            rel = 'assets/' + os.path.relpath(full, adir).replace(os.sep, '/')
            z.writestr(zipfile.ZipInfo(rel, (2026, 9, 26, 0, 0, 0)), open(full, 'rb').read(), zipfile.ZIP_DEFLATED)
apk = buf.getvalue()

eocd = apk.rfind(b'PK\x05\x06')
cd_off = u32(apk, eocd + 16)
sec1, sec3, sec4 = apk[:cd_off], apk[cd_off:eocd], apk[eocd:]

# ---------- 3. APK 签名方案 v2 ----------
def lp(b): return struct.pack('<I', len(b)) + b
def chunked_digest(sections):
    chunks = []
    for s in sections:
        for i in range(0, len(s), 1 << 20):
            c = s[i:i + (1 << 20)]
            chunks.append(hashlib.sha256(b'\xa5' + struct.pack('<I', len(c)) + c).digest())
    return hashlib.sha256(b'\x5a' + struct.pack('<I', len(chunks)) + b''.join(chunks)).digest()

import os
KEY, CERT = os.path.join(KEYDIR, 'key.pem'), os.path.join(KEYDIR, 'cert.pem')
if os.path.exists(KEY):  # 复用同一个签名，之后的版本可以直接覆盖安装
    key = serialization.load_pem_private_key(open(KEY, 'rb').read(), None)
    cert = x509.load_pem_x509_certificate(open(CERT, 'rb').read())
else:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Nimo Reader')])
    now = datetime.datetime(2026, 9, 1)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now)
            .not_valid_after(now + datetime.timedelta(days=365 * 30)).sign(key, hashes.SHA256()))
    open(KEY, 'wb').write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    open(CERT, 'wb').write(cert.public_bytes(serialization.Encoding.PEM))
cert_der = cert.public_bytes(serialization.Encoding.DER)
pub_der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)

ALG = 0x0103  # RSASSA-PKCS1-v1_5 + SHA2-256
digest = chunked_digest([sec1, sec3, sec4])
signed_data = (lp(lp(struct.pack('<I', ALG) + lp(digest)))
               + lp(lp(cert_der))
               + lp(b''))
sig = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
signer = lp(signed_data) + lp(lp(struct.pack('<I', ALG) + lp(sig))) + lp(pub_der)
v2_value = lp(lp(signer))

pair = struct.pack('<Q', 4 + len(v2_value)) + struct.pack('<I', 0x7109871A) + v2_value
block_size = len(pair) + 8 + 16
block = struct.pack('<Q', block_size) + pair + struct.pack('<Q', block_size) + b'APK Sig Block 42'

new_eocd = bytearray(sec4)
struct.pack_into('<I', new_eocd, 16, cd_off + len(block))
open(OUT, 'wb').write(sec1 + block + sec3 + bytes(new_eocd))
print('apk', OUT, len(sec1) + len(block) + len(sec3) + len(new_eocd), 'bytes; cert sha256',
      hashlib.sha256(cert_der).hexdigest()[:16])
