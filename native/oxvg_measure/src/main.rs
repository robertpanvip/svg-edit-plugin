use oxvg_ast::{parse::roxmltree::parse, serialize::Node as _, visitor::Info};
use oxvg_optimiser::Jobs;

fn main() {
    let svg = r##"<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120">
  <rect x="10" y="10" width="80" height="60" fill="#4caf50"/>
</svg>"##;
    let mut jobs = Jobs::default();
    let out = parse(svg, |dom, allocator| {
        let _ = jobs.run(dom, &Info::new(allocator));
        dom.serialize().unwrap_or_default()
    })
    .unwrap_or_default();
    println!("{out}");
}