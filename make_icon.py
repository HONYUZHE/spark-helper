#!/usr/bin/env python3
"""生成火花助手图标：橙红渐变圆角方块 + 白色火苗。"""
import os
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "proj", "res")
SIZES = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
         "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}


def flame_mask(size):
    """返回一张 L 模式的火苗蒙版。"""
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    s = size / 192.0
    cx = 96 * s
    base_y = 134 * s
    r = 38 * s
    # 底部圆
    d.ellipse([cx - r, base_y - r, cx + r, base_y + r], fill=255)
    # 上方的火苗三角
    d.polygon([(cx, 38 * s), (cx - r * 1.12, base_y - r * 0.2),
               (cx + r * 1.12, base_y - r * 0.2)], fill=255)
    # 火苗腰部鼓一点，看起来不那么三角
    d.ellipse([cx - r * 0.72, base_y - r * 1.35, cx + r * 0.72, base_y + r * 0.1], fill=255)
    return m


def make(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # 渐变背景
    grad = Image.new("RGBA", (size, size))
    gp = grad.load()
    for y in range(size):
        t = y / float(size - 1)
        r = int(0xFF * (1 - t) + 0xE0 * t)
        g = int(0x6A * (1 - t) + 0x1F * t)
        b = int(0x00 * (1 - t) + 0x3D * t)
        for x in range(size):
            gp[x, y] = (r, g, b, 255)
    # 圆角蒙版
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1],
                                           radius=int(size * 0.22), fill=255)
    img.paste(grad, (0, 0), mask)
    # 白色火苗
    white = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    img.paste(white, (0, 0), flame_mask(size))
    return img


def main():
    for folder, size in SIZES.items():
        d = os.path.join(OUT, folder)
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, "ic_launcher.png")
        make(size).save(p, "PNG")
        print("已生成", p, size)


if __name__ == "__main__":
    main()
