# estfc (Ecliptic Seasons ↔ TerraFirmaCraft Compat) 架构文档

> 将 TFC 群峦的气候系统（温度、降水）桥接到 ES 节气的覆雪视觉系统，
> 使 TFC 方块能显示基于真实位置温度的概率性积雪。

---

## 1. 模组关系图

```
┌─────────────────────────────────────────────────────────────────┐
│                    Minecraft ServerLevel                         │
│  tick() → advanceWeatherCycle() → tickChunk()                   │
└──────────┬──────────────────────┬───────────────────────────────┘
           │                      │
    ┌──────▼──────┐        ┌──────▼──────────────────────┐
    │  TFC 群峦    │        │  ES 节气                     │
    │             │        │                             │
    │ Climate     │        │ WeatherManager              │
    │  .getInstant│        │  .agentAdvanceWeatherCycle()│ ← [已取消]
    │   Temperature│       │  .updateSnowOrMelt()        │
    │  .getRain() │        │  .getSnowStatus()           │ ← [已劫持]
    │             │        │                             │
    │ Calendars   │        │ SnowyStatusKeeper           │
    │  .getCalendar│       │  .tickChunk()               │ ← [已取消]
    │   Ticks()   │        │  .set()/.isSnowyBlock()    │
    │             │        │                             │
    │ WeatherHelpe│        │ SnowyMapChecker             │
    │  .onTickChunk│ ← [已取消]│  .updateAllChunks()         │ ← [被调用]
    │  .advanceWea│ther     │  .forceChunkUpdateHeight() │
    │   Cycle     │        │                             │
    └──────┬──────┘        └──────┬──────────────────────┘
           │                      │
           └──────────┬───────────┘
                      │
              ┌───────▼────────┐
              │   estfc 桥接层  │
              │                │
              │ WeatherBridge  │ ← 核心：温度↔雪深 + effectiveDepth + 增量刷新 + referenceTemp
              │ Config         │ ← 全部配置项
              │ SnowDepthCache │ ← 客户端 biome 雪深 + referenceTemp 缓存
              │ TfcUnloadedTemp│ ← 未加载区块温度估算
              │   Estimator    │
              └───────┬────────┘
                      │
    ┌─────────────────┼─────────────────┐
    │                 │                 │
┌───▼────┐  ┌─────────▼────┐  ┌────────▼──────┐
│  DH    │  │  Voxy        │  │  Voxy-ES       │
│ 远景   │  │  体素远景     │  │  兼容          │
│        │  │              │  │                │
│ DhLevel│  │ MapChecker   │  │ (ES 节气模型    │
│ Wrapper│  │  Mixin       │  │  定义 + voxy    │
│ Mixin  │  │ (shouldSnow  │  │  覆盖)         │
│        │  │  AtBiome)    │  │                │
└────────┘  └──────────────┘  └────────────────┘
```

---

## 2. 包结构

```
me.xuan.estfc/
├── EclipticSeasonsTFCrepair.java   ← @Mod 入口
├── Config.java                     ← 全部配置（NeoForge ModConfigSpec）
├── WeatherBridge.java              ← 核心桥接：温度→雪深、降水检测、定时刷新
├── SnowDepthCache.java             ← 客户端 biome 雪深缓存
├── EstfcTags.java                  ← 方块标签常量（estfc:simple_snow, estfc:snowable）
│
├── mixin/                          ← 14 个 Mixin
│   ├── [服务端 mixin]
│   ├── [客户端 mixin]
│   └── ...
│
├── client/
│   ├── BakedQuadSnow.java          ← BakedQuad 覆雪贴图替换
│   ├── BakedQuadTextureOnly.java   ← 保留 UV 的贴图替换
│   ├── SnowTextureSwapModel.java   ← 覆雪 BakedModel 包装器
│   ├── TfcUnloadedTempEstimator.java ← 未加载区块温度估算
│   └── gui/
│       ├── EstfcConfigScreen.java  ← 配置界面
│       └── ScrollableLayout.java   ← 可滚动布局
│
└── data/
    ├── DataGenerators.java         ← 数据生成入口
    └── TfcSnowDefinitionProvider.java ← ES SnowDefinition 生成器
```

---

## 3. 核心类详解

### 3.1 `EclipticSeasonsTFCrepair` — 模组入口

```java
@Mod("estfc")
public class EclipticSeasonsTFCrepair { ... }
```

| 职责 | 说明 |
|------|------|
| 注册配置 | `Config.SPEC` → `ModConfig.Type.COMMON` |
| 注册配置变更监听 | `modEventBus.addListener(Config::updateConfig)` |
| 注册配置界面 | `EstfcConfigScreen` 作为 IConfigScreenFactory |

### 3.2 `WeatherBridge` — 核心桥接器（v2 重写）

**是 estfc 的中枢**，所有 TFC ↔ ES 数据转换都在这里。
v2 重写核心：状态机驱动的缓慢积雪/融雪 + 参考温度锚定。

| 方法 | 职责 | 调用方 |
|------|------|--------|
| `tickWeatherCycle(ServerLevel)` | **主循环入口**：状态机轮转 biome（INIT→ACCUMULATING→HOLDING→MELTING）、维护 referenceTemp、缓慢渐变雪深 | `WeatherHelpersMixin` (TFC advanceWeatherCycle TAIL) |
| `tempToSnowDepth(temp)` | TFC 温度 → 雪深 0-100。固定曲线（无降水参数） | 内部 |
| `calcEffectiveDepth(temp, precip, biomeDepth, refTemp)` | **核心公式**：biomeDepth==0→仅降水时覆雪；biomeDepth>0→biomeDepth+tempContribution | `SnowyMapCheckerMixin`, `MapCheckerMixin`, `DhLevelWrapperMixin` |
| `shouldSnow(depth, seed)` | 雪深→概率判断 `depth > \|seed % 100\|` | 多处 |
| `isPrecipitating(Level)` | 客户端降水检测（日历 tick 级缓存） | `ExtraModelManagerMixin`, `MapCheckerMixin` |
| `isPrecipitating(ServerLevel, BlockPos)` | 服务端降水检测（per-position） | `WeatherManagerMixin` |
| `calcSnowDepthClient(Level, model, pos)` | 客户端：纯温度→雪深（旧版兼容） | 保留向后兼容 |
| `calcSnowDepthClient(Level, model, pos, biomeDepth, refTemp)` | 客户端：含 biome 历史的有效雪深（供 DH/Voxy 远景） | `MapCheckerMixin`, `DhLevelWrapperMixin` |
| `getBiomeDepthAt(Level, BlockPos)` | 查找指定位置的 biome 累积雪深 | 多处 |
| `getReferenceTempAt(Level, BlockPos)` | 查找指定位置的 biome referenceTemp（服务端从 WeatherBridge，客户端从 SnowDepthCache） | 多处 |
| `getModelIfActive(Level)` | 获取 TFC ClimateModel（天气关闭则返回 null） | 内部/多处 |
| `accumulationStepTicks()` | 积雪每 +1 的 tick 间隔（从 snowFullTransitionMinutes 计算） | 内部 |
| `meltStepTicks()` | 融雪每 -1 的 tick 间隔（从 snowMeltTransitionMinutes 计算） | 内部 |

**关键字段**：
- `biomeStates[256]` — 每个 biome 的状态 (0=INIT, 1=ACCUMULATING, 2=HOLDING, 3=MELTING)
- `lastStepTick[256]` — 每个 biome index 上次雪深 ±1 的时间，控制渐变速度
- `referenceTemps[256]` — **每个 biome 的参考温度（°C）**。下雪时冻结为锚点，无雪时跟踪当前温度。公开访问（供 SnowyMapCheckerMixin 直接读取）

**v2 移除的方法**：
- `ticksPerStep()` — 替换为 `accumulationStepTicks()` / `meltStepTicks()`（直接基于分钟数）
- `isPeriodicRefresh()` / `startRefresh()` / `processRefreshBatch()` — 增量刷新逻辑已移除
- `calcSnowDepth(ServerLevel, BlockPos)` 双曲线版 — 不再需要

### 3.3 `Config` — 配置系统

| 分组 | 配置项 | 默认值 | 说明 |
|------|--------|--------|------|
| General | `enableTFCCompat` | true | 总开关 |
| Snow Temperature | `snowAppearanceTemp` | 0.0°C | 雪开始出现的温度（基准线） |
| | `snowFullCoverageTemp` | -4.0°C | 满雪温度（低于此温度=100%覆雪） |
| | `snowMeltTemp` | 0.0°C | 融雪温度（高于此温度开始融化） |
| | `dhUnloadedTempOffset` | 0.0°C | DH/Voxy 远景温度微调 |
| | `unloadedTempSampleRange` | 10240 | 未加载区块温度采样半径 |
| Climate Calculation | `elevationLapseRate` | -1.6225 | 海拔递减率 °C/10m |
| | `snowFullTransitionMinutes` | 10.0 | **积雪 0→100 所需分钟数（仅降雪期间累积）** |
| | `snowMeltTransitionMinutes` | 5.0 | **融雪 100→0 所需分钟数（仅零上融化）** |
| | `snowRefreshInterval` | 60 | 全区块刷新间隔（tick），0=禁用 |
| | `snowRefreshChunksPerTick` | 200 | 每次 tick 处理的 chunk 数 |
| Snow Texture | `snowTextureSimpleMode` | false | 所有面统一用 snow.png |
| | `snowTextureReplaceMode` | true | 替换模式（关=叠加模式） |
| | `snowTextureDefaultPath` | block/snow | 默认覆雪贴图路径 |
| TFC Block Snow Prefixes | `tfcSnowBlockPrefixes` | [plant/, crop/, ...] | 哪些 TFC 路径前缀方块可覆雪 |
| | `tfcSimpleSnowBlocks` | [bone, clam, ...] | 哪些方块用简单雪模式 |

**已移除的配置项（v2）**：
- `snowSnowfallFullTemp` — 不再需要双温度曲线（降雪期间始终以 100 为目标）
- `maxTempSnowContribution` — 温度贡献由公式自封顶（min(100, biomeDepth + tempContribution)）
- `snowAccumulationSpeed` / `snowMeltSpeed` — 替换为直观的「分钟数」配置

### 3.4 `SnowDepthCache` — 客户端雪深 + 参考温度缓存

```java
public static final byte[] DEPTHS = new byte[1024];          // 按 ES biome ID 索引
public static final float[] REFERENCE_TEMPS = new float[1024]; // 客户端 referenceTemp 缓存
```

- `DEPTHS`：由 `NetworkUtilMixin` 拦截 `BiomeWeatherMessage` 写入，渲染时用于 `ExtraModelManagerMixin`
- `REFERENCE_TEMPS`：由 `ClientConMixin` 在客户端 tick 中维护（跟踪降水状态变化），供 DH/Voxy 远景的 `calcEffectiveDepth` 使用

### 3.5 `EstfcTags` — 方块标签

| 标签 | 含义 |
|------|------|
| `estfc:simple_snow` | 所有 quad 统一替换为 snow.png |
| `estfc:snowable` | 逐 quad 贴图替换（tfc: → estfc:） |

---

## 4. Mixin 详解（按目标模组分类）

### 4.1 目标：TFC（群峦）

#### `WeatherHelpersMixin` — 拦截 TFC 天气/积雪

| 注入点 | 作用 |
|--------|------|
| `onTickChunk` HEAD cancel | 阻止 TFC 放置物理雪块/冰柱/冰堆（TFC 不再管理 snow pile） |
| `advanceWeatherCycle` RETURN | 调用 `WeatherBridge.tickWeatherCycle()` 驱动 ES 雪深同步 |

**关系链**：TFC WeatherHelpers → WeatherBridge.tickWeatherCycle → ES biome 雪深 + 全区块刷新

#### `ConnectedGrassBlockMixin` — 禁用 TFC 连接纹理

| 注入点 | 作用 |
|--------|------|
| `updateStateFromDirection` RETURN | 强制 NORTH/EAST/SOUTH/WEST = false |
| `updateShape` RETURN | 同上 |

**原因**：TFC ConnectedGrassBlock 的连接纹理方向属性会干扰覆雪模型的渲染，关闭后 TFC multipart 的 SNOWY=true 模型统一使用 snowy_side。

### 4.2 目标：ES（节气）

#### `WeatherManagerMixin` — 核心天气接管

| 注入点 | 作用 | 影响 |
|--------|------|------|
| `agentAdvanceWeatherCycle` HEAD cancel | **禁用 ES 自主天气循环** | ES 不再修改 ServerLevelData rainTime/clearTime，完全由 TFC 接管 |
| `hasNonePrecipitation` HEAD cancel | 所有群系都可能通过 TFC 降水 | ES 不再按 biome tag 过滤降水 |
| `getRainOrSnow` HEAD cancel | 委托给 TFC `WeatherHelpers.getPrecipitationAt` | 用 TFC 判断雨/雪 |
| `getSnowStatus(4参数)` HEAD cancel | **核心劫持**：TFC per-block 温度 → SNOW/SNOW_MELT/NONE | SnowyStatusKeeper.tickChunk + updateSnowOrMelt 都用此结果 |
| `getPrecipitationAt` HEAD cancel | 先查 TFC 是否降水，再按 TFC 温度分雨/雪 | 之前只按温度，修复了不下雪时仍然返回 SNOW 的 bug |

**getSnowStatusImpl（共享实现）的逻辑**：
```
if temp > meltTemp → SNOW_MELT（融雪）
else if precipitating → SNOW（积雪）
else → NONE（不动，保持现状）
```

#### `SnowyStatusKeeperMixin` — 雪深替换 + 禁用逐 tick

| 注入点 | 作用 |
|--------|------|
| `tickChunk` HEAD cancel | 当 `snowRefreshInterval > 0` 时取消逐 tick 雪更新 |
| `tickChunk` 中 `getSnowDepthAtBiome` ×2 WrapOperation | 将 ES biome 缓存深度替换为 TFC per-block 温度→深度 |

**注意**：`tickChunk` 取消后，雪更新完全由 `WeatherBridge` 的增量刷新接管。WrapOperation 仅当 `snowRefreshInterval = 0`（回退模式）时生效。

**estfc$getPerBlockDepth**：调用 TFC `model.getInstantTemperature()` → `WeatherBridge.tempToSnowDepth()` → 返回 0-100 深度。返回 -1 时回退到 ES 原生 biome 深度。

#### `SnowyMapCheckerMixin` — 全区块刷新时的 TFC 覆写

在 ES 的 `forceChunkUpdateHeight` RETURN 之后执行，用 TFC per-position 温度重新判断每个方块的雪状态。

| 场景 | 行为 |
|------|------|
| `loadingChunk=true` | 完整雪/融判断（`calcEffectiveDepth`→概率→set/clear） |
| `WeatherBridge.isPeriodicRefresh()=true` | 完整雪/融判断（同 loadingChunk） |
| 其他（方块变更触发） | **仅融雪**（温度高于 meltTemp 时清除雪标志） |

**per-block 深度计算**：使用 `WeatherBridge.calcEffectiveDepth(temp, precipitating, biomeSnowDepth, referenceTemp)`，其中 `biomeSnowDepth` 和 `referenceTemp` 在 chunk 级预先从 biome 列表查找并缓存。

**遍历逻辑**：对每个 (x,z)，取 surfaceHeight 到 solidHeight 之间的所有方块列，用 `MapChecker.getDefaultBlockTypeFlag` 过滤非雪方块。

#### `EclipticUtilMixin` — 强制启用雪视觉

```java
canSnowyBlockInteract → 强制返回 true
```

**作用**：ES 的雪视觉系统默认只在特定维度/配置下激活。此 Mixin 强制启用，使所有 TFC 维度都能显示覆雪。

#### `SolarDataManagerMixin` — TFC 日历→节气

| 注入点 | 作用 |
|--------|------|
| `getSolarTermIndex` HEAD | 从 TFC 日历派生节气索引（每月=2节气，前半/后半） |

**公式**：`solarTermIndex = ((month.ordinal() + 11) * 2 + (fraction >= 0.5 ? 1 : 0)) % 24`

**影响**：ES 的季节判断（萤火虫/蝴蝶/大雁粒子、蟋蟀音效）与 TFC 月份同步。

#### `ServerLevelMixin` — tickChunk 尾部额外雪处理

在 `ServerLevel.tickChunk` TAIL 生成额外随机位置并调用 `SnowyStatusKeeper.tickChunk()`。

**注意**：当 `snowRefreshInterval > 0` 时，`SnowyStatusKeeperMixin` 取消了 tickChunk 内部逻辑，此 Mixin 实际上不再产生效果。保留用于 `snowRefreshInterval = 0`（回退旧行为）时的兼容。

### 4.3 目标：ES 客户端

#### `ExtraModelManagerMixin` — maySnowyAt 回退

`maySnowyAt` 是 ES 客户端判断方块是否应渲染雪的方法。此 Mixin 在其 RETURN 后做回退：

- 如果 ES 已经返回 true → 不处理
- 如果 mapSlice 不为 null（有服务端数据）→ 不处理（信任服务端）
- 否则，查 TFC 温度 + ES biome 雪深 → 概率判断

**getBiomeDepth**：从 `WeatherManager.getBiomeList(level)` 查当前群系的 biome 雪深，带单条目缓存。

#### `SnowModelMixin` — 覆雪模型替换

在 `ExtraModelManager.getSnowyModel` TAIL 注入：

| 方块类型 | 处理方式 |
|----------|----------|
| ConnectedGrassBlock | 设置 SNOWY=true + 关闭连接方向 → TFC multipart 自动选 snowy_top/snowy_side |
| 其他 TFC 方块 | 包装为 `SnowTextureSwapModel`（贴图路径 tfc: → estfc:） |

**isTfcBlock** / **isSimpleSnowBlock**：通过标签或配置匹配。配置支持三种格式：

| 格式 | 示例 | 说明 |
|------|------|------|
| 路径前缀 | `plant/` | 以 / 结尾，匹配所有以此开头的方块路径 |
| 完整 ID | `tfc:plant/plum_leaves` | 含 `:` 的精确匹配 |
| 正则表达式 | `regex:plant/.*_leaves` | `regex:` 前缀，匹配方块路径 |

**默认覆雪的 TFC 方块**：`plant/`（含果树树叶如 plum_leaves）、`crop/`、`groundcover/`、`wild_crop/`、`wood/fallen_leaves/`、`wood/twig/`、`rock/loose/`、`rock/mossy_loose/`、`soil/`、`peat`、`kaolin`、`regex:plant/.*_leaves`

### 4.4 目标：ES 网络

#### `NetworkUtilMixin` — 拦截 BiomeWeatherMessage

```java
processBiomeWeatherMessage HEAD → System.arraycopy msg.snowDepth → SnowDepthCache.DEPTHS
```

将服务端同步的 biome 雪深写入客户端缓存，供 `ExtraModelManagerMixin` 使用。

### 4.5 目标：ES 客户端天气

#### `ClientConMixin` — 南半球季节翻转

在 `ClientCon.tick` TAIL：

- **南半球季节翻转**：北半球不修改，南半球 `nowSeason` 翻转 + `nowSolarTerm` ordinal +12
- v3 已移除 referenceTemp 维护（不再需要滑动基准）

### 4.6 目标：DH（Distant Horizons）

#### `DhLevelWrapperMixin` — DH 远景覆雪（含 biome 积雪历史）

在 DH 生成 LOD 网格时，为低温位置的方块计算白色混合。

```java
getBlockColor RETURN:
  1. 获取 TFC ClimateModel
  2. getBiomeDepthAt(level, mcPos) → biomeDepth
  3. getReferenceTempAt(level, mcPos) → referenceTemp
  4. calcSnowDepthClient(level, model, mcPos, biomeDepth, refTemp) → depth (0-100)
  5. depth / 100 → factor [0,1]
  6. 原始颜色 + factor 线性混合到白色
```

**为什么用直接位置温度而非玩家参考温度**：
- 玩家参考温度随时间变化（TFC 昼夜温差 10+°C） → 不同时刻生成的 LOD 雪量不一致 → 128×128 网格花纹
- TFC `getInstantTemperature` 是纯计算函数，基于世界种子 + 位置，线程安全

#### `MapCheckerMixin` — Voxy 远景覆雪（含 biome 积雪历史）

```java
shouldSnowAtBiome HEAD cancel:
  1. 仅客户端处理（`level instanceof ServerLevel` → return）
  2. getBiomeDepthAt(level, mcPos) → biomeSnowDepth
  3. getReferenceTempAt(level, mcPos) → referenceTemp
  4. calcSnowDepthClient(level, model, mcPos, biomeDepth, refTemp) → effectiveDepth
  5. shouldSnow(effectiveDepth, seed) → 概率判断
```

与旧版的区别：不再用纯温度→雪深，而是用 `calcEffectiveDepth` 将 biome 积雪历史纳入计算。

---

## 5. 客户端渲染类

### 5.1 `SnowTextureSwapModel` — 覆雪 BakedModel

实现 `BakedModel` + `IESReplaceModel`（`isReplace()=true` → ES 用此模型替换而非叠加）。

| 构造参数 | 作用 |
|----------|------|
| `tfcModel` | TFC 原始 BakedModel |
| `simpleSnow` | true=所有 quad → estfc:block/snow |
| `overrideState` | 非 null=覆写渲染用的 BlockState（ConnectedGrassBlock 用） |

**贴图替换逻辑**（`swapQuads`）：
1. simpleSnow 模式 → 所有 quad 用 `BakedQuadRetexturedAndReUV(quad, snowSprite, false)`
2. ConnectedGrassBlock 路径（overrideState != null）→ 保留 tintIndex（TFC grassBlockColor 对 SNOWY=true 返回白色）
3. 通用路径 → tfc: 贴图→ estfc: 贴图，tintIndex=-1 强制不着色
4. 贴图查找回退：`dense_leaves/` → `leaves/`

### 5.2 `BakedQuadTextureOnly` — 保留 UV 的贴图替换

**与 `BakedQuadRetexturedAndReUV` 的关键区别**：
- `BakedQuadRetexturedAndReUV` 从顶点位置重新投影 UV，会破坏有非标准 UV 的方块（如 sword_fern 的 `"uv":[16,12,0,16]`）
- `BakedQuadTextureOnly` 将旧精灵 atlas UV 线性映射到新精灵 atlas UV，保留 sprite 内相对位置

| 构造参数 | 作用 |
|----------|------|
| `quad` | 原始 BakedQuad |
| `textureIn` | 替换用贴图 |
| `preserveTint` | true=保留原始 tintIndex（ConnectedGrassBlock），false=强制 -1（通用） |

### 5.3 `BakedQuadSnow` — 直接 UV 重映射（备用）

与 `BakedQuadTextureOnly` 类似但用于跨方块边界的植物模型。直接从原 quad UV 经由原 sprite → [0,1] → 新 sprite 重映射。

### 5.4 `TfcUnloadedTempEstimator` — 未加载区块温度估算

**问题**：TFC `getInstantTemperature` 在 ChunkData.EMPTY 时 avgSeaLevelTemp 固定 10°C → 未加载区块偏暖 → 漏雪。

**方案**：从已加载区块沿 Z 轴采样真实 avgSeaLevelTemp，缓存到 `TreeMap<Integer, Float>`（绝对 Z 坐标，只增不删）。

**修正公式**：`temp_REAL = temp_EMPTY - 10 + cached_avg(Z) + offset`

| 步骤 | 说明 |
|------|------|
| 采样触发 | 玩家移动 ≥64 格或间隔 ≥100 tick |
| 采样范围 | `unloadedTempSampleRange`（默认 10240），间隔 64 格 |
| 缓存查找 | TreeMap.floorEntry/ceilingEntry 二分查找 |
| 插值策略 | 上下界都有→线性插值；单侧→取最近值（不用梯度外推，TFC 噪声场梯度不可靠） |
| 半球 | 南北半球影响由 TFC 内置月度温度自动处理 |

---

## 6. 数据生成

### 6.1 `DataGenerators`

NeoForge `GatherDataEvent` 监听器，注册 `TfcSnowDefinitionProvider`。

### 6.2 `TfcSnowDefinitionProvider`

继承 `ESClientDataMapProvider<SnowDefinition>`，为 TFC 方块生成 ES `SnowDefinition` JSON。

**覆盖的方块类型**：
- groundcover（16 种）
- loose rock + mossy loose rock（各 21 种岩石）
- fallen leaves（20 种树木）
- twig（20 种树木）
- krummholz（5 种）
- wild crops（29 种）

每个定义设置 `flag=FLAG_CUSTOM_JSON_PLANTS`, `offset=1`, `mid=OVERLAY_TINY`。

---

## 7. 配置界面

### `EstfcConfigScreen`

使用 `HeaderAndFooterLayout` + `GridLayout` 2 列布局的自定义配置界面（替代 NeoForge 默认纯列表）。

5 个配置分组带醒目标题，底部 [返回]/[完成] 按钮。支持的类型：布尔（开关按钮）、double/int（EditBox + 范围验证）、string、list（逗号分隔输入）。

### `ScrollableLayout`

从 ES 移植的可滚动布局容器。超出屏幕高度时自动显示滚动条，支持鼠标滚轮和拖拽。

---

## 8. 关键数据流

### 8.0 effectiveDepth 公式（v3 简洁架构）

```
无 referenceTemp、无状态机、无滑动基准。

两个深度来源，在 calcEffectiveDepth 中合并：

  tempDepth   = tempToSnowDepth(actualTemp)    — 温度推导（连续渐变，无断层）
  biomeDepth  = 服务端缓慢累积 (0→100, ~10分钟) — 降水历史

公式：
  融化中 (temp > meltTemp) → effectiveDepth = biomeDepth
  零下   (temp ≤ meltTemp) → effectiveDepth = min(100, biomeDepth + tempDepth)

biomeDepth 由 tickWeatherCycle 驱动：
  降水+低温 → 每 N tick biomeDepth += 1（N = snowFullTransitionMinutes * 1200 / 100）
  零上      → 每 M tick biomeDepth -= 1（M = snowMeltTransitionMinutes * 1200 / 100）
  低温无降水 → biomeDepth 不变（保持）

示例（appear=0°C, full=-4°C, range=4°C）：
  没下过雪 (biomeDepth=0)：
    -2°C → tempDepth=50 → effective=50
    -4°C → tempDepth=100 → effective=100（满雪）
    -6°C → tempDepth=100 → effective=100（满雪）
    +1°C → 融化路径 → effective=0

  下雪中 (biomeDepth=20)：
    -2°C → min(100, 20+50)=70
    -4°C → min(100, 20+100)=100
    0°C  → min(100, 20+0)=20（只有累积雪，温度不贡献）

  融化中 (temp=+2°C, biomeDepth=50→0)：
    effective=biomeDepth（缓慢递减：50→49→...→0）
```

### 8.1 积雪流程（v2 状态机）

```
TFC 降水+低温（开始下雪）
  │
  ├─ 1. TFC advanceWeatherCycle → WeatherHelpersMixin (TAIL)
  │     └→ WeatherBridge.tickWeatherCycle()
  │          ├→ 选取 1 个 biome（轮转）
  │          ├→ 采样 TFC 温度 + 降水
  │          ├→ 状态转换（INIT → ACCUMULATING → HOLDING → MELTING → INIT）
  │          │
  │          ├→ ACCUMULATING 状态：
  │          │   ├→ 若 biomeDepth==0: referenceTemp = 采样温度（冻结锚点）
  │          │   └→ 每 120 tick (6秒, 默认10分钟/100) biomeDepth += 1
  │          │      └→ 仅在降雪+低温时累积
  │          │
  │          ├→ HOLDING 状态：
  │          │   └→ 雪深保持不变（低温但无降水）
  │          │
  │          ├→ MELTING 状态：
  │          │   └→ 每 tick biomeDepth -= 1（受 snowMeltTransitionMinutes 控制）
  │          │      └→ depth < 3 → 重置为 INIT，refTemp 解冻
  │          │
  │          ├→ INIT 状态：
  │          │   └→ refTemp 跟踪当前温度（为 DH/Voxy 提供基准）
  │          │
  │          └→ 每 100 tick：同步 biome 雪深到客户端
  │
  ├─ 2. Per-block 雪花（chunk 加载 + tickChunk）：
  │     ├→ calcEffectiveDepth(actualTemp, precip, biomeDepth, referenceTemp)
  │     │    ├→ biomeDepth==0 + 无降水 → 0（冷无雪不积雪）
  │     │    ├→ biomeDepth==0 + 降水 → tempToSnowDepth(shiftedTemp)（初雪头起）
  │     │    ├→ biomeDepth>0 + 低温 → min(100, biomeDepth + tempContribution)
  │     │    └→ biomeDepth>0 + 零上 → biomeDepth（缓慢递减融化）
  │     └→ shouldSnow(effectiveDepth, seed) → set/clear FLAG_SNOW
  │
  └─ 3. DH/Voxy 远景
       ├→ DhLevelWrapperMixin: getBiomeDepthAt + getReferenceTempAt → calcEffectiveDepth
       └→ MapCheckerMixin: 同上
```

### 8.2 融雪流程（v2）

```
TFC 温度升高（超过 snowMeltTemp，默认 0°C）
  │
  ├─ 1. WeatherBridge.tickWeatherCycle()
  │     ├→ 状态转换：ACCUMULATING/HOLDING → MELTING
  │     ├→ biomeDepth 缓慢递减（受 snowMeltTransitionMinutes 控制，默认 5 分钟/100）
  │     ├→ depth < 3 → 重置为 INIT（refTemp 解冻，跟踪当前温度）
  │     └→ calcEffectiveDepth 在融化期间返回 biomeDepth（纯累积值，无温度贡献）
  │
  ├─ 2. tickChunk per-block 融化：
  │     ├→ WeatherManagerMixin.getSnowStatusImpl: temp > meltTemp + biomeDepth > 0 → SNOW_MELT
  │     ├→ SnowyStatusKeeperMixin: depth = calcEffectiveDepth → biomeDepth（当前递减值）
  │     └→ shouldSnow(depth, seed) → 随着 depth 减小，越来越多的方块失去雪标志
  │
  └─ 3. chunk 首次加载 → loadingChunk=true → calcEffectiveDepth 返回 biomeDepth
```

### 8.3 biome 雪深数值同步

```
服务端 WeatherBridge.tickWeatherCycle()
  ├→ biomeWeather.setSnowDepth(currentDepth ± 1)
  └→ 每 100 tick：WeatherManager.sendBiomePacket(level, biomes, players)
       └→ BiomeWeatherMessage(snows[], special[], weather[])
            └→ 客户端 NetworkUtil.handle → processBiomeWeatherMessage
                 └→ NetworkUtilMixin: System.arraycopy → SnowDepthCache.DEPTHS
                      └→ ExtraModelManagerMixin.getBiomeDepth() 读取
```

### 8.4 禁用 TFC 物理积雪

```
TFC WeatherHelpers.onTickChunk(level, chunk)
  → 尝试放置 SnowPileBlock / IcePileBlock / IcicleBlock
  → WeatherHelpersMixin HEAD cancel → 阻止
  → 转而由 ES SnowyStatusKeeper 提供纯视觉积雪
```

---

## 9. 各模组间的关键接口依赖

| 来源模组 | 类/方法 | estfc 用途 |
|----------|---------|------------|
| **TFC** | `Climate.get(level)` | 获取 ClimateModel |
| | `ClimateModel.getInstantTemperature(level, pos)` | per-position 温度 |
| | `ClimateModel.getRain(calTicks)` | 日历降水强度 |
| | `ClimateModel.getInstantRainfall(level, pos)` | per-position 降水 |
| | `ClimateModel.getAverageTemperature(level, pos)` | avgSeaLevelTemp 采样 |
| | `Calendars.get(level).getCalendarTicks()` | 日历 tick |
| | `ICalendar.getMonthOfYear()` / `getFractionOfMonth()` | TFC→ES 节气映射 |
| | `WeatherHelpers.isPrecipitating(rainIntensity, rainfall)` | 降水判断 |
| | `WeatherHelpers.getPrecipitationAt(level, pos, fallback)` | 雨/雪类型 |
| | `WorldTracker.get(level).isWeatherEnabled()` | 天气是否启用 |
| | `ChunkData.get(level, pos)` | 安全检查：TFC 区块数据就绪 |
| | `ConnectedGrassBlock.SNOWY/NORTH/EAST/SOUTH/WEST` | 覆雪模型 |
| | `SolarCalculator.getInNorthernHemisphere(z, scale)` | 南半球季节翻转 |
| **ES** | `WeatherManager.getBiomeList(level)` | biome 列表 |
| | `WeatherManager.BiomeWeather.getSnowDepth()/setSnowDepth()` | biome 雪深读写 |
| | `WeatherManager.getSnowStatus(level, biome, pos, rain)` | 雪状态（→已劫持） |
| | `WeatherManager.sendBiomePacket()` | 同步到客户端 |
| | `SnowyStatusKeeper.set(pos, flag)` / `isSnowyBlock(pos)` | 方块雪标志 |
| | `SnowyStatusKeeper.tickChunk()` | 逐 tick 雪更新（→已取消） |
| | `SnowyMapChecker.updateAllChunks(level)` | 全区块刷新（→被定时调用） |
| | `SnowyMapChecker.forceChunkUpdateHeight()` | 单 chunk 刷新 |
| | `SnowyMapChecker.getSnowyStatusKeeper(chunk)` | 获取 chunk 雪数据 |
| | `SnowyMapChecker.getWeatherStatusKeeper(chunk)` | 获取 chunk 天气数据 |
| | `MapChecker.getDefaultBlockTypeFlag(state)` | 方块类型标记 |
| | `MapChecker.getChunkInfoMapOrCreate(level, pos)` | chunk 高度图 |
| | `MapChecker.shouldSnowAtBiome()` | Voxy 远景判断（→已劫持） |
| | `EclipticUtil.canSnowyBlockInteract()` | 是否启用雪视觉（→强制 true） |
| | `IBiomeWeatherProvider.es$get()` / `es$getTickBiome()` | biome 轮转访问 |
| | `ClientCon.nowSeason` / `nowSolarTerm` | 南半球翻转目标 |
| | `IESReplaceModel.isReplace()` | 模型替换标记 |
| | `SnowDefinition` / `ESClientDataMapProvider` | 数据生成 |
| **DH** | `ClientLevelWrapper_neoforge.getBlockColor()` | 远景 LOD 颜色（→劫持 white blend） |
| | `DhBlockPos` / `IBlockStateWrapper` / `FullDataSourceV2` | DH 数据结构 |

---

## 10. 线程安全说明

- 所有服务端逻辑通过 `tickWeatherCycle` → `updateAllChunks` → `forceChunkUpdateHeight` 在主服务器线程执行
- TFC `ClimateModel.getInstantTemperature()` 是纯计算函数（基于世界种子+位置），线程安全
- `TfcUnloadedTempEstimator` 的 `zAvgTempCache` 仅在客户端线程写入，`TreeMap` 非线程安全是安全的
- `WeatherBridge.isPeriodicRefresh` 是 `boolean`，Java 对其读写是原子的（仅主线程写）
- `SnowDepthCache.DEPTHS` 在客户端网络线程写入、渲染线程读取，`byte[]` 的单元素读写是原子的

---

## 11. 配置回退

`snowRefreshInterval = 0` 时完整回退到旧行为：
- `WeatherBridge` 跳过 `updateAllChunks` 调用
- `SnowyStatusKeeperMixin` 不取消 `tickChunk`
- `SnowyMapCheckerMixin` 按旧逻辑（非 loadingChunk 仅融雪）
- `ServerLevelMixin` 的额外 tickChunk 调用恢复作用
