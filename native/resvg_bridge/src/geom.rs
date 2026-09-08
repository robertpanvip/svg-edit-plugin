//! Matrix helpers shared by the editor DOM and the session layer.

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Mat {
    pub a: f64,
    pub b: f64,
    pub c: f64,
    pub d: f64,
    pub e: f64,
    pub f: f64,
}

impl Mat {
    pub const IDENTITY: Mat = Mat {
        a: 1.0,
        b: 0.0,
        c: 0.0,
        d: 1.0,
        e: 0.0,
        f: 0.0,
    };

    pub fn mul(self, r: Mat) -> Mat {
        Mat {
            a: self.a * r.a + self.c * r.b,
            b: self.b * r.a + self.d * r.b,
            c: self.a * r.c + self.c * r.d,
            d: self.b * r.c + self.d * r.d,
            e: self.a * r.e + self.c * r.f + self.e,
            f: self.b * r.e + self.d * r.f + self.f,
        }
    }

    pub fn apply(self, x: f64, y: f64) -> (f64, f64) {
        (
            self.a * x + self.c * y + self.e,
            self.b * x + self.d * y + self.f,
        )
    }

    pub fn invert(self) -> Option<Mat> {
        let det = self.a * self.d - self.b * self.c;
        if det.abs() < 1e-12 {
            return None;
        }
        Some(Mat {
            a: self.d / det,
            b: -self.b / det,
            c: -self.c / det,
            d: self.a / det,
            e: (self.c * self.f - self.d * self.e) / det,
            f: (self.b * self.e - self.a * self.f) / det,
        })
    }

    pub fn translate(dx: f64, dy: f64) -> Mat {
        Mat {
            e: dx,
            f: dy,
            ..Mat::IDENTITY
        }
    }

    pub fn scale(sx: f64, sy: f64) -> Mat {
        Mat {
            a: sx,
            d: sy,
            ..Mat::IDENTITY
        }
    }

    pub fn abs_scale(self) -> f64 {
        self.a
            .abs()
            .max(self.b.abs())
            .max(self.c.abs())
            .max(self.d.abs())
            .max(1e-6)
    }

    pub fn is_translate_only(self) -> bool {
        approx(self.a, 1.0) && approx(self.b, 0.0) && approx(self.c, 0.0) && approx(self.d, 1.0)
    }
}

fn approx(v: f64, target: f64) -> bool {
    (v - target).abs() < 1e-9
}

fn skip_sep(b: &[u8], i: &mut usize) {
    while *i < b.len() && (b[*i] as char).is_ascii_whitespace() || (*i < b.len() && b[*i] == b',') {
        *i += 1;
    }
}

fn parse_num(s: &str, b: &[u8], i: &mut usize) -> Option<f64> {
    let start = *i;
    if *i < b.len() && (b[*i] == b'+' || b[*i] == b'-') {
        *i += 1;
    }
    let mut seen_digit = false;
    while *i < b.len() && b[*i].is_ascii_digit() {
        *i += 1;
        seen_digit = true;
    }
    if *i < b.len() && b[*i] == b'.' {
        *i += 1;
        while *i < b.len() && b[*i].is_ascii_digit() {
            *i += 1;
            seen_digit = true;
        }
    }
    if !seen_digit {
        *i = start;
        return None;
    }
    if *i < b.len() && (b[*i] == b'e' || b[*i] == b'E') {
        let save = *i;
        *i += 1;
        if *i < b.len() && (b[*i] == b'+' || b[*i] == b'-') {
            *i += 1;
        }
        let mut exp_digits = false;
        while *i < b.len() && b[*i].is_ascii_digit() {
            *i += 1;
            exp_digits = true;
        }
        if !exp_digits {
            *i = save;
        }
    }
    s[start..*i].parse::<f64>().ok()
}

pub fn parse_transform(s: &str) -> Mat {
    let b = s.as_bytes();
    let mut i = 0usize;
    let mut out = Mat::IDENTITY;
    loop {
        skip_sep(b, &mut i);
        if i >= b.len() {
            break;
        }
        let start = i;
        while i < b.len() && (b[i] as char).is_ascii_alphabetic() {
            i += 1;
        }
        let name = &s[start..i];
        if name.is_empty() {
            break;
        }
        skip_sep(b, &mut i);
        if i < b.len() && b[i] == b'(' {
            i += 1;
        }
        let mut nums: Vec<f64> = Vec::new();
        loop {
            skip_sep(b, &mut i);
            if i >= b.len() {
                break;
            }
            if b[i] == b')' {
                i += 1;
                break;
            }
            match parse_num(s, b, &mut i) {
                Some(v) => nums.push(v),
                None => break,
            }
        }
        let m = match name {
            "matrix" if nums.len() >= 6 => Mat {
                a: nums[0],
                b: nums[1],
                c: nums[2],
                d: nums[3],
                e: nums[4],
                f: nums[5],
            },
            "translate" => {
                let dx = nums.first().copied().unwrap_or(0.0);
                let dy = nums.get(1).copied().unwrap_or(0.0);
                Mat::translate(dx, dy)
            }
            "scale" => {
                let sx = nums.first().copied().unwrap_or(1.0);
                let sy = nums.get(1).copied().unwrap_or(sx);
                Mat::scale(sx, sy)
            }
            "rotate" => {
                let ang = nums.first().copied().unwrap_or(0.0).to_radians();
                let (cos, sin) = (ang.cos(), ang.sin());
                let r = Mat {
                    a: cos,
                    b: sin,
                    c: -sin,
                    d: cos,
                    e: 0.0,
                    f: 0.0,
                };
                if nums.len() >= 3 {
                    let (cx, cy) = (nums[1], nums[2]);
                    Mat::translate(cx, cy).mul(r).mul(Mat::translate(-cx, -cy))
                } else {
                    r
                }
            }
            "skewX" => Mat {
                c: nums.first().copied().unwrap_or(0.0).to_radians().tan(),
                ..Mat::IDENTITY
            },
            "skewY" => Mat {
                b: nums.first().copied().unwrap_or(0.0).to_radians().tan(),
                ..Mat::IDENTITY
            },
            _ => Mat::IDENTITY,
        };
        out = out.mul(m);
    }
    out
}

pub fn fmt_num(v: f64) -> String {
    let v = if v.abs() < 1e-9 { 0.0 } else { v };
    let rounded = v.round();
    if (v - rounded).abs() < 1e-9 {
        format!("{}", rounded as i64)
    } else {
        let s = format!("{:.6}", v);
        s.trim_end_matches('0').trim_end_matches('.').to_string()
    }
}

pub fn fmt_transform(m: Mat) -> String {
    if m.is_translate_only() {
        format!("translate({} {})", fmt_num(m.e), fmt_num(m.f))
    } else {
        format!(
            "matrix({} {} {} {} {} {})",
            fmt_num(m.a),
            fmt_num(m.b),
            fmt_num(m.c),
            fmt_num(m.d),
            fmt_num(m.e),
            fmt_num(m.f)
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn close(a: f64, b: f64) -> bool {
        (a - b).abs() < 1e-6
    }

    #[test]
    fn translate_only_format() {
        let m = Mat::translate(12.5, -3.0);
        assert_eq!(fmt_transform(m), "translate(12.5 -3)");
    }

    #[test]
    fn general_format() {
        let m = parse_transform("rotate(90)");
        assert_eq!(fmt_transform(m), "matrix(0 1 -1 0 0 0)");
    }

    #[test]
    fn parse_translate_comma_and_space() {
        let m = parse_transform("translate(10,20)  scale(2)");
        let (x, y) = m.apply(5.0, 5.0);
        assert!(close(x, 20.0) && close(y, 30.0));
    }

    #[test]
    fn parse_rotate_around_center() {
        let m = parse_transform("rotate(90 10 10)");
        let (x, y) = m.apply(20.0, 10.0);
        assert!(close(x, 10.0) && close(y, 20.0));
    }

    #[test]
    fn parse_scientific_and_signed() {
        let m = parse_transform("matrix(1.5e0 0 -0.5 2 +1 -3)");
        assert!(close(m.a, 1.5) && close(m.c, -0.5) && close(m.d, 2.0) && close(m.e, 1.0) && close(m.f, -3.0));
    }

    #[test]
    fn invert_roundtrip() {
        let m = parse_transform("translate(30 40) rotate(35) scale(1.5 2)");
        let inv = m.invert().unwrap();
        let (x, y) = inv.apply(7.0, 9.0);
        let (bx, by) = m.apply(x, y);
        assert!(close(bx, 7.0) && close(by, 9.0));
    }

    #[test]
    fn singular_has_no_inverse() {
        assert!(Mat::scale(0.0, 1.0).invert().is_none());
    }

    #[test]
    fn mul_order_is_svg_semantics() {
        let t = Mat::translate(10.0, 0.0);
        let s = Mat::scale(2.0, 2.0);
        let composed = t.mul(s);
        let (x, _) = composed.apply(5.0, 0.0);
        assert!(close(x, 20.0));
        let composed2 = s.mul(t);
        let (x2, _) = composed2.apply(5.0, 0.0);
        assert!(close(x2, 30.0));
    }

    #[test]
    fn fmt_num_trims() {
        assert_eq!(fmt_num(3.0), "3");
        assert_eq!(fmt_num(-0.0), "0");
        assert_eq!(fmt_num(1.23456789), "1.234568");
        assert_eq!(fmt_num(0.5), "0.5");
    }
}
