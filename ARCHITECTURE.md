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

依赖版本（Modrinth version id）：

| 版本 | TFC | Ecliptic Seasons | DH | NeoForge |
|---|---|---|---|---|
| 1.21.1 | 4.2.11 (`Cvw1rnGv`) | 0.15.0-rc-3 (`Tok0V0sp`) | 3.3.3 (`9w34y8ai`) | 21.1.264（与测试包一致；TFC 4.2.11 要求 ≥21.1.234） |
| 1.20.1 | 3.2.28 (`dR2GA31g`) | 0.15.0-rc-3 (`xO4vsPui`) | 3.2.0-b (`FWGxbEM3`) | Forge 47.x |

改依赖时同步 `build.gradle` / `gradle.properties` 与 `mods.toml` 的版本范围；Mixin 目标是版本敏感的。

下面 §1 起描述的是**两版共用的设计**；各版本的 API 差异集中在三处：TFC 气候/天气读取（`ClimateQuery`）、loader 粘接（配置/网络/配置界面）、DH 适配。

### 1.20.1 侧的已知差异

| 处 | 1.21.1 | 1.20.1 |
|---|---|---|
| 气候模型 | `Climate.get(level)` + `supportsRain()` | `Climate.model(level)` + `instanceof OverworldClimateModel`（收敛在 `ClimateQuery.getModelIfActive`） |
| 瞬时温度 / 降水 | `model.getInstantTemperature` / `getInstantRainfall` | `Climate.getTemperature` / `Climate.getRainfall`（见 `ClimateQuery`） |
| 是否降水 | `WeatherHelpers.isPrecipitating(intensity, rainfall)` | `WorldTracker.isRaining(level, pos)` |
| 半球 | `SolarCalculator.getInNorthernHemisphere(z, model.hemisphereScale())` | TFC 1.20.1 无此概念 → `HemisphereSeasons.isNorthernHemisphere(z, Config.hemisphereScale)`（公式照搬 1.21） |
| 阻止物理雪 | 默认取消 `WeatherHelpers.onTickChunk`；`tfcPhysicalSnow=true` 时放行 | 默认取消 `EnvironmentHelpers.tickChunk`（`EnvironmentHelpersMixin`）+ 拦 TFE 的 `NTEWeatherHelpers.onTickChunk`（`estfc.tfe.mixins.json`）；`tfcPhysicalSnow=true` 时全部放行 |
| 实时逐块覆雪驱动 | ES 自驱：它在 `ServerLevel.tickChunk` 里包 `getBlockRandomPos`（ordinal 1）= `tickPrecipitation` 循环；estfc 的 `SnowyStatusKeeperMixin` 把随机列改成区块内随机列序游标（见 §3.2.1） | **estfc 自驱 `ChunkSnowTicker`**：ES 包的 ordinal 1 在 MC 1.20.1 指向 `iceandsnow` 块，而 TFC 的 `ServerLevelMixin` 把该块守卫 `nextInt(16)` 强改成 1 → 整块不执行。TFC 那条 `EnvironmentHelpers.tickChunk` TAIL 路径实测也不执行 |
| 世界生成的物理雪 | 无世界生成铺雪（TFC 1.21.1 的 `IceAndSnowFeature` 是空实现） | 拦两处（`tfcPhysicalSnow=true` 时放行）：`ClimateMixin` 取消 `Climate.onChunkLoad`（TFC 自己在冷区按温度+噪声铺 `minecraft:snow`/冰，入口是 TFC 自己的方法名，能稳定命中）+ `SnowAndFreezeFeatureMixin` 取消 vanilla 的 `minecraft:freeze_top_layer`。**注意**：`IceAndSnowFeature.place` 覆写的是 MC 的 `Feature.place`，生产 jar 里叫 `m_142674_`，用字面量 `place` + `remap=false` 的 mixin 永远命不中 |
| 天气循环驱动 | 注入 `WeatherHelpers.advanceWeatherCycle` RETURN | 监听 `TickEvent.LevelTickEvent` END（入口注册） |
| `ConnectedGrassBlock.updateShape`（MC 覆写） | 生产名就是 `updateShape`（Mojmap），`remap=false` 直接命中 | 生产名是 `m_7417_`；`ConnectedGrassBlockMixin` 用默认 remap 让 `updateShape` 走 refmap，TFC 自己的 `updateStateFromDirection` 单独 `remap=false` |
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
- **无网络/被墙构建**：ForgeGradle 6 在配置期会 HEAD 请求 `maven.minecraftforge.net` 和
  `libraries.minecraft.net`，且 `connect()` 没有超时，会一直卡在配置阶段。加
  `-Dnet.minecraftforge.gradle.check.certs=false` 跳过；配合 `--offline` 使用本地缓存。

### Forge 1.20.1 必须有 pack.mcmeta

NeoForge 1.21.1 对缺 `pack.mcmeta` 的模组 jar 会自动补一个；**Forge 1.20.1 不会**，
缺了直接报「未能为 estfc 加载有效资源包信息」并拒绝加载模组。

`pack.mcmeta` 放**各版本目录**（`pack_format` 随 MC 版本变，不能放 `shared/`）：

| 版本 | pack_format |
|---|---|
| 1.20.1 | 15 |
| 1.21.1 | 34 |

### 1.20.1 的物理雪来自四处，默认都要拦

配置 `tfcPhysicalSnow=true` 时下列所有入口都放行（恢复 TFC/原版物理雪；会与 ES 视觉雪共存）。

运行期：

- **TFC 本体**：`EnvironmentHelpers.tickChunk` → 由 estfc 的 `EnvironmentHelpersMixin` HEAD 取消。
- **TFE（TerraFirmaEarth，可选）**：它自己也注入 `EnvironmentHelpers.tickChunk` 的 HEAD 并 cancel，
  在里面调用 `NTEWeatherHelpers.onTickChunk` 放雪。**同一注入点的多个 handler 都会执行，
  `ci.cancel()` 只取消目标方法体、不跳过其它 handler —— 给 mixin 提优先级没用**。
  必须单独拦 `NTEWeatherHelpers.onTickChunk`（`mixin/tfe/`，放在 `estfc.tfe.mixins.json`，
  `required:false`）。注意：装了 TFE 时，TFE 的 handler 仍会取消 TFC 方法体；`tfcPhysicalSnow=true`
  时运行的是 TFE 的向下移植积雪实现（行为等价），不是 TFC 本体实现。

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

（1.21.1 的 `getInstantTemperature` 同样经 `ChunkData.get` → `level.getChunk(pos)`，但
`forceChunkUpdateHeight` 在区块挂载后执行，读到的是已加载区块；仍不要在 pre-load 回调中调用。）

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
│   ├── SnowDepthRules.java         — 纯函数 (tempToSnowDepth, depthFromCoverage, rainfallFactor, shouldSnow)
│   ├── WeatherCycleService.java    — tick 主循环 (按维度分键的 lastStepTick, 覆雪率滑窗, 防积压)
│   ├── BiomeSampleCache.java       — 每个群系的代表采样点 (维度+biome → 最近地表坐标)
│   ├── BiomeSnowSeed.java          — 群系首次采样时用当地温度播种一次覆雪率
│   ├── BiomeCoverageCache.java     — 每群系期望覆雪率 (浮点, 按 ES 采样速率推进)
│   ├── ChunkSnowRefresh.java       — 区块加载/更新覆雪刷新 (prepare, ChunkTemperature, refreshChunk, applyOnLoad, applyMeltOnly)
│   ├── SnowDefinitionRegistrar.java— 运行时补齐 datapack 未覆盖的 TFC 方块雪定义 (服务端+客户端分类)
│   ├── SolarTermMapper.java        — TFC 月份 → ES 节气索引公式
│   └── HemisphereSeasons.java      — 南半球季节/节气翻转
│
├── util/
│   └── BlockPatternMatcher.java    — 配置条目匹配 (前缀/完整ID/正则, ConcurrentHashMap 正则缓存)
│
├── mixin/                          — 与注入目标 1:1, 业务逻辑在 weather/ 与 client/
│   ├── WeatherHelpersMixin         — [TFC] 取消onTickChunk, advanceWeatherCycle TAIL→WeatherCycleService
│   ├── WeatherManagerMixin         — [ES] getSnowStatus劫持 (累积雪深或降水→SNOW), agentAdvanceWeatherCycle取消
│   ├── SnowyStatusKeeperMixin      — [ES] tickChunk 节流 + 区块内列游标 + getSnowDepthAtBiome→getPerBlockDepth
│   ├── SnowyMapCheckerMixin        — [ES] forceChunkUpdateHeight TAIL→ChunkSnowRefresh
│   ├── SnowCheckerMixin            — [ES] resetUpdate RETURN→SnowDefinitionRegistrar（运行时补 TFC 雪定义）
│   ├── SnowModelMixin              — [ES] getSnowyModel TAIL→SnowTextureSwapModel; clearForRebaked→程序化树叶SnowDefinition
│   ├── ExtraModelManagerMixin      — [ES] maySnowyAt RETURN回退
│   ├── MapCheckerMixin             — [ES] shouldSnowAtBiome HEAD→DistantSnowService.calcSnowDepthClient（voxy 远景接线点）
│   ├── ClientConMixin              — [ES] tick TAIL→HemisphereSeasons
│   ├── ConnectedGrassBlockMixin    — [TFC] 按 tfcConnectedTexture 断开/保留连接纹理方向
│   ├── EclipticUtilMixin           — [ES] canSnowyBlockInteract→强制true
│   └── SolarDataManagerMixin       — [ES] getSolarTermIndex→SolarTermMapper
│
├── client/
│   ├── DhSnowService.java          — DH 远景覆雪 (DhApiBlockColorOverrideEvent, 区域海拔, 需 ES DH兼容关闭)
│   ├── DistantSnowService.java     — voxy 远景覆雪 (calcSnowDepthClient=累积雪深模型)
│   ├── DistantTemperature.java     — 远景温度（avgSeaLevelTemp+月温+日温±海拔；月温/日温/海拔复用 TFC @Invoker）
│   ├── RegionClimateCache.java     — 区域气候点（温度+高度）客户端缓存/请求/双线性/预取/自检
│   ├── SnowTextureSwapModel.java   — 覆雪BakedModel (tfc:→estfc:贴图替换, IESReplaceModel)
│   ├── BakedQuadTextureOnly.java  — 保留UV的贴图替换
│   └── gui/
│       ├── EstfcConfigScreen.java — 配置界面(HeaderAndFooterLayout+GridLayout)
│       └── ScrollableLayout.java  — 可滚动布局
│
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

### 2.2 depthFromCoverage — 所有路径统一（播种 + 采样滑窗）

```java
// weather/SnowDepthRules.java
public static int depthFromCoverage(float actualTemp, int coverage, float rainfall) {
    if (actualTemp > Config.snowMeltTemp()) return 0;
    if (coverage <= 0) {
        return Math.round(tempToSnowDepth(actualTemp) * rainfallFactor(rainfall));
    }
    return Math.min(100, coverage);
}
```

语义：**覆盖率 C 是唯一状态**；温度只负责播种和融雪门槛，降水系数只影响 C 的推进速度/种子。
- `C=0` = **未初始化**：用当前温度斜坡兜底（新区块、Voxy 未访问区域）。
- `BiomeSnowSeed` 在每个群系首次拿到代表点时播种：`C = 温度斜坡 × 降水系数`；
  降水系数 >0 时至少为 1，干旱地区保持 0。
- 尚未发生降雪/融雪前 `climateOnly=true`：后续采样可继续刷新种子 C；老区块用奇偶 tick 的
  `SNOW`/`SNOW_MELT` 交替把已有方块加/清到 C。第一次实际累积/融化后置 `false`，进入历史模型。
- `BiomeCoverageCache` 同时保存目标 C 和显示 `DISPLAY`：
  - 降水+低温：目标 `C += (100-C) × (maxCoverage/100) × step/256`
  - 升温：目标 `C *= (1 - step/256)`
  - 雪停/非降水：目标冻结为 `DISPLAY`，不再保留更高的旧目标。
  - 每次 biome 更新 `DISPLAY` 最多移动 `100 × step / (256 × (48 / randomTickSpeed))`，与已加载区块实际采样速率一致。
  - `maxCoverage = 100 × 降水系数`（年均降水 <80mm → 0；>150mm → 100；之间线性）。
- 已加载区块的 `getSnowDepthAtBiome` 替换返回 `DISPLAY`，融雪返回 0；没有 DISPLAY 时回退降水目标。
  **不再把 NONE 包成 SNOW**：雪停后不会继续补列。
- 新区块、DH、voxy 读 `DISPLAY`；已加载区块用 16×16 随机列序游标逐列检查，不整块重刷。

| actualTemp | coverage=0（未初始化） | coverage=30 | coverage=80 | coverage=100 |
|------------|-------------------------|--------------|--------------|---------------|
| 2°C | 0 | 0 | 0 | 0 |
| 0°C | 0 | 0 | 0 | 0 |
| -1°C | 25 | 30 | 80 | 100 |
| -2°C | 50 | 30 | 80 | 100 |
| -4°C | 100 | 30 | 80 | 100 |

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
**采样点**: `BiomeSampleCache`，按 `(维度, biome)` 记录最近一次区块加载/实时 tickChunk 见到的地表位置

```
每 tick:
  1. getTickBiome() → 取当前处理的 biome
  2. 取该 biome 的代表点（没有或已卸载 → 推进时间戳后跳过）
  3. 首次拿到代表点: BiomeSnowSeed 用当地温度播种一次（深度 >= 1）
  4. 用代表点自己的 TFC 温度+降水判断累积/融化
  5. 累积/递减:
     elapsed = gameTime - lastStepTick[index]
     step = min(100, elapsed / interval)
     maxCoverage = 100 × 降水系数
     降水+低温: coverage += (100 - coverage) * (maxCoverage/100) * step / 256
     零上:      coverage *= (1 - step / 256)   // 下限1
     lastStepTick[index] += step * interval   // 保留不足一步的余数
  6. coverage 同步到 BiomeWeather.snowDepth（取整）供客户端/新区块读取
  7. 每 biomeSyncIntervalTicks(100): 同步所有biome覆雪率到客户端
```

**雪深写入者唯一**：ES 的 `WeatherManager.updateSnowOrMelt` 用 `BlockPos.ZERO` 作哨兵，
按自己的 `getSnowStatus(ZERO)` 直接加减 `BiomeWeather.snowDepth`；estfc 的
`WeatherManagerMixin` 对 `BlockPos.ZERO` 直接返回 `NONE`，否则 ES 会把雪深拉到 100、
estfc 下一 tick 又写回覆盖率，DH 表现为「突然满雪 → 跳回渐变」。

**为什么用代表点**: TFC 的 `getRain(calendarTicks)` 是全局雨强，`getInstantRainfall(level,pos)`
按位置过滤；拿第一个玩家的位置给所有 biome 记账会把“玩家所在地的雨”当成“所有群系的雨”。
代表点让每个 biome 用自己的局部气候，且不需要加载未访问区块。

**interval 来源**: `Config.tickChunkIntervalTicks()` (默认 2)；实际每区块采样还受 vanilla `randomTickSpeed/48` 随机门限制
- biome 数量不影响全局速度（每个 biome 每轮转一次，按 elapsed 补步数）
- 10 tick → 100步×10=1000tick≈50秒满雪

**时间戳**:
`Map<ResourceKey<Level>, long[]>`，按维度分键，数组按实际群系表长度扩容；新槽位用当前
`gameTime` 初始化，空闲/无样本/模型不可用时推进时间戳，防止空服回来一次冲满。
历史版本是全局 `long[256]`——群系注册表可远超 256（vanilla ~64 + TFC 127 ≈ 190，
再装生物群系模组即越界），越界异常被空 catch 吞掉导致积雪静默停止；且各维度共用一个
数组互相破坏时间戳。均已修复。

### 3.2 getSnowStatus — per-block雪状态决策

**注入点**: `WeatherManagerMixin` → ES `getSnowStatus` (4参数版) HEAD cancel

```java
if (temp > meltTemp)      → SNOW_MELT   // 零上 → 融雪
else if (precipitating)   → SNOW        // 降水 → 新增雪
else                      → NONE        // 雪停后保持，不再继续铺
```

ES的 `SnowyStatusKeeper.tickChunk` 根据返回状态决定: SNOW→尝试设FLAG_SNOW, MELT→尝试清FLAG_NONE, NONE→不变。
初始/播种后的整块铺设由 `ChunkSnowRefresh` 在区块加载时完成；已加载区块靠同一采样速率逐步逼近
`BiomeCoverageCache`，不依赖整区块重刷。

该分支里两处 `WeatherManager.getSnowDepthAtBiome`（源码顺序 = ordinal：0 = SNOW 分支、1 = SNOW_MELT 分支）
**都由 `SnowyStatusKeeperMixin` 的 `@WrapOperation` 换成 `estfc$getPerBlockDepth`**（不设 `ordinal`，两处都命中）。
它优先返回 **DISPLAY**（与新区块/DH/voxy 同一个显示值）；融雪返回 0；没有 DISPLAY 时回退
`round(100×降水系数)`。DISPLAY 由 `BiomeCoverageCache` 按实际随机采样速率推进。
**降水时返回 100 会让已加载区块抢跑；雪停后把 NONE 包成 SNOW 会让雪继续补，现已移除。**

### 3.2.1 列温度：按列做 TFC 海拔修正（不能只插值最终覆雪率）

现象：陡坡上覆雪图案**每过一个区块重复一遍**（下坡走到新区块又从头开始）。

原因（2026-10-05 实测，探针已删）：`ChunkSnowRefresh` 原本用
「本区块中心地表温度 + `(列高 − 中心高) × 0.16225` 外推」当列温度。而 TFC 自己的海拔降温在
`Helpers.adjustAverageTemperatureByElevation` 里被 **`Mth.clamp(..., 0, 17.822f)` 封顶**
（≈110 格海拔，即 `17.822 / 0.16225`）。高地形的中心温度早就封顶 ⇒ **几乎不随中心高度变化**：

```
[55,125] baseTemp=-1.09  centerSurface=212     ← 中心高 212
[56,125] baseTemp=-1.04  centerSurface=191     ← 中心低 21 格，温度只差 0.05°C
交界处：A 在 h≈199 深度≈0，B 在 h≈197 深度≈50   ← 相邻两列差 3°C / 50 深度
```

每个区块都拿自己中心（≈同一个封顶温度）当锚点 ⇒ 每块重新起一轮 16 格的斜坡 ⇒ 锯齿。

**修法**：区块级只取一次 `model.getInstantTemperature`（海平面参考点）得到「月温 + 日温」基，
每列从 `ChunkData.getAverageSeaLevelTemp(x,z)` 取自己的 avgSea，再用 TFC 的
`Helpers.adjustAverageTemperatureByElevation(y, avgSea, SEA_LEVEL)`（1.20.1 用手写的同一 clamp 公式）
逐列修正海拔。这样每区块 TFC 调用从 256 次降到 1 次，陡坡仍按列封顶，不会重复起斜坡。
海平面及以下保留逐列 `getInstantTemperature`，因为 TFC 会按深度衰减月/日温。
`ChunkContext` 带 `biomeSnowDepth + rainfall + ChunkTemperature`。

**排查过但不是原因的（别再走）**：

- vanilla 选点 LCG 低位退化：ES 用 `ServerLevel.getBlockRandomPos` 的 (x,z)，其 LCG
  （`randValue = randValue * 3 + 1013904223`，x 偏移 `(randValue >> 2) & 15` 周期仅 32）
  经节流后每区块只反复采到固定 2~4 列（步长 200 时 x 偏移只出现 {0,4,8,12}）—— 退化真实存在。
  现在由 `SnowyStatusKeeperMixin` 的 `@ModifyVariable` 用区块内 16×16 随机列序游标替换随机列；
  雪越多随机命中已覆雪列的概率越高、逼近 C 越慢的问题同时解决。
- 抖动种子：`Mth.getSeed` 原样实现后统计 16×16 区块覆盖率（42%/51%）分布均匀，无角状聚簇。
- `WORLD_SURFACE − ES 雪位高度` 实测全 0 ⇒ 植被没顶高海拔基准。

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
        └─ ES 的 MixinFullDataToRenderDataTransformer 会替换部分 getBlockColor 调用，
           返回 ES 雪色时不触发 estfc 的 API 事件。estfc 的 DHToolMixin 直接让
           DHTool.computeBaseColor/shouldFrozen HEAD 返回 null，等效于
           DistantHorizonsWinterLOD=false，不依赖对方配置文件。
voxy: ES-Voxy-Compact MixinModelFactory → VoxyTool.changeBlockId
        └─ MapChecker.shouldSnowAtBiome → estfc MapCheckerMixin（唯一接线点）
```

**调用链（DH）**: `DhSnowService.maybeOverride(EventParam)` — 事件参数自带位置/群系/方块/
数据源。列内自上而下，**首事件 = 表面数据点**；同一列更低的数据点原样返回（列 key
= level + x>>level + z>>level，所有数据点共享；列缓存按世界绑定，换世界清空）。
缓存记录首事件的底部 y 和地表高度：底部小于缓存 → 新 LOD 构建的表面事件，复用高度重算
温度（日温变化）；底部大于缓存 → 同列内部点，直接跳过。
→ `RegionClimateCache.sampleClimate(x,z)`（温度+区域高度一次采样）
→ `DistantTemperature.distantTemperatureRegional`（avgSeaLevelTemp + 月温 − 区域海拔）
→ `ClimateQuery.getBiomeDepth(level, biome)`（用事件传入的群系，未加载位置
`level.getBiome(pos)` 只有占位群系雪深恒 0）
→ `SnowDepthRules.depthFromCoverage` → `shouldSnow(depth, seed)`（逐块抖动，二值输出；
seed 的 y 用区域高度——事件 y 级别相关，直接用会产生级别边界细网格线）

**调用链（voxy）**: `MapCheckerMixin.shouldSnowAtBiome(level, biome, …, mcPos)`
→ `ClimateQuery.getBiomeDepth(level, biome)`
→ `DistantSnowService.calcSnowDepthClient(level, mcPos, biomeDepth)`
→ `SnowDepthRules.shouldSnow(depth, seed)`

**区域数据缺失兜底**：DH/voxy 的 `RegionClimateCache.sampleClimate` 在 4 个
128 格角点未同步时返回 null。此时直接跳过会把整块 LOD 烘焙成基础色（远景 0%，刷新后才
跳回 70%）；`DhSnowService`/`DistantSnowService` 用已同步的群系覆盖率 `biomeDepth`
做兜底（按 seed 二值化），数据到达后再按温度/降水细化。

**与近景一致的原则（播种 + 累积）**：远景和近景走同一个
`depthFromCoverage = 覆盖率`，并用同一份服务端同步的 `BiomeWeather.snowDepth`。
- `biomeDepth=0` 表示该群系还没被采样/播种：用当前温度斜坡做气候种子，所以新世界冷区和
  未访问的 Voxy 冷山都有雪。
- 群系首次被采样后 `BiomeSnowSeed` 播种一次，之后只由降水累积、升温融雪；雪停不再增加。
- 近景、新区块、DH、voxy 都读同一个 `BiomeCoverageCache` 覆盖率；已加载区块按相同采样速率逼近。
- 远景仍用区域高度（DH）/体素 y（voxy）；温度含日温（与 TFC 实时温度同源）。
- `biomeDepth` 每 `biomeSyncIntervalTicks`(100) 同步一次，远景最多滞后约 5 秒。
- 已加载区块的 LOD 边界不直接读 `SnowyStatusKeeper`（DH/voxy worker 线程读非线程安全结构
  有数据竞争风险），所以边界仍可能有少量差异；要逐方块精确需另做线程安全快照。

**DH 真实 y（2026-10-26）**：事件自带的 y 是 **merged run 底部**，不是地表。现在优先从
事件的数据源反推：`IDhApiFullDataSource` 实际是内部 `FullDataSourceV2`，
用 `getPos()` + `getDataDetailLevel()` 算相对列坐标，`tryGetColumnAtRelPos` 取列，
优先取事件所在 data point 的 topY；否则取列内最高 **非空气** data point 的
`bottomY + height + levelMinY`。失败时回退 128 格区域高度。

**区域海拔（2026-08-22 定稿，作为回退）**：服务端 `TFCChunkGenerator.getBaseHeight`
按 128 格网格同步 → 客户端双线性 → `avgSeaLevelTemp + 月温 − 区域海拔`。
区域场平滑、级别无关；voxy 用体素真实 y。

**远景温度**：`DistantTemperature` = `avgSeaLevelTemp(x,z) + 月温(z) + 日温(x,z,日历) − 海拔(y)`。
月温/日温/海拔公式经 `OverworldClimateModelMixin` @Invoker 复用 TFC 原实现
（`calculateMonthlyTemperature`/`calculateDailyTemperature`/`adjustTemperatureByElevation`），
避免公式复刻随 TFC 漂移；`climateSeed`/`temperatureScale` 都由 TFC `STREAM_CODEC` 同步，
客户端日温与实时温度一致。海拔 y 两个来源：voxy 体素 y（=真实地表）与
DH 区域表面高度（128 格平滑场）。voxy 由 VoxyCompact 定时重渲染；DH 颜色烘焙在
LOD render source 里，日温要等该 LOD 重建才刷新（列缓存会识别新一轮构建并重算）。

**avgSeaLevelTemp / 区域高度数据源**：未加载区块 ChunkData.EMPTY 恒 10°C。
服务端按 128 格网格点（`Units.GRID_BITS=7`）用
`ChunkGeneratorExtension.chunkDataGenerator()` + `getBaseHeight` 确定性生成
（纯种子函数、时不变、不加载区块），异步线程池计算（`dhTempServerThreads`/
`dhTempServerSubmitPerTick` 可调），经 `RegionClimatePayloads` 同步，
客户端 `RegionClimateCache` 缓存 + 双线性插值 + 玩家周围预取。
首屏 1-2 秒数据未到时远景先无雪（不再回退纯 biomeDepth，避免无温度/降水时错误显示雪），属预期。

---

## 4. 完整配置表

### 4.1 覆雪温度

| 配置 | 默认 | 范围 | 说明 |
|------|------|------|------|
| `snowAppearanceTemp` | 0.0 | [-273, 500] | 开始出现雪的温度；高于此值气候种子为 0 |
| `snowFullCoverageTemp` | -4.0 | [-273, 500] | 满雪覆盖的温度；低于此值气候种子为 100% |
| `snowMeltTemp` | 0.0 | [-273, 500] | 高于此温度融雪；低于此温度按累积雪深显示 |
| `snowRainfallNoSnow` | 80 | [0, 500] | 年均降水<此值 → 无雪 |
| `snowRainfallFullSnow` | 150 | [0, 500] | 年均降水>此值 → 覆雪不受降水影响 |
| `dhTempPrefetchRadius` | 8192 | [128, 16384] | [DH/voxy] 区域气候点(128格网格)预取半径 |
| `dhTempRequestsPerTick` | 512 | [1, 2048] | [DH/voxy] 客户端每tick区域点请求上限 |
| `dhTempServerThreads` | 4 | [1, 32] | [DH/voxy] 服务端计算线程数(min(本值,核数)) |
| `dhTempServerSubmitPerTick` | 64 | [1, 512] | [DH/voxy] 每tick提交给服务端线程池的上限 |

### 4.2 速度控制

| 配置 | 默认 | 范围 | 说明 |
|------|------|------|------|
| `tickChunkIntervalTicks` | 2 | [1, 200] | tickChunk处理+biomeDepth步进间隔；实际速度再乘 randomTickSpeed/48 |
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

> 上面画的是「取到模型之后」的贴图分类。能不能取到模型由 ES 的可覆雪判定决定 ——
> 运行时雪定义的 `offset` 语义（双层高植物的坑）见 §4.5.1。

### 4.5.1 双层高植物的 offset（`SnowDefinitionRegistrar`）

`SnowChecker.resetUpdate` 之后由 `SnowCheckerMixin` → `SnowDefinitionRegistrar.registerDefaults()`
补齐 datapack 未覆盖的 TFC 方块；datapack 已覆盖的跳过（`SNOW_DEFINITION_MAP.containsKey` 提前 continue）。

**offset 决定 ES 的可覆雪判定**（`ExtraModelManager.findModel` / `canSnowy`）：

```
isLight = mapHeight <= pos.getY() - offset        // mapHeight = IMapSlice.getBlockHeight(pos)
最终标雪还要求 isLight == true
```

| 方块 | offset | 原因 |
|---|---|---|
| crop / groundcover / 浆果丛 / 单层花 | 1 | y 在列高之上，判定天然通过 |
| **TFC 双层高植物**（`part=lower`+`part=upper`，`marigold`/`switchgrass`/`foxglove`…） | **0** | `part=lower`（真正画植物的那半）的 y 正好等于列高，offset=1 时 `isLight=false` → 拿不到覆雪模型；`part=upper` 反而拿得到，可它的模型是 `tfc:block/empty`（空几何）→ 什么都不画。现象 = 雪旗打在 upper 上、植物看不见雪，物种级全灭 |

故 `offset = isTallPlant(block) ? 0 : 1`（`isTallPlant` = 方块有 `part` 属性且取值恰为 lower/upper）；
upper 那半同用 0 无副作用（空模型渲染不出几何）。

**不要用 `SnowDefinition.PropertyTester` + `ExactMatcher` 按 part 分注册。**
`ExactMatcher` 比的是 `blockState.getValue(property).toString()`，TFC `Part` 枚举的 `toString()`
是大写 `LOWER`/`UPPER`；写小写永不命中 → 所有状态退回 `SnowDefinition.Info.EMPTY`（`flag=0`）
→ **全场掉雪**（连原本正常的方块一起）。

排查时排除过的方向（别再走）：

- 贴图替换链路本身正常：plant 贴图全 HIT；MISS 的都是 estfc 本来就没有的贴图
  （`clay/*`、`dirt/*`、`fruit_tree/*`、`wood/log_top/*`、`block/empty`），且 `atlasHas=minecraft:missingno` 属预期。
- **Continuity**（连接纹理）的 `EmissiveBakedModel extends ForwardingBakedModel`（FRAPI）不转发
  NeoForge 的 `getModelData` → TFC 动态模型的 `BakedModelData`（带真实 blockPos）丢失。
  实测**不影响覆雪**（`part=lower` 渲染时仍拿到数据），关掉 Continuity 现象不变。
- 逐块图案是 ES 设计使然：`shouldSnow(depth, seed)` 按坐标定死、随温度/累积雪深变化 →
  同一物种隔一格一个有雪一个没雪属正常，不是物种级 bug。

### 4.6 配置界面

`EstfcConfigScreen` — 纯原生MC组件, 无前置依赖:
- `HeaderAndFooterLayout`: 标题+5标签栏 / ScrollableLayout滚动内容 / Cancel+Reset+Done
- `buildListEditor`: 可折叠的逐行列表编辑器 (EditBox+删除+添加按钮)
- `resetToDefaults`: `ModConfigSpec.ConfigValue[]` 数组循环 → 新增配置自动重置

### 4.7 其他

| 配置 | 默认 | 说明 |
|------|------|------|
| `tfcConnectedTexture` | false | true→ConnectedGrassBlock保留连接纹理但不覆雪; false→强制断开连接以正确显示雪 |
| `tfcPhysicalSnow` | false | 允许 TFC/原版放置物理雪（雪堆/雪块/冰/冰柱）；1.20.1 同时放行世界生成物理雪与 TFE |
| `debugLogs` | false | Voxy/区域气候诊断日志开关；正常游玩保持关闭 |

---

## 5. 数据流详解

### 5.1 积雪全流程

```
TFC advanceWeatherCycle (每tick, 服务端主线程)
  │
  ├─ WeatherHelpersMixin TAIL → WeatherCycleService.tickWeatherCycle()
  │   ├─ 取该 biome 在 BiomeSampleCache 中的代表点温度+降水
  │   ├─ 首次: BiomeSnowSeed 用当地温度播种覆盖率 (>=1)
  │   ├─ 1个biome: 更新目标 coverage；非降水/非融雪时冻结到 DISPLAY
  │   ├─ DISPLAY 以 randomTickSpeed/48 的速率追目标
  │   ├─ DISPLAY → BiomeWeather.snowDepth (取整) 供客户端/新区块读取
  │   └─ 每100tick: sendBiomePacket → 客户端由 ES 原生 processBiomeWeatherMessage
  │      解包进 BiomeWeather (getBiomeDepthAt 实际读取的对象)
  │
  ├─ ES MixinServerLevel (每tick) → SnowyStatusKeeper.tickChunk
  │   └─ SnowyStatusKeeperMixin HEAD: 按tickChunkIntervalTicks节流, 非间隔期cancel
  │   └─ WeatherManager.getSnowStatus → WeatherManagerMixin.estfc$getSnowStatusImpl
  │       └─ temp>melt→SNOW_MELT, precip→SNOW, else→NONE
  │   └─ WeatherManager.getSnowDepthAtBiome → SnowyStatusKeeperMixin.replaceDepthForMelt
  │       └─ estfc$getPerBlockDepth → 优先 DISPLAY / 融雪 0 / 无 DISPLAY 时降水目标
  │           → shouldSnow(depth, seed) → keeper.set(pos, FLAG_SNOW/FLAG_NONE)
  │
  ├─ Chunk加载: ChunkEvent.Load → ChunkSnowRefresh（1.20 为 scheduler 入队；SnowyMapCheckerMixin 仍保留）
  │   ├─ prepare: 地表群系 + 记录代表点 + 首次播种 + 当前覆盖率
  │   ├─ 区块级: 1 次 TFC 瞬时温度 = 月温+日温基；每列再加 avgSea + 海拔修正
  │   │   colSnowDepth = depthFromCoverage(colTemp, coverage)
  │   ├─ loadingChunk: applyOnLoad (零上或 depth=0→无雪; 否则 shouldSnow); 否则 applyMeltOnly
  │   └─ keeper.updateAndSend
```

### 5.2 远景全流程 (客户端)

```
DH:   DhApiBlockColorOverrideEvent（每数据点，列内自上而下）  [要求 ES DistantHorizonsWinterLOD=false]
        └─ DhSnowService.maybeOverride
            ├─ 守卫: 非空气/非液体 + OverworldClimateModel
            ├─ 真实 y（DH 数据源列顶）或 DistantTemperatureRegional(x,z) 回退
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
        │   │   └─ + 月温(z) + 日温(z,日历)
        │   ├─ 数据未同步 → 无雪
        │   └─ SnowDepthRules.depthFromCoverage(temp, biomeDepth, rainfall)
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
- `SnowyStatusKeeperMixin.getPerBlockDepth` — tickChunk 中 per-block（取该列地表群系）
- `ChunkSnowRefresh.prepare` — chunk 加载 per-chunk（取区块中心地表群系）
- `WeatherManagerMixin.getSnowStatusImpl` — per-block（用调用方传入的群系）
- `ExtraModelManagerMixin.maySnowyAt` — 客户端回退（按坐标）
- `MapCheckerMixin` — voxy 远景 per-block（**按传入群系**）
- `DhSnowService` — DH 远景 per-数据点（**按事件传入群系**，TTL 缓存）

---

## 7. 线程模型

| 路径 | 线程 |
|------|------|
| WeatherCycleService.tickWeatherCycle | 主服务端线程 |
| BiomeSampleCache / BiomeSnowSeed / BiomeCoverageCache | 主服务端线程写/读 (ConcurrentHashMap) |
| tickChunk (SnowyStatusKeeperMixin throttle) | 主服务端线程 |
| forceChunkUpdateHeight (SnowyMapCheckerMixin → ChunkSnowRefresh) | 主服务端线程 |
| getSnowStatus (WeatherManagerMixin) | 主服务端线程 |
| ClientConMixin.tick | 客户端线程 |
| SnowModelMixin.getSnowyModel | 客户端渲染线程 |
| DhSnowService (DhApiBlockColorOverrideEvent) | DH LOD 构建线程池（并发，列缓存 ConcurrentHashMap） |
| MapCheckerMixin (经 VoxyTool) | voxy ingest/worker 线程（并发） |
| RegionClimateCache | 并发: 请求集合 worker 写 / 客户端 tick 线程 drain 发包 (ConcurrentHashMap + newKeySet) |
| RegionClimatePayloads 服务端生成 | 服务端主线程入队（ServerTickEvent.Post 限流）；每格记录所有请求者 UUID；计算在 estfc-region-climate 线程池（daemon），回主线程发给所有在线请求者；世界代际号丢弃换世界后的旧结果 |
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