**Ecliptic Seasons: TFC repair （ESTFC）**<br>

![image](icon_background.png)<br>

是一个将 **[Ecliptic Seasons（节气）](https://github.com/TeamTeaMC/Ecliptic-Seasons)** 的视觉雪系统代替了 **[TerraFirmaCraft（TFC）](https://github.com/TerraFirmaCraft/TerraFirmaCraft)** 的方块雪系统的模组，为了解决 **[Distant Horizons（DH）](https://gitlab.com/distant-horizons-team/distant-horizons)** 等模组的 TFC 远景没有实时雪景的缺陷。

---

### 1. 雪与贴图☃️

- 雪的出现、累积和融化完全由 **TFC 的温度和天气** 决定，而温度同时受海拔影响，并且阻止了 TFC 放置物理雪方块。<br>
  *<font color="#8e8e8e">由于 TFC 每个地方的温度差异无法使用 ES 原有的群系雪深逻辑，不可避免的出现新加载区块与已加载区块积雪量不同而产生的断层。</font>*

- 给 TFC 方块添加**视觉雪覆盖**，同时支持**两种雪纹理模式**：  
  - **简单模式**（`simpleSnow`） – 所有面统一使用 `estfc:block/snow` 贴图。  
  - **替换模式**（默认） – 逐面将 TFC 原纹理替换为 `estfc` 命名空间下的对应雪纹理。

- **标签支持**：通过数据包或其他模组，使用 `estfc:simple_snow`<font color="#8e8e8e">(简单模式)</font> 和 `estfc:snowable`<font color="#8e8e8e">(替换模式)</font> 标签**扩展覆雪方块**。

---

### 2. 模组兼容🖇️

- 在 **DH 生成的 LOD 网格** 中，根据修正后的 TFC 温度为方块着色，使**远景雪色与近景尽可能接近**。

- 关于 **Voxy** 模组，已通过 *Ecliptic Seasons: VoxyCompact* 兼容，但是只针对部分 Voxy 版本，其中对于[这个版本](https://github.com/NHblock714/voxy)不需要 Ecliptic Seasons: VoxyCompact（Voxy 里面已经内置），并且有额外的功能。

---

### 3. 已知问题⚠️

- **开启 TFC 自带的连接纹理（`tfcConnectedTexture = true`）时，覆雪贴图会失效**<br>
  <font color="#8e8e8e">连接纹理与覆雪贴图渲染冲突，当前只能在两者之间二选一；默认关闭连接纹理以保证覆雪正常。</font>

- **DH 远景高度采样精度问题，覆雪与实际有偏差**<br>
  <font color="#8e8e8e">高度数据来自 TFC 的 `getBaseHeight`，大尺度的高山/低谷在远景中表现正确，但局部陡峭地形（单座尖峰、窄谷、凹坑）会被平滑，导致远景雪线与实际有明显偏差。</font>

- **覆雪贴图全是py脚本生成的**<br>
  <font color="#8e8e8e">不好看:(</font>

- **开始下雪后已加载区块和新加载区块覆雪率不一致**<br>
  <font color="#8e8e8e"></font>

---
<br>*❗<font color="#feeb43">必要的前置模组：</font>**[Ecliptic Seasons（节气）](https://github.com/TeamTeaMC/Ecliptic-Seasons)**，**[TerraFirmaCraft（TFC）](https://github.com/TerraFirmaCraft/TerraFirmaCraft)***<br>
*❗<font color="#fa8c8c">此模组使用了AI编写</font>*<br>
*❗<font color="#fa8c8c">使用本模组前请备份存档</font>*