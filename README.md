# SVG Editor Plugin

一个 IntelliJ IDEA 的 SVG 编辑器插件：在常规 SVG **查看** 之外，提供 **编辑** 与 **拖拽** 能力。
渲染与布局信息来自 **resvg / usvg**（Rust），面板交互、碰撞检测、离屏 canvas 用 **Kotlin** 实现。

## 特性

- 用 `resvg` 把 SVG 渲染成位图，显示在编辑器面板（离屏 `BufferedImage`）。
- 用 `usvg` 提取每个元素的 **绝对包围盒** 与变换矩阵（布局信息）。
- 面板监听鼠标位置，基于布局做 **碰撞检测**（hover 高亮 + tooltip）。
- 点击拖拽即可 **移动元素**，改动写回 SVG 源码并实时重渲染。
- 纯 Kotlin 核心层，可在无 IntelliJ SDK 的环境下用 JUnit 直接单测。

## 快速开始

```bash
# 构建原生库（产出 resvg_bridge.dll）
cd native/resvg_bridge && cargo test && cargo build --release

# 运行 Kotlin 测试（纯逻辑 + 面板 + 端到端集成）
./gradlew :core:test

# 构建/运行插件（需联网下载 IDEA SDK）
./gradlew :plugin:buildPlugin
./gradlew :plugin:runIde
```

## 独立运行（原生 app，无需 IDEA 也无需 JVM）

除了 IDEA 插件形态，本项目还包含一个**完全独立的桌面编辑器** `native/svg_easy`：纯 Rust + GPUI
的单文件二进制，进程内直连 `resvg_bridge` 渲染，没有 JVM、没有宿主进程、没有 sidecar。

```bash
# 运行（不带参数则打开内置示例文档）
cd native/svg_easy && cargo run
cargo run -- path/to/file.svg     # 打开文件，Ctrl+S 写回

cargo test                        # 文档模型 + 引擎单测
```

发版时由 `.github/workflows/build-app.yml` 在三个平台各打一个归档
（`svg_easy-<OS>-<ARCH>.tar.gz` / `.zip`），挂到同一个 tag 的 Release 上。

## 模块

| 模块 | 说明 |
| --- | --- |
| `native/resvg_bridge` | Rust cdylib，桥接 resvg/usvg，输出 PNG 字节与布局 JSON（C-ABI）。 |
| `native/svg_easy` | 独立桌面编辑器：Rust + GPUI，进程内直接用 `resvg_bridge`，无 JVM。 |
| `core` | Kotlin 引擎：布局模型、碰撞检测、交互状态机、JNA 桥接、Swing 面板。无 IntelliJ 依赖。 |
| `plugin` | IntelliJ 工具窗口与 `plugin.xml`，把原生库接入 IDE。 |

详见 [DESIGN.md](DESIGN.md)。
