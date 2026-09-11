//! Embeds `assets/app-icon.ico` into the Windows executable.
//!
//! Windows reads an application's icon from a resource linked into the `.exe` — there is no API to
//! set it from code — so the `.ico` has to be compiled in at build time. The other targets ignore
//! this: X11 gets its icon from `WindowOptions` (`icons::app_icon`) and macOS from the bundle.
//!
//! The `.ico` is generated from `assets/app-icon.svg`; run `cargo run --example gen-app-icon`
//! after changing the artwork.

fn main() {
    println!("cargo:rerun-if-changed=assets/app-icon.ico");

    #[cfg(windows)]
    winresource::WindowsResource::new()
        .set_icon("assets/app-icon.ico")
        .compile()
        .expect("failed to embed the application icon");
}
