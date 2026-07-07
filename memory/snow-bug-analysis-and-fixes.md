---
name: snow-bug-analysis-and-fixes
description: Analysis of snow coverage bugs in estfc mod — TFC temperature Z-dependence and config system
metadata:
  type: project
---

# Snow Bug Analysis & Fixes

## TFC Temperature Formula (Critical for understanding both bugs)

TFC `OverworldClimateModel.getInstantTemperature()`:
```
temp = avgSeaLevelTemp(x, z, ChunkData)    ← ONLY piece needing ChunkData!
     + monthlyTemperature(z, monthFactor)   ← depends only on Z (latitude) ✓
     + dailyTemperature(z, calendarTime)    ← depends only on Z + calendar ✓
     + elevationAdjustment(y)              ← depends only on Y (-1.6°C/10m) ✓
```

For `ChunkData.EMPTY` (unloaded chunks): `avgSeaLevelTemp = 10°C` (constant worldwide).
For real ChunkData: `avgSeaLevelTemp = temperatureLayer.getValue(...)` (varies with X,Z based on worldgen noise, primarily Z-dependent).

**Key insight**: `monthlyTemperature(z)`, `dailyTemperature(z)`, and `elevationAdjustment(y)` are ALL correct even with EMPTY ChunkData. Only `avgSeaLevelTemp` is wrong.

## Bug #1: Near-field edge block snow missing

Not fully diagnosed. The `SnowyMapCheckerMixin` on the server side and `ExtraModelManagerMixin` on the client side use TFC temperature for snow decisions. ChunkData sync delay on client could cause temporary wrong snow rendering until the server sends ChunkWatchPacket, but user says this is not the focus.

## Bug #2: DH distant view grid snow

**Root cause**: `MapChecker.shouldSnowAtBiome` calls `getInstantTemperature()` for unloaded chunks → `avgSeaLevelTemp = 10°C`. In cold regions (real avg ~-5°C), this causes 15°C temperature jump at loaded/unloaded boundaries → snow/no-snow grid.

**Previous wrong fix**: Used player position as temperature reference. **WRONG** because TFC temperature depends on Z coordinate (latitude), not player position.

**Current fix**: `MapCheckerMixin` builds a Z→avgSeaLevelTemp cache from loaded chunks:
1. Samples `getAverageTemperature(level, posAtSeaLevel)` at multiple Z coordinates within the loaded area
2. At y=64 (SEA_LEVEL), `getAverageTemperature = avgSeaLevelTemp` (no elevation adjustment needed)
3. For unloaded positions, interpolates the cache by target Z to estimate `avgSeaLevelTemp(Z)`
4. Computes: `temp_correct = temp_EMPTY - 10 + estimatedAvgSeaLevelTemp(Z) + configOffset`

Cache is rebuilt when:
- Cache is older than 2 seconds
- Player moves >128 blocks in Z (changed climate zone)

## Config System

Full NeoForge ModConfigSpec system in `Config.java`:
- **Snow Temperature**: `snowAppearanceTemp` (0°C), `snowFullCoverageTemp` (-4°C), `snowMeltTemp` (0°C), `dhUnloadedTempOffset` (0°C)
- **Climate Calculation**: `elevationLapseRate` (-1.6), `snowAccumulationSpeedMultiplier` (1.0), `snowMeltSpeedMultiplier` (1.0)
- **Snow Texture**: `snowTextureSimpleMode`, `snowTextureReplaceMode`, `snowTextureDefaultPath`
- **Debug**: `debugLogTemperature`, `debugLogSnowPlacement`

Config reload listener in main mod class updates cached values at runtime.
`WeatherBridge.tempToSnowDepth()` and `WeatherManagerMixin` use config values.

**How to apply**: Build with `./gradlew build`. Config at `config/estfc-common.toml`.
