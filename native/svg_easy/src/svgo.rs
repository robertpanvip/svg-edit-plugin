//! The standalone app's SVGO settings file, plus the Chinese names for the engine's catalogue.
//!
//! The engine owns the pass list and its defaults ([`resvg_bridge::optimize`]); this module only
//! stores the user's exceptions and gives the settings dialog localized text. The IntelliJ plugin
//! keeps its own store on the IDE side, so neither front end needs the other's plumbing — both
//! speak to the same engine.
//!
//! Settings are a single JSON file under the platform's config directory. A missing or unreadable
//! file is not an error: it just means "SVGO defaults", which is also what a corrupt file should
//! degrade to rather than blocking the dialog.

use std::fs;
use std::path::{Path, PathBuf};

use resvg_bridge::optimize::OptimizeOptions;

/// The user's saved settings, or the engine defaults when there is nothing readable on disk.
pub fn load_options() -> OptimizeOptions {
    load_from(config_path().as_deref())
}

/// Writes the settings back. Failures are silent: losing a preference is not worth an error
/// banner, and the next save will try again.
pub fn save_options(options: &OptimizeOptions) {
    save_to(config_path().as_deref(), options);
}

/// `~/.config/svg_easy/svgo.json` on Linux, `%APPDATA%\svg_easy\svgo.json` on Windows,
/// `~/Library/Application Support/svg_easy/svgo.json` on macOS.
///
/// Hand-rolled rather than pulled from a `directories`-style crate: three cases and no other
/// config in the app, so a dependency would be more code than this.
fn config_path() -> Option<PathBuf> {
    let dir = match std::env::consts::OS {
        "windows" => PathBuf::from(std::env::var_os("APPDATA")?),
        "macos" => PathBuf::from(std::env::var_os("HOME")?).join("Library/Application Support"),
        _ => match std::env::var_os("XDG_CONFIG_HOME") {
            Some(dir) => PathBuf::from(dir),
            None => PathBuf::from(std::env::var_os("HOME")?).join(".config"),
        },
    };
    Some(dir.join("svg_easy").join("svgo.json"))
}

fn load_from(path: Option<&Path>) -> OptimizeOptions {
    path.and_then(|path| fs::read_to_string(path).ok())
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

fn save_to(path: Option<&Path>, options: &OptimizeOptions) {
    let Some(path) = path else { return };
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(text) = serde_json::to_string_pretty(options) {
        let _ = fs::write(path, text);
    }
}

/// The settings dialog's Chinese label for a pass.
///
/// The engine's catalogue is the source of truth for *which* passes exist; this table only
/// translates. A pass the table does not know yet still appears, under its English label, so a
/// newer engine can add one without the app dropping it.
pub fn label<'a>(name: &str, fallback: &'a str) -> &'a str {
    LABELS
        .iter()
        .find(|(key, _)| *key == name)
        .map(|(_, label)| *label)
        .unwrap_or(fallback)
}

/// The Chinese heading for a catalogue group, or the engine's own name when it is new to us.
pub fn group_label(group: &str) -> &str {
    match group {
        "Document" => "文档",
        "Structure" => "结构",
        "Attributes" => "属性",
        "Styles" => "样式",
        "Shapes" => "形状与路径",
        other => other,
    }
}

const LABELS: &[(&str, &str)] = &[
    ("removeDoctype", "移除 DOCTYPE 声明"),
    ("removeXMLProcInst", "移除 XML 声明"),
    ("removeComments", "移除注释"),
    ("removeMetadata", "移除 metadata"),
    ("removeEditorsNSData", "移除编辑器命名空间"),
    ("removeDesc", "移除 desc 描述"),
    ("cleanupIds", "压缩 id（无用即删）"),
    ("removeUselessDefs", "移除无用的 defs"),
    ("removeUnusedNS", "移除未使用的命名空间"),
    ("removeEmptyAttrs", "移除空属性"),
    ("removeEmptyContainers", "移除空容器"),
    ("removeEmptyText", "移除空 text"),
    ("removeHiddenElems", "移除隐藏元素"),
    ("collapseGroups", "折叠无用分组"),
    ("sortDefsChildren", "排序 defs 子节点"),
    ("cleanupAttrs", "清理属性中的多余空白"),
    ("cleanupNumericValues", "数值取整、去掉默认单位"),
    ("cleanupEnableBackground", "清理 enable-background"),
    ("convertColors", "颜色写法的等价最短形式"),
    ("convertTransform", "transform 写法的等价最短形式"),
    ("removeUnknownsAndDefaults", "移除默认值与非法项"),
    ("removeNonInheritableGroupAttrs", "移除分组上不会继承的属性"),
    ("removeUselessStrokeAndFill", "移除无用的 stroke / fill"),
    ("sortAttrs", "按固定顺序排序属性"),
    ("inlineStyles", "把 <style> 内联到元素"),
    ("minifyStyles", "压缩 <style> 内容"),
    ("mergeStyles", "合并多个 <style>"),
    ("convertShapeToPath", "基本图形转 <path>"),
    ("convertEllipseToCircle", "正椭圆转 <circle>"),
    ("convertPathData", "路径数据最短化"),
    ("mergePaths", "合并相邻 <path>"),
    ("moveElemsAttrsToGroup", "元素属性上提到分组"),
    ("moveGroupAttrsToElems", "分组属性下放到元素"),
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_engine_pass_has_a_chinese_label() {
        for pass in resvg_bridge::optimize::PASSES {
            assert_ne!(
                label(pass.name, pass.label),
                pass.label,
                "{} has no Chinese label",
                pass.name
            );
        }
        // ...and the table has no entries the engine does not know, which would be a stale
        // leftover after a pass is renamed.
        for (name, _) in LABELS {
            assert!(
                resvg_bridge::optimize::PASSES.iter().any(|p| p.name == *name),
                "{name} is not in the engine catalogue"
            );
        }
    }

    #[test]
    fn an_unknown_pass_falls_back_to_the_engine_label() {
        assert_eq!(label("somethingNew", "Something new"), "Something new");
    }

    #[test]
    fn a_missing_file_means_the_defaults() {
        let options = load_from(Some(Path::new("/nonexistent/svg_easy/svgo.json")));
        assert!(options.passes.is_empty());
        assert_eq!(options.enabled_count(), resvg_bridge::optimize::PASSES.len());
    }

    #[test]
    fn settings_round_trip_through_the_file() {
        let path = std::env::temp_dir().join(format!("svg-easy-svgo-{}.json", std::process::id()));
        let mut options = OptimizeOptions::default();
        options.passes.insert("removeComments".into(), false);
        save_to(Some(&path), &options);

        let loaded = load_from(Some(&path));
        assert_eq!(loaded.enabled("removeComments"), false);
        assert_eq!(loaded.enabled("cleanupIds"), true);
        assert_eq!(loaded.passes.len(), 1);
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn a_corrupt_file_degrades_to_the_defaults() {
        let path = std::env::temp_dir().join(format!("svg-easy-svgo-bad-{}.json", std::process::id()));
        fs::write(&path, "{ this is not json").expect("write");
        let loaded = load_from(Some(&path));
        assert!(loaded.passes.is_empty());
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn the_config_path_sits_under_the_config_directory() {
        // The platform env vars are not guaranteed in CI, so this only checks the shape when a
        // home is set — the point is that the app never writes next to the document.
        if std::env::var_os("HOME").is_none() && std::env::var_os("APPDATA").is_none() {
            return;
        }
        let path = config_path().expect("a config path");
        assert!(path.ends_with("svg_easy/svgo.json") || path.ends_with("svg_easy\\svgo.json"));
    }
}
