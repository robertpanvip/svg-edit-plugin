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

## 独立运行（exe，无需 IDEA）

除了 IDEA 插件形态，本项目还包含一个 **IntelliJ 无关的独立运行时**：直接用 `resvg` 渲染、
做碰撞检测与拖拽编辑，打包成双击即运行、内嵌 JRE 的 `.exe`。非常适合本地随手测试。

```bash
# 1) 先把原生库编进资源（仅首次 / 重新编译 resvg 后需要）
cp native/resvg_bridge/target/debug/resvg_bridge.dll app/src/main/resources/native/resvg_bridge.dll

# 2) 运行（GUI 模式；也可加 --smoke 做无头自测）
./gradlew :app:run
./gradlew :app:run --args="--smoke"

# 3) 打包成独立 exe（产物：app/build/dist/SvgEditor/SvgEditor.exe，含 runtime/ 内嵌 JRE）
./gradlew :app:packageExe
# 再打个 zip 便于分发：app/build/dist/SvgEditor.zip
./gradlew :app:packageExeZip

# 直接运行（无需 JDK）：
app/build/dist/SvgEditor/SvgEditor.exe
```

`SvgEditor.exe` 会把内嵌的 `resvg_bridge.dll`（打包在 jar 资源里）解压到临时目录并加载，
所以整目录拷到任何 Windows 机器都能跑。若重新编译了 `resvg_bridge`，记得重做第 1 步再打包。

## 模块

| 模块 | 说明 |
| --- | --- |
| `native/resvg_bridge` | Rust cdylib，桥接 resvg/usvg，输出 PNG 字节与布局 JSON（C-ABI）。 |
| `core` | Kotlin 引擎：布局模型、碰撞检测、交互状态机、JNA 桥接、Swing 面板。无 IntelliJ 依赖。 |
| `app` | 独立运行时入口：Swing 窗口 + 源编辑器 + 拖入 `.svg` + `--smoke` 自测 + jpackage 打包。 |
| `plugin` | IntelliJ 工具窗口与 `plugin.xml`，把原生库接入 IDE。 |

详见 [DESIGN.md](DESIGN.md)。
