# png_snowy_drawn — 手绘覆雪贴图覆盖

gen_snowy.py 自动生成的某些覆雪贴图效果不佳时，可在此放入手绘贴图，
**编译时**由 Gradle 任务 `overlayDrawnTextures` 自动覆盖到
`src/main/resources/assets/estfc/textures/block/` 的同相对路径。

## 结构

相对路径与 `textures/block/` 一一对应，例如：

```
src/png_snowy_drawn/plant/allium/allium_0.png
    → 覆盖 src/main/resources/assets/estfc/textures/block/plant/allium/allium_0.png
```

- 只有存在的相对路径会被覆盖；目标目录缺失时自动创建。
- 目录不存在时（当前状态）构建会打印 WARNING 并跳过，不影响构建。
- 优先级：gen_snowy.py 先生成 → 构建时手绘贴图覆盖 → 打包进 jar。
