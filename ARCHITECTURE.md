# estfc 架构文档

将 TFC 群峦的气候系统（per-position 温度/降水）桥接到 ES 节气的覆雪视觉系统（per-block 雪标志 + 覆雪模型 + DH/Voxy 远景）。

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
│   ├── SnowDepthRules.java         — 纯函数 (tempToSnowDepth, calcEffectiveDepth, shouldSnow)
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
│   ├── MapCheckerMixin             — [ES] shouldSnowAtBiome HEAD→DistantSnowService.calcSnowDepthClient(群系深度)
│   ├── DhLevelWrapperMixin         — [DH] getBlockColor RETURN→DistantSnowService.snowColorFor（唯一允许出现 com.seibel.* 的文件, @Pseudo + require=0）
│   ├── ClientConMixin              — [ES] tick TAIL→HemisphereSeasons
│   ├── ConnectedGrassBlockMixin    — [TFC] 禁用连接纹理方向
│   ├── EclipticUtilMixin           — [ES] canSnowyBlockInteract→强制true
│   └── SolarDataManagerMixin       — [ES] getSolarTermIndex→SolarTermMapper
│
├── client/
│   ├── DistantSnowService.java     — DH/Voxy 远景覆雪 (calcSnowDepthClient=群系累积深度, snowColorFor, blendToWhite)
│   ├── SnowTextureSwapModel.java   — 覆雪BakedModel (tfc:→estfc:贴图替换, IESReplaceModel)
│   ├── BakedQuadTextureOnly.java  — 保留UV的贴图替换
│   ├── TfcUnloadedTempEstimator.java — 未加载区块温度修正
│   └── gui/
│       ├── EstfcConfigScreen.java — 配置界面(HeaderAndFooterLayout+GridLayout)
│       └── ScrollableLayout.java  — 可滚动布局
│
└── data/
    ├── DataGenerators.java         — GatherDataEvent注册
    └── TfcSnowDefinitionProvider.java — ES SnowDefinition生成(TFC方块)
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
public static int calcEffectiveDepth(float actualTemp, int biomeDepth) {
    return Math.min(100, Math.max(biomeDepth, tempToSnowDepth(actualTemp)));
}
```

（历史版本有第二个 `precipitating` 参数，从未被使用，已删除。）

温度提供**地板**（更冷=更高地板），biomeDepth 超过地板后接管。

| actualTemp | biomeDepth=0 | biomeDepth=30 | biomeDepth=80 |
|------------|-------------|---------------|---------------|
| 2°C | 0 | 30 (仅累积) | 80 |
| 0°C | 0 | 30 | 80 |
| -2°C | 50 | 50 | 80 |
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

**interval 来源**: `Config.tickChunkIntervalTicks()` (默认 20)
- biome 数量不影响速度 (轮转 170 biomes 也每 20tick +1)
- 20 tick → 100步×20=2000tick≈1.7分钟满雪

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

**调用链**: `MapCheckerMixin.shouldSnowAtBiome` / `DhLevelWrapperMixin.getBlockColor`
→ `ClimateQuery.getBiomeDepthAt(level, pos)` (ES 原生 O(1) 查询)
→ `DistantSnowService.calcSnowDepthClient(level, model, pos, biomeDepth)`

```java
public static int calcSnowDepthClient(Level level, ClimateModel model, BlockPos pos, int biomeDepth) {
    float temp = TfcUnloadedTempEstimator.getCorrectedTemperature(level, model, pos);
    if (temp > Config.snowMeltTemp()) return 0;  // 零上→无雪(远景无tickChunk融雪)
    return SnowDepthRules.calcEffectiveDepth(temp, biomeDepth);
}
```

DH 着色由 `snowColorFor(level, model, pos, baseArgb)` 吸收整条链
（getBiomeDepthAt → calcSnowDepthClient → depth≤0 原样返回 → blendToWhite），
`DhLevelWrapperMixin` 只保留 DH 类型相关守卫。

`TfcUnloadedTempEstimator`: 未加载区块 TFC 温度修正 (ChunkData.EMPTY 时 avgSeaLevelTemp 固定10°C → 从已加载区块Z轴采样替换)。缓存为 ConcurrentSkipListMap，采样线程由 CAS 选出唯一一个——它被 DH LOD Builder 线程池并发进入。

---

## 4. 完整配置表

### 4.1 覆雪温度

| 配置 | 默认 | 范围 | 说明 |
|------|------|------|------|
| `snowAppearanceTemp` | 0.0 | [-20, 30] | 雪开始出现的温度 |
| `snowFullCoverageTemp` | -4.0 | [-50, 20] | 温度满雪线(appear到full=过渡带宽度) |
| `snowMeltTemp` | 0.0 | [-20, 30] | 融雪温度 |
| `dhUnloadedTempOffset` | 0.0 | [-30, 30] | DH/Voxy远景温度微调 |
| `unloadedTempSampleRange` | 10240 | [1024, 100000] | TfcUnloadedTempEstimator采样半径 |

### 4.2 速度控制

| 配置 | 默认 | 范围 | 说明 |
|------|------|------|------|
| `tickChunkIntervalTicks` | 20 | [1, 200] | tickChunk处理+biomeDepth步进间隔(唯一速度控制器) |
| `biomeSyncIntervalTicks` | 100 | [20, 1200] | 服务端biomeDepth同步客户端间隔 |
| `enableTFCCompat` | true | - | 总开关（**当前不生效**：getter 零调用；且注册为 COMMON 类型，NeoForge 不向客户端同步，做不成真正的全局开关。暂保持原样） |

### 4.3 覆雪贴图

| 配置 | 默认 | 说明 |
|------|------|------|
| `snowTextureSimpleMode` | false | 全局开关—所有TFC方块强制简单雪(snow.png) |
| `snowTextureReplaceMode` | true | 替换模式(关=ES叠加渲染) |
| `snowTextureDefaultPath` | block/snow | 可自定义简单雪贴图路径 |

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
DH LOD 着色: DhLevelWrapperMixin.getBlockColor RETURN
  └─ DistantSnowService.snowColorFor(level, model, mcPos, baseArgb)
      ├─ ClimateQuery.getBiomeDepthAt → ES 原生 O(1) 查询
      ├─ calcSnowDepthClient
      │   ├─ TfcUnloadedTempEstimator.getCorrectedTemperature → 修正未加载区块温度
      │   ├─ temp>meltTemp → 0
      │   └─ SnowDepthRules.calcEffectiveDepth(temp, biomeDepth)
      └─ depth/100 → factor [0,1] → blendToWhite

DH 未加载区块雪判断: MapCheckerMixin.shouldSnowAtBiome HEAD cancel
  ├─ 仅客户端处理 (ServerLevel→return)
  ├─ getBiomeDepthAt + calcSnowDepthClient
  └─ shouldSnow(depth, seed) → boolean
```

---

## 6. Biome深度查找

`ClimateQuery.getBiomeDepthAt(level, pos)`: 直接调 ES 原生
`WeatherManager.getBiomeWeather(Level, Holder<Biome>)` —— O(1)，内部走
`eclipticseasons$getBindId()` 带边界检查的 `list.get(id)`，与 ES 自己的
`getSnowDepthAtBiome`/`getPrecipitationAt` 同源，无本地缓存。

调用方:
- `SnowyStatusKeeperMixin.getPerBlockDepth` — tickChunk中per-block
- `ChunkSnowRefresh.prepare` — chunk加载 per-chunk
- `MapCheckerMixin` / `DistantSnowService.snowColorFor` — DH per-block
- `WeatherManagerMixin.getSnowStatusImpl` — per-block
- `ExtraModelManagerMixin.maySnowyAt` — 客户端回退

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
| MapCheckerMixin (经 ES DHTool) | DH transformer 线程 |
| DhLevelWrapperMixin (DH getBlockColor) | DH LOD Builder 线程池 (大小=CPU核数, 异步!) |
| TfcUnloadedTempEstimator | 并发: DH LOD Builder 池 + MapChecker 路径 (ConcurrentSkipListMap + CAS 采样) |
| ClimateQuery.isPrecipitating(Level) 缓存 | 并发: DH workers + 区块编译线程 (volatile 不可变快照) |
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