#!/usr/bin/env python3
import os
from PIL import Image

def main():
    brand_dir = "/Users/Python/小蝠/品牌"
    drawable_dir = "/Users/Python/小蝠/app/src/main/res/drawable"
    res_dir = "/Users/Python/小蝠/app/src/main/res"

    mapping = {
        "logo.png": "img_xiaofu_logo.webp",
        "动作形象/01-打招呼.png": "img_xiaofu_greet.webp",
        "动作形象/02-认真倾听.png": "img_xiaofu_listen.webp",
        "动作形象/03-接收文件.png": "img_xiaofu_receive.webp",
        "动作形象/04-提取音频.png": "img_xiaofu_extract.webp",
        "动作形象/05-整理文字.png": "img_xiaofu_transcribe.webp",
        "动作形象/06-完成庆祝.png": "img_xiaofu_celebrate.webp",
        "动作形象/07-耐心等待.png": "img_xiaofu_wait.webp",
        "动作形象/08-疑惑.png": "img_xiaofu_confused.webp",
        "动作形象/09-安心休息.png": "img_xiaofu_rest.webp",
        "动作形象/10-挥翼告别.png": "img_xiaofu_farewell.webp",
    }

    total_bytes = 0
    for src_rel, dst_name in mapping.items():
        src_path = os.path.join(brand_dir, src_rel)
        dst_path = os.path.join(drawable_dir, dst_name)
        img = Image.open(src_path).convert("RGBA")
        img_resized = img.resize((512, 512), Image.Resampling.LANCZOS)
        img_resized.save(dst_path, format="WEBP", quality=85, method=6)
        size = os.path.getsize(dst_path)
        total_bytes += size
        print(f"Exported {dst_name}: {size/1024:.1f} KB")

    print(f"Total illustrations size: {total_bytes/1024:.1f} KB")

    logo_img = Image.open(os.path.join(brand_dir, "logo.png")).convert("RGBA")
    mipmap_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    for folder, sz in mipmap_sizes.items():
        out_dir = os.path.join(res_dir, folder)
        os.makedirs(out_dir, exist_ok=True)
        icon_resized = logo_img.resize((sz, sz), Image.Resampling.LANCZOS)
        icon_resized.save(os.path.join(out_dir, "ic_launcher.png"), format="PNG")
        icon_resized.save(os.path.join(out_dir, "ic_launcher_round.png"), format="PNG")
        print(f"Exported {folder}/ic_launcher.png ({sz}x{sz})")

if __name__ == "__main__":
    main()
