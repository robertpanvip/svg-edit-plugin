//! The application view: toolbar, split layout, XML pane and canvas — plus the wiring that keeps
//! the two panes in step.

use std::path::PathBuf;

use gpui::{
    Bounds, Context, Entity, FocusHandle, IntoElement, ParentElement, Pixels, Render, Styled,
    Subscription, Window, div, prelude::*, px, rgb,
};
use gpui_component::{
    Disableable, h_resizable,
    button::Button,
    input::{Editor, EditorState, InputEvent},
    resizable_panel,
};

use crate::canvas::{self, Mapping, Shared, View};
use crate::document::Editor as Document;
use crate::theme;

/// Width of the XML pane at startup. The divider is draggable, so this is only the initial split.
const XML_PANE_WIDTH: f32 = 520.0;

pub struct SvgEasyApp {
    pub editor: Document,
    pub view: View,
    /// Raster cache + last-frame mapping, shared with the paint closures. See [`crate::canvas`].
    pub shared: Shared,
    /// Bumped on every document change so the raster cache can recognise a stale frame.
    pub source_revision: u64,
    pub editor_state: Entity<EditorState>,
    pub focus_handle: FocusHandle,
    /// Transient message for the status banner (save result, open failure).
    pub status: Option<String>,
    _editor_sub: Subscription,
}

impl SvgEasyApp {
    pub fn new(path: Option<PathBuf>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let (editor, status) = Document::open(path);

        let editor_state = cx.new(|cx| {
            EditorState::new(window, cx)
                .language("xml")
                .default_value(editor.source.clone())
        });

        // XML pane -> canvas. `EditorState::set_value` suppresses its own change event, so a
        // canvas edit fed back into the pane cannot bounce back through here; the equality check
        // in `set_source` is the second guard.
        let editor_sub = cx.subscribe(
            &editor_state,
            |this: &mut Self,
             pane: Entity<EditorState>,
             ev: &InputEvent,
             cx: &mut Context<Self>| {
                if !matches!(ev, InputEvent::Change) {
                    return;
                }
                let text = pane.read(cx).value().to_string();
                if this.editor.set_source(text) {
                    this.source_revision = this.source_revision.wrapping_add(1);
                    cx.notify();
                }
            },
        );

        Self {
            editor,
            view: View::new(),
            shared: canvas::new_shared(),
            source_revision: 1,
            editor_state,
            focus_handle: cx.focus_handle(),
            status,
            _editor_sub: editor_sub,
        }
    }

    /// The doc<->screen mapping of the last painted frame, or `None` before the first paint.
    pub fn mapping(&self) -> Option<Mapping> {
        self.shared.borrow().mapping
    }

    /// The canvas viewport of the last painted frame.
    pub fn bounds(&self) -> Option<Bounds<Pixels>> {
        self.shared.borrow().bounds
    }

    /// Adopts an engine-side edit (move, delete): republishes the source into the XML pane, bumps
    /// the revision so the raster re-renders, and repaints.
    pub fn after_document_edit(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.source_revision = self.source_revision.wrapping_add(1);
        let text = self.editor.source.clone();
        self.editor_state
            .update(cx, |pane, cx| pane.set_value(text, window, cx));
        cx.notify();
    }

    pub fn save(&mut self, _window: &mut Window, cx: &mut Context<Self>) {
        self.status = Some(match self.editor.save() {
            Ok(path) => format!("saved {}", path.display()),
            Err(e) => e,
        });
        cx.notify();
    }

    /// The status line: the parse error if the document is currently broken, otherwise the last
    /// transient message. The bool marks "this is an error".
    fn banner(&self) -> Option<(String, bool)> {
        if let Some(err) = self.editor.parse_error.as_ref() {
            return Some((format!("XML error — showing the last valid render: {err}"), true));
        }
        self.status.clone().map(|msg| (msg, false))
    }

    fn toolbar(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let title = self.editor.title();
        let zoom = self.view.zoom;
        let can_delete = self.editor.has_selection();
        let has_file = self.editor.path.is_some();

        div()
            .flex()
            .flex_row()
            .items_center()
            .gap_2()
            .px_3()
            .h(px(42.))
            .bg(theme::chrome_bg())
            .child(
                div()
                    .text_size(px(13.))
                    .text_color(rgb(0xe6e6e6))
                    .child(title),
            )
            .child(div().flex_1())
            .child(
                Button::new("fit")
                    .label("Fit")
                    .compact()
                    .tooltip("Fit the document to the window (Ctrl+0)")
                    .on_click(cx.listener(|this, _, _window, cx| {
                        this.view.reset();
                        cx.notify();
                    })),
            )
            .child(
                Button::new("zoom-out")
                    .label("−")
                    .compact()
                    .tooltip("Zoom out (Ctrl+-)")
                    .on_click(cx.listener(|this, _, _window, cx| {
                        this.view.zoom = (this.view.zoom * 0.8).clamp(0.05, 32.0);
                        cx.notify();
                    })),
            )
            .child(
                div()
                    .text_size(px(12.))
                    .text_color(rgb(0x9d9d9d))
                    .child(format!("{:.0}%", zoom * 100.0)),
            )
            .child(
                Button::new("zoom-in")
                    .label("+")
                    .compact()
                    .tooltip("Zoom in (Ctrl+=)")
                    .on_click(cx.listener(|this, _, _window, cx| {
                        this.view.zoom = (this.view.zoom * 1.25).clamp(0.05, 32.0);
                        cx.notify();
                    })),
            )
            .child(
                Button::new("delete")
                    .label("Delete")
                    .compact()
                    .tooltip("Delete the selected elements (Del)")
                    .disabled(!can_delete)
                    .on_click(cx.listener(|this, _, window, cx| {
                        if this.editor.delete_selection() {
                            this.after_document_edit(window, cx);
                        }
                    })),
            )
            .child(
                Button::new("save")
                    .label(if has_file { "Save" } else { "Save as untitled.svg" })
                    .compact()
                    .tooltip("Write the document back to disk (Ctrl+S)")
                    .on_click(cx.listener(|this, _, window, cx| this.save(window, cx))),
            )
    }
}

impl Render for SvgEasyApp {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let toolbar = self.toolbar(cx);
        let canvas = self.canvas_view(cx);
        let banner = self.banner();

        let right = div()
            .size_full()
            .relative()
            .child(canvas)
            .when_some(banner, |el, (text, is_error)| {
                el.child(
                    div()
                        .absolute()
                        .top_2()
                        .left_2()
                        .right_2()
                        .px_3()
                        .py_2()
                        .rounded_lg()
                        .text_size(px(13.))
                        .bg(if is_error {
                            theme::error_bg()
                        } else {
                            theme::chrome_bg()
                        })
                        .text_color(if is_error {
                            theme::error_fg()
                        } else {
                            theme::selection()
                        })
                        .child(text),
                )
            });

        div()
            .size_full()
            .flex()
            .flex_col()
            .bg(theme::chrome_bg())
            .child(toolbar)
            .child(
                div().flex_1().min_h(px(0.)).child(
                    h_resizable("svg-easy-split")
                        .child(
                            resizable_panel()
                                .size(px(XML_PANE_WIDTH))
                                // The editor does not stretch on its own: inside a flex column it
                                // would collapse to a single line, so ask for the full height.
                                .child(
                                    div()
                                        .size_full()
                                        .child(Editor::new(&self.editor_state).h_full()),
                                ),
                        )
                        .child(resizable_panel().child(right)),
                ),
            )
    }
}
