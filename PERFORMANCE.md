# 性能分析（spark）

- 报告：<https://spark.lucko.me/cCCoeYe706>
- 采样：2026-10-28，1.21.1 测试包（TFC 4.2.11 / ES 0.15.0-rc-3 / DH 3.3.3）
- 范围：**仅 Server thread**，12 个 60 秒窗口，总墙钟 **666.2s**
- `jdk.internal.misc.Unsafe.park` 占 **527.8s（79.2%）**：整合服务器在等客户端/空转。
  下表百分比以**非 park 的活跃时间 138.4s** 为分母，避免被空转稀释。

## 活跃时间 top（self time）

| 占比 | 时间 | 方法 |
|---:|---:|---|
| 11.80% | 16.34s | `dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.step` |
| 2.65% | 3.67s | `org.lwjgl.system.JNI.invokeD` |
| 2.40% | 3.32s | `net.neoforged.bus.EventBus.post` |
| 2.28% | 3.16s | `net.minecraft.world.level.chunk.PalettedContainer.get` |
| 1.88% | 2.60s | `net.minecraft.util.SimpleBitStorage.lithium$compact` |
| 1.76% | 2.44s | `net.minecraft.server.MinecraftServer.runServer` |
| 1.73% | 2.39s | `dev.architectury.event.EventFactory$1.handleInvocation` |
| 1.70% | 2.35s | `net.minecraft.world.level.LevelHeightAccessor.getMinSection` |
| 1.56% | 2.16s | `net.minecraft.server.level.ServerLevel.tickChunk` |
| 1.55% | 2.15s | `net.minecraft.server.MinecraftServer$$Lambda.getAsBoolean` |
| 1.47% | 2.04s | `net.minecraft.world.level.biome.BiomeManager.getBiome` |
| 1.34% | 1.86s | `net.minecraft.world.level.Level.getChunk` |
| 1.26% | 1.75s | `net.minecraft.server.level.DistanceManager.runAllUpdates` |
| 1.26% | 1.75s | `java.util.WeakHashMap.expungeStaleEntries` |
| 1.21% | 1.67s | `net.minecraft.world.level.chunk.PalettedContainer.pack` |
| 1.12% | 1.55s | `net.neoforged.bus.ConsumerEventHandler.invoke` |
| 0.92% | 1.27s | `net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase.getCollisionShape` |
| 0.91% | 1.26s | `net.minecraft.server.level.ChunkMap.saveChunkIfNeeded` |
| 0.88% | 1.22s | `net.minecraft.world.level.LevelHeightAccessor.handler$...$fucksable$overrideMaxBuildHeight` |
| 0.82% | 1.14s | `net.minecraft.server.level.ServerChunkCache.tickChunks` |

`ServerChunkCache.tickChunks` 子树总耗时 **18.41s（13.30% 活跃）**，其中
`ServerLevel.tickChunk` 子树 4.06s（2.94%）。

## estfc 相关

| 占比 | 时间（total） | 方法 |
|---:|---:|---|
| 0.69% | 0.96s | `ChunkSnowRefresh.refreshChunk` |
| 0.69% | 0.96s | `SnowyMapChecker.handler$...$estfc$afterForceChunkUpdate` |
| 0.45% | 0.62s | `ChunkSnowRefresh.columnTemperature` |
| 0.16% | 0.23s | `ConnectedGrassBlock.handler$...$estfc$clearConnectionOnShapeUpdate` |
| 0.15% | 0.21s | `ConnectedGrassBlock.estfc$clearHorizontalConnections` |
| 0.13% | 0.18s | `WeatherManager.handler$...$estfc$getRainOrSnow` |
| 0.10% | 0.13s | `ChunkSnowRefresh.applyOnLoad` |
| 0.09% | 0.12s | `RegionClimatePayloads.onServerTick` |
| 0.05% | 0.07s | `ChunkSnowRefresh.prepare` / `isSkippable` |
| 0.03% | 0.04s | `SnowyStatusKeeper.estfc$getPerBlockDepth` |
| 0.02% | 0.03s | `WeatherCycleService.tickWeatherCycle` |

**结论**：这次采样里 estfc 全部服务端路径合计约 2–3s，约为活跃时间的 1.5–2%，
不是主要瓶颈；最大头是 Sable/Rapier 物理、NeoForge/Architectury 事件总线、
PalettedContainer/区块存取。区块 tick 本身占 13%，其中原版/其它模组占绝大部分。

## 第二次采样（2026-10-28 14:15，本地 sparkprofile）

- 文件：`D:\G_\_Minecraft\0\versions\1.21.1-放逐\config\spark\profile-2026-10-28_14.15.40.sparkprofile`
- 仅 Server thread，3 个窗口，总 160.4s；`Unsafe.park` 132.4s（82.6%），活跃 28.0s。
- 活跃 top（self）：`JNI.invokeD` 3.43%、`Sable Rapier3D.step` 3.12%、
  `SimpleBitStorage.lithium$compact` 2.94%、`ChunkMap.saveChunkIfNeeded` 2.24%、
  `NaturalSpawner.spawnCategoryForChunk` 2.16%、`LevelHeightAccessor.getMinSection` 1.93%、
  `BiomeManager.getBiome` 1.82%、`PalettedContainer.pack` 1.66%、`EventBus.post` 1.52%。
- estfc：`ChunkSnowRefresh.refreshChunk` 0.64s（2.3% 活跃），
  新的 `ChunkTemperature.at` 只有 0.06s（0.21%），说明区块锚点优化后逐列 TFC 查询已不是热点；
  `ChunkSnowRefresh.prepare` 0.03s，`WeatherCycleService` 0.00s。
- ES 自身：`AllListener.onChunkLoad` 0.73s（2.6%）、`SnowyStatusKeeper$Serializer.write` 0.49s（1.7%）、
  `SnowyMapChecker.forceChunkUpdateHeight` 0.42s（1.5%）。
- 该 profile 只有 Server thread；Voxy 客户端 LOD 构建不在内。
