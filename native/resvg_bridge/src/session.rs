//! Editor session: owns the fidelity DOM (source of truth) and rebuilds a
//! throw-away usvg projection for rendering / hit-testing on every change.

use std::collections::{HashMap, HashSet};

use resvg::tiny_skia;
use usvg::{tiny_skia_path, Node, Options, Tree};

use crate::dom::{Document, Mode};
use crate::geom::{fmt_transform, Mat};

const MAX_PX: u32 = 16384;
const FLATTEN_STEPS: usize = 16;

/// Per-element info exposed to the Kotlin layer.
#[derive(Clone, Debug)]
pub struct ElementInfo {
    pub tag: String,
    pub user_id: Option<String>,
}

pub struct Session {
    pub doc: Document,
    pub tree: Tree,
    /// usvg id string (synthesized `eN` or the user's id) -> editor node id.
    id_to_node: HashMap<String, usize>,
    /// usvg id string -> abs stroke bounding box (x, y, w, h) in root space.
    boxes: HashMap<String, [f64; 4]>,
    pub width: f64,
    pub height: f64,
}

pub fn usvg_options() -> Options<'static> {
    let mut o = Options::default();
    o.fontdb_mut().load_system_fonts();
    o
}

fn mat_of(t: usvg::Transform) -> Mat {
    Mat {
        a: t.sx as f64,
        b: t.ky as f64,
        c: t.kx as f64,
        d: t.sy as f64,
        e: t.tx as f64,
        f: t.ty as f64,
    }
}

impl Session {
    pub fn new(svg: &str) -> Result<Session, String> {
        let doc = Document::parse(svg);
        let injected = doc.serialize(&Mode::InjectIds);
        let tree = Tree::from_str(&injected, &usvg_options()).map_err(|e| e.to_string())?;
        let width = tree.size().width() as f64;
        let height = tree.size().height() as f64;

        // Synthesized ids first so explicit user ids win on collision.
        let mut id_to_node = HashMap::new();
        for idx in doc.element_indices() {
            let el = doc.element(idx).unwrap();
            id_to_node.insert(format!("e{}", el.node_id), el.node_id);
        }
        for idx in doc.element_indices() {
            if let Some(uid) = doc.element(idx).unwrap().attr("id") {
                id_to_node.insert(uid, doc.element(idx).unwrap().node_id);
            }
        }

        let mut s = Session {
            doc,
            tree,
            id_to_node,
            boxes: HashMap::new(),
            width,
            height,
        };
        s.recollect_boxes();
        Ok(s)
    }

    /// Re-parses the injected DOM into a fresh usvg tree and refreshes boxes.
    /// The usvg tree is a projection only; edit state never lives here.
    fn rebuild_projection(&mut self) -> Result<(), String> {
        let injected = self.doc.serialize(&Mode::InjectIds);
        self.tree = Tree::from_str(&injected, &usvg_options()).map_err(|e| e.to_string())?;
        self.width = self.tree.size().width() as f64;
        self.height = self.tree.size().height() as f64;
        self.recollect_boxes();
        Ok(())
    }

    fn recollect_boxes(&mut self) {
        self.boxes.clear();
        collect_group_boxes(self.tree.root(), &mut self.boxes);
    }

    /// Element list in document (paint) order for the Kotlin layer panel.
    pub fn layout_json(&self) -> serde_json::Value {
        let mut arr = Vec::new();
        for idx in self.doc.element_indices() {
            let el = self.doc.element(idx).unwrap();
            let user_id = el.attr("id");
            let key = user_id.clone().unwrap_or_else(|| format!("e{}", el.node_id));
            let (x, y, w, h) = match self.boxes.get(&key) {
                Some(&[x, y, w, h]) => (x, y, w, h),
                None => continue, // not projected (e.g. inside <defs>)
            };
            arr.push(serde_json::json!({
                "nodeId": el.node_id,
                "id": user_id,
                "tag": el.name,
                "x": x,
                "y": y,
                "w": w,
                "h": h,
            }));
        }
        serde_json::json!({ "width": self.width, "height": self.height, "elements": arr })
    }

    /// Topmost element whose geometry contains (x, y) within `tol` root units.
    pub fn hit_test(&self, x: f64, y: f64, tol: f64) -> Option<usize> {
        self.hit_group(self.tree.root(), None, x, y, tol)
    }

    fn hit_group(
        &self,
        group: &usvg::Group,
        override_node: Option<usize>,
        x: f64,
        y: f64,
        tol: f64,
    ) -> Option<usize> {
        group.children().iter().rev().find_map(|child| {
            self.hit_node(child, override_node, x, y, tol)
        })
    }

    fn hit_node(
        &self,
        node: &Node,
        override_node: Option<usize>,
        x: f64,
        y: f64,
        tol: f64,
    ) -> Option<usize> {
        match node {
            // Groups are never hit targets. usvg synthesizes ANONYMOUS wrapper groups around
            // many constructs (transformed children, clipped/overflow content, text runs), and
            // those carry no id, so they must not block the descent into their children.
            Node::Group(g) => self.hit_group(g, override_node, x, y, tol),
            _ => {
                let resolved = override_node.or_else(|| {
                    let id = node_id_of(node)?;
                    self.id_to_node.get(id).copied()
                })?;
                match node {
                    Node::Path(p) => {
                        if !p.is_visible() {
                            return None;
                        }
                        let t = mat_of(p.abs_transform());
                        let inv = t.invert()?;
                        let (lx, ly) = inv.apply(x, y);
                        let ltol = tol / t.abs_scale();
                        if self.path_hit(p, lx, ly, ltol) {
                            Some(resolved)
                        } else {
                            None
                        }
                    }
                    Node::Image(img) => {
                        let b = img.abs_bounding_box();
                        let (bx, by, bw, bh) =
                            (b.x() as f64, b.y() as f64, b.width() as f64, b.height() as f64);
                        if x >= bx - tol && x <= bx + bw + tol && y >= by - tol && y <= by + bh + tol {
                            Some(resolved)
                        } else {
                            None
                        }
                    }
                    Node::Text(txt) => {
                        // Glyph paths carry no ids; keep the text element's identity.
                        self.hit_group(txt.flattened(), Some(resolved), x, y, tol)
                    }
                    Node::Group(_) => unreachable!(),
                }
            }
        }
    }

    fn path_hit(&self, p: &usvg::Path, x: f64, y: f64, tol: f64) -> bool {
        let has_fill = p.fill().is_some();
        let has_stroke = p.stroke().is_some();
        if !has_fill && !has_stroke {
            return false;
        }
        let rings = flatten_rings(p.data());
        if has_fill && winding_contains(&rings, x, y) {
            return true;
        }
        if has_stroke {
            let half = p
                .stroke()
                .map(|s| s.width().get() as f64 / 2.0)
                .unwrap_or(0.0);
            let limit = half.max(tol);
            if dist_to_rings(&rings, x, y) <= limit {
                return true;
            }
        }
        false
    }
}

fn node_id_of(node: &Node) -> Option<&str> {
    match node {
        Node::Group(g) => {
            let id = g.id();
            if id.is_empty() {
                None
            } else {
                Some(id)
            }
        }
        Node::Path(p) => {
            let id = p.id();
            if id.is_empty() {
                None
            } else {
                Some(id)
            }
        }
        Node::Image(i) => {
            let id = i.id();
            if id.is_empty() {
                None
            } else {
                Some(id)
            }
        }
        Node::Text(t) => {
            let id = t.id();
            if id.is_empty() {
                None
            } else {
                Some(id)
            }
        }
    }
}

fn rect4(r: usvg::Rect) -> [f64; 4] {
    [
        r.x() as f64,
        r.y() as f64,
        r.width() as f64,
        r.height() as f64,
    ]
}

fn collect_group_boxes(group: &usvg::Group, out: &mut HashMap<String, [f64; 4]>) {
    for node in group.children() {
        if let Some(id) = node_id_of(node) {
            let b = match node {
                Node::Image(_) => node.abs_bounding_box(),
                _ => node.abs_stroke_bounding_box(),
            };
            out.insert(id.to_string(), rect4(b));
        }
        if let Node::Group(g) = node {
            collect_group_boxes(g, out);
        }
    }
}

/// Flattens path data into polylines (rings), subdividing curves.
fn flatten_rings(data: &tiny_skia_path::Path) -> Vec<Vec<(f64, f64)>> {
    let mut rings: Vec<Vec<(f64, f64)>> = Vec::new();
    let mut cur: Vec<(f64, f64)> = Vec::new();
    let mut start = (0.0f64, 0.0f64);
    let mut at = (0.0f64, 0.0f64);

    fn push_ring(rings: &mut Vec<Vec<(f64, f64)>>, cur: &mut Vec<(f64, f64)>) {
        if cur.len() >= 2 {
            rings.push(std::mem::take(cur));
        } else {
            cur.clear();
        }
    }

    for seg in data.segments() {
        match seg {
            tiny_skia_path::PathSegment::MoveTo(p) => {
                push_ring(&mut rings, &mut cur);
                start = (p.x as f64, p.y as f64);
                at = start;
                cur.push(start);
            }
            tiny_skia_path::PathSegment::LineTo(p) => {
                at = (p.x as f64, p.y as f64);
                cur.push(at);
            }
            tiny_skia_path::PathSegment::QuadTo(c, p) => {
                let (x0, y0) = at;
                let (cx, cy) = (c.x as f64, c.y as f64);
                let (x1, y1) = (p.x as f64, p.y as f64);
                for k in 1..=FLATTEN_STEPS {
                    let t = k as f64 / FLATTEN_STEPS as f64;
                    let u = 1.0 - t;
                    let px = u * u * x0 + 2.0 * u * t * cx + t * t * x1;
                    let py = u * u * y0 + 2.0 * u * t * cy + t * t * y1;
                    cur.push((px, py));
                }
                at = (x1, y1);
            }
            tiny_skia_path::PathSegment::CubicTo(c1, c2, p) => {
                let (x0, y0) = at;
                let (ax, ay) = (c1.x as f64, c1.y as f64);
                let (bx, by) = (c2.x as f64, c2.y as f64);
                let (x1, y1) = (p.x as f64, p.y as f64);
                for k in 1..=FLATTEN_STEPS {
                    let t = k as f64 / FLATTEN_STEPS as f64;
                    let u = 1.0 - t;
                    let px = u * u * u * x0 + 3.0 * u * u * t * ax + 3.0 * u * t * t * bx + t * t * t * x1;
                    let py = u * u * u * y0 + 3.0 * u * u * t * ay + 3.0 * u * t * t * by + t * t * t * y1;
                    cur.push((px, py));
                }
                at = (x1, y1);
            }
            tiny_skia_path::PathSegment::Close => {
                push_ring(&mut rings, &mut cur);
                at = start;
                cur.push(start);
            }
        }
    }
    push_ring(&mut rings, &mut cur);
    rings
}

/// Non-zero winding fill rule over flattened rings.
fn winding_contains(rings: &[Vec<(f64, f64)>], x: f64, y: f64) -> bool {
    let mut wind = 0i32;
    for ring in rings {
        let n = ring.len();
        for i in 0..n {
            let (x1, y1) = ring[i];
            let (x2, y2) = ring[(i + 1) % n];
            let cross = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1);
            if y1 <= y {
                if y2 > y && cross > 0.0 {
                    wind += 1;
                }
            } else if y2 <= y && cross < 0.0 {
                wind -= 1;
            }
        }
    }
    wind != 0
}

fn dist_point_seg(px: f64, py: f64, x1: f64, y1: f64, x2: f64, y2: f64) -> f64 {
    let dx = x2 - x1;
    let dy = y2 - y1;
    let len2 = dx * dx + dy * dy;
    let t = if len2 <= f64::EPSILON {
        0.0
    } else {
        (((px - x1) * dx + (py - y1) * dy) / len2).clamp(0.0, 1.0)
    };
    let (qx, qy) = (x1 + t * dx, y1 + t * dy);
    ((px - qx).powi(2) + (py - qy).powi(2)).sqrt()
}

fn dist_to_rings(rings: &[Vec<(f64, f64)>], x: f64, y: f64) -> f64 {
    let mut best = f64::INFINITY;
    for ring in rings {
        let n = ring.len();
        for i in 0..n {
            let (x1, y1) = ring[i];
            let (x2, y2) = ring[(i + 1) % n];
            best = best.min(dist_point_seg(x, y, x1, y1, x2, y2));
        }
    }
    best
}

/// Renders an SVG string; used for the two startDrag images (fresh trees).
fn render_svg_str(
    svg: &str,
    vw: u32,
    vh: u32,
    scale: f64,
    tx: f64,
    ty: f64,
) -> Result<Vec<u8>, String> {
    let tree = Tree::from_str(svg, &usvg_options()).map_err(|e| e.to_string())?;
    render_tree_viewport(&tree, vw, vh, scale, tx, ty)
}

fn render_tree_viewport(
    tree: &Tree,
    vw: u32,
    vh: u32,
    scale: f64,
    tx: f64,
    ty: f64,
) -> Result<Vec<u8>, String> {
    let w = vw.clamp(1, MAX_PX);
    let h = vh.clamp(1, MAX_PX);
    let mut pm = tiny_skia::Pixmap::new(w, h).ok_or_else(|| "pixmap alloc failed".to_string())?;
    let ts = tiny_skia::Transform::from_row(
        scale as f32,
        0.0,
        0.0,
        scale as f32,
        tx as f32,
        ty as f32,
    );
    resvg::render(tree, ts, &mut pm.as_mut());
    pm.encode_png().map_err(|e| e.to_string())
}

pub struct DragImages {
    pub bg_png: Vec<u8>,
    pub ghost_png: Vec<u8>,
    pub w: u32,
    pub h: u32,
}

impl Session {
    /// One-shot pre-render for a drag gesture: the background with the dragged
    /// subtree cut out, plus a ghost image containing only that subtree.
    /// During the drag the Kotlin side just moves the ghost locally.
    pub fn start_drag(
        &self,
        node_id: usize,
        vw: u32,
        vh: u32,
        scale: f64,
        tx: f64,
        ty: f64,
    ) -> Result<DragImages, String> {
        let idx = self
            .doc
            .find_by_node_id(node_id)
            .ok_or_else(|| format!("unknown nodeId {node_id}"))?;
        let bg_svg = self.doc.serialize(&Mode::Hide(node_id));
        let chain: HashSet<usize> = self.doc.path_to(idx).into_iter().collect();
        let ghost_svg = self.doc.serialize(&Mode::Solo(chain));
        let bg_png = render_svg_str(&bg_svg, vw, vh, scale, tx, ty)?;
        let ghost_png = render_svg_str(&ghost_svg, vw, vh, scale, tx, ty)?;
        Ok(DragImages {
            bg_png,
            ghost_png,
            w: vw.clamp(1, MAX_PX),
            h: vh.clamp(1, MAX_PX),
        })
    }

    /// Commits a finished drag: `m` is the accumulated root-space delta matrix.
    /// The transform lands on the element as `T_new = A^-1 * M * A * T_old`
    /// so it stays local and composes with pre-existing transforms.
    pub fn commit(
        &mut self,
        node_id: usize,
        m: Mat,
        vw: u32,
        vh: u32,
        scale: f64,
        tx: f64,
        ty: f64,
    ) -> Result<serde_json::Value, String> {
        let idx = self
            .doc
            .find_by_node_id(node_id)
            .ok_or_else(|| format!("unknown nodeId {node_id}"))?;
        let a = self.doc.ancestor_transform(idx);
        let el = self.doc.element_mut(idx).unwrap();
        let t_old = el.transform_mat();
        let t_new = a
            .invert()
            .unwrap_or(Mat::IDENTITY)
            .mul(m)
            .mul(a)
            .mul(t_old);
        el.set_attr("transform", &fmt_transform(t_new));

        self.rebuild_projection()?;
        let png = render_tree_viewport(&self.tree, vw, vh, scale, tx, ty)?;
        let svg = self.doc.serialize(&Mode::Full);
        Ok(serde_json::json!({
            "svg": svg,
            "png": base64_png(&png),
            "w": vw.clamp(1, MAX_PX),
            "h": vh.clamp(1, MAX_PX),
            "elements": self.layout_json()["elements"],
        }))
    }

    /// Re-renders the current document under a viewport transform
    /// (pan `tx/ty` in pixels, uniform `scale`). This is the viewBox zoom.
    pub fn render_viewport(
        &self,
        vw: u32,
        vh: u32,
        scale: f64,
        tx: f64,
        ty: f64,
    ) -> Result<serde_json::Value, String> {
        let png = render_tree_viewport(&self.tree, vw, vh, scale, tx, ty)?;
        Ok(serde_json::json!({
            "png": base64_png(&png),
            "w": vw.clamp(1, MAX_PX),
            "h": vh.clamp(1, MAX_PX),
        }))
    }
}

pub fn base64_png(bytes: &[u8]) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD.encode(bytes)
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

    fn box_of(layout: &serde_json::Value, node_id: usize) -> (f64, f64, f64, f64) {
        layout["elements"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["nodeId"].as_u64() == Some(node_id as u64))
            .map(|e| {
                (
                    e["x"].as_f64().unwrap(),
                    e["y"].as_f64().unwrap(),
                    e["w"].as_f64().unwrap(),
                    e["h"].as_f64().unwrap(),
                )
            })
            .unwrap()
    }

    #[test]
    fn layout_lists_elements_in_paint_order() {
        let s = Session::new(SAMPLE).unwrap();
        let layout = s.layout_json();
        let ids: Vec<u64> = layout["elements"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| e["nodeId"].as_u64().unwrap())
            .collect();
        assert_eq!(&ids[..5], &[2, 3, 4, 5, 6]);
        // text (nodeId 7) only projects when an environment font matches.
        assert_eq!(layout["elements"][0]["id"], "bg");
        assert_eq!(layout["elements"][0]["tag"], "rect");
        assert_eq!(layout["elements"][2]["id"], serde_json::Value::Null);
        let (x, y, w, h) = box_of(&layout, 3);
        assert_eq!((x, y, w, h), (10.0, 10.0, 50.0, 50.0));
        // dot sits inside a translated group: ancestor transform applied.
        let (x, y, w, h) = box_of(&layout, 6);
        assert_eq!((x, y, w, h), (110.0, 70.0, 20.0, 20.0));
    }

    #[test]
    fn hit_test_prefers_topmost_leaf() {
        let s = Session::new(SAMPLE).unwrap();
        assert_eq!(s.hit_test(30.0, 30.0, 4.0), Some(3)); // box-a over bg
        assert_eq!(s.hit_test(120.0, 80.0, 2.0), Some(6)); // dot center
        assert_eq!(s.hit_test(90.0, 90.0, 1.0), Some(2)); // bg only
        assert_eq!(s.hit_test(150.0, 150.0, 1.0), None); // outside canvas
    }

    #[test]
    fn commit_accumulates_translate_and_roundtrips() {
        let mut s = Session::new(SAMPLE).unwrap();
        let m = Mat::translate(5.0, 0.0);
        s.commit(3, m, 400, 240, 2.0, 0.0, 0.0).unwrap();
        s.commit(3, m, 400, 240, 2.0, 0.0, 0.0).unwrap();
        let idx = s.doc.find_by_node_id(3).unwrap();
        assert_eq!(
            s.doc.element(idx).unwrap().attr("transform").unwrap(),
            "translate(10 0)"
        );
        let (x, _, _, _) = box_of(&s.layout_json(), 3);
        assert_eq!(x, 20.0);
        // Round-trip fidelity survives the edit.
        let svg = s.doc.serialize(&Mode::Full);
        assert!(svg.contains("<!-- drawn by hand -->"));
        assert!(svg.contains("<rect id='bg' x='0' y='0' width='200' height='120' fill='#eef'/>"));
        assert!(svg.contains("transform=\"translate(10 0)\""));
    }

    #[test]
    fn start_drag_renders_two_distinct_pngs() {
        let s = Session::new(SAMPLE).unwrap();
        let d = s.start_drag(3, 400, 240, 2.0, 0.0, 0.0).unwrap();
        assert_eq!(d.w, 400);
        assert_eq!(d.h, 240);
        assert_eq!(&d.bg_png[..4], b"\x89PNG");
        assert_eq!(&d.ghost_png[..4], b"\x89PNG");
        assert_ne!(d.bg_png, d.ghost_png);
    }

    #[test]
    fn render_viewport_scales() {
        let s = Session::new(SAMPLE).unwrap();
        let out = s.render_viewport(400, 240, 2.0, 0.0, 0.0).unwrap();
        assert_eq!(out["w"], 400);
        assert_eq!(out["h"], 240);
        let png = base64_png::decode(out["png"].as_str().unwrap());
        assert_eq!(&png[..4], b"\x89PNG");
    }

    mod base64_png {
        use base64::Engine as _;
        pub fn decode(s: &str) -> Vec<u8> {
            base64::engine::general_purpose::STANDARD
                .decode(s)
                .unwrap()
        }
    }

    // Mirrors the reported icon: a lone transformed <path> with the root carrying icon-style
    // attributes (no width/height, class + style + overflow). usvg wraps such leaves in
    // anonymous wrapper groups that carry no id — hit testing must descend through them.
    const WRAPPED_ICON: &str = r##"<svg xmlns="http://www.w3.org/2000/svg" fill="currentColor" class="icon" overflow="hidden" style="width:1em;height:1em;vertical-align:middle" viewBox="0 0 1024 1024">
      <path fill="#666" d="M512 341.333 C416 341.333 341.333 416 341.333 512 S416 682.667 512 682.667 682.667 608 682.667 512 S608 341.333 512 341.333 Z M512 414 C578.1 414 632 467.9 632 512 S578.1 610 512 610 392 556.1 392 512 445.9 414 512 414 Z" transform="translate(-62.060606 9.873278)"/>
    </svg>"##;

    #[test]
    fn hit_test_descends_through_anonymous_wrapper_groups() {
        let s = Session::new(WRAPPED_ICON).unwrap();
        // The fixture must actually reproduce the failure mode: usvg wraps the transformed leaf
        // in an id-less group. Without this, the test would not guard the regression.
        assert!(
            s.tree.root().children().iter().any(|c| matches!(c, Node::Group(g) if g.id().is_empty())),
            "fixture must produce an anonymous wrapper group",
        );
        let layout = s.layout_json();
        let node = layout["elements"][0]["nodeId"].as_u64().unwrap() as usize;
        // Ring centre is at (450, 522) after the translate; the band spans radii ~98..171.
        // Regression: the leaf sat under an id-less usvg wrapper group, so every hit returned
        // None and the ring (and any transformed icon path) could never be selected.
        assert_eq!(s.hit_test(590.0, 522.0, 0.5), Some(node)); // right band
        assert_eq!(s.hit_test(310.0, 522.0, 0.5), Some(node)); // left band
        assert_eq!(s.hit_test(450.0, 380.0, 0.5), Some(node)); // top band
        assert_eq!(s.hit_test(450.0, 522.0, 0.5), None); // hollow centre
        assert_eq!(s.hit_test(800.0, 800.0, 0.5), None); // outside
    }
}
