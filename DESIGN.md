# SVG Editor Plugin — 设计文档

一个面向 **IntelliJ IDEA** 的 SVG 编辑器插件。除了基础的 SVG *查看*，还提供
**编辑**与**拖拽**能力；渲染与布局信息来自 **resvg / usvg**（Rust），面板交互、
碰撞检测、离屏 canvas 全部用 **Kotlin** 实现。

---

## 1. 目标

1. **查看**：把 SVG 渲染成位图显示在面板里。
2. **布局信息**：用 `resvg`/`usvg` 解析 SVG，拿到每个元素的**绝对包围盒**与变换矩阵。
3. **编辑 / 拖拽**：在面板上选中元素、拖拽移动，改动写回 SVG 源码并重新渲染。
4. **碰撞检测**：面板监听鼠标位置，用布局信息做命中测试（hover 高亮、框选）。
5. **离屏 canvas**：`resvg` 渲染结果先画到一张离屏 `BufferedImage`，再合成到可见面板。
6. **测试**：原生层 Rust 测试 + Kotlin 单元测试 + 端到端集成测试。

---

## 2. 架构总览

```
┌──────────────────────────────────────────────────────────────┐
│  IntelliJ IDEA / 独立 app                                     │
│                                                                │
│   SvgEditorToolWindowFactory / AppMain                         │
│        │  SvgEditorPanel(asyncRendering = true) + dispose      │
│        ▼                                                       │
│   SvgEditorPanel (Swing)                                       │
│     · EDT 只做 blit 与浮点矢量覆盖层（选中框/手柄/marquee）      │
│     · RenderScheduler：单后台渲染线程，latest-wins             │
│     · 鼠标/键盘 → InteractionController → 引擎零光栅化提交      │
└───────────────────────────┬──────────────────────────────────┘
                             │ 使用
                             ▼
┌──────────────────────────────────────────────────────────────┐
│  core (Kotlin/JVM, 无 IntelliJ 依赖, 可独立单测)                │
│   SvgEditorEngine ── 模型 + 布局 + 编辑（不产光栅）              │
│   RenderScheduler（异步队列）· RgbaImages（RGBA 直接打包）       │
│   EditorTheme（LeaferJS 视觉常量）                              │
│   CollisionDetector · InteractionController                    │
│   SvgLayout / SvgElement · Json · SvgUtils                     │
│   SvgEditorPanel (纯 Swing，便于无 SDK 单测)                    │
└───────────────────────────┬──────────────────────────────────┘
                             │ JNA
                             ▼
┌──────────────────────────────────────────────────────────────┐
│  resvg_bridge (Rust cdylib)   ←── cargo build  ── resvg + usvg  │
│   svg_render_rgba_bytes() → 预乘 RGBA8（跳过 PNG 编解码）       │
│   svg_render_png_bytes()  → PNG 字节（兼容路径）                │
│   svg_layout_json()       → 每个元素的 id / 绝对包围盒 / 矩阵    │
└──────────────────────────────────────────────────────────────┘
```

数据流向：
`SVG 文本 → resvg_bridge.svg_render_rgba_bytes → 预乘 RGBA8 → RgbaImages.fromRgba（TYPE_INT_ARGB_PRE，零 PNG 往返）→ 离屏 BufferedImage`
`SVG 文本 → resvg_bridge.layout_json → JSON → SvgLayout(包围盒列表)`
`鼠标坐标 → 坐标映射 → CollisionDetector.hitTest → hover/选中/框选`
`拖拽 → InteractionController(EditResult) → 引擎 reloadLayout（仅重算布局）→ 提交时面板后台重渲`

---

## 3. 原生层：`resvg_bridge`（Rust）

文件：`native/resvg_bridge/`

- 依赖：`resvg = "=0.43.0"`、`usvg = "=0.43.0"`（精确锁定以保证 ABI/API 一致）。
- 编译产物：`target/{debug,release}/resvg_bridge.dll`（Windows）。
- 对外暴露 **C-ABI** 函数（供 JNA 调用）：

| 函数 | 作用 |
| --- | --- |
| `svg_render_rgba_bytes(svg, fitW, fitH, *len, *w, *h) -> *u8` | 渲染为 **预乘 RGBA8** 像素缓冲（跳过 PNG 编码，编辑器主管线）。`fitW/fitH=0` 按原始尺寸。 |
| `svg_render_png_bytes(svg, fitW, fitH, *len, *w, *h) -> *u8` | 渲染为 **PNG 字节**（兼容/调试路径）。 |
| `svg_free_bytes(ptr)` | 释放上两者返回的缓冲区（用全局注册表记录 len/cap）。 |
| `svg_layout_json(svg) -> *char` | 返回 JSON：文档宽高 + 每个元素的 `index/id/kind/x/y/width/height/transform`。 |
| `svg_free_string(s) -> void` | 释放上者返回的字符串。 |

布局提取逻辑（`lib.rs` 中 `collect`）：
- 从 `tree.root()` 递归遍历 `Group.children()`；
- 对每个节点取 `abs_bounding_box()`（画布坐标系，已是像素单位）与 `abs_transform()`；
- 跳过面积为 0 的节点（空 group 等不可命中元素）；
- 序列化成紧凑 JSON（无第三方 JSON 依赖）。

`resvg::render(tree, transform, pixmap.as_mut())` 完成栅格化；`pixmap.encode_png()` 得到 PNG。

---

## 4. 核心层：`core`（Kotlin/JVM）

文件：`core/src/main/kotlin/com/example/svgeditor/core/`

| 类名 | 职责 |
| --- | --- |
| `SvgRenderer` (接口) | 渲染 + 布局抽象；`renderRgba`（预乘 RGBA，主管线）与 `render`（PNG，兼容）；`ResvgBridge` 是生产实现，`FakeSvgRenderer` 用于测试。 |
| `ResvgBridge` | JNA 封装：UTF-8+NUL 编码入参，拷贝出参后立刻释放原生内存。 |
| `RgbaImages` | 把预乘 RGBA8 字节直接打包为 `TYPE_INT_ARGB_PRE` 的 `BufferedImage`——零 PNG 编解码往返。 |
| `RenderScheduler` | 单后台渲染线程的任务队列（CONTENT / LAYERS 两个槽位，latest-wins；可同步内联模式供测试）。 |
| `SvgLayout` / `SvgElement` | 布局数据模型；`hitTest` / `intersecting` 命中查询。 |
| `Json` | 极简 JSON 解析器（仅支持本 schema 所需子集，零依赖）。 |
| `CollisionDetector` | 碰撞检测：点命中（取最上层）、矩形相交（框选）、元素两两重叠。 |
| `InteractionController` | 鼠标交互状态机（hover/拖动/缩放/旋转，含 snap），产出 `EditResult.Move/Resize/Rotate`。 |
| `SvgUtils` | 源码级编辑：`translate`/`rotate`/`matrix` 变换叠加，以及 `hideElement` / `soloElement`（分层渲染源构建）。 |
| `SvgEditorEngine` | 引擎：持有 SVG 源码为唯一真相。`load`/`renderAt` 全量渲染；`loadLayoutOnly` 与 `reloadLayout` 只重算布局不产光栅——拖拽提交零光栅化。 |
| `EditorTheme` | LeaferJS 风格视觉常量（主色 #836DFF、手柄、marquee、snap 色），宿主可统一换肤。 |
| `SvgEditorPanel` (Swing) | 编辑器面板（见第 6、7、11 节）。 |

`core` **不依赖 IntelliJ API**，因此可在普通 JVM 上用 Gradle/JUnit 直接跑单测。

---

## 5. IntelliJ 插件层

文件：`plugin/`

- `SvgEditorToolWindowFactory`：注册右侧 Tool Window，加载 `resvg_bridge`（优先打包进插件，
  否则回退到本地 `target/{debug,release}` 构建），注入 `SvgEditorPanel`。
- `META-INF/plugin.xml`：声明 `toolWindow` 扩展点。
- 构建：`org.jetbrains.intellij.platform` Gradle 插件（需要联网下载 IDEA SDK）。

---

## 6. 渲染管线与坐标映射（需求 3）

### 6.1 异步管线（卡顿治理）

旧实现把 PNG 解码/光栅化都压在 EDT 上（加载、按下选中、滚轮缩放各渲一次），大文档必卡。
重构后职责重新划分：

- **渲染只发生在后台**：`RenderScheduler`（单线程，latest-wins）持有 CONTENT（整图）与
  LAYERS（分层）两类任务；新请求替换同槽位旧任务，永不排队堆积。EDT 只做
  `g.drawImage` blit 与矢量覆盖层绘制。
- **零 PNG 往返**：Rust 侧 `svg_render_rgba_bytes` 产出预乘 RGBA8，
  `RgbaImages.fromRgba` 直接打包为 `TYPE_INT_ARGB_PRE` 位图。
- **失效守卫**：任务携带 `RenderTag`/`LayerTag`（svg 源引用 + 尺寸），回帖时做引用相等
  校验，过期结果直接丢弃。
- **缩放节流**：滚轮先重采样旧位图即时反馈，160 ms `javax.swing.Timer` 后台重渲清晰版。
- **hover 预热**：悬停 120 ms 后预取该元素的分层（bg/fg 两渲一个 LAYERS 任务成对返回），
  按下拖拽时层已就绪，拖动全程零光栅化。
- **分层合成**：选中元素时面板用 `SvgUtils.hideElement`（隐藏目标）渲背景层、
  `soloElement`（仅目标+祖先链）渲前景层；拖动中只把裁剪后的小图 `fgCrop` 用浮点
  `AffineTransform` 贴到预览框，拖拽→释放表示法一致，无“落地闪跳”。

### 6.2 坐标映射

`panel 像素 → SVG/canvas 坐标`（`toImage`：去掉偏移并按 `viewScale` 缩放），命中测试用
`CollisionDetector.hitTest`；空白处按下拖拽为 marquee 框选（`intersecting` 取最上层）。

---

## 7. 拖拽编辑（需求 1）

- **按下**：命中旋转手柄 → 旋转；命中元素 → `InteractionController` 进入拖拽并请求分层；
  空白处 → 进入 marquee。
- **拖拽中**：`onDragMove` 产出带 snap 的 `previewBox` / `previewAngle`，面板贴 fgCrop +
  重绘覆盖层，全程不触碰 resvg。
- **释放**：`EditResult.Move/Resize/Rotate` → 引擎 `moveElement`/`setElementBox`/
  `rotateElement` 以 `reloadLayout` 提交（只重算布局，不渲染）→ 面板后台重渲 CONTENT 与
  LAYERS → 提交结果与最后一帧预览逐像素一致。

因为“唯一真相”是 SVG 文本，编辑结果天然可被任意 SVG 工具继续处理。

---

## 8. 交互与视觉（LeaferJS 化重构）

**视觉**（全部集中在 `EditorTheme`，主色 LeaferJS 紫 `#836DFF`）：

- hover：半透明主色圆角描边（浮点 `RoundRectangle2D`，不再整数截断）。
- 选中：浮点 `Path2D` 旋转轮廓 + 8 个圆点手柄（`Ellipse2D`，白底主色描边）+
  顶部旋转杆与圆握把，替换旧的整型线段/方块手柄。
- 对齐参考线：Figma 风格红粉 `#FF3B5C`。
- 框选（marquee）：虚线主色边框 + 半透明填充；已移除旧十字准星。

**交互**：

- 空白处按下拖拽 = marquee 框选，释放时选中 `intersecting(...).lastOrNull()`（最上层）；
  <3 px 视为点击取消。
- 中键或按住 **空格** 拖拽 = 画布平移（操作 viewport，缩放锚点跟随光标）。
- 滚轮缩放带锚点 + 160 ms 节流重渲；Ctrl+滚轮保留语义。
- 光标随上下文切换（平移手型 / 缩放手柄 / 可拖 MOVE）。

**宿主适配**：

- 独立 app 与 IDEA 插件统一以 `SvgEditorPanel(renderer, asyncRendering = true)` 运行异步
  管线；测试与 headless 校验用默认同步模式（确定性）。
- 生命周期：app 窗口关闭 `panel.dispose()`；插件 ToolWindow 用 `Content.setDisposer`、
  `SvgPreviewPanel`（FileEditor）在 `dispose()` 释放渲染线程与计时器。

---

## 9. 测试策略（需求 4）

| 测试 | 文件 | 是否需要原生/SDK |
| --- | --- | --- |
| Rust 单元/集成测试（渲染 + 布局提取 + RGBA 输出） | `native/resvg_bridge/src/lib.rs` `#[cfg(test)]` | 需要 cargo（自带） |
| `SvgLayoutTest` | `core/src/test` | 否 |
| `CollisionDetectorTest` | `core/src/test` | 否 |
| `InteractionControllerTest` | `core/src/test` | 否 |
| `SvgUtilsTest` | `core/src/test` | 否 |
| `EngineFakeRendererTest` | `core/src/test` | 否（FakeSvgRenderer） |
| `RenderSchedulerTest` | `core/src/test` | 否（同步内联 + 线程队列语义） |
| `RgbaImagesTest` | `core/src/test` | 否（RGBA 打包正确性） |
| `SvgEditorPanelTest` | `core/src/test` | 否（FakeSvgRenderer + 合成鼠标事件） |
| `ResvgIntegrationTest` | `core/src/test` | **需要** `resvg_bridge` 动态库，否则自动跳过 |

纯逻辑测试不依赖任何原生库，保证 CI 无 Rust 工具链也能绿；集成测试在 `cargo build`
之后跑完整 `render → layout → 碰撞 → 编辑` 闭环。

---

## 10. 构建与运行

```bash
# 1) 原生库（产出 resvg_bridge.dll）
cd native/resvg_bridge
cargo test            # 编译 + 跑 Rust 测试
cargo build --release # 产出 target/release/resvg_bridge.dll

# 1b) 跨平台插件 zip：在 Linux 上交叉编译 Windows dll（需 mingw-w64：
#     apt install gcc-mingw-w64-x86-64），`:plugin:buildPlugin` 会把它
#     与 .so 一起打进插件 jar，一个 zip 通吃 Windows/Linux
RUSTFLAGS="-C target-feature=+crt-static" \
  cargo build --release --target x86_64-pc-windows-gnu # 产出 target/x86_64-pc-windows-gnu/release/resvg_bridge.dll

# 2) Kotlin 单测（无需 IDEA SDK）
./gradlew :core:test  # 纯逻辑 + 面板（FakeSvgRenderer）

# 3) 端到端集成测试（需要上面构建出的 dll）
./gradlew :core:test  # ResvgIntegrationTest 自动发现并运行；无 dll 时跳过

# 4) 构建/运行插件（需要联网下载 IDEA SDK）
./gradlew :plugin:buildPlugin
./gradlew :plugin:runIde
```

---

## 11. 文件结构

```
svg-editor-plugin/
├── settings.gradle.kts
├── core/                      # 纯 Kotlin 引擎 + 面板（可独立单测）
│   ├── build.gradle.kts
│   └── src/{main,test}/kotlin/com/example/svgeditor/core/
├── plugin/                    # IntelliJ 插件胶水（ToolWindow + plugin.xml）
│   ├── build.gradle.kts
│   └── src/main/{kotlin,resources/META-INF}/
├── native/resvg_bridge/       # Rust cdylib（resvg/usvg 桥接）
│   ├── Cargo.toml
│   └── src/lib.rs
├── samples/sample.svg
└── DESIGN.md / README.md
```
