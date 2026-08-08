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
│  IntelliJ IDEA                                                 │
│                                                                │
│   SvgEditorToolWindowFactory  ── 加载 resvg_bridge (.dll)       │
│        │                                                       │
│        ▼                                                       │
│   SvgEditorPanel (Swing/JPanel)                                │
│     · 离屏 canvas (BufferedImage) = resvg 渲染结果              │
│     · MouseMotionListener / MouseListener                      │
│     · 坐标映射 (panel ↔ SVG) + 碰撞检测 + 拖拽编辑              │
└───────────────────────────┬──────────────────────────────────┘
                             │ 使用
                             ▼
┌──────────────────────────────────────────────────────────────┐
│  core (Kotlin/JVM, 无 IntelliJ 依赖, 可独立单测)                │
│   SvgEditorEngine ── SvgRenderer (接口)                         │
│   CollisionDetector · InteractionController                    │
│   SvgLayout / SvgElement · Json (解析) · SvgUtils (改写源码)    │
│   SvgEditorPanel (纯 Swing，便于无 SDK 单测)                    │
└───────────────────────────┬──────────────────────────────────┘
                             │ JNA
                             ▼
┌──────────────────────────────────────────────────────────────┐
│  resvg_bridge (Rust cdylib)   ←── cargo build  ── resvg + usvg  │
│   svg_render_png_bytes()  → PNG 字节 (离屏 canvas 源)           │
│   svg_layout_json()       → 每个元素的 id / 绝对包围盒 / 矩阵    │
└──────────────────────────────────────────────────────────────┘
```

数据流向：
`SVG 文本 → resvg_bridge.render → PNG 字节 → 离屏 BufferedImage`
`SVG 文本 → resvg_bridge.layout_json → JSON → SvgLayout(包围盒列表)`
`鼠标坐标 → 坐标映射 → CollisionDetector.hitTest → hover/选中`
`拖拽 → InteractionController → SvgUtils.applyTranslate → 改写源码 → 重新 render`

---

## 3. 原生层：`resvg_bridge`（Rust）

文件：`native/resvg_bridge/`

- 依赖：`resvg = "=0.43.0"`、`usvg = "=0.43.0"`（精确锁定以保证 ABI/API 一致）。
- 编译产物：`target/{debug,release}/resvg_bridge.dll`（Windows）。
- 对外暴露 **C-ABI** 函数（供 JNA 调用）：

| 函数 | 作用 |
| --- | --- |
| `svg_render_png_bytes(svg, fitW, fitH, *len, *w, *h) -> *u8` | 渲染 SVG 为 **PNG 字节**（离屏 canvas 的源）。`fitW/fitH=0` 按原始尺寸。 |
| `svg_free_bytes(ptr)` | 释放上者返回的缓冲区（用全局注册表记录 len/cap）。 |
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
| `SvgRenderer` (接口) | 渲染 + 布局抽象；`ResvgBridge` 是生产实现，`FakeSvgRenderer` 用于测试。 |
| `ResvgBridge` | JNA 封装：UTF-8+NUL 编码入参，拷贝出参后立刻释放原生内存。 |
| `SvgLayout` / `SvgElement` | 布局数据模型；`hitTest` / `intersecting` 命中查询。 |
| `Json` | 极简 JSON 解析器（仅支持本 schema 所需子集，零依赖）。 |
| `CollisionDetector` | 碰撞检测：点命中（取最上层）、矩形相交（框选）、元素两两重叠。 |
| `InteractionController` | 鼠标交互状态机：`IDLE / HOVER / DRAG`，产生拖拽增量 `DragResult`。 |
| `SvgUtils` | 源码级编辑：给目标 `id` 的元素插入/叠加 `transform="translate(dx,dy)"`。 |
| `SvgEditorEngine` | 引擎：持有 SVG 源码为唯一真相，调用 `render`+`layoutJson` 刷新，提供 `moveElement`。 |
| `SvgEditorPanel` (Swing) | 编辑器面板（见第 5、6 节）。 |

`core` **不依赖 IntelliJ API**，因此可在普通 JVM 上用 Gradle/JUnit 直接跑单测。

---

## 5. IntelliJ 插件层

文件：`plugin/`

- `SvgEditorToolWindowFactory`：注册右侧 Tool Window，加载 `resvg_bridge`（优先打包进插件，
  否则回退到本地 `target/{debug,release}` 构建），注入 `SvgEditorPanel`。
- `META-INF/plugin.xml`：声明 `toolWindow` 扩展点。
- 构建：`org.jetbrains.intellij.platform` Gradle 插件（需要联网下载 IDEA SDK）。

---

## 6. 离屏 canvas 与坐标映射（需求 3）

`SvgEditorPanel` 把 `resvg` 渲染出的 PNG 解码为离屏 `BufferedImage`（即“离屏 canvas”），
在 `paintComponent` 里用 `g.drawImage` 把它合成到可见面板。`MouseMotionListener`
监听指针位置，做两件事：

1. **坐标映射**：`panel 像素 → SVG/canvas 坐标`（`toImage`：去掉偏移并按 `viewScale` 缩放）。
2. **碰撞检测**：用映射后的坐标调用 `CollisionDetector.hitTest`，得到指针下方的元素，
   画高亮框 + tooltip（元素 `id` 与类型）。

拖拽过程中还会画一个半透明的“幽灵”矩形，预览元素移动后的位置——同样基于布局包围盒与
拖拽增量，验证了“用布局信息做碰撞检测 / 离屏 canvas”的路线。

---

## 7. 拖拽编辑（需求 1）

- 鼠标按下：若指针命中某元素，则 `InteractionController` 进入 `DRAG` 并记下起点。
- 拖拽中：累计相对起点的位移 `(dx, dy)`（画布坐标）。
- 释放：`engine.moveElement(id, dx, dy)` → `SvgUtils.applyTranslate` 在 SVG 源码里给该
  元素叠加 `transform="translate(dx,dy)"` → 重新 `render` + 重新 `layoutJson` → 离屏 canvas
  与布局同步刷新。

因为“唯一真相”是 SVG 文本，编辑结果天然可被任意 SVG 工具继续处理。

---

## 8. 测试策略（需求 4）

| 测试 | 文件 | 是否需要原生/SDK |
| --- | --- | --- |
| Rust 单元/集成测试（渲染 + 布局提取） | `native/resvg_bridge/src/lib.rs` `#[cfg(test)]` | 需要 cargo（自带） |
| `SvgLayoutTest` | `core/src/test` | 否 |
| `CollisionDetectorTest` | `core/src/test` | 否 |
| `InteractionControllerTest` | `core/src/test` | 否 |
| `SvgUtilsTest` | `core/src/test` | 否 |
| `EngineFakeRendererTest` | `core/src/test` | 否（FakeSvgRenderer） |
| `SvgEditorPanelTest` | `core/src/test` | 否（FakeSvgRenderer + 合成鼠标事件） |
| `ResvgIntegrationTest` | `core/src/test` | **需要** `resvg_bridge.dll`，否则自动跳过 |

纯逻辑测试不依赖任何原生库，保证 CI 无 Rust 工具链也能绿；集成测试在 `cargo build`
之后跑完整 `render → layout → 碰撞 → 编辑` 闭环。

---

## 9. 构建与运行

```bash
# 1) 原生库（产出 resvg_bridge.dll）
cd native/resvg_bridge
cargo test            # 编译 + 跑 Rust 测试
cargo build --release # 产出 target/release/resvg_bridge.dll

# 2) Kotlin 单测（无需 IDEA SDK）
./gradlew :core:test  # 纯逻辑 + 面板（FakeSvgRenderer）

# 3) 端到端集成测试（需要上面构建出的 dll）
./gradlew :core:test  # ResvgIntegrationTest 自动发现并运行；无 dll 时跳过

# 4) 构建/运行插件（需要联网下载 IDEA SDK）
./gradlew :plugin:buildPlugin
./gradlew :plugin:runIde
```

---

## 10. 文件结构

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
