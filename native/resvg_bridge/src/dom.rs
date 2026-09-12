//! Fidelity-preserving SVG DOM.
//!
//! This tree is the editor's source of truth: elements keep their original
//! names, attribute spelling and structure. Untouched subtrees are serialized
//! by copying the original source spans, so round-tripping a partially edited
//! file reproduces the input byte-for-byte outside the edited regions.

use std::collections::HashSet;

use crate::geom::{parse_transform, Mat};

/// One attribute. `raw_value` keeps the value exactly as written in the
/// source, including the original quotes (empty string for valueless attrs).
#[derive(Clone, Debug)]
pub struct Attr {
    pub name: String,
    pub raw_value: String,
}

impl Attr {
    /// Decoded value (quotes stripped, character entities resolved).
    pub fn value(&self) -> String {
        let r = self.raw_value.as_str();
        if r.len() >= 2
            && ((r.starts_with('\'') && r.ends_with('\''))
                || (r.starts_with('"') && r.ends_with('"')))
        {
            decode_entities(&r[1..r.len() - 1])
        } else {
            decode_entities(r)
        }
    }
}

pub struct ElementData {
    pub name: String,
    pub attrs: Vec<Attr>,
    /// Arena indices of child nodes, in document order.
    pub children: Vec<usize>,
    pub self_closing: bool,
    /// Set when the editor changed this element; such elements are rebuilt
    /// on serialization instead of copied from the source span.
    pub dirty: bool,
    /// In-memory identity assigned at parse time (document order, root = 0).
    /// Never written back to the file.
    pub node_id: usize,
    pub parent: usize,
    pub open_span: (usize, usize),
    pub close_span: Option<(usize, usize)>,
}

impl ElementData {
    pub fn attr(&self, name: &str) -> Option<String> {
        self.attrs.iter().find(|a| a.name == name).map(|a| a.value())
    }

    pub fn set_attr(&mut self, name: &str, value: &str) {
        let raw = format!("\"{}\"", escape_attr(value));
        match self.attrs.iter_mut().find(|a| a.name == name) {
            Some(a) => a.raw_value = raw,
            None => self.attrs.push(Attr {
                name: name.to_string(),
                raw_value: raw,
            }),
        }
        self.dirty = true;
    }

    /// The element's own `transform` attribute as a matrix.
    pub fn transform_mat(&self) -> Mat {
        self.attr("transform")
            .map(|t| parse_transform(&t))
            .unwrap_or(Mat::IDENTITY)
    }
}

pub enum NodeKind {
    Element(ElementData),
    /// Raw source slice; entity decoding is not needed for serialization.
    Text(String),
    Comment(String),
    Cdata(String),
    Processing(String),
    Decl(String),
}

/// Where a restack moves an element among its siblings.
///
/// SVG has no z-index: later siblings paint over earlier ones, so `Front` is the top of the
/// stack and `Back` its bottom.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Stack {
    Front,
    Forward,
    Backward,
    Back,
}

/// How serialization should treat the tree.
pub enum Mode {
    /// Round-trip: untouched source is copied verbatim.
    Full,
    /// Like Full but every element without an `id` gets `id="eN"` injected;
    /// used only to build the temporary usvg projection.
    InjectIds,
    /// Skip the subtree rooted at the given node id.
    Hide(usize),
    /// Skip every subtree rooted at one of the given node ids (a group drag
    /// hides all its members from the background layer at once).
    HideMany(HashSet<usize>),
    /// Keep only the given chain of arena indices (root + ancestors + node).
    Solo(HashSet<usize>),
}

pub struct Document {
    pub source: String,
    /// Flat arena in document order; index 0 is the `#document` root.
    /// Children always have higher indices than their parent.
    pub nodes: Vec<NodeKind>,
    /// Tombstones: subtrees removed by an edit (their parent's children lists no longer
    /// reference them, but the arena slots stay so every index remains valid).
    pub removed: Vec<bool>,
}

impl Document {
    pub fn parse(source: &str) -> Document {
        let mut nodes = vec![NodeKind::Element(ElementData {
            name: "#document".to_string(),
            attrs: Vec::new(),
            children: Vec::new(),
            self_closing: false,
            dirty: false,
            node_id: 0,
            parent: 0,
            open_span: (0, 0),
            close_span: None,
        })];
        let mut stack: Vec<usize> = vec![0];
        let mut next_id: usize = 1;
        let b = source.as_bytes();
        let n = b.len();
        let mut i = 0usize;

        while i < n {
            if b[i] == b'<' && source[i..].starts_with("<!--") {
                let end = find_seq(b, i + 4, b"-->").map(|p| p + 3).unwrap_or(n);
                push_child(&mut nodes, &stack, NodeKind::Comment(source[i..end].to_string()));
                i = end;
            } else if b[i] == b'<' && source[i..].starts_with("<![CDATA[") {
                let end = find_seq(b, i + 9, b"]]>").map(|p| p + 3).unwrap_or(n);
                push_child(&mut nodes, &stack, NodeKind::Cdata(source[i..end].to_string()));
                i = end;
            } else if b[i] == b'<' && source[i..].starts_with("<!") {
                // DOCTYPE (possibly with an internal subset) or other declaration.
                let mut j = i + 2;
                let mut depth = 0usize;
                while j < n {
                    match b[j] {
                        b'[' => depth += 1,
                        b']' => depth = depth.saturating_sub(1),
                        b'>' if depth == 0 => {
                            j += 1;
                            break;
                        }
                        _ => {}
                    }
                    j += 1;
                }
                push_child(&mut nodes, &stack, NodeKind::Decl(source[i..j].to_string()));
                i = j;
            } else if b[i] == b'<' && source[i..].starts_with("<?") {
                let end = find_seq(b, i + 2, b"?>").map(|p| p + 2).unwrap_or(n);
                push_child(&mut nodes, &stack, NodeKind::Processing(source[i..end].to_string()));
                i = end;
            } else if b[i] == b'<' && source[i..].starts_with("</") {
                let end = memchr_gt(b, i + 2).map(|p| p + 1).unwrap_or(n);
                let inner = source[i + 2..end.saturating_sub(1)].trim();
                let name = inner.split_whitespace().next().unwrap_or("");
                if !name.is_empty() {
                    if let Some(pos) = stack.iter().rposition(|&idx| {
                        idx != 0 && element_name(&nodes[idx]) == Some(name)
                    }) {
                        // Implicitly close everything above the match.
                        while stack.len() > pos + 1 {
                            stack.pop();
                        }
                        let top = *stack.last().unwrap();
                        if let NodeKind::Element(el) = &mut nodes[top] {
                            el.close_span = Some((i, end));
                        }
                        stack.pop();
                    }
                }
                i = end;
            } else if b[i] == b'<' && i + 1 < n && is_name_start(b[i + 1]) {
                let (mut el, next, stays_open) = parse_open_tag(source, i);
                let parent = *stack.last().unwrap();
                let idx = nodes.len();
                el.parent = parent;
                el.node_id = next_id;
                next_id += 1;
                if let NodeKind::Element(parent_el) = &mut nodes[parent] {
                    parent_el.children.push(idx);
                }
                nodes.push(NodeKind::Element(el));
                if stays_open {
                    stack.push(idx);
                }
                i = next;
            } else {
                // Text run (also swallows stray '<' that starts nothing).
                let end = memchr_lt(b, i + 1).unwrap_or(n);
                push_child(&mut nodes, &stack, NodeKind::Text(source[i..end].to_string()));
                i = end;
            }
        }

        let count = nodes.len();
        Document {
            source: source.to_string(),
            nodes,
            removed: vec![false; count],
        }
    }

    pub fn root(&self) -> &ElementData {
        match &self.nodes[0] {
            NodeKind::Element(el) => el,
            _ => unreachable!(),
        }
    }

    pub fn element(&self, idx: usize) -> Option<&ElementData> {
        match self.nodes.get(idx) {
            Some(NodeKind::Element(el)) => Some(el),
            _ => None,
        }
    }

    pub fn element_mut(&mut self, idx: usize) -> Option<&mut ElementData> {
        match self.nodes.get_mut(idx) {
            Some(NodeKind::Element(el)) => Some(el),
            _ => None,
        }
    }

    pub fn find_by_node_id(&self, node_id: usize) -> Option<usize> {
        self.nodes.iter().enumerate().position(|(i, n)| {
            matches!(n, NodeKind::Element(e) if e.node_id == node_id && !self.removed[i])
        })
    }

    /// All element arena indices in document order (z-order for SVG), excluding removed ones.
    pub fn element_indices(&self) -> impl Iterator<Item = usize> + '_ {
        (1..self.nodes.len())
            .filter(|&i| matches!(self.nodes[i], NodeKind::Element(_)) && !self.removed[i])
    }

    /// Remove the subtree rooted at `idx` (its arena slot is tombstoned; the node is dropped
    /// from its parent's children so it is neither serialized nor listed afterwards).
    pub fn remove_node(&mut self, idx: usize) {
        if idx == 0 || self.removed[idx] {
            return;
        }
        fn mark(nodes: &Vec<NodeKind>, removed: &mut [bool], i: usize) {
            removed[i] = true;
            if let NodeKind::Element(el) = &nodes[i] {
                for &c in &el.children {
                    mark(nodes, removed, c);
                }
            }
        }
        mark(&self.nodes, &mut self.removed, idx);
        let parent = self.element(idx).map(|e| e.parent).unwrap_or(0);
        if let NodeKind::Element(parent_el) = &mut self.nodes[parent] {
            parent_el.children.retain(|&c| c != idx);
            // The parent's content changed, so it (and its ancestors) must be re-serialized
            // from the tree instead of copied verbatim from the source span.
            parent_el.dirty = true;
        }
    }

    /// Moves `idx` among its sibling *elements*, which is what changes paint order.
    ///
    /// Only the element's slot in the parent's child list moves; the text and comment nodes
    /// around it stay where they are, so the document keeps its indentation when it is
    /// re-serialized. Returns false when the element is already where it was asked to go.
    pub fn restack(&mut self, idx: usize, to: Stack) -> bool {
        if idx == 0 || self.removed[idx] {
            return false;
        }
        let Some(parent) = self.element(idx).map(|e| e.parent) else {
            return false;
        };
        let Some(parent_el) = self.element(parent) else {
            return false;
        };
        let siblings: Vec<usize> = parent_el
            .children
            .iter()
            .copied()
            .filter(|&c| matches!(self.nodes[c], NodeKind::Element(_)))
            .collect();
        let Some(pos) = siblings.iter().position(|&c| c == idx) else {
            return false;
        };
        let target = match to {
            Stack::Front => siblings.len() - 1,
            Stack::Back => 0,
            Stack::Forward if pos + 1 < siblings.len() => pos + 1,
            Stack::Backward if pos > 0 => pos - 1,
            _ => return false,
        };
        if target == pos {
            return false;
        }

        let other = siblings[target];
        let children = &mut self.element_mut(parent).expect("checked above").children;
        let a = children.iter().position(|&c| c == idx).expect("sibling");
        let b = children.iter().position(|&c| c == other).expect("sibling");
        children.swap(a, b);
        // The parent's content changed, so it (and its ancestors) must be re-serialized from the
        // tree instead of copied verbatim from the source span.
        self.element_mut(parent).expect("checked above").dirty = true;
        true
    }

    /// Duplicates the subtree rooted at `idx`, inserting the clone as the sibling
    /// immediately after the original (so it paints on top of it). Returns the new root's
    /// arena index, or `None` when `idx` cannot be duplicated.
    ///
    /// The clone is a live copy: every element gets a fresh `node_id`, any `id` attribute is
    /// renamed to a unique one (SVG ids must not collide or `usvg` would mis-map them), and the
    /// whole subtree is marked `dirty` so serialization rebuilds it from the tree instead of
    /// recycling the original's source span (which points at the wrong position now).
    pub fn duplicate(&mut self, idx: usize) -> Option<usize> {
        if idx == 0 || self.removed[idx] {
            return None;
        }
        let parent = self.element(idx).map(|e| e.parent).unwrap_or(0);

        let mut ids: HashSet<String> = self
            .nodes
            .iter()
            .filter_map(|n| match n {
                NodeKind::Element(e) => e.attr("id"),
                _ => None,
            })
            .collect();
        let next_id = self
            .nodes
            .iter()
            .filter_map(|n| match n {
                NodeKind::Element(e) => Some(e.node_id),
                _ => None,
            })
            .max()
            .unwrap_or(0)
            + 1;

        let new_root = clone_subtree(&mut self.nodes, &mut self.removed, &mut ids, idx, parent, next_id);
        let parent_el = self.element_mut(parent).expect("parent exists");
        if let Some(pos) = parent_el.children.iter().position(|&c| c == idx) {
            parent_el.children.insert(pos + 1, new_root);
        }
        parent_el.dirty = true;
        Some(new_root)
    }

    /// Chain of arena indices from the root (inclusive) down to `idx`.
    pub fn path_to(&self, idx: usize) -> Vec<usize> {
        let mut out = vec![idx];
        let mut cur = idx;
        while cur != 0 {
            cur = self.element(cur).map(|e| e.parent).unwrap_or(0);
            out.push(cur);
        }
        out.reverse();
        out
    }

    /// Product of all ancestor `transform` attributes, outermost first.
    pub fn ancestor_transform(&self, idx: usize) -> Mat {
        let mut acc = Mat::IDENTITY;
        let mut cur = self.element(idx).map(|e| e.parent).unwrap_or(0);
        while cur != 0 {
            match self.element(cur) {
                Some(el) => {
                    let t = el.transform_mat();
                    acc = t.mul(acc);
                    cur = el.parent;
                }
                None => break,
            }
        }
        acc
    }

    pub fn serialize(&self, mode: &Mode) -> String {
        let blocked = self.span_blocked(mode);
        let mut out = String::with_capacity(self.source.len() + 64);
        self.write_node(&mut out, 0, mode, &blocked);
        out
    }

    /// Single bottom-up pass marking elements whose subtree cannot be copied
    /// from the source span under the given mode.
    fn span_blocked(&self, mode: &Mode) -> Vec<bool> {
        let mut blocked = vec![false; self.nodes.len()];
        for idx in (1..self.nodes.len()).rev() {
            if let NodeKind::Element(el) = &self.nodes[idx] {
                let own = match mode {
                    Mode::Full => false,
                    Mode::InjectIds => true,
                    Mode::Hide(h) => el.node_id == *h,
                    Mode::HideMany(set) => set.contains(&el.node_id),
                    Mode::Solo(set) => !set.contains(&idx),
                };
                blocked[idx] = own || el.dirty || el.children.iter().any(|&c| blocked[c]);
            }
        }
        blocked
    }

    fn write_node(&self, out: &mut String, idx: usize, mode: &Mode, blocked: &[bool]) {
        match &self.nodes[idx] {
            NodeKind::Element(el) => {
                if idx == 0 {
                    for &c in &el.children {
                        self.write_node(out, c, mode, blocked);
                    }
                    return;
                }
                if let Mode::Hide(h) = mode {
                    if el.node_id == *h {
                        return;
                    }
                }
                if let Mode::HideMany(set) = mode {
                    if set.contains(&el.node_id) {
                        return;
                    }
                }
                if let Mode::Solo(set) = mode {
                    if !set.contains(&idx) {
                        return;
                    }
                }
                let copyable = !el.dirty
                    && !blocked[idx]
                    && (el.close_span.is_some() || (el.self_closing && el.children.is_empty()));
                if copyable {
                    let end = el.close_span.map(|e| e.1).unwrap_or(el.open_span.1);
                    out.push_str(&self.source[el.open_span.0..end]);
                    return;
                }
                out.push('<');
                out.push_str(&el.name);
                for a in &el.attrs {
                    out.push(' ');
                    out.push_str(&a.name);
                    if !a.raw_value.is_empty() {
                        out.push('=');
                        out.push_str(&a.raw_value);
                    }
                }
                if let Mode::InjectIds = mode {
                    if el.node_id != 0 && !el.attrs.iter().any(|a| a.name == "id") {
                        out.push_str(&format!(" id=\"e{}\"", el.node_id));
                    }
                }
                if el.self_closing && el.children.is_empty() {
                    out.push_str("/>");
                } else {
                    out.push('>');
                    for &c in &el.children {
                        self.write_node(out, c, mode, blocked);
                    }
                    out.push_str("</");
                    out.push_str(&el.name);
                    out.push('>');
                }
            }
            NodeKind::Text(s)
            | NodeKind::Comment(s)
            | NodeKind::Cdata(s)
            | NodeKind::Processing(s)
            | NodeKind::Decl(s) => out.push_str(s),
        }
    }
}

/// Deep-clones the subtree rooted at `src` (a live copy: new arena slots, fresh `node_id`s,
/// unique `id` attrs, and `dirty` elements so they serialize from the tree). Returns the new
/// root's arena index.
fn clone_subtree(
    nodes: &mut Vec<NodeKind>,
    removed: &mut Vec<bool>,
    ids: &mut HashSet<String>,
    src: usize,
    parent: usize,
    mut next_id: usize,
) -> usize {
    let new_idx = nodes.len();
    match &nodes[src] {
        NodeKind::Element(el) => {
            let mut e = ElementData {
                name: el.name.clone(),
                attrs: el.attrs.clone(),
                children: Vec::new(),
                self_closing: el.self_closing,
                dirty: true,
                node_id: next_id,
                parent,
                open_span: (0, 0),
                close_span: None,
            };
            next_id += 1;
            // Rename the `id` attribute (if any) so it stays unique across the document.
            if let Some(old) = e.attr("id") {
                let base = format!("copy-of-{old}");
                let mut candidate = base.clone();
                let mut k = 1;
                while ids.contains(&candidate) {
                    candidate = format!("{base}-{k}");
                    k += 1;
                }
                if let Some(a) = e.attrs.iter_mut().find(|a| a.name == "id") {
                    a.raw_value = format!("\"{candidate}\"");
                }
                ids.insert(candidate);
            }
            let child_snapshot: Vec<usize> = el.children.iter().copied().collect();
            nodes.push(NodeKind::Element(e));
            removed.push(false);
            let children: Vec<usize> = child_snapshot
                .into_iter()
                .map(|c| clone_subtree(nodes, removed, ids, c, new_idx, next_id))
                .collect();
            if let NodeKind::Element(ee) = &mut nodes[new_idx] {
                ee.children = children;
            }
        }
        NodeKind::Text(s) => nodes.push(NodeKind::Text(s.clone())),
        NodeKind::Comment(s) => nodes.push(NodeKind::Comment(s.clone())),
        NodeKind::Cdata(s) => nodes.push(NodeKind::Cdata(s.clone())),
        NodeKind::Processing(s) => nodes.push(NodeKind::Processing(s.clone())),
        NodeKind::Decl(s) => nodes.push(NodeKind::Decl(s.clone())),
    }
    removed.push(false);
    new_idx
}

fn push_child(nodes: &mut Vec<NodeKind>, stack: &[usize], node: NodeKind) {
    let parent = *stack.last().unwrap();
    let idx = nodes.len();
    if let NodeKind::Element(el) = &mut nodes[parent] {
        el.children.push(idx);
    }
    nodes.push(node);
}

/// Parses `<name attr='v' flag ...>` starting at `start` (the '<').
/// Returns the element, the index after the tag, and whether the element
/// stays open on the stack (no `/>` and no EOF truncation).
fn parse_open_tag(source: &str, start: usize) -> (ElementData, usize, bool) {
    let b = source.as_bytes();
    let n = b.len();
    let mut i = start + 1;
    let name_begin = i;
    while i < n && is_name_char(b[i]) {
        i += 1;
    }
    let name = source[name_begin..i].to_string();
    let mut attrs: Vec<Attr> = Vec::new();

    loop {
        while i < n && b[i].is_ascii_whitespace() {
            i += 1;
        }
        if i >= n {
            break;
        }
        if b[i] == b'>' {
            let el = ElementData {
                name,
                attrs,
                children: Vec::new(),
                self_closing: false,
                dirty: false,
                node_id: 0,
                parent: 0,
                open_span: (start, i + 1),
                close_span: None,
            };
            return (el, i + 1, true);
        }
        if b[i] == b'/' {
            i += 1;
            if i < n && b[i] == b'>' {
                i += 1;
            }
            let el = ElementData {
                name,
                attrs,
                children: Vec::new(),
                self_closing: true,
                dirty: false,
                node_id: 0,
                parent: 0,
                open_span: (start, i),
                close_span: None,
            };
            return (el, i, false);
        }

        // Attribute name.
        let an_begin = i;
        while i < n
            && b[i] != b'='
            && b[i] != b'>'
            && b[i] != b'/'
            && !b[i].is_ascii_whitespace()
        {
            i += 1;
        }
        let an = source[an_begin..i].to_string();

        while i < n && b[i].is_ascii_whitespace() {
            i += 1;
        }
        let mut raw = String::new();
        if i < n && b[i] == b'=' {
            i += 1;
            while i < n && b[i].is_ascii_whitespace() {
                i += 1;
            }
            if i < n && (b[i] == b'"' || b[i] == b'\'') {
                let q = b[i];
                let v_begin = i;
                i += 1;
                while i < n && b[i] != q {
                    i += 1;
                }
                if i < n {
                    i += 1;
                }
                raw = source[v_begin..i].to_string();
            } else {
                let v_begin = i;
                while i < n && b[i] != b'>' && !b[i].is_ascii_whitespace() {
                    i += 1;
                }
                raw = source[v_begin..i].to_string();
            }
        }
        if !an.is_empty() {
            attrs.push(Attr {
                name: an,
                raw_value: raw,
            });
        }
    }

    // EOF before '>': keep what we parsed, do not leave the element open.
    let el = ElementData {
        name,
        attrs,
        children: Vec::new(),
        self_closing: false,
        dirty: false,
        node_id: 0,
        parent: 0,
        open_span: (start, n),
        close_span: None,
    };
    (el, n, false)
}

fn is_name_start(b: u8) -> bool {
    b.is_ascii_alphabetic() || b == b'_' || b == b':'
}

fn is_name_char(b: u8) -> bool {
    is_name_start(b) || b.is_ascii_digit() || b == b'-' || b == b'.'
}

fn memchr_gt(b: &[u8], from: usize) -> Option<usize> {
    if from >= b.len() {
        return None;
    }
    b[from..].iter().position(|&c| c == b'>').map(|p| p + from)
}

fn memchr_lt(b: &[u8], from: usize) -> Option<usize> {
    if from >= b.len() {
        return None;
    }
    b[from..].iter().position(|&c| c == b'<').map(|p| p + from)
}

fn find_seq(b: &[u8], from: usize, pat: &[u8]) -> Option<usize> {
    if from >= b.len() {
        return None;
    }
    b[from..]
        .windows(pat.len())
        .position(|w| w == pat)
        .map(|p| p + from)
}

fn element_name(node: &NodeKind) -> Option<&str> {
    match node {
        NodeKind::Element(el) => Some(&el.name),
        _ => None,
    }
}

fn escape_attr(v: &str) -> String {
    let mut out = String::with_capacity(v.len());
    for c in v.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            _ => out.push(c),
        }
    }
    out
}

fn decode_entities(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let bytes = s.as_bytes();
    let n = bytes.len();
    let mut i = 0usize;
    while i < n {
        let c = s[i..].chars().next().unwrap();
        if c != '&' {
            out.push(c);
            i += c.len_utf8();
            continue;
        }
        // Try `&name;`, `&#ddd;` and `&#xhh;` within a bounded window.
        let limit = (i + 12).min(n);
        if let Some(rel) = bytes[i + 1..limit].iter().position(|&b| b == b';') {
            let ent = &s[i + 1..i + 1 + rel];
            let decoded = match ent {
                "amp" => Some('&'),
                "lt" => Some('<'),
                "gt" => Some('>'),
                "quot" => Some('"'),
                "apos" => Some('\''),
                other => {
                    let code = if let Some(hex) =
                        other.strip_prefix("#x").or_else(|| other.strip_prefix("#X"))
                    {
                        u32::from_str_radix(hex, 16).ok()
                    } else if let Some(dec) = other.strip_prefix('#') {
                        dec.parse::<u32>().ok()
                    } else {
                        None
                    };
                    code.and_then(char::from_u32)
                }
            };
            if let Some(ch) = decoded {
                out.push(ch);
                i += rel + 2; // '&' + entity + ';'
                continue;
            }
        }
        out.push('&');
        i += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "\
<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<!-- drawn by hand -->
<svg xmlns='http://www.w3.org/2000/svg' width='200' height='120' viewBox='0 0 200 120'>
  <rect id='bg' x='0' y='0' width='200' height='120' fill='#eef'/>
  <path id='box-a' d='M10 10 H60 V60 H10 Z' fill='#f00'/>
  <path d='M70 10 h20 v20 h-20 z' fill='#0f0'/>
  <g id='grp' transform='translate(120,80)'>
    <circle id='dot' r='10' fill='#00f'/>
  </g>
  <text id='label' x='10' y='110'>Hello</text>
</svg>
";

    #[test]
    fn round_trip_untouched_is_verbatim() {
        let doc = Document::parse(SAMPLE);
        assert_eq!(doc.serialize(&Mode::Full), SAMPLE);
    }

    #[test]
    fn node_ids_assigned_in_document_order() {
        let doc = Document::parse(SAMPLE);
        let ids: Vec<usize> = doc.element_indices().map(|i| doc.element(i).unwrap().node_id).collect();
        assert_eq!(ids, vec![1, 2, 3, 4, 5, 6, 7]);
        assert_eq!(doc.element(doc.find_by_node_id(1).unwrap()).unwrap().name, "svg");
        assert_eq!(doc.root().children.len(), 6); // decl + text + comment + text + <svg> + text
    }

    #[test]
    fn set_attr_rebuilds_only_the_target() {
        let mut doc = Document::parse(SAMPLE);
        let idx = doc.find_by_node_id(3).unwrap();
        assert_eq!(doc.element(idx).unwrap().attr("id").unwrap(), "box-a");
        doc.element_mut(idx).unwrap().set_attr("transform", "translate(5 0)");
        let out = doc.serialize(&Mode::Full);
        assert!(out.contains("transform=\"translate(5 0)\""));
        // Rebuilt element keeps original single-quoted attrs verbatim.
        assert!(out.contains("<path id='box-a' d='M10 10 H60 V60 H10 Z' fill='#f00' transform="));
        // Everything else stays byte-identical, including the comment.
        assert!(out.contains("<!-- drawn by hand -->"));
        assert!(out.contains("<rect id='bg' x='0' y='0' width='200' height='120' fill='#eef'/>"));
    }

    #[test]
    fn inject_ids_only_on_idless_elements() {
        let doc = Document::parse(SAMPLE);
        let out = doc.serialize(&Mode::InjectIds);
        assert!(out.contains("id=\"e4\"")); // the id-less green path
        assert!(out.contains("id='bg'")); // user ids keep their quoting
        assert!(out.contains("<!-- drawn by hand -->"));
        assert_eq!(doc.serialize(&Mode::Full), SAMPLE);
    }

    #[test]
    fn hide_removes_subtree_only() {
        let doc = Document::parse(SAMPLE);
                let out = doc.serialize(&Mode::Hide(6));
        assert!(!out.contains("<circle"));
        assert!(out.contains("id='box-a'"));
        assert!(out.contains("<!-- drawn by hand -->"));
    }

    #[test]
    fn hide_many_removes_every_marked_subtree() {
        let doc = Document::parse(SAMPLE);
        let set: HashSet<usize> = [3, 6].into_iter().collect(); // box-a + dot
        let out = doc.serialize(&Mode::HideMany(set));
        assert!(!out.contains("id='box-a'"));
        assert!(!out.contains("<circle"));
        assert!(out.contains("id='bg'"));
        assert!(out.contains("<text"));
        assert!(out.contains("<!-- drawn by hand -->"));
    }

    #[test]
    fn hide_many_matches_plain_document_order_ids() {
        let src = concat!(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\" height=\"200\">\n",
            "  <rect id=\"left\" x=\"40\" y=\"60\" width=\"60\" height=\"60\" fill=\"#ff0000\"/>\n",
            "  <rect id=\"right\" x=\"240\" y=\"60\" width=\"60\" height=\"60\" fill=\"#0000ff\"/>\n",
            "</svg>"
        );
        let doc = Document::parse(src);
        let set: HashSet<usize> = [2, 3].into_iter().collect(); // left=2, right=3
        let out = doc.serialize(&Mode::HideMany(set));
        assert!(!out.contains("id=\"left\""));
        assert!(!out.contains("id=\"right\""));
    }

    #[test]
    fn solo_keeps_only_chain() {
        let doc = Document::parse(SAMPLE);
        let dot = doc.find_by_node_id(6).unwrap();
        let chain: HashSet<usize> = doc.path_to(dot).into_iter().collect();
        let out = doc.serialize(&Mode::Solo(chain));
        assert!(out.contains("<circle id='dot' r='10' fill='#00f'/>"));
        assert!(!out.contains("id='box-a'"));
        assert!(!out.contains("id='bg'"));
        assert!(!out.contains("<text"));
    }

    #[test]
    fn restack_swaps_sibling_slots_and_keeps_the_document_formatted() {
        let mut doc = Document::parse(SAMPLE);
        let box_a = doc.find_by_node_id(3).unwrap();

        assert!(doc.restack(box_a, Stack::Front), "box-a moves to the top");
        let out = doc.serialize(&Mode::Full);
        let at = |needle: &str| out.find(needle).unwrap_or_else(|| panic!("missing {needle}"));
        assert!(at("id='bg'") < at("id='label'"));
        assert!(at("id='label'") < at("id='grp'"));
        assert!(at("id='grp'") < at("id='box-a'"), "the topmost sibling is written last");
        // Everything else survives verbatim…
        assert!(out.contains("<!-- drawn by hand -->"));
        assert!(out.contains("<rect id='bg' x='0' y='0' width='200' height='120' fill='#eef'/>"));
        // …and the moved element keeps its own line and indentation, because only the *slots*
        // were swapped, not the whitespace between them.
        assert!(out.contains("\n  <path id='box-a' d='M10 10 H60 V60 H10 Z' fill='#f00'/>\n</svg>"));

        assert!(!doc.restack(box_a, Stack::Front), "already on top");
        assert!(doc.restack(box_a, Stack::Backward), "one step down");
        let out = doc.serialize(&Mode::Full);
        assert!(out.find("id='box-a'").unwrap() < out.find("id='grp'").unwrap());
    }

    #[test]
    fn restack_refuses_where_there_is_nowhere_to_go() {
        let mut doc = Document::parse(SAMPLE);
        // `bg` is the oldest sibling, so it is already at the bottom of the stack.
        let bg = doc.find_by_node_id(2).unwrap();
        assert!(!doc.restack(bg, Stack::Backward), "nothing below it");
        assert!(!doc.restack(bg, Stack::Back), "…and Back lands where it already is");
        assert!(doc.restack(bg, Stack::Forward), "one step towards the front");
        assert!(doc.restack(bg, Stack::Back), "and back down");
        assert!(!doc.restack(bg, Stack::Back), "where it now already is");

        // The root <svg> is the only element inside #document, so it has no siblings to swap with.
        let svg = doc.find_by_node_id(1).unwrap();
        assert!(!doc.restack(svg, Stack::Front));
        assert!(!doc.restack(svg, Stack::Back));
        // The document element itself is not restackable either.
        assert!(!doc.restack(0, Stack::Front));
    }

    #[test]
    fn attr_values_decode_entities() {
        let doc = Document::parse("<a x=\"a&amp;b&lt;c\" y='q&quot;q' z=&#65;w flag/>");
        let el = doc.element(1).unwrap();
        assert_eq!(el.attr("x").unwrap(), "a&b<c");
        assert_eq!(el.attr("y").unwrap(), "q\"q");
        assert_eq!(el.attr("z").unwrap(), "Aw");
        assert_eq!(el.attr("flag").unwrap(), "");
        assert!(el.self_closing);
    }

    #[test]
    fn ancestor_transform_multiplies_outermost_first() {
        let src = "<svg><g transform='translate(10 0)'><g transform='translate(0 20)'><rect/></g></g></svg>";
        let doc = Document::parse(src);
        let rect = doc.find_by_node_id(4).unwrap();
        let a = doc.ancestor_transform(rect);
        assert_eq!(a.e, 10.0);
        assert_eq!(a.f, 20.0);
    }
}