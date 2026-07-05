#!/usr/bin/env python3
"""
TFC 方块覆雪贴图生成器。仅处理贴图。
模型JSON和雪定义由 Java data gen (runData) 负责。

用法:
  pip install Pillow
  cd src/png_snowy
  python gen_snowy.py          # 生成覆雪贴图 (composited)
  python gen_snowy.py --mask   # 同时生成雪遮罩 (snow-only, 透明背景)
"""

import os, sys
from PIL import Image

SCRIPT_DIR    = os.path.dirname(os.path.abspath(__file__))
ROOT_RESOURCES = os.path.join(SCRIPT_DIR, "..", "main", "resources")
SNOWY_PNG     = os.path.join(SCRIPT_DIR, "snowy.png")
SNOWY_TOP_PNG = os.path.join(SCRIPT_DIR, "snowy_top.png")
TEX_OUT       = os.path.join(ROOT_RESOURCES, "assets", "estfc", "textures", "block")
MASK_OUT      = os.path.join(ROOT_RESOURCES, "assets", "estfc", "textures", "block", "snow")
IGNORE_LIST   = {"snowy.png", "snowy_top.png", "gen_snowy.py"}

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

# ─── 核心：贴图覆雪处理 ──────────────────────────────

def process_one(name, img, snowy, snowy_top, mask_mode=False):
    """
    mask_mode=False: 在原始贴图上合成白雪 → 输出 composited 贴图 (用于无 tint 模型)
    mask_mode=True:  仅输出雪像素 (透明背景) → 输出雪遮罩 (用于 mixed_cross 的非着色层)
    """
    img = img.convert("RGBA")
    w, h = img.size
    px = img.load(); sw, sh = snowy.size

    if snowy_top and count_edge_solid(px, w, h) > 40:
        if mask_mode:
            # 遮罩模式: 直接使用 snowy_top 作为遮罩
            result = snowy_top.resize((w,h), Image.LANCZOS).convert("RGBA")
            print(f"  [{name}] 外圈>40 -> snowy_top 遮罩")
        else:
            result = img.copy()
            r = Image.alpha_composite(result, snowy_top.resize((w,h), Image.LANCZOS).convert("RGBA"))
            print(f"  [{name}] 外圈>40 -> snowy_top 覆盖")
            return r
        return result

    sp = find_split(px, w, h)
    has = sp < h
    tu = find_top(px, w, h, 0)
    tl = find_top(px, w, h, sp+1) if has else h

    if mask_mode:
        # 遮罩模式: 从透明背景开始，只画雪像素
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

            if du == 0:
                trans = 0.0
            elif du == 1:
                trans = 0.25 * t
            elif du == 2:
                trans = 0.45 + 0.25 * t
            else:
                trans = 0.80 + 0.20 * t

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
                    rp[x, y] = spx  # 仅雪像素，不混合原始纹理
                else:
                    rp[x, y] = blend(rp[x, y], spx)

    suffix = " (遮罩)" if mask_mode else ""
    print(f"  [{name}] OK{suffix}" + (f", split={sp}" if has else ""))
    return r

# ─── 入口 ────────────────────────────────────────────

def collect_inputs(base_dir, ignore):
    pngs = []
    for root, dirs, files in os.walk(base_dir):
        for f in files:
            if f.lower().endswith(".png") and f.lower() not in ignore:
                pngs.append(os.path.relpath(os.path.join(root, f), base_dir))
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
        if not os.listdir(root) and root != output_dir:
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

    pngs = collect_inputs(SCRIPT_DIR, IGNORE_LIST)
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
            # 1) 生成覆雪合成贴图 (composited)
            out = process_one(rel, Image.open(src), snowy, snowy_top, mask_mode=False)
        except Exception as e:
            print(f"  [{rel}] 错误: {e}")
            skipped += 1
            continue
        dest = os.path.join(TEX_OUT, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        out.save(dest)
        ok += 1

        # 2) 可选: 生成雪遮罩 (snow-only, 透明背景)
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