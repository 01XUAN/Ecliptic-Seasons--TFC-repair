**ESTFC** 是一个将 **[Ecliptic Seasons（节气）](https://github.com/TeamTeaMC/Ecliptic-Seasons)** 的视觉积雪系统与 **[TerraFirmaCraft（TFC）](https://github.com/TerraFirmaCraft/TerraFirmaCraft)** 兼容的模组。  
**核心目标**：用节气的**纯渲染视觉雪**替代 TFC 的**物理雪**，完整保留 **TFC 的降雪逻辑**，并确保与 **[Distant Horizons（DH）](https://gitlab.com/distant-horizons-team/distant-horizons)** 兼容，使雪景更新**不再局限于已加载区块**。

---

### 1. 基于 TFC 气候的积雪

- **温度**  
  雪的出现、累积和融化完全由 **TFC 的实时温度和天气** 决定，替代了 ES 原有的群系雪深逻辑。

- **降水**  
  仅当 **TFC 正在降水且温度低于阈值** 时雪才会积累。

- **海拔**  
  依照 **TFC 的海拔递减率（-1.6°C/10 格）** 动态变化，使雪线随海拔温度自然起伏。

---

### 2. 节气 视觉雪替换 TFC 物理雪

- **完全阻止** TFC 在区块 tick 中放置物理雪方块。

- 所有符合条件的 TFC 方块（草地、植物、树叶、木枝等）会被替换为**视觉覆盖雪模型**，**仅改变外观**。

- 支持**两种雪纹理模式**：  
  - **简单模式**（`simpleSnow`） – 所有面统一使用 `estfc:block/snow` 贴图。  
  - **替换模式**（默认） – 逐面将 TFC 原纹理替换为 `estfc` 命名空间下的对应雪纹理。

- **标签支持**：通过数据包或其他模组，使用 `estfc:simple_snow` 和 `estfc:snowable` 标签**扩展覆雪方块**。

---

### 3. 与 Distant Horizons 模组兼容

- 在 **DH 生成的 LOD 网格** 中，根据修正后的 TFC 温度为方块着色，使**远景雪色与近景一致**。

- 关于 **Voxy** 模组，通过 *Ecliptic Seasons: VoxyCompact* 兼容，但是只针对部分 Voxy 版本，其中对于[这个版本](https://github.com/NHblock714/voxy)不需要 Ecliptic Seasons: VoxyCompact（Voxy 里面已经内置），并且兼容较好。