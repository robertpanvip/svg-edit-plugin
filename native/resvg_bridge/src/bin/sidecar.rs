//! `svg_easy_sidecar` — the stdio JSON-RPC process behind the SvgEasy editor.
//!
//! Protocol: one JSON request per stdin line, one JSON response per stdout line.
//! Logs/diagnostics go to stderr only so the framing never corrupts.
//!
//! Requests:  `{"id":N,"method":"...","params":{...}}`
//! Responses: `{"id":N,"result":...}` or `{"id":N,"error":"..."}` (`id` may be
//! null when the line was not even parseable JSON).
//!
//! Methods:
//!
//! - `ping` → `{"version":"0.5.0","protocol":1}`
//! - `open` `{svg}` → layout `{width,height,elements:[{nodeId,id,tag,x,y,w,h}]}`
//!   (`id` is the user's id or null; coordinates are root-space)
//! - `hitTest` `{x,y,tol?}` → `{"nodeId":N}` or `{}` (topmost wins)
//! - `startDrag` `{nodeId,vw,vh,scale,tx,ty}` →
//!   `{bgPng,ghostPng,w,h}` — two base64 PNGs, rendered once; during the drag
//!   the Kotlin side only moves the ghost locally, no further RPC.
//! - `commit` `{nodeId,matrix,vw,vh,scale,tx,ty}` →
//!   `{svg,png,w,h,elements}` — `matrix` is the accumulated root-space drag
//!   delta in SVG `matrix(a,b,c,d,e,f)` order; the landed `transform` is
//!   `T_new = A^-1 * M * A * T_old`; `svg` is the round-trip-faithful document.
//! - `renderViewport` `{vw?,vh?,scale?,tx?,ty?}` → `{png,w,h}` (viewBox zoom)
//!
//! Any panic inside a handler is caught and turned into an `error` response so
//! a single bad request never takes the process (and thus the IDE session) down.

use std::io::{self, BufRead, Write};

use serde_json::{json, Value};

use resvg_bridge::geom::Mat;
use resvg_bridge::session::{base64_png, Session};

const PROTOCOL: u32 = 1;

fn main() {
    let stdin = io::stdin();
    let mut out = io::stdout();
    let mut session: Option<Session> = None;

    for line in stdin.lock().lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => break,
        };
        let line = line.trim();
        if line.is_empty() {
            continue;
        }

        let req: Value = match serde_json::from_str(line) {
            Ok(v) => v,
            Err(e) => {
                reply(&mut out, Value::Null, Err(format!("bad request: {e}")));
                continue;
            }
        };

        let id = req.get("id").cloned().unwrap_or(Value::Null);
        let method = req
            .get("method")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        let params = req.get("params").cloned().unwrap_or_else(|| json!({}));

        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            dispatch(&mut session, &method, &params)
        }))
        .unwrap_or_else(|_| Err("sidecar internal panic".to_string()));

        reply(&mut out, id, result);
    }
}

fn reply(out: &mut io::Stdout, id: Value, result: Result<Value, String>) {
    let msg = match result {
        Ok(v) => json!({ "id": id, "result": v }),
        Err(e) => json!({ "id": id, "error": e }),
    };
    let _ = writeln!(out, "{msg}");
    let _ = out.flush();
}

fn dispatch(session: &mut Option<Session>, method: &str, p: &Value) -> Result<Value, String> {
    match method {
        "ping" => Ok(json!({ "version": env!("CARGO_PKG_VERSION"), "protocol": PROTOCOL })),
        "open" => {
            let svg = p.get("svg").and_then(Value::as_str).ok_or("missing svg")?;
            let s = Session::new(svg)?;
            let layout = s.layout_json();
            *session = Some(s);
            Ok(layout)
        }
        "hitTest" => {
            let s = session.as_ref().ok_or("no document open")?;
            let x = num(p, "x")?;
            let y = num(p, "y")?;
            let tol = p.get("tol").and_then(Value::as_f64).unwrap_or(4.0);
            Ok(match s.hit_test(x, y, tol) {
                Some(node_id) => json!({ "nodeId": node_id }),
                None => json!({}),
            })
        }
        "startDrag" => {
            let s = session.as_ref().ok_or("no document open")?;
            let node_id = node_id(p)?;
            let (vw, vh) = dims(p);
            let (scale, tx, ty) = view(p);
            let d = s.start_drag(node_id, vw, vh, scale, tx, ty)?;
            Ok(json!({
                "bgPng": base64_png(&d.bg_png),
                "ghostPng": base64_png(&d.ghost_png),
                "w": d.w,
                "h": d.h,
            }))
        }
        "commit" => {
            let s = session.as_mut().ok_or("no document open")?;
            let node_id = node_id(p)?;
            let m = matrix(p)?;
            let (vw, vh) = dims(p);
            let (scale, tx, ty) = view(p);
            s.commit(node_id, m, vw, vh, scale, tx, ty)
        }
        "renderViewport" => {
            let s = session.as_ref().ok_or("no document open")?;
            let (vw, vh) = dims(p);
            let (scale, tx, ty) = view(p);
            s.render_viewport(vw, vh, scale, tx, ty)
        }
        other => Err(format!("unknown method '{other}'")),
    }
}

fn num(p: &Value, key: &str) -> Result<f64, String> {
    p.get(key)
        .and_then(Value::as_f64)
        .ok_or_else(|| format!("missing number '{key}'"))
}

fn node_id(p: &Value) -> Result<usize, String> {
    p.get("nodeId")
        .and_then(Value::as_u64)
        .map(|v| v as usize)
        .ok_or_else(|| "missing nodeId".to_string())
}

fn dims(p: &Value) -> (u32, u32) {
    let vw = p.get("vw").and_then(Value::as_f64).unwrap_or(800.0).max(1.0) as u32;
    let vh = p.get("vh").and_then(Value::as_f64).unwrap_or(600.0).max(1.0) as u32;
    (vw, vh)
}

fn view(p: &Value) -> (f64, f64, f64) {
    let scale = p.get("scale").and_then(Value::as_f64).unwrap_or(1.0);
    let tx = p.get("tx").and_then(Value::as_f64).unwrap_or(0.0);
    let ty = p.get("ty").and_then(Value::as_f64).unwrap_or(0.0);
    (scale, tx, ty)
}

/// SVG `matrix(a,b,c,d,e,f)` argument order.
fn matrix(p: &Value) -> Result<Mat, String> {
    let arr = p
        .get("matrix")
        .and_then(Value::as_array)
        .ok_or("missing matrix")?;
    if arr.len() != 6 {
        return Err("matrix must have exactly 6 numbers".to_string());
    }
    let mut v = [0.0f64; 6];
    for (slot, item) in v.iter_mut().zip(arr) {
        *slot = item.as_f64().ok_or("matrix entries must be numbers")?;
    }
    Ok(Mat { a: v[0], b: v[1], c: v[2], d: v[3], e: v[4], f: v[5] })
}
