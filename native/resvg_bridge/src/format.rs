//! SVG pretty-printing — the engine behind the *Format* action.
//!
//! The editor's source text is the document, and SVGO hands back a single minified line. That is
//! the right thing to *ship* and the wrong thing to *read*, so formatting is a separate, explicit
//! action: the text on disk stays as small as the optimiser made it until the user asks otherwise.
//!
//! This is a re-indent, not a re-serialisation. Attribute order and spelling, the self-closing
//! form and every non-whitespace text node are carried over verbatim from the source; the only
//! thing that changes is the whitespace *between* elements. That matters because in XML that
//! whitespace is not always insignificant, so the one rule that keeps this safe is:
//!
//! > An element that contains any non-whitespace text is emitted in-line, together with its whole
//! > subtree.
//!
//! `<text>`, `<tspan>`, `<style>` and friends therefore keep the exact bytes the author wrote —
//! re-flowing them would move glyphs on the canvas. Everything else is structural, and there the
//! indentation is free to change.

use crate::dom::{Document, ElementData, NodeKind};

/// One level of nesting. Two spaces, matching the XML pane's own editor.
const INDENT: &str = "  ";

/// Re-indents `svg`. Never fails: the parser ([`Document::parse`]) accepts malformed input by
/// design, and whatever it makes of the text is what gets laid out.
pub fn format(svg: &str) -> String {
    let doc = Document::parse(svg);
    let mut out = String::with_capacity(svg.len() + svg.len() / 4);
    write_children(&doc, 0, 0, &mut out);
    if !out.ends_with('\n') {
        out.push('\n');
    }
    out
}

/// Lays out the children of element `idx`, one per line.
fn write_children(doc: &Document, idx: usize, depth: usize, out: &mut String) {
    let Some(el) = doc.element(idx) else { return };
    for &child in &el.children {
        match &doc.nodes[child] {
            NodeKind::Element(_) => write_element(doc, child, depth, out),
            // Whitespace-only text between elements is exactly what this function replaces; a text
            // run that carries content only survives here if its element has element siblings too,
            // in which case it still gets its own line.
            NodeKind::Text(text) => {
                if !text.trim().is_empty() {
                    push_indent(out, depth);
                    out.push_str(text.trim());
                    out.push('\n');
                }
            }
            NodeKind::Comment(raw)
            | NodeKind::Cdata(raw)
            | NodeKind::Processing(raw)
            | NodeKind::Decl(raw) => {
                push_indent(out, depth);
                out.push_str(raw);
                out.push('\n');
            }
        }
    }
}

/// Lays out one element: open tag at `depth`, children one level in, close tag back at `depth`.
fn write_element(doc: &Document, idx: usize, depth: usize, out: &mut String) {
    let Some(el) = doc.element(idx) else { return };
    push_indent(out, depth);
    if write_open_tag(el, out) {
        out.push('\n');
        return;
    }

    if has_text_content(doc, el) {
        // In-line: the subtree keeps its original whitespace, so no glyph can move.
        write_inline_children(doc, el, out);
        write_close_tag(el, out);
        out.push('\n');
        return;
    }

    if el.children.is_empty() {
        // `<g/>` and `<g></g>` mean the same thing; keep whichever the author wrote.
        if !el.self_closing {
            write_close_tag(el, out);
        }
        out.push('\n');
        return;
    }

    out.push('\n');
    write_children(doc, idx, depth + 1, out);
    push_indent(out, depth);
    write_close_tag(el, out);
    out.push('\n');
}

/// Emits `<name a="1"/>` or `<name a="1">`. Returns true when the tag closed itself.
fn write_open_tag(el: &ElementData, out: &mut String) -> bool {
    out.push('<');
    out.push_str(&el.name);
    for attr in &el.attrs {
        out.push(' ');
        out.push_str(&attr.name);
        // A valueless attribute has no raw value at all — the parser stores an empty string for it.
        if !attr.raw_value.is_empty() {
            out.push('=');
            out.push_str(&attr.raw_value);
        }
    }
    if el.self_closing && el.children.is_empty() {
        out.push_str("/>");
        true
    } else {
        out.push('>');
        false
    }
}

fn write_close_tag(el: &ElementData, out: &mut String) {
    out.push_str("</");
    out.push_str(&el.name);
    out.push('>');
}

/// Whether this element must stay on one line: it has text of its own, so its whitespace is part of
/// its content rather than of the document's layout.
fn has_text_content(doc: &Document, el: &ElementData) -> bool {
    el.children.iter().any(|&child| match &doc.nodes[child] {
        NodeKind::Cdata(_) => true,
        NodeKind::Text(text) => !text.trim().is_empty(),
        _ => false,
    })
}

/// Writes an element's subtree with no added whitespace at all.
fn write_inline_children(doc: &Document, el: &ElementData, out: &mut String) {
    for &child in &el.children {
        match &doc.nodes[child] {
            NodeKind::Element(_) => {
                let Some(child_el) = doc.element(child) else {
                    continue;
                };
                if !write_open_tag(child_el, out) {
                    write_inline_children(doc, child_el, out);
                    write_close_tag(child_el, out);
                }
            }
            NodeKind::Text(raw)
            | NodeKind::Comment(raw)
            | NodeKind::Cdata(raw)
            | NodeKind::Processing(raw)
            | NodeKind::Decl(raw) => out.push_str(raw),
        }
    }
}

fn push_indent(out: &mut String, depth: usize) {
    for _ in 0..depth {
        out.push_str(INDENT);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MINIFIED: &str = r##"<?xml version="1.0" encoding="UTF-8"?><!-- card --><svg xmlns="http://www.w3.org/2000/svg" width="200" height="120" viewBox="0 0 200 120"><rect id="box-a" x="10" y="10" width="80" height="60" fill="#4caf50"/><g id="grp" transform="translate(120,80)"><circle cx="20" cy="20" r="10"/></g></svg>"##;

    #[test]
    fn minified_input_comes_back_one_element_per_line() {
        let pretty = format(MINIFIED);
        assert!(pretty.lines().count() > 5, "{pretty}");
        // The declaration and the comment lead, then the root at depth 0.
        let lines: Vec<&str> = pretty.lines().collect();
        assert_eq!(lines[0], r#"<?xml version="1.0" encoding="UTF-8"?>"#);
        assert_eq!(lines[1], "<!-- card -->");
        assert!(lines[2].starts_with("<svg "));

        // Two spaces per level: `<g>` sits inside `<svg>`, its `<circle>` inside `<g>`.
        let indent_of = |prefix: &str| {
            lines
                .iter()
                .find(|line| line.trim_start().starts_with(prefix))
                .map(|line| line.len() - line.trim_start().len())
        };
        assert_eq!(indent_of("<g "), Some(2), "{pretty}");
        assert_eq!(indent_of("<circle"), Some(4), "{pretty}");
    }

    #[test]
    fn formatting_is_idempotent() {
        let once = format(MINIFIED);
        assert_eq!(once, format(&once));
    }

    #[test]
    fn formatting_keeps_the_document_renderable() {
        let pretty = format(MINIFIED);
        let before = usvg::Tree::from_str(MINIFIED, &usvg::Options::default()).expect("parse");
        let after = usvg::Tree::from_str(&pretty, &usvg::Options::default()).expect("reparse");
        assert_eq!(before.size().width(), after.size().width());
        assert_eq!(before.size().height(), after.size().height());
        assert_eq!(before.root().children().len(), after.root().children().len());
    }

    #[test]
    fn text_content_is_never_re_flowed() {
        let svg = r#"<svg xmlns="http://www.w3.org/2000/svg"><text x="1" y="2">  hello
   world  </text><style>  .a{fill:red}  </style><script><![CDATA[go()]]></script></svg>"#;
        let pretty = format(svg);
        assert!(pretty.contains(">  hello\n   world  </text>"), "{pretty}");
        assert!(pretty.contains("<style>  .a{fill:red}  </style>"), "{pretty}");
        assert!(pretty.contains("<script><![CDATA[go()]]></script>"), "{pretty}");
    }

    #[test]
    fn attribute_spelling_and_quoting_survive() {
        let svg = r##"<svg xmlns='http://www.w3.org/2000/svg'><rect fill="#4caf50" data-x="a&amp;b"/></svg>"##;
        let pretty = format(svg);
        assert!(pretty.contains("xmlns='http://www.w3.org/2000/svg'"), "{pretty}");
        assert!(pretty.contains(r#"data-x="a&amp;b""#), "{pretty}");
        // Self-closing form is kept as written.
        assert!(pretty.contains("<rect fill=\"#4caf50\" data-x=\"a&amp;b\"/>"), "{pretty}");
    }

    #[test]
    fn comments_keep_their_place() {
        let svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><!-- a --><rect/></svg>";
        let pretty = format(svg);
        let lines: Vec<&str> = pretty.lines().collect();
        assert_eq!(lines[1], "  <!-- a -->");
        assert_eq!(lines[2], "  <rect/>");
    }

    #[test]
    fn whitespace_between_elements_is_dropped_not_duplicated() {
        let svg = "<svg xmlns=\"http://www.w3.org/2000/svg\">\n\n   <rect/>\n   <circle/>\n\n</svg>";
        let pretty = format(svg);
        assert_eq!(
            pretty,
            "<svg xmlns=\"http://www.w3.org/2000/svg\">\n  <rect/>\n  <circle/>\n</svg>\n"
        );
    }

    #[test]
    fn malformed_input_is_laid_out_rather_than_rejected() {
        // The editor formats half-typed documents; the pass must not panic or lose text.
        let pretty = format("<svg><g><rect/></svg>");
        assert!(pretty.contains("<rect/>"), "{pretty}");
        assert!(pretty.contains("<g>"), "{pretty}");
    }
}
