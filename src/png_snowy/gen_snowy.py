#!/usr/bin/env python3
"""
TFC 方块覆雪贴图 + ES 模型/定义生成器。

生成内容：
  1. 积雪贴图  → assets/estfc/textures/block/<relpath>.png
  2. 方块模型  → assets/estfc/models/block/snowy/tfc/<relpath>.json
  3. 模型定义  → assets/estfc/eclipticseasons/model_definitions/snowy/tfc_<plant>.json
  4. 雪定义    → data/estfc/eclipticseasons/snow_definitions/tfc_plant_<plant>.json

用法:
  pip install Pillow
  cd src/png_snowy
  python gen_snowy.py
"""

import os, sys, json, re
from collections import defaultdict
from PIL import Image

SCRIPT_DIR    = os.path.dirname(os.path.abspath(__file__))
ROOT_RESOURCES = os.path.join(SCRIPT_DIR, "..", "main", "resources")
SNOWY_PNG     = os.path.join(SCRIPT_DIR, "snowy.png")
SNOWY_TOP_PNG = os.path.join(SCRIPT_DIR, "snowy_top.png")
TEX_OUT       = os.path.join(ROOT_RESOURCES, "assets", "estfc", "textures", "block")
MODEL_OUT     = os.path.join(ROOT_RESOURCES, "assets", "estfc", "models", "block", "snowy", "tfc")
GENERATED_OUT = os.path.join(SCRIPT_DIR, "..", "generated", "resources")
# 模型定义和雪定义只输出到 generated/（避免与 processResources 重复）
DEF_OUT       = os.path.join(GENERATED_OUT, "assets", "estfc", "eclipticseasons", "model_definitions", "snowy")
SNOWDEF_OUT   = os.path.join(GENERATED_OUT, "data", "estfc", "eclipticseasons", "snow_definitions")
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

def count_solid(px, w, h):
    return sum(1 for y in range(h) for x in range(w) if is_solid(px[x, y]))

# ─── TFC 贴图→方块映射 ──────────────────────────────

def get_plant_info(rel_path):
    """
    从贴图相对路径推断 TFC 植物信息。
    返回 (tfc_block_id, plant_key) 或 None。

    plant/<name>/<variant>.png  → tfc:plant/<name>
    crop/<name>_<variant>.png   → tfc:crop/<name>
    azalea/<variant>.png        → tfc:plant/azalea
    """
    parts = rel_path.replace("\\", "/").split("/")

    if parts[0] == "plant" and len(parts) >= 2:
        name = parts[1]
        return (f"tfc:plant/{name}", name)

    if parts[0] == "crop" and len(parts) >= 2:
        fn = parts[1]
        # 去掉 _dead, _wild, _dead_young, _young, _0~_7 等后缀
        base = re.sub(r'_(dead_young|dead|wild|young|\d+)$', '', os.path.splitext(fn)[0])
        if not base:
            base = os.path.splitext(fn)[0]
        return (f"tfc:crop/{base}", base)

    if parts[0] == "azalea":
        return ("tfc:plant/azalea", "azalea")

    return None

# ─── 核心：贴图覆雪处理 ──────────────────────────────

def process_one(name, img, snowy, snowy_top):
    img = img.convert("RGBA")
    w, h = img.size
    px = img.load(); sw, sh = snowy.size

    # 外圈非透明像素 > 40 → 直接 snowy_top 覆盖
    if snowy_top and count_edge_solid(px, w, h) > 40:
        result = img.copy()
        r = Image.alpha_composite(result, snowy_top.resize((w,h), Image.LANCZOS).convert("RGBA"))
        print(f"  [{name}] 外圈>40 → snowy_top 覆盖")
        return r

    # 检测分上下段
    sp = find_split(px, w, h)
    has = sp < h
    tu = find_top(px, w, h, 0)
    tl = find_top(px, w, h, sp+1) if has else h

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

            # 水平边缘因子: 0=边缘, 1=内部(dh>=3)
            dh = min(dl, dr)
            t = min(dh, 3) / 3.0

            # du=0: 透明度   0% → alpha 1.00
            # du=1: 透明度 0~25% → alpha 1.00~0.75 (t 插值)
            # du=2: 透明度 45~70% → alpha 0.55~0.30 (t 插值)
            # du>=3:透明度 80~100% → alpha 0.20~0.00 (t 插值)
            if du == 0:
                trans = 0.0
            elif du == 1:
                trans = 0.25 * t
            elif du == 2:
                trans = 0.45 + 0.25 * t
            else:
                trans = 0.80 + 0.20 * t

            a = 1.0 - trans

            # 边缘加成: alpha + 0~0.20 (边缘最大)
            edge_bonus = 0.20 * (1.0 - t)
            a += edge_bonus

            # 上方每个非透明像素 -2% (alpha -0.02)
            a -= an * 0.02
            a = max(0.0, min(1.0, a))
            if a < 0.01: continue

            # snowy 对齐到段落顶部
            sy = max(0, min(sh-1, y - et))
            sx = max(0, min(sw-1, int(x * sw / w)))
            spx = snowy.getpixel((sx, sy))
            spx = (*spx, 255) if len(spx) == 3 else spx
            spx = (spx[0], spx[1], spx[2], int(spx[3] * a))
            if spx[3]:
                rp[x, y] = blend(rp[x, y], spx)

    print(f"  [{name}] OK" + (f", split={sp}" if has else ""))
    return r

# ─── JSON 生成 ────────────────────────────────────────

def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")

def generate_models_and_definitions(plant_groups, all_rel_paths):
    """
    对每个植物生成：
      1. 方块模型 JSON (models/block/snowy/tfc/)
      2. 模型定义 JSON (eclipticseasons/model_definitions/snowy/)
      3. 雪定义 JSON   (data/estfc/eclipticseasons/snow_definitions/)

    plant_groups: {(tfc_block_id, plant_key): [rel_path, ...]}
    """
    # 清理旧模型/定义
    for out_dir in [MODEL_OUT, DEF_OUT, SNOWDEF_OUT]:
        if os.path.exists(out_dir):
            _clean_dir(out_dir)
            print(f"  清理: {os.path.relpath(out_dir, SCRIPT_DIR)}")

    model_count = 0
    def_count = 0
    snowdef_count = 0

    for (block_id, plant_key), rel_paths in sorted(plant_groups.items()):
        # 选择最"成熟"的贴图作为主贴图（像素最多的非透明像素 = 最茂盛）
        best_rel = max(rel_paths, key=lambda p: (
            _texture_stage_key(os.path.basename(p)),  # 按阶段排序
            count_solid(*_load_input_px(p))           # 按像素数排序
        ))

        # 1. 方块模型 JSON (一个植物一个，使用最茂盛贴图)
        best_model_path = best_rel.replace("\\", "/")
        best_model_path = best_model_path[:best_model_path.rfind(".")]

        model_data = {
            "parent": "minecraft:block/cross",
            "textures": {"cross": f"estfc:block/{best_model_path}"}
        }
        model_path = os.path.join(MODEL_OUT, best_model_path + ".json")
        write_json(model_path, model_data)
        model_count += 1

        # 2. 模型定义 JSON (一个植物一个，replace=true)
        # 特殊: 水果树叶 → 用 flag 1101
        is_leaves = plant_key.endswith("_leaves") or plant_key in {
            "cherry_sapling", "green_apple_sapling", "lemon_sapling",
            "olive_sapling", "orange_sapling", "peach_sapling",
            "plum_sapling", "red_apple_sapling", "banana_sapling",
        }

        def_data = {
            "replace": True,
            "variants": {
                "": {"model": f"estfc:block/snowy/tfc/{best_model_path}"}
            }
        }
        def_path = os.path.join(DEF_OUT, f"tfc_{plant_key}.json")
        write_json(def_path, def_data)
        def_count += 1

        # 3. 雪定义 JSON
        flag = 1101 if is_leaves else 1001
        sd_data = {
            "blocks": [block_id],
            "flag": flag,
            "offset": 1,
            "mid": f"estfc:snowy/tfc_{plant_key}"
        }
        if is_leaves:
            sd_data["mid"] = "eclipticseasons:snowy_leaves_top"
            sd_data["mid2"] = "eclipticseasons:snowy_leaves_attach"
            del sd_data["offset"]

        sd_path = os.path.join(SNOWDEF_OUT, f"tfc_plant_{plant_key}.json")
        write_json(sd_path, sd_data)
        snowdef_count += 1

        print(f"  [{plant_key}] 模型+定义 ({len(rel_paths)} 变体, 主={os.path.basename(best_rel)})" +
              (" [leaves]" if is_leaves else ""))

    return model_count, def_count, snowdef_count

def _texture_stage_key(filename):
    """贴图阶段排序键：最高阶段最优先"""
    m = re.search(r'_(\d+)', filename)
    if m:
        return int(m.group(1))
    # dead/young 等特殊阶段优先级低
    if '_dead' in filename: return -10
    if '_young' in filename: return -5
    if '_wild' in filename: return 0
    return 0

def _load_input_px(rel_path):
    """加载输入贴图的像素数据"""
    src = os.path.join(SCRIPT_DIR, rel_path)
    img = Image.open(src).convert("RGBA")
    return img.load(), img.size[0], img.size[1]

def _clean_dir(dir_path):
    """递归清空目录"""
    if not os.path.exists(dir_path):
        return
    for root, dirs, files in os.walk(dir_path, topdown=False):
        for f in files:
            os.remove(os.path.join(root, f))
        for d in dirs:
            try:
                os.rmdir(os.path.join(root, d))
            except OSError:
                pass

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
                    fp = os.path.join(root, f)
                    os.remove(fp)
                    removed += 1
                    print(f"  清理旧贴图: {rel}")
        if not os.listdir(root) and root != output_dir:
            try:
                os.rmdir(root)
            except OSError:
                pass
    if removed:
        print(f"  共清理 {removed} 个旧贴图\n")

def group_by_plant(rel_paths):
    """将贴图按植物分组"""
    groups = defaultdict(list)
    for rel in rel_paths:
        info = get_plant_info(rel)
        if info:
            groups[info].append(rel)
        else:
            print(f"  警告: 无法识别植物类型: {rel}")
    return dict(groups)

def main():
    if not os.path.exists(SNOWY_PNG):
        print(f"缺少 {SNOWY_PNG}"); sys.exit(1)

    snowy = Image.open(SNOWY_PNG).convert("RGBA")
    snowy_top = Image.open(SNOWY_TOP_PNG).convert("RGBA") if os.path.exists(SNOWY_TOP_PNG) else None

    pngs = collect_inputs(SCRIPT_DIR, IGNORE_LIST)
    if not pngs:
        print("无待处理 PNG"); sys.exit(1)

    print(f"输入贴图: {len(pngs)} 个")
    print(f"输出目录: {os.path.abspath(TEX_OUT)}\n")

    # 1. 清理旧贴图
    clean_output(TEX_OUT, pngs)

    # 2. 生成积雪贴图
    print("─ 生成积雪贴图 ─")
    ok, skipped = 0, 0
    for rel in sorted(pngs):
        src = os.path.join(SCRIPT_DIR, rel)
        try:
            out = process_one(rel, Image.open(src), snowy, snowy_top)
        except Exception as e:
            print(f"  [{rel}] 错误: {e}")
            skipped += 1
            continue
        dest = os.path.join(TEX_OUT, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        out.save(dest)
        ok += 1

    print(f"  贴图: {ok} 成功, {skipped} 跳过\n")

    # 3. 生成模型 JSON + 模型定义 JSON + 雪定义 JSON
    print("─ 生成模型与定义 ─")
    plant_groups = group_by_plant(pngs)
    models, defs, sdefs = generate_models_and_definitions(plant_groups, pngs)

    print(f"\n完成: {ok} 贴图, {models} 模型, {defs} 模型定义, {sdefs} 雪定义")
    print(f"贴图:  {os.path.abspath(TEX_OUT)}")
    print(f"模型:  {os.path.abspath(MODEL_OUT)}")
    print(f"定义:  {os.path.abspath(DEF_OUT)}")
    print(f"雪定义:{os.path.abspath(SNOWDEF_OUT)}")

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"错误: {e}")
        import traceback; traceback.print_exc()

    input("\n按 Enter 退出...")