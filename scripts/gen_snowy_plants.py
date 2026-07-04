#!/usr/bin/env python3
"""
为 TFC 植物生成积雪贴图和 ES 模型 JSON。
用法:
  python gen_snowy_plants.py --tfc-textures ./tfc_textures/ --output ./estfc_snowy/

需要先将 TFC JAR 中的 textures/block/plant/ 解压到 --tfc-textures 目录。
"""

import os, json, argparse
from PIL import Image

PLANTS = [
    # === 普通植物 ===
    "allium", "anthurium", "arrowhead", "artists_conk", "azalea",
    "badderlocks", "barrel_cactus", "beachgrass", "bear_grass",
    "bird_nest_fern", "black_orchid", "blood_lily", "blue_ginger",
    "blue_orchid", "bluegrass", "bromegrass", "bur_reed", "buttercup",
    "butterfly_milkweed", "calendula", "cattail", "cobblestone_lichen",
    "coontail", "cordgrass", "cornflower", "dandelion", "dead_bush",
    "desert_flame", "dry_grass", "duckweed", "edelweiss", "eel_grass",
    "elegant_sunburst_lichen", "fan_palm", "field_horsetail", "fountain_grass",
    "foxglove", "grape_hyacinth", "green_algae", "gutweed", "guzmania",
    "heather", "heliconia", "hibiscus", "houstonia", "ivy",
    "kangaroo_paw", "king_fern", "kinnikinnick", "jungle_vines",
    "labrador_tea", "lady_fern", "laminaria", "licorice_fern",
    "lilac", "lily_of_the_valley", "lotus",
    "maiden_pink", "manatee_grass", "marigold", "meads_milkweed",
    "milfoil", "morning_glory", "moss", "moss_campion",
    "mountain_hullwort",
    "nasturtium", "orchard_grass", "ostrich_fern", "oxeye_daisy",
    "palash", "penwortel", "philodendron", "phragmite", "pickerelweed",
    "pistia", "poppy", "prickly_pear", "prickly_pear_purple",
    "primrose", "pulsatilla", "qantu",
    "raddia_grass", "ramirezella", "ramunda", "red_algae",
    "red_oat_grass", "red_sealing_wax_palm", "reindeer_lichen",
    "ryegrass", "rose",
    "sacred_datura", "sagebrush", "sago", "saguaro_fruit",
    "sapphire_tower", "sargassum", "scutch_grass", "sea_lavender",
    "sea_palm", "shawiash", "silken_pincushion_cactus",
    "silver_bromeliad", "silver_spurflower",
    "snapdragon_pink", "snapdragon_red", "snapdragon_white", "snapdragon_yellow",
    "star_grass", "strelitzia", "sunflower", "switchgrass", "sword_fern",
    "tall_fescue_grass", "tank_bromeliad", "timothy_grass",
    "toquilla_palm", "trillium", "tropical_milkweed",
    "tulip_orange", "tulip_pink", "tulip_red", "tulip_white",
    "turtle_grass", "vriesea", "water_canna", "water_taro",
    "white_water_lily", "purple_water_lily", "yellow_water_lily",
    "yellow_saxifrage", "yucca",
    # === 高株/藤蔓 ===
    "hanging_vines", "hanging_vines_plant",
    "spanish_moss", "spanish_moss_plant",
    "liana", "liana_plant",
    "flame_vine", "flame_vine_plant",
    "tree_fern", "tree_fern_plant",
    "arundo", "arundo_plant",
    "dry_phragmite", "dry_phragmite_plant",
    "winged_kelp", "winged_kelp_plant",
    "leafy_kelp", "leafy_kelp_plant",
    "giant_kelp_plant", "giant_kelp_flower",
    "saguaro", "saguaro_plant", "cycad", "cycad_plant",
    "golden_bamboo", "golden_bamboo_sapling",
    # === 环境指示 ===
    "athyrium_fern", "canna", "goldenrod", "pampas_grass", "perovskia",
    "anemone_green", "anemone_purple", "anemone_large_orange", "anemone_large_purple",
    "barnacles", "mussels", "starfish",
    # === 果树/浆果/矮曲木 (小写 key，贴图可能不在 plant/ 目录) ===
    "cherry_sapling", "green_apple_sapling", "lemon_sapling", "olive_sapling",
    "orange_sapling", "peach_sapling", "plum_sapling", "red_apple_sapling",
    "banana_sapling",
    "snowberry_bush", "bunchberry_bush", "gooseberry_bush", "cloudberry_bush",
    "strawberry_bush", "wintergreen_berry_bush",
    "blackberry_bush", "raspberry_bush", "blueberry_bush", "elderberry_bush",
    "cranberry_bush",
    "pine_krummholz", "spruce_krummholz", "white_cedar_krummholz",
    "douglas_fir_krummholz", "aspen_krummholz",
]


def add_snow_topgradient(img: Image.Image) -> Image.Image:
    """
    在贴图上半部叠加白色渐变积雪。
    只影响非透明像素，顶部最白，向下逐渐透明。
    """
    img = img.convert("RGBA")
    w, h = img.size
    snow_layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    src = img.load()
    dst = snow_layer.load()
    for y in range(h):
        # 顶部 35% 高度内积雪最厚，向下线性衰减
        frac = 1.0 - y / h          # 1.0(top) → 0.0(bottom)
        alpha = int(max(0, min(220, 260 * frac - 30)))
        if alpha <= 0:
            continue
        for x in range(w):
            if src[x, y][3] > 10:   # 只在有像素的地方加雪
                dst[x, y] = (255, 255, 255, alpha)
    return Image.alpha_composite(img, snow_layer)


def add_snow_edge(img: Image.Image) -> Image.Image:
    """
    仅沿贴图上半部外轮廓叠加白边（模拟雪积在叶片边缘）。
    """
    img = img.convert("RGBA")
    w, h = img.size
    snow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    src = img.load()
    dst = snow.load()

    def is_solid(x, y):
        if 0 <= x < w and 0 <= y < h:
            return src[x, y][3] > 10
        return False

    for y in range(h):
        for x in range(w):
            if not is_solid(x, y):
                continue
            # 只处理上半部
            if y > h * 0.5:
                continue
            # 边缘检测：周围有透明像素 → 这是边缘
            edge = any(not is_solid(x + dx, y + dy)
                       for dx in (-1, 0, 1) for dy in (-1, 0, 1)
                       if dx != 0 or dy != 0)
            if edge:
                frac = 1.0 - y / (h * 0.5)
                alpha = int(max(0, min(200, 220 * frac)))
                dst[x, y] = (255, 255, 255, alpha)
    return Image.alpha_composite(img, snow)


def create_snowy_cross_texture(img: Image.Image) -> Image.Image:
    """
    创建半透明白色 cross 贴图 —— 基于原贴图形状但变白。
    用于 replace:false 模式，叠加在植物上方产生积雪感。
    """
    img = img.convert("RGBA")
    w, h = img.size
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    src = img.load()
    dst = out.load()
    for y in range(h):
        frac = 1.0 - y / h
        alpha = int(max(0, min(180, 220 * frac - 40)))
        for x in range(w):
            if src[x, y][3] > 10:
                dst[x, y] = (255, 255, 255, alpha)
    return out


def main():
    parser = argparse.ArgumentParser(description="生成 TFC 植物积雪贴图 + ES JSON")
    parser.add_argument("--tfc-textures", required=True,
                        help="TFC textures/block/plant/ 目录路径 (从 TFC JAR 解压)")
    parser.add_argument("--output", default="./out_estfc_snowy",
                        help="输出根目录 (默认 ./out_estfc_snowy)")
    parser.add_argument("--mode", choices=["topgradient", "edge", "whitecross"], default="topgradient",
                        help="积雪模式: topgradient=顶部渐变白, edge=边缘白边, whitecross=白色cross叠加")
    args = parser.parse_args()

    tex_dir = os.path.join(args.tfc_textures, "")  # 确保末尾有 /
    out = args.output

    # 输出目录结构
    tex_out = os.path.join(out, "assets/estfc/textures/block/snowy/tfc")
    model_out = os.path.join(out, "assets/estfc/models/block/snowy/tfc")
    def_out = os.path.join(out, "assets/estfc/eclipticseasons/model_definitions/snowy")
    snowdef_out = os.path.join(out, "data/estfc/eclipticseasons/snow_definitions")

    for d in [tex_out, model_out, def_out, snowdef_out]:
        os.makedirs(d, exist_ok=True)

    count = 0
    for plant in PLANTS:
        # 查找原始贴图 (TFC 用 .png)
        src = os.path.join(tex_dir, f"{plant}.png")
        if not os.path.exists(src):
            print(f"  SKIP (无贴图): {plant}")
            continue

        img = Image.open(src)

        # 生成积雪贴图
        if args.mode == "topgradient":
            snowy = add_snow_topgradient(img)
        elif args.mode == "edge":
            snowy = add_snow_edge(img)
        else:  # whitecross
            snowy = create_snowy_cross_texture(img)

        snowy.save(os.path.join(tex_out, f"{plant}.png"))

        # 生成模型 JSON (parent: block/cross)
        model_json = {
            "parent": "minecraft:block/cross",
            "textures": {"cross": f"estfc:block/snowy/tfc/{plant}"}
        }
        with open(os.path.join(model_out, f"{plant}.json"), "w") as f:
            json.dump(model_json, f, indent=2)

        # 生成模型定义 JSON
        def_json = {
            "replace": True,
            "variants": {"": {"model": f"estfc:block/snowy/tfc/{plant}"}}
        }
        with open(os.path.join(def_out, f"tfc_{plant}.json"), "w") as f:
            json.dump(def_json, f, indent=2)

        # 生成 snow_definition JSON
        sd_json = {
            "blocks": [f"tfc:plant/{plant}"],
            "flag": 1000,
            "offset": 0,
            "mid": f"estfc:snowy/tfc_{plant}"
        }
        # 果树叶子用 flag 1101
        if plant.endswith("_leaves"):
            sd_json["flag"] = 1101
            sd_json["mid"] = "eclipticseasons:snowy_leaves_top"
            sd_json["mid2"] = "eclipticseasons:snowy_leaves_attach"
            del sd_json["offset"]

        with open(os.path.join(snowdef_out, f"tfc_plant_{plant}.json"), "w") as f:
            json.dump(sd_json, f, indent=2)

        count += 1
        print(f"  OK: {plant}")

    print(f"\n完成: {count} 个植物")
    print(f"输出目录: {os.path.abspath(out)}")
    print(f"将输出目录下 assets/ 和 data/ 复制到 estfc 模组的 src/main/resources/ 即可。")


if __name__ == "__main__":
    main()
