# estfc 重构 + Bug 修复计划

> 本文件是可中断续做的执行清单。每完成一步，把该步的 `- [ ]` 改成 `- [x]` 并提交。
> 中断后重新开始时：先看「进度」区，找到第一个未打勾的步骤，从那里继续。
> 完整背景、每步的详细做法见任务说明（本文件只保留进度与关键约束）。

## 进度

- [x] F0 修复：恢复 estfc.mixins.json（自 6be084f 取回，mixins 8 条、client 5 条）
- [x] R1 重构：抽出 util/BlockPatternMatcher（从 SnowModelMixin 逐字搬移，暂保持 HashMap）
- [ ] R2 重构：拆分并删除 WeatherBridge（→ ClimateQuery / SnowDepthRules / WeatherCycleService / DistantSnowService.calcSnowDepthClient，重定向 14 个调用点后删除原类）
- [ ] R3 重构：把业务逻辑移出 Mixin（ChunkSnowRefresh / SolarTermMapper / HemisphereSeasons / DistantSnowService.snowColorFor / ConnectedGrassBlockMixin 抽公共方法）
- [ ] R4 重构：删除死代码（SnowDepthCache、NetworkUtilMixin+json 条目、BakedQuadSnow、calcEffectiveDepth 的 precipitating 参数及 3 处随之失效的降水计算）
- [ ] F1 修复：群系雪深缓存永不失效（改用 ES 原生 WeatherManager.getBiomeWeather O(1) 查询，删 depthCache/cachedBiomeList 及 ExtraModelManagerMixin.getBiomeDepth）
- [ ] F2 修复：TreeMap 跨线程（zAvgTempCache → ConcurrentSkipListMap + AtomicReference CAS 采样 + sampleCount；MapCheckerMixin 整体 try/catch）
- [ ] F3 修复：降水缓存竞态（不可变 PrecipSnapshot record + volatile 单字段）
- [ ] F4 修复：正则缓存并发化（PATTERN_CACHE → ConcurrentHashMap，非法正则缓存为 NEVER_MATCHES）
- [ ] F5 修复：lastStepTick[256] 越界（按维度分键 + 按实际群系表长度扩容）
- [ ] D1 文档：更新 ARCHITECTURE.md（目录树、matchesEntry/PATTERN_CACHE 描述、O(N) 扫描段、线程表、WeatherBridge 调用链；补记 enableTFCCompat 不生效）

## 关键约束

- 修复与重构分开提交，修复可单独回退。
- DH 类型（com.seibel.*）只允许出现在 DhLevelWrapperMixin 里（@Pseudo + require=0）。
- 不动 SnowyStatusKeeperMixin 第 47 行 @WrapOperation 的目标描述符字符串。
- enableTFCCompat 本次不处理。
- 每步验证：`./gradlew compileJava --offline`；改 mixins.json 的步骤（F0/R4）额外跑同步检查（json 条目 ↔ mixin/ 文件一一对应）。
- compileJava 不校验 mixin 目标/注入点/refmap，全部做完后需 runClient 实机验证（run/mods 里的 TFC jar 当前无效，需先修好 run 环境）。

## 完工验证清单

1. `./gradlew compileJava` 全绿
2. mixins.json 同步检查无输出
3. `./gradlew runClient`，日志搜 `Mixin apply failed` / `was not found` 无命中
4. 实测：降温+降水覆雪 / 升温融雪；DH 远景雪色无网格花纹；节气与 TFC 月份对齐、南半球季节相反；配置界面 5 个标签页正常
5. F5 专项：装生物群系模组使注册表超 256，确认积雪仍累积
