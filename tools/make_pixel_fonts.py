"""
把像素字体转成萤读自己的点阵格式（.ydpf），放进 app/src/main/assets/fonts/。

眼镜画面直接按点阵逐像素绘制，不经过系统字体渲染，所以不会因为缩放、抗锯齿而发糊变粗。

  yingdu_pixel16.ydpf  16～23px：GNU Unifont 16 点阵（字形和眼镜自带界面一致）
  yingdu_pixel12.ydpf  12～15px、24px 以上：Fusion Pixel Font 12px 等宽简体（绘制时放大）

用法：
  pip install pillow fonttools brotli
  python3 tools/make_pixel_fonts.py <unifont.otf> <fusion-pixel-12px-monospaced-zh_hans 目录>

  unifont.otf：Debian/Ubuntu 的 fonts-unifont 包（/usr/share/fonts/opentype/unifont/unifont.otf），
               或 https://unifoundry.com/unifont/ 。
  Fusion Pixel：https://github.com/TakWolf/fusion-pixel-font 的 12px monospaced otf，
               或 npm 包 @vp-tw/cjk-web-fonts-fusion-pixel-font 里 dist/12px/monospaced/zh_hans 下的 woff2 子集。

.ydpf 格式（整数都是大端）：
  "YDPF"  版本 u8=1  字高 u8  基线 u8（从顶部算）  每行字节数 u8  字数 u32
  码位 u16 × 字数（升序）
  字宽 u8 × 字数
  点阵 × 字数：每字 字高 行，每行 每行字节数 个字节，最高位是最左边的像素
"""
import glob, os, struct, sys
from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, 'app/src/main/assets/fonts')

# 收录范围：拉丁、希腊、西里尔、常用标点和符号、制表符与几何图形、中日文标点和假名、中日韩统一汉字、全角字符
RANGES = [
    (0x0020, 0x007E), (0x00A0, 0x017F), (0x0370, 0x03FF), (0x0400, 0x045F),
    (0x2000, 0x206F), (0x2100, 0x21FF), (0x2200, 0x22FF), (0x2460, 0x24FF),
    (0x2500, 0x25FF), (0x2600, 0x26FF), (0x3000, 0x30FF), (0x3200, 0x32FF),
    (0x4E00, 0x9FFF), (0xFF00, 0xFFEF),
]


SS = 8   # 超采样倍数


def wanted(cp):
    return any(a <= cp <= b for a, b in RANGES)


def build(sources, size, ascent, out_name):
    """sources：[(字体文件, 该文件的码位集合)]，同一个码位取第一个有它的文件。"""
    glyphs = {}
    for path, cmap in sources:
        # 放大 SS 倍画，再取每个像素中心点：像素字体的轮廓都是整像素的方块，这样得到的点阵最准确，
        # 不受字体微调（hinting）影响
        font = ImageFont.truetype(path, size * SS)
        for cp in sorted(cmap):
            if cp in glyphs or not wanted(cp):
                continue
            ch = chr(cp)
            adv = int(round(font.getlength(ch) / SS))
            if adv <= 0 or adv > 16:
                continue
            img = Image.new('L', (adv * SS, size * SS), 0)
            ImageDraw.Draw(img).text((0, ascent * SS), ch, font=font, fill=255, anchor='ls')
            big = img.load()
            glyphs[cp] = (adv, [[big[x * SS + SS // 2, y * SS + SS // 2] > 127 for x in range(adv)] for y in range(size)])
    row_bytes = 2
    codes = sorted(glyphs)
    out = bytearray(b'YDPF') + struct.pack('>BBBBI', 1, size, ascent, row_bytes, len(codes))
    out += b''.join(struct.pack('>H', cp) for cp in codes)
    out += bytes(glyphs[cp][0] for cp in codes)
    for cp in codes:
        adv, rows = glyphs[cp]
        for y in range(size):
            bits = 0
            for x in range(adv):
                if rows[y][x]:
                    bits |= 0x8000 >> x
            out += struct.pack('>H', bits)
    path = os.path.join(OUT_DIR, out_name)
    open(path, 'wb').write(out)
    print(f'{out_name}: {len(codes)} 字, {len(out) // 1024} KB')


def main():
    unifont, fusion_dir = sys.argv[1], sys.argv[2]
    # Unifont：upem 64，每像素 4 个单位；基线在第 14 行
    build([(unifont, set(TTFont(unifont).getBestCmap()))], 16, 14, 'yingdu_pixel16.ydpf')
    # Fusion Pixel 12px：upem 1200，每像素 100 个单位；基线在第 10 行
    files = sorted(glob.glob(os.path.join(fusion_dir, '*.woff2')) + glob.glob(os.path.join(fusion_dir, '*.otf')))
    build([(f, set(TTFont(f).getBestCmap())) for f in files], 12, 10, 'yingdu_pixel12.ydpf')


if __name__ == '__main__':
    main()
