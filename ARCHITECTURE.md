# estfc 架构文档

将 TFC 群峦的气候系统（per-position 温度/降水）桥接到 ES 节气的覆雪视觉系统（per-block 雪标志 + 覆雪模型 + DH/Voxy 远景）。

---

## 0. 仓库布局

```
shared/resources/        手写资源（贴图、lang、数据包标签、ES 模型定义）——两版共用
shared/generated/        生成资源（snow_definitions / model_definitions / snowy_empty.json）——两版共用
shared/png_snowy*/       贴图源文件
v1_21_1-neoforge/        NeoForge 1.21.1 构建（Java 21，ModDevGradle，Gradle 9.x）
v1_20_1-forge/           Forge 1.20.1 构建（Java 17，ForgeGradle 6，自带 Gradle 8.8 wrapper）
```

每个版本是**独立的 Gradle 构建**（ForgeGradle 6 与 ModDevGradle 不能同构建共存），根目录只放 wrapper 与文档。

构建：两个版本都 `cd <版本目录> && ./gradlew build`，各用自带 wrapper（1.21.1 = Gradle 9.2.1，1.20.1 = 8.8）。

下面 §1 起描述的是**两版共用的设计**；各版本的 API 差异集中在三处：TFC 气候/天气读取（`ClimateQuery`）、loader 粘接（配置/网络/配置界面）、DH 适配。

### 1.20.1 侧的已知差异

| 处 | 1.21.1 | 1.20.1 |
|---|---|---|
| 气候模型 | `Climate.get(level)` + `supportsRain()` | `Climate.model(level)` + `instanceof OverworldClimateModel`（收敛在 `ClimateQuery.getModelIfActive`） |
| 瞬时温度 / 降水 | `model.getInstantTemperature` / `getInstantRainfall` | `Climate.getTemperature` / `Climate.getRainfall`（见 `ClimateQuery`） |
| 是否降水 | `WeatherHelpers.isPrecipitating(intensity, rainfall)` | `WorldTracker.isRaining(level, pos)` |
| 半球 | `SolarCalculator.getInNorthernHemisphere(z, model.hemisphereScale())` | TFC 1.20.1 无此概念 → `HemisphereSeasons.isNorthernHemisphere(z, Config.hemisphereScale)`（公式照搬 1.21） |
| 阻止物理雪 | 取消 `WeatherHelpers.onTickChunk` | 取消 `EnvironmentHelpers.tickChunk`（`EnvironmentHelpersMixin`）+ 拦 TFE 的 `NTEWeatherHelpers.onTickChunk`（`estfc.tfe.mixins.json`） |
| 实时逐块覆雪驱动 | ES 自驱：它在 `ServerLevel.tickChunk` 里包 `getBlockRandomPos`（ordinal 1）= `tickPrecipitation` 循环，正常执行 | **estfc 自驱 `ChunkSnowTicker`**：ES 包的 ordinal 1 在 MC 1.20.1 指向 `iceandsnow` 块，而 TFC 的 `ServerLevelMixin` 把该块守卫 `nextInt(16)` 强改成 1 → 整块不执行。TFC 那条 `EnvironmentHelpers.tickChunk` TAIL 路径实测也不执行 |
| 世界生成的物理雪 | 取消 `WeatherHelpers.onTickChunk` 即可（无世界生成铺雪） | 拦两处：`ClimateMixin` 取消 `Climate.onChunkLoad`（TFC 自己在冷区按温度+噪声铺 `minecraft:snow`/冰，入口是 TFC 自己的方法名，能稳定命中）+ `SnowAndFreezeFeatureMixin` 取消 vanilla 的 `minecraft:freeze_top_layer`。**注意**：`IceAndSnowFeature.place` 覆写的是 MC 的 `Feature.place`，生产 jar 里叫 `m_142674_`，用字面量 `place` + `remap=false` 的 mixin 永远命不中 |
| 天气循环驱动 | 注入 `WeatherHelpers.advanceWeatherCycle` RETURN | 监听 `TickEvent.LevelTickEvent` END（入口注册） |
| 区域气候点生成 | `chunkDataGenerator().generate(data)` | `chunkDataProvider().createAndGeneratePartial(pos)` |
| `ChunkData` 字段 | `getAverageSeaLevelTemp` / `getAverageRainfall` | `getAverageTemp` / `getRainfall` |
| 网络 | NeoForge payload（`RegisterPayloadHandlersEvent`） | Forge `SimpleChannel`（`RegionClimatePayloads.CHANNEL`） |
| 配置界面 | 自定义 `EstfcConfigScreen` | **用 Forge 自带界面**（1.20.1 缺 `AbstractContainerWidget`，自定义滚动布局未移植） |
| 方块 ID 查询 | `state.getBlockHolder().getKey()` | `BuiltInRegistries.BLOCK.getKey(state.getBlock())` |
| 命名空间 | `tfc` | `tfc` + `tfe`（TerraFirmaEarth 向下移植模组，见 `Config.blockNamespaces`） |

### 1.20.1 工具链约束（不要"升级"）

- **Gradle 必须是 8.8**：ForgeGradle 6.0.33 在 Gradle 8.14+ 上会报
  `GradleRepositoryAdapter$1 does not define ... isContinueOnConnectionFailure()`。
- **JDK 必须是 17**（已由 `org.gradle.java.home` 钉在 `v1_20_1-forge/gradle.properties`）：
  Gradle 8.8 的 Groovy 解析不了 Java 25 编出的 class 文件（`Unsupported class file major version 69`）。
- Parchment 映射依赖 `org.parchmentmc:librarian` 插件（`buildscript` classpath + `apply plugin`），
  缺了会报 `Unknown mapping provider: parchment_...`。
- 反向的坑：**1.21.1 别用 8.x wrapper**。8.13 上 MDG 2.0.141 会在创建 `:test` 任务时炸
  （`Could not create task ':test' … Type T not present`）。两个模块各钉 9.2.1 / 8.8，互不借用。

### Forge 1.20.1 必须有 pack.mcmeta

NeoForge 1.21.1 对缺 `pack.mcmeta` 的模组 jar 会自动补一个；**Forge 1.20.1 不会**，
缺了直接报「未能为 estfc 加载有效资源包信息」并拒绝加载模组。

`pack.mcmeta` 放**各版本目录**（`pack_format` 随 MC 版本变，不能放 `shared/`）：

| 版本 | pack_format |
|---|---|
| 1.20.1 | 15 |
| 1.21.1 | 34 |

### 1.20.1 的物理雪来自三处，都要拦

运行期：

- **TFC 本体**：`EnvironmentHelpers.tickChunk` → 由 estfc 的 `EnvironmentHelpersMixin` HEAD 取消。
- **TFE（TerraFirmaEarth，可选）**：它自己也注入 `EnvironmentHelpers.tickChunk` 的 HEAD 并 cancel，
  在里面调用 `NTEWeatherHelpers.onTickChunk` 放雪。**同一注入点的多个 handler 都会执行，
  `ci.cancel()` 只取消目标方法体、不跳过其它 handler —— 给 mixin 提优先级没用**。
  必须单独拦 `NTEWeatherHelpers.onTickChunk`（`mixin/tfe/`，放在 `estfc.tfe.mixins.json`，
  `required:false`）。

世界生成（运行期怎么拦都没用，只在新区块出现）：

- **TFC 自己**：`Climate.onChunkLoad` → `OverworldClimateModel.onChunkLoad`，按
  `snowPatchNoise + 温度` 给地表放 `minecraft:snow`、给水面放冰 —— 判据**与群系无关**，
  所以「任何群系只要温度低」都会出现。由 `ClimateMixin` 在 HEAD 取消。
- **vanilla**：`minecraft:freeze_top_layer`（`SnowAndFreezeFeature`），由
  `SnowAndFreezeFeatureMixin` 取消（MC 类，用默认 remap 走 refmap）。

### 绝不在区块加载回调里读 TFC 气候（1.20.1 会自死锁）

1.20.1 的 `Climate.getTemperature/getRainfall` → `ChunkData.get` → `Level.getBiome` →
`Level.getChunk(load=true)` → 把加载任务提交给主线程并 park 等待。

ES 的 `SnowyMapChecker.forceChunkUpdateHeight` 是从 `ChunkMap` 的区块加载 future 回调触发的，
**跑在主线程上**。在那里读气候 = 主线程等自己 = 永久卡在「加载世界 0%」，无异常无日志。

**规则：**
- 注入点只入队，真正的刷新放服务端 tick（`ChunkSnowScheduler`，`LevelTickEvent` END 相位）。
- 取群系必须用 `chunk.getNoiseBiome(QuartPos...)`，**不要用 `level.getBiome(pos)`**。
- `ChunkSnowRefresh.prepare` 已删除 —— 那正是踩雷的实现，别再写回来。

（1.21.1 没这个问题：那边 TFC 的瞬时温度不走 ChunkData。）

### 校验生成数据（1.20.1）

`verifySnowBlockIds` 任务**解析太粗**（只认 `register("...", ...)` 字面量，漏掉 `Helpers.mapOf` 拼接注册的 berry bush、`wood/fallen_leaves`、`wood/twig`），会报大量假阳性。要可信结果，用 TFC jar 的 blockstate 列表比对：

```bash
# 从 TFC jar 抽方块 ID
unzip -l <TFC jar> | grep "assets/tfc/blockstates/" | sed 's|.*blockstates/||;s|\.json$||' | sort -u > /tmp/tfc_blocks.txt
# 从生成的雪定义抽引用的方块 ID
grep -ho '"tfc:[^"]*"' shared/generated/resources/data/estfc/eclipticseasons/snow_definitions/*.json \
  | tr -d '"' | sed 's/^tfc://' | sort -u > /tmp/defs.txt
comm -23 /tmp/defs.txt /tmp/tfc_blocks.txt   # 输出 = 该版本 TFC 里不存在的 ID
```

2026-09-10 实测：170 条定义在 TFC 1.21.1 全部存在；在 TFC 1.20.1 缺 8 条 ——
`rock/loose/tuff`、`rock/mossy_loose/tuff`（1.21 新增岩石）与
`wild_crop/{alfalfa,canola,cassava,lentil,peanut,radish}`（TFE 补的 6 个作物）。

⚠ **这 8 条不是"无害的死条目"——它们会让游戏崩。** `snow_definitions` 是 ES 的
**数据包注册表**，引用不存在的方块 → 整个注册表解析失败 →
点「单人游戏」时 `RegistryDataLoader` 抛 `Failed to load registries due to above errors` 直接崩。
所以 1.20.1 的 build 里加了 `filterEstfcGeneratedData` 任务，按构建期 TFC jar 的
blockstates 把这类条目过滤掉（输出到本工程 `build/filtered-generated/`，不污染 `shared/`）。
**新增生成器条目后要重建一次，确认输出里 `dropped=N` 符合预期。**

---

## 1. 包结构

```
me.xuan.estfc/
├── EclipticSeasonsTFCrepair.java   — @Mod 入口
├── Config.java                     — NeoForge ModConfigSpec（7 个编号分区）
├── EstfcTags.java                  — estfc:simple_snow / estfc:snowable 标签常量
│
├── weather/
│   ├── ClimateQuery.java           — 对 TFC/ES 状态的受保护读取 (getModelIfActive, isPrecipitating×3, getBiomeDepthAt)
│   ├── SnowDepthRules.java         — 纯函数 (tempToSnowDepth, calcEffectiveDepth, depthFromTemp, shouldSnow)
│   ├── WeatherCycleService.java    — tick 主循环 (tickWeatherCycle, 按维度分键的 lastStepTick, 无条件推进防积压)
│   ├── ChunkSnowRefresh.java       — 区块加载/更新覆雪刷新 (prepare, columnTemperature, applyOnLoad, applyMeltOnly)
│   ├── SnowDefinitionRegistrar.java— 运行时补齐 datapack 未覆盖的 TFC 方块雪定义 (服务端+客户端分类)
│   ├── SolarTermMapper.java        — TFC 月份 → ES 节气索引公式
│   └── HemisphereSeasons.java      — 南半球季节/节气翻转
│
├── util/
│   └── BlockPatternMatcher.java    — 配置条目匹配 (前缀/完整ID/正则, ConcurrentHashMap 正则缓存)
│
├── mixin/                          — 与注入目标 1:1, 业务逻辑在 weather/ 与 client/
│   ├── WeatherHelpersMixin         — [TFC] 取消onTickChunk, advanceWeatherCycle TAIL→WeatherCycleService
│   ├── WeatherManagerMixin         — [ES] getSnowStatus劫持, agentAdvanceWeatherCycle取消
│   ├── SnowyStatusKeeperMixin      — [ES] tickChunk HEAD节流 + getSnowDepthAtBiome→getPerBlockDepth
│   ├── SnowyMapCheckerMixin        — [ES] forceChunkUpdateHeight TAIL→ChunkSnowRefresh
│   ├── SnowCheckerMixin            — [ES] resetUpdate RETURN→SnowDefinitionRegistrar（运行时补 TFC 雪定义）
│   ├── SnowModelMixin              — [ES] getSnowyModel TAIL→SnowTextureSwapModel; clearForRebaked→程序化树叶SnowDefinition
│   ├── ExtraModelManagerMixin      — [ES] maySnowyAt RETURN回退
│   ├── MapCheckerMixin             — [ES] shouldSnowAtBiome HEAD→DistantSnowService.calcSnowDepthClient（voxy 远景接线点）
│   ├── ClientConMixin              — [ES] tick TAIL→HemisphereSeasons
│   ├── ConnectedGrassBlockMixin    — [TFC] 禁用连接纹理方向
│   ├── EclipticUtilMixin           — [ES] canSnowyBlockInteract→强制true
│   └── SolarDataManagerMixin       — [ES] getSolarTermIndex→SolarTermMapper
│
├── client/
│   ├── DhSnowService.java          — DH 远景覆雪 (DhApiBlockColorOverrideEvent, 区域海拔, 需 ES DH兼容关闭)
│   ├── DistantSnowService.java     — voxy 远景覆雪 (calcSnowDepthClient=温度 vs 群系累积深度取大)
│   ├── DistantTemperature.java     — 远景温度（avgSeaLevelTemp+月温±海拔，无日温；月温/海拔复用 TFC @Invoker）
│   ├── RegionClimateCache.java     — 区域气候点（温度+高度）客户端缓存/请求/双线性/预取/自检
│   ├── SnowTextureSwapModel.java   — 覆雪BakedModel (tfc:→estfc:贴图替换, IESReplaceModel)
│   ├── BakedQuadTextureOnly.java  — 保留UV的贴图替换
│   └── gui/
│       ├── EstfcConfigScreen.java — 配置界面(HeaderAndFooterLayout+GridLayout)
│       └── ScrollableLayout.java  — 可滚动布局
│
└── data/
    ├── DataGenerators.java         — GatherDataEvent注册
    └── TfcSnowDefinitionProvider.java — ES SnowDefinition生成(TFC方块)

net/
└── RegionClimatePayloads.java      — 区域气候点（温度+表面高度+年均降水）请求/响应 + 服务端确定性生成
                                     （ChunkGeneratorExtension.chunkDataGenerator + TFC getBaseHeight）
```

## 2. 核心公式

### 2.1 tempToSnowDepth

```
tempToSnowDepth(temp) = (appear - temp) / (appear - full) * 100
  appear = snowAppearanceTemp (默认 0°C)
  full   = snowFullCoverageTemp (默认 -4°C)
  temp > appear → 0
  temp < full   → 100
```

例: 0°C→0, -2°C→50, -4°C→100

### 2.2 calcEffectiveDepth — 所有路径统一

```java
// weather/SnowDepthRules.java
public static int calcEffectiveDepth(float actualTemp, int biomeDepth, float rainfall) {
    int ramp = tempToSnowDepth(actualTemp);
    int depth = ramp == 100 ? 100 : (biomeDepth <= 0 ? ramp : Math.min(biomeDepth, ramp));
    return (int) (depth * rainfallFactor(rainfall));   // 降水系数：<noSnow→0，>fullSnow→1，之间线性
}
// 零上恒 0（带融雪门槛的入口，所有路径共用）
public static int depthFromTemp(float actualTemp, int biomeDepth, float rainfall) {
    return actualTemp > Config.snowMeltTemp() ? 0 : calcEffectiveDepth(actualTemp, biomeDepth, rainfall);
}
```

语义：≤-4°C 满雪；过渡带（-4..0）内**有积雪**时覆雪率 = min(累积量, 温度斜坡)
——下雪后回温覆雪率跟随温度（-1°C→25%，-2°C→50%），累积量只作上限；
**干冷**（无累积）时温度斜坡兜底（干冷地区也有覆雪）。
最后乘**降水系数**（年均降水 < snowRainfallNoSnow(80mm) → 无雪；> snowRainfallFullSnow(150mm) → 不影响；
之间线性过渡，如 115mm → 50%）。降水量：加载视图用 `model.getAverageRainfall`，
远景用区域网格降雨层（`RegionClimatePayloads` 同步）。

| actualTemp | biomeDepth=0 | biomeDepth=30 | biomeDepth=80 |
|------------|-------------|---------------|---------------|
| 2°C | 0 | 0 | 0 |
| 0°C | 0 | 0 | 0 |
| -1°C | 25 | 25 | 25 |
| -2°C | 50 | 30 (累积上限) | 50 |
| -4°C | 100 | 100 | 100 |

### 2.3 shouldSnow

```java
public static boolean shouldSnow(int depth, long seed) {
    return depth > Math.abs(seed % 100);
}
```

`depth=N` → 约 N% 的方块覆雪（均匀随机分布）。

---

## 3. 核心方法详解（weather/ 与 client/）

### 3.1 WeatherCycleService.tickWeatherCycle — biomeDepth 累积/融雪

**注入点**: `WeatherHelpersMixin` → TFC `advanceWeatherCycle` TAIL
**频率**: 每 game tick 1 次, 处理 1 个 biome (轮转)
**biome深度存储**: ES `WeatherManager.BiomeWeather.snowDepth` (0-255, 取低8位)

```
每 tick:
  1. getTickBiome() → 取当前处理的 biome
  2. 采样玩家位置温度+降水
  3. 绝对时间累积/递减:
     elapsed = gameTime - lastStepTick[index]
     if 降水+低温: gain = elapsed / interval; biomeDepth += gain (封顶100)
     if 零上:      loss = elapsed / interval; biomeDepth -= loss (下限0)
  4. 滑动窗口: lastStepTick[index] += gain/loss * interval (剩余时间保留)
  5. 每 biomeSyncIntervalTicks(100): 同步所有biome深度到客户端
```

**interval 来源**: `Config.tickChunkIntervalTicks()` (默认 10)
- biome 数量不影响速度 (轮转 170 biomes 也每 10tick +1)
- 10 tick → 100步×10=1000tick≈50秒满雪

**lastStepTick 存储**: `Map<ResourceKey<Level>, long[]>`，按维度分键，数组按实际
群系表长度扩容。历史版本是全局 `long[256]`——群系注册表可远超 256（vanilla ~64
+ TFC 127 ≈ 190，再装生物群系模组即越界），越界异常被空 catch 吞掉导致积雪静默
停止；且各维度共用一个数组互相破坏时间戳。均已修复。

### 3.2 getSnowStatus — per-block雪状态决策

**注入点**: `WeatherManagerMixin` → ES `getSnowStatus` (4参数版) HEAD cancel

```java
if (temp > meltTemp)    → SNOW_MELT   // 零上 → 融雪
else if (precipitating) → SNOW        // 降水+冷 → 积雪
else                    → NONE        // 冷无降水 → 保持
```

ES的 `SnowyStatusKeeper.tickChunk` 根据返回状态决定: SNOW→尝试设FLAG_SNOW, MELT→尝试清FLAG_NONE, NONE→不变。

### 3.3 降水检测 (ClimateQuery, 三个重载)

| 重载 | 用途 | 线程 |
|------|------|------|
| `isPrecipitating(Level)` | 客户端日历级(缓存同日历tick, 不可变快照+volatile) | 渲染/DH worker |
| `isPrecipitating(ServerLevel, BlockPos)` | 服务端per-position | 主服务端线程 |
| `isPrecipitating(ClimateModel, ServerLevel, BlockPos)` | 重用已有model | 主服务端线程 |

降水条件: `WeatherHelpers.isPrecipitating(rainIntensity, rainfall) > 0`
- `rainIntensity = model.getRain(calendarTicks)` (TFC日历降水强度)
- `rainfall = model.getInstantRainfall(level, pos)` (per-position降水)

### 3.4 远景 (DH/Voxy) — client/DistantSnowService

**两条路径**（决策公式相同，hook 点不同）：

```
DH:   estfc DhSnowService — DH 公共 API DhApiBlockColorOverrideEvent（每数据点触发）
        └─ 需要 ES DistantHorizonsWinterLOD=false（ES @WrapOperation 会短路 getBlockColor）
voxy: ES-Voxy-Compact MixinModelFactory → VoxyTool.changeBlockId
        └─ MapChecker.shouldSnowAtBiome → estfc MapCheckerMixin（唯一接线点）
```

**调用链（DH）**: `DhSnowService.maybeOverride(EventParam)` — 事件参数自带位置/群系/方块/
数据源。列内自上而下，**首事件 = 表面数据点**；同一列其余数据点原样返回（列 key
= level + x>>level + z>>level，所有数据点共享；列缓存按世界绑定，换世界清空）。
→ `RegionClimateCache.sampleClimate(x,z)`（温度+区域高度一次采样）
→ `DistantTemperature.distantTemperatureRegional`（avgSeaLevelTemp + 月温 − 区域海拔）
→ `ClimateQuery.getBiomeDepth(level, biome)`（用事件传入的群系，未加载位置
`level.getBiome(pos)` 只有占位群系雪深恒 0）
→ `SnowDepthRules.depthFromTemp` → `shouldSnow(depth, seed)`（逐块抖动，二值输出；
seed 的 y 用区域高度——事件 y 级别相关，直接用会产生级别边界细网格线）

**调用链（voxy）**: `MapCheckerMixin.shouldSnowAtBiome(level, biome, …, mcPos)`
→ `ClimateQuery.getBiomeDepth(level, biome)`
→ `DistantSnowService.calcSnowDepthClient(level, mcPos, biomeDepth)`
→ `SnowDepthRules.shouldSnow(depth, seed)`

**区域海拔（2026-08-22 定稿）**：DH 事件给的 y 不是表面高度——粗级别=列内最高点、
细级别=顶部 merged run 的底部（run 可叠几块高），逐 quad 用不可靠 y 采样海拔 =
quad 尺度噪声 = 网格（完整海拔、按级别分档均已失败）。改用**区域表面高度**：
服务端 `TFCChunkGenerator.getBaseHeight`（复刻世界生成地形表面，无需加载区块）
按 128 格网格同步 → 客户端双线性（`RegionClimatePayloads` 与温度同包）→
`avgSeaLevelTemp + 月温 − 区域海拔`。区域场平滑、级别无关 → 相邻 quad 连续、
无网格；高山（区域尺度）保留雪线。不做覆盖率亚采样（旧方案网格的另一来源）。
voxy 例外：ingest 体素级 y=真实表面，海拔保留。

**远景温度**：`DistantTemperature` = `avgSeaLevelTemp(x,z) + 月温(z) − 海拔(y)`，
只去掉**日温**（±4°C 跨日跳变，LOD 重建才刷新，保留会造成随机变脸）。
月温/海拔公式经 `OverworldClimateModelMixin` @Invoker 复用 TFC 原实现
（`calculateMonthlyTemperature`/`adjustTemperatureByElevation`，日温=0），
避免公式复刻随 TFC 漂移。海拔 y 两个来源：voxy 体素 y（=真实地表）与
DH 区域表面高度（128 格平滑场）。

**avgSeaLevelTemp / 区域高度数据源**：未加载区块 ChunkData.EMPTY 恒 10°C。
服务端按 128 格网格点（`Units.GRID_BITS=7`）用
`ChunkGeneratorExtension.chunkDataGenerator()` + `getBaseHeight` 确定性生成
（纯种子函数、时不变、不加载区块），异步线程池计算（`dhTempServerThreads`/
`dhTempServerSubmitPerTick` 可调），经 `RegionClimatePayloads` 同步，
客户端 `RegionClimateCache` 缓存 + 双线性插值 + 玩家周围预取。
首屏 1-2 秒数据未到时远景先无雪（回退纯 biomeDepth），属预期。

---

## 4. 完整配置表

### 4.1 覆雪温度

| 配置 | 默认 | 范围 | 说明 |
|------|------|------|------|
| `snowAppearanceTemp` | 0.0 | [-20, 30] | 雪开始出现的温度 |
| `snowFullCoverageTemp` | -4.0 | [-50, 20] | 温度满雪线(appear到full=过渡带宽度) |
| `snowMeltTemp` | 0.0 | [-273, 500] | 融雪温度 |
| `snowRainfallNoSnow` | 80 | [0, 500] | 年均降水<此值 → 无雪 |
| `snowRainfallFullSnow` | 150 | [0, 500] | 年均降水>此值 → 覆雪不受降水影响 |
| `dhTempPrefetchRadius` | 1024 | [128, 16384] | [DH/voxy] 区域气候点(128格网格)预取半径 |
| `dhTempRequestsPerTick` | 512 | [1, 2048] | [DH/voxy] 客户端每tick区域点请求上限 |
| `dhTempServerThreads` | 4 | [1, 32] | [DH/voxy] 服务端计算线程数(min(本值,核数)) |
| `dhTempServerSubmitPerTick` | 64 | [1, 512] | [DH/voxy] 每tick提交给服务端线程池的上限 |

### 4.2 速度控制

| 配置 | 默认 | 范围 | 说明 |
|------|------|------|------|
| `tickChunkIntervalTicks` | 10 | [1, 200] | tickChunk处理+biomeDepth步进间隔(唯一速度控制器) |
| `biomeSyncIntervalTicks` | 100 | [20, 1200] | 服务端biomeDepth同步客户端间隔 |

### 4.3 覆雪贴图

| 配置 | 默认 | 说明 |
|------|------|------|
| `snowTextureSimpleMode` | false | 全局开关—所有TFC方块强制简单雪(原版 minecraft:block/snow，材质包可覆盖) |
| `snowTextureReplaceMode` | true | 替换模式(关=ES叠加渲染) |

### 4.4 TFC方块覆雪匹配

| 配置 | 默认 | 格式 |
|------|------|------|
| `tfcSnowBlockPrefixes` | plant/, crop/, groundcover/, wild_crop/, wood/fallen_leaves/, wood/twig/, rock/loose/, rock/mossy_loose/ | 路径前缀/完整ID/正则 → SnowTextureSwapModel 贴图替换 |
| `tfcSimpleSnowBlocks` | groundcover(不含seaweed/driftwood/stick), rock/loose/, rock/mossy_loose/ | 同上 → 全部quad统一snow.png |
| `tfcLeavesSnowBlocks` | regex:plant/.*_leaves | 同上 → ES原生flag 1101双层雪(顶雪+内部雪斑), 程序化注册无需JSON |

正则格式: `regex:pattern` (Pattern 预编译缓存于 `util/BlockPatternMatcher.PATTERN_CACHE`, ConcurrentHashMap; 非法正则缓存为永不匹配哨兵, 不会反复重编译)

### 4.5 SnowModelMixin 分类流程

```
getSnowyModel(state, ...) TAIL
  │
  ├─ ConnectedGrassBlock?
  │   ├─ tfcConnectedTexture=true  → return (保留连接纹理, 无雪)
  │   └─ tfcConnectedTexture=false → 强制SNOWY+断连 → SnowTextureSwapModel(simpleSnow=false)
  │
  ├─ matchesBlockState → BlockPatternMatcher.matches(state, tfcLeavesSnowBlocks)?
  │   └─ return (ES原生 flag 1101 双层雪处理)
  │       ↑ SnowDefinition 由 clearForRebaked RETURN hook 程序化注册
  │
  ├─ isTfcBlock(state)?  → BlockPatternMatcher.matches(state, tfcSnowBlockPrefixes)
  │   ├─ isSimpleSnowBlock(state)?  → 全局开关 || 标签 || BlockPatternMatcher.matches(tfcSimpleSnowBlocks)
  │   │   ├─ true  → SnowTextureSwapModel(simpleSnow=true)  全部quad→snow.png
  │   │   └─ false → SnowTextureSwapModel(simpleSnow=false) tfc→estfc贴图替换
  │   └─ (ConnectedGrassBlock已在上面处理, 此处排除)
  │
  └─ 都不匹配 → return (无雪)
```

### 4.6 配置界面

`EstfcConfigScreen` — 纯原生MC组件, 无前置依赖:
- `HeaderAndFooterLayout`: 标题+5标签栏 / ScrollableLayout滚动内容 / Cancel+Reset+Done
- `buildListEditor`: 可折叠的逐行列表编辑器 (EditBox+删除+添加按钮)
- `resetToDefaults`: `ModConfigSpec.ConfigValue[]` 数组循环 → 新增配置自动重置

### 4.7 其他

| 配置 | 默认 | 说明 |
|------|------|------|
| `tfcConnectedTexture` | false | true→ConnectedGrassBlock保留连接纹理但不覆雪; false→强制断开连接以正确显示雪 |

---

## 5. 数据流详解

### 5.1 积雪全流程

```
TFC advanceWeatherCycle (每tick, 服务端主线程)
  │
  ├─ WeatherHelpersMixin TAIL → WeatherCycleService.tickWeatherCycle()
  │   ├─ 采样玩家位置温度+降水
  │   ├─ 1个biome: biomeDepth += elapsed/interval (绝对时间累积)
  │   └─ 每100tick: sendBiomePacket → 客户端由 ES 原生 processBiomeWeatherMessage
  │      解包进 BiomeWeather (getBiomeDepthAt 实际读取的对象)
  │
  ├─ ES MixinServerLevel (每tick) → SnowyStatusKeeper.tickChunk
  │   └─ SnowyStatusKeeperMixin HEAD: 按tickChunkIntervalTicks节流, 非间隔期cancel
  │   └─ WeatherManager.getSnowStatus → WeatherManagerMixin.estfc$getSnowStatusImpl
  │       └─ temp>0→SNOW_MELT, precip→SNOW, else→NONE
  │   └─ WeatherManager.getSnowDepthAtBiome → SnowyStatusKeeperMixin.replaceDepthForMelt
  │       └─ estfc$getPerBlockDepth → SnowDepthRules.calcEffectiveDepth(temp, biomeDepth)
  │           → shouldSnow(depth, seed) → keeper.set(pos, FLAG_SNOW/FLAG_NONE)
  │
  └─ Chunk加载: SnowyMapCheckerMixin (forceChunkUpdateHeight TAIL) → ChunkSnowRefresh
      ├─ prepare: 中心地表温度 + biomeDepth; 每列: columnTemperature (海拔外推),
      │   colSnowDepth = calcEffectiveDepth(colTemp, biomeDepth)
      ├─ loadingChunk: applyOnLoad (零上→depth=0, 零下→shouldSnow); 否则 applyMeltOnly
      └─ keeper.updateAndSend
```

### 5.2 远景全流程 (客户端)

```
DH:   DhApiBlockColorOverrideEvent（每数据点，列内自上而下）  [要求 ES DistantHorizonsWinterLOD=false]
        └─ DhSnowService.maybeOverride
            ├─ 守卫: 非空气/非液体 + OverworldClimateModel
            ├─ DistantTemperatureRegional(x,z)（区域海拔——128格平滑场，服务端 getBaseHeight 同步）
            ├─ ClimateQuery.getBiomeDepth(level, biome)  ← 用事件传入群系
            ├─ 首事件=表面 → 判定 + 雪色；同列其余数据点原样返回
            └─ shouldSnow(depth, seed) → setColor(雪色)

voxy: ES-Voxy-Compact MixinModelFactory → VoxyTool.changeBlockId（守卫: flag/skyLight/上方方块）
        └─ MapChecker.shouldSnowAtBiome ─┐
                                         │
                                         ▼
      MapCheckerMixin.shouldSnowAtBiome HEAD cancel
        ├─ 仅客户端处理 (ServerLevel→return)
        ├─ ClimateQuery.getBiomeDepth(level, biome)  ← 用传入群系
        ├─ calcSnowDepthClient
        │   ├─ DistantTemperature.distantTemperature(含海拔, voxy 体素 y=真实地表)
        │   │   ├─ RegionClimateCache.avgSeaLevelTemp(x,z) ← 服务端同步的128格网格
        │   │   │     （RegionClimatePayloads: ChunkGeneratorExtension 确定性生成 + getBaseHeight）
        │   │   └─ + 月温(z)（无日温）
        │   ├─ 数据未同步 → 回退 biomeDepth
        │   └─ SnowDepthRules.depthFromTemp(temp, biomeDepth)
        └─ shouldSnow(depth, seed) → boolean
```

---

## 6. Biome深度查找

`ClimateQuery.getBiomeDepth(level, biome)`: 直接调 ES 原生
`WeatherManager.getBiomeWeather(Level, Biome)` —— O(1)，内部走
`eclipticseasons$getBindId()` 带边界检查的 `list.get(id)`，与 ES 自己的
`getSnowDepthAtBiome`/`getPrecipitationAt` 同源，无本地缓存。

`getBiomeDepthAt(level, pos)` 是按坐标回查群系的便捷重载，**只能用于已加载区块**。
远景路径必须用 `getBiomeDepth(level, biome)` 传入调用方给的群系，否则
`level.getBiome(pos)` 对未加载位置返回占位群系，雪深恒为 0（累积雪深失效，
退化成纯温度判定）。

调用方:
- `SnowyStatusKeeperMixin.getPerBlockDepth` — tickChunk中per-block（按坐标）
- `ChunkSnowRefresh.prepare` — chunk加载 per-chunk（按坐标）
- `WeatherManagerMixin.getSnowStatusImpl` — per-block（按坐标）
- `ExtraModelManagerMixin.maySnowyAt` — 客户端回退（按坐标）
- `MapCheckerMixin` — voxy 远景 per-block（**按传入群系**）
- `DhSnowService` — DH 远景 per-数据点（**按事件传入群系**，TTL 缓存）

---

## 7. 线程模型

| 路径 | 线程 |
|------|------|
| WeatherCycleService.tickWeatherCycle | 主服务端线程 |
| tickChunk (SnowyStatusKeeperMixin throttle) | 主服务端线程 |
| forceChunkUpdateHeight (SnowyMapCheckerMixin → ChunkSnowRefresh) | 主服务端线程 |
| getSnowStatus (WeatherManagerMixin) | 主服务端线程 |
| ClientConMixin.tick | 客户端线程 |
| SnowModelMixin.getSnowyModel | 客户端渲染线程 |
| DhSnowService (DhApiBlockColorOverrideEvent) | DH LOD 构建线程池（并发，列缓存 ConcurrentHashMap） |
| MapCheckerMixin (经 VoxyTool) | voxy ingest/worker 线程（并发） |
| RegionClimateCache | 并发: 请求集合 worker 写 / 客户端 tick 线程 drain 发包 (ConcurrentHashMap + newKeySet) |
| RegionClimatePayloads 服务端生成 | 服务端主线程入队（ServerTickEvent.Post 限流），计算在 estfc-region-climate 线程池（daemon），回主线程发包 |
| ClimateQuery.isPrecipitating(Level) 缓存 | 并发: 区块编译线程 (volatile 不可变快照) |
| BlockPatternMatcher regex 缓存 | 并发: DH workers + 渲染线程 (ConcurrentHashMap) |

---

## 8. TFC 气候接口依赖

| 接口 | 用途 | estfc调用位置 |
|------|------|--------------|
| `Climate.get(level)` | 获取ClimateModel | getModelIfActive |
| `ClimateModel.getInstantTemperature(level, pos)` | per-position温度 | tickWeatherCycle, getSnowStatusImpl, SnowyMapCheckerMixin |
| `ClimateModel.getRain(calTicks)` | 日历降水强度 | isPrecipitating |
| `ClimateModel.getInstantRainfall(level, pos)` | per-position降水 | isPrecipitating |
| `Calendars.get(level).getCalendarTicks()` | 日历tick | isPrecipitating |
| `WeatherHelpers.isPrecipitating(intensity, rainfall)` | 降水判断 | isPrecipitating |
| `WorldTracker.get(level).isWeatherEnabled()` | 天气开关 | getModelIfActive |

### TFC 原版积雪常量 (参考)

```
TICKS_PER_SNOW_ACCUMULATION = 80                     — 平均80tick放一层雪
TICKS_PER_SNOW_MELT = 240                            — 平均240tick融一层
advanceWeatherCycle: rainLevel ±0.01/tick            — 100tick渐变
onTickChunk: 每1/80概率→检查温度<-2+降水→handleSnowAccumulation
             每1/3概率(联合1/80)→检查温度>2→handleSnowMelting
```