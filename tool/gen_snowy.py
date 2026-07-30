#!/usr/bin/env python3
"""
TFC 方块覆雪贴图生成器。仅处理贴图。
模型JSON和雪定义由 gradle task 负责。

用法:
  pip install Pillow
  cd src/png_snowy
  python gen_snowy.py          # 生成覆雪贴图 (灰度自动着色 + 覆雪)
  python gen_snowy.py --mask   # 同时生成雪遮罩 (snow-only, 透明背景)
"""

import os, sys
from PIL import Image

SCRIPT_DIR    = os.path.dirname(os.path.abspath(__file__))
ADD_DIR       = os.path.join(SCRIPT_DIR, "add")
TEX_OUT       = os.path.join(ADD_DIR, "assets", "estfc", "textures", "block")
MASK_OUT      = os.path.join(ADD_DIR, "assets", "estfc", "textures", "block", "snow")
SNOWY_PNG     = os.path.join(SCRIPT_DIR, "snowy.png")
SNOWY_TOP_PNG = os.path.join(SCRIPT_DIR, "snowy_top.png")
IGNORE_LIST   = {"snowy.png", "snowy_top.png", "gen_snowy.py"}
IGNORE_DIRS   = {"add"}  # 要忽略的目录名

# 默认草色 (Minecraft plains biome 近似)
GRASS_COLOR   = (124, 189, 107)  # #7CBD6B

# ─── 工具 ────────────────────────────────────────────

def is_solid(px):   return px[3] > 10

def solid_at(px, x, y, w, h):
    return 0 <= x < w and 0 <= y < h and is_solid(px[x, y])

def count_edge_solid(px, w, h):
    n = 0
    for x in range(w):
        if solid_at(px, x, 0, w, h):     n += 1
        if solid_at(px, x, h-1, w, h):   n += 1
    for y in range(1, h-1):
        if solid_at(px, 0, y, w, h):     n += 1
        if solid_at(px, w-1, y, w, h):   n += 1
    return n

def dist_above(px, x, y, w, h, min_y):
    d = 0
    for cy in range(y - 1, min_y - 1, -1):
        if not solid_at(px, x, cy, w, h): return d
        d += 1
    return d

def dist_left(px, x, y, w, h):
    for d, cx in enumerate(range(x - 1, -1, -1)):
        if not solid_at(px, cx, y, w, h): return d
    return x

def dist_right(px, x, y, w, h):
    for d, cx in enumerate(range(x + 1, w)):
        if not solid_at(px, cx, y, w, h): return d
    return w - x - 1

def count_above(px, x, y, w, h):
    return sum(1 for cy in range(y-1, -1, -1) if solid_at(px, x, cy, w, h))

def find_top(px, w, h, start_y):
    for y in range(start_y, h):
        for x in range(w):
            if solid_at(px, x, y, w, h): return y
    return h

def find_split(px, w, h):
    for y in range(4, h-4):
        if not any(solid_at(px, x, y, w, h) for x in range(w)): return y
    return h

def blend(bg, fg):
    ba, fa = bg[3]/255.0, fg[3]/255.0
    oa = fa + ba*(1-fa)
    if oa < .001: return (0,0,0,0)
    return (int((fg[0]*fa+bg[0]*ba*(1-fa))/oa),
            int((fg[1]*fa+bg[1]*ba*(1-fa))/oa),
            int((fg[2]*fa+bg[2]*ba*(1-fa))/oa),
            int(oa*255))

# ─── 灰度检测与着色 ──────────────────────────────────

def is_grayscale_texture(img):
    """检测贴图是否主要为灰度 (用于 tintindex 着色)"""
    px = img.load(); w, h = img.size
    gray_px = 0; total_px = 0
    for y in range(h):
        for x in range(w):
            if px[x, y][3] < 10: continue
            total_px += 1
            r, g, b = px[x, y][0], px[x, y][1], px[x, y][2]
            if abs(r-g) < 8 and abs(g-b) < 8 and abs(r-b) < 8:
                gray_px += 1
    if total_px < 50: return False
    return gray_px > total_px * 0.75

def colorize_grayscale(img, tint_color=GRASS_COLOR):
    """将灰度贴图的非透明像素着上 biome 颜色 (保留明暗层次)"""
    img = img.convert("RGBA")
    px = img.load(); w, h = img.size
    tr, tg, tb = tint_color
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a < 10: continue
            gray = int(0.299*r + 0.587*g + 0.114*b)
            factor = gray / 255.0
            nr = int(r * (1 - factor * 0.7) + tr * factor * 0.7)
            ng = int(g * (1 - factor * 0.7) + tg * factor * 0.7)
            nb = int(b * (1 - factor * 0.7) + tb * factor * 0.7)
            px[x, y] = (max(0, min(255, nr)),
                        max(0, min(255, ng)),
                        max(0, min(255, nb)), a)
    return img

# ─── 核心：贴图覆雪处理 ──────────────────────────────

def process_one(name, img, snowy, snowy_top, mask_mode=False):
    img = img.convert("RGBA")
    w, h = img.size
    px = img.load()
    sw, sh = snowy.size

    # 灰度贴图着色
    gray = is_grayscale_texture(img)
    if gray and not mask_mode:
        img = colorize_grayscale(img)
        px = img.load()

    # ─── 整张雪顶覆盖（仅当边缘固态像素较多时）───
    if snowy_top and count_edge_solid(px, w, h) > 32:
        snow = snowy_top.resize((w, h), Image.LANCZOS).convert("RGBA")
        if mask_mode:
            # 遮罩模式：只保留原图非透明区域的雪
            mask = img.split()[3]
            background = Image.new('RGBA', (w, h), (0, 0, 0, 0))
            result = Image.composite(snow, background, mask)
            print(f"  [{name}] OK (遮罩, 整张覆盖)")
            return result
        else:
            # 合成模式：雪叠在原图上，但透明区域恢复透明
            base = img.copy()
            blended = Image.alpha_composite(base, snow)
            final = Image.composite(blended, base, base.split()[3])
            print(f"  [{name}] OK (整张覆盖)")
            return final

    # ─── 原有逐像素覆雪（条件不成立时执行）───
    sp = find_split(px, w, h)
    has = sp < h
    tu = find_top(px, w, h, 0)
    tl = find_top(px, w, h, sp+1) if has else h

    if mask_mode:
        r = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    else:
        r = img.copy()
    rp = r.load()

    for y in range(h):
        et, rs = (tu, 0) if not has or y < sp else (tl, sp)
        if et >= h: continue
        for x in range(w):
            if not solid_at(px, x, y, w, h): continue

            du = dist_above(px, x, y, w, h, rs)
            dl = dist_left(px, x, y, w, h)
            dr = dist_right(px, x, y, w, h)
            an = count_above(px, x, y, w, h)

            dh = min(dl, dr)
            t = min(dh, 3) / 3.0

            if du == 0:     trans = 0.0
            elif du == 1:   trans = 0.25 * t
            elif du == 2:   trans = 0.45 + 0.25 * t
            else:           trans = 0.80 + 0.20 * t

            a = 1.0 - trans
            a += 0.20 * (1.0 - t)
            a -= an * 0.02
            a = max(0.0, min(1.0, a))
            if a < 0.01: continue

            sy = max(0, min(sh-1, y - et))
            sx = max(0, min(sw-1, int(x * sw / w)))
            spx = snowy.getpixel((sx, sy))
            spx = (*spx, 255) if len(spx) == 3 else spx
            spx = (spx[0], spx[1], spx[2], int(spx[3] * a))
            if spx[3]:
                if mask_mode:
                    rp[x, y] = spx
                else:
                    rp[x, y] = blend(rp[x, y], spx)

    suffix = " (遮罩)" if mask_mode else ""
    gray_note = " 灰度→着色" if gray and not mask_mode else ""
    print(f"  [{name}] OK{suffix}{gray_note}" + (f", split={sp}" if has else ""))
    return r

# ─── 入口 ────────────────────────────────────────────

def collect_inputs(base_dir, ignore_files, ignore_dirs):
    """收集所有子目录中的 PNG 文件，排除忽略的目录和文件"""
    pngs = []
    for root, dirs, files in os.walk(base_dir):
        # 过滤掉要忽略的目录
        dirs[:] = [d for d in dirs if d not in ignore_dirs]
        
        for f in files:
            if f.lower().endswith(".png") and f.lower() not in ignore_files:
                rel_path = os.path.relpath(os.path.join(root, f), base_dir)
                pngs.append(rel_path)
    return pngs

def clean_output(output_dir, keep_rel_paths):
    if not os.path.exists(output_dir):
        return
    keep = set(keep_rel_paths)
    removed = 0
    for root, dirs, files in os.walk(output_dir, topdown=False):
        for f in files:
            if f.lower().endswith(".png"):
                rel = os.path.relpath(os.path.join(root, f), output_dir)
                if rel not in keep:
                    os.remove(os.path.join(root, f))
                    removed += 1
                    print(f"  清理旧贴图: {rel}")
        # 不删除输出根目录本身
        if root != output_dir and not os.listdir(root):
            try: os.rmdir(root)
            except OSError: pass
    if removed:
        print(f"  共清理 {removed} 个旧贴图\n")

def main():
    if not os.path.exists(SNOWY_PNG):
        print(f"缺少 {SNOWY_PNG}"); sys.exit(1)

    gen_mask = "--mask" in sys.argv

    snowy = Image.open(SNOWY_PNG).convert("RGBA")
    snowy_top = Image.open(SNOWY_TOP_PNG).convert("RGBA") if os.path.exists(SNOWY_TOP_PNG) else None

    pngs = collect_inputs(SCRIPT_DIR, IGNORE_LIST, IGNORE_DIRS)
    if not pngs:
        print("无待处理 PNG"); sys.exit(1)

    print(f"输入贴图: {len(pngs)} 个")
    print(f"输出目录: {os.path.abspath(TEX_OUT)}")
    if gen_mask:
        print(f"遮罩目录: {os.path.abspath(MASK_OUT)}")
    print()

    clean_output(TEX_OUT, pngs)
    if gen_mask:
        clean_output(MASK_OUT, pngs)

    ok, skipped, mask_ok = 0, 0, 0
    for rel in sorted(pngs):
        src = os.path.join(SCRIPT_DIR, rel)
        try:
            out = process_one(rel, Image.open(src), snowy, snowy_top, mask_mode=False)
        except Exception as e:
            print(f"  [{rel}] 错误: {e}")
            skipped += 1
            continue
        dest = os.path.join(TEX_OUT, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        out.save(dest)
        ok += 1

        if gen_mask:
            try:
                mask = process_one(rel, Image.open(src), snowy, snowy_top, mask_mode=True)
            except Exception as e:
                print(f"  [{rel}] 遮罩错误: {e}")
                continue
            mask_dest = os.path.join(MASK_OUT, rel)
            os.makedirs(os.path.dirname(mask_dest), exist_ok=True)
            mask.save(mask_dest)
            mask_ok += 1

    print(f"\n贴图: {ok} 成功, {skipped} 跳过")
    if gen_mask:
        print(f"遮罩: {mask_ok} 成功")
    print(f"\n完成。贴图: {os.path.abspath(TEX_OUT)}")
    if gen_mask:
        print(f"遮罩: {os.path.abspath(MASK_OUT)}")

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"错误: {e}")
        import traceback; traceback.print_exc()

    input("\n按 Enter 退出...")