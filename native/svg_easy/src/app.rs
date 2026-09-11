//! The application view: toolbar, split layout, layer panel, XML pane and canvas — plus the
//! wiring that keeps the panes in step.

use std::path::PathBuf;

use gpui::{
    AnyElement, AppContext as _, Bounds, Context, Entity, FocusHandle, IntoElement, KeyDownEvent,
    ParentElement, PathPromptOptions, Pixels, Render, Styled, Subscription, Window, div, prelude::*,
    px, rgba, rgb,
};
use gpui_component::{
    Disableable, h_resizable,
    button::Button,
    input::{Editor, EditorState, InputEvent},
    resizable_panel,
};
use resvg_bridge::dom::Stack;

use crate::canvas::{self, Mapping, Shared, View};
use crate::document::{self, Editor as Document, Layer};
use crate::theme;

/// Width of the layer panel at startup. The divider is draggable, so this is only the initial split.
const LAYER_PANE_WIDTH: f32 = 220.0;
/// Width of the XML pane at startup. The divider is draggable, so this is only the initial split.
const XML_PANE_WIDTH: f32 = 520.0;
/// Indentation per level of nesting in the layer list.
const LAYER_INDENT: f32 = 12.0;

/// Something the user asked for that cannot go ahead while the document has unsaved changes.
///
/// The request is remembered while the prompt is up and replayed once the user answers, so the
/// choice in the prompt is the only thing the prompt has to decide.
pub enum Pending {
    New,
    Open(PathBuf),
    Close,
}

/// The answer to the unsaved-changes prompt.
pub enum Choice {
    Save,
    Discard,
    Cancel,
}

pub struct SvgEasyApp {
    pub editor: Document,
    pub view: View,
    /// Raster cache + last-frame mapping, shared with the paint closures. See [`crate::canvas`].
    pub shared: Shared,
    /// Bumped on every document change so the raster cache can recognise a stale frame.
    pub source_revision: u64,
    pub editor_state: Entity<EditorState>,
    pub focus_handle: FocusHandle,
    /// Transient message for the banner, with `true` marking it as a failure. Parse errors take
    /// precedence and are always failures.
    pub status: Option<(String, bool)>,
    /// Set while the "unsaved changes" prompt is up. The window's close button is refused by
    /// returning `false` from `on_should_close`, so `Pending::Close` is how a close gets retried
    /// once the document is clean.
    pub pending: Option<Pending>,
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

        // Refuse to close on an unsaved document and put the prompt up instead; the window can only
        // go away once the user has answered.
        let this = cx.weak_entity();
        window.on_window_should_close(cx, move |window, cx| {
            this.update(cx, |this, cx| this.on_should_close(window, cx))
                .unwrap_or(true)
        });

        Self {
            editor,
            view: View::new(),
            shared: canvas::new_shared(),
            source_revision: 1,
            editor_state,
            focus_handle: cx.focus_handle(),
            status: status.map(|message| (message, true)),
            pending: None,
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

    /// Adopts an engine-side change to the document (a move, a delete, a freshly opened file) by
    /// republishing the source into the XML pane, bumping the raster revision, and repainting.
    pub fn after_document_edit(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.source_revision = self.source_revision.wrapping_add(1);
        let text = self.editor.source.clone();
        self.editor_state
            .update(cx, |pane, cx| pane.set_value(text, window, cx));
        cx.notify();
    }

    /// Ctrl+S. Falls through to Save As when the document has no file yet.
    pub fn save(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.editor.path.is_none() {
            self.prompt_save_as(window, cx);
            return;
        }
        self.status = Some(match self.editor.save() {
            Ok(path) => (format!("saved {}", path.display()), false),
            Err(e) => (e, true),
        });
        cx.notify();
    }

    /// Ctrl+N: a new, empty document. Asks first if the current one has unsaved changes.
    fn request_new(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.request(Pending::New, window, cx);
    }

    /// Routers a request through the unsaved-changes prompt when there is something to lose.
    fn request(&mut self, pending: Pending, window: &mut Window, cx: &mut Context<Self>) {
        if self.editor.dirty() {
            self.pending = Some(pending);
            cx.notify();
        } else {
            self.run(pending, window, cx);
        }
    }

    /// Carries out a request that is no longer blocked.
    fn run(&mut self, pending: Pending, window: &mut Window, cx: &mut Context<Self>) {
        match pending {
            Pending::New => {
                self.editor = Document::new(document::NEW_DOC.to_string(), None);
                self.view.reset();
                self.status = None;
                self.after_document_edit(window, cx);
            }
            Pending::Open(path) => self.load(path, window, cx),
            Pending::Close => window.remove_window(),
        }
    }

    /// Answers the unsaved-changes prompt.
    fn resolve(&mut self, choice: Choice, window: &mut Window, cx: &mut Context<Self>) {
        let Some(pending) = self.pending.take() else {
            return;
        };
        match choice {
            Choice::Cancel => {
                cx.notify();
            }
            Choice::Discard => self.run(pending, window, cx),
            Choice::Save => {
                // No file yet, so "Save" has to become "Save As" first. `prompt_save_as` resumes the
                // request once the file is written.
                if self.editor.path.is_none() {
                    self.pending = Some(pending);
                    self.prompt_save_as(window, cx);
                    return;
                }
                match self.editor.save() {
                    Ok(_) => self.run(pending, window, cx),
                    Err(e) => {
                        // Keep the prompt up: nothing was written, so the request is still blocked.
                        self.pending = Some(pending);
                        self.status = Some((e, true));
                        cx.notify();
                    }
                }
            }
        }
    }

    /// Called when the window manager asks the window to close. Returning false refuses the close.
    fn on_should_close(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> bool {
        if self.pending.is_some() {
            // The prompt is already up; a second close request must not stack another one.
            return false;
        }
        if !self.editor.dirty() {
            return true;
        }
        self.pending = Some(Pending::Close);
        cx.notify();
        false
    }

    /// Document-level shortcuts.
    ///
    /// Attached to the root rather than the canvas so they work no matter which pane has focus.
    /// Only modifier combinations are handled here — the XML pane consumes plain typing, and
    /// Delete/Escape stay scoped to the canvas (they mean "edit text" inside the pane).
    fn on_global_key(&mut self, ev: &KeyDownEvent, window: &mut Window, cx: &mut Context<Self>) {
        if !ev.keystroke.modifiers.secondary() {
            return;
        }
        match ev.keystroke.key.as_str() {
            "s" if ev.keystroke.modifiers.shift => self.prompt_save_as(window, cx),
            "s" => self.save(window, cx),
            "o" => self.prompt_open(window, cx),
            "n" => self.request_new(window, cx),
            "0" => {
                self.view.reset();
                cx.notify();
            }
            "=" | "+" => {
                self.view.zoom = (self.view.zoom * 1.25).clamp(0.05, 32.0);
                cx.notify();
            }
            "-" => {
                self.view.zoom = (self.view.zoom * 0.8).clamp(0.05, 32.0);
                cx.notify();
            }
            _ => {}
        }
    }

    /// The shortcuts that have to beat the focused pane to the keystroke.
    ///
    /// The XML pane binds Ctrl+Z to its own editor history. Undo here means the *document* history,
    /// which also covers canvas moves and deletes, so it is claimed in the capture phase and the
    /// event is stopped before the pane sees it — two histories on one keystroke would let a single
    /// press undo twice.
    fn on_capture_key(&mut self, ev: &KeyDownEvent, window: &mut Window, cx: &mut Context<Self>) {
        if !ev.keystroke.modifiers.secondary() || ev.keystroke.key != "z" {
            return;
        }
        cx.stop_propagation();
        if ev.keystroke.modifiers.shift {
            self.redo(window, cx);
        } else {
            self.undo(window, cx);
        }
    }

    /// Ctrl+Z: steps the document back one edit.
    fn undo(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.editor.undo() {
            self.after_document_edit(window, cx);
        }
    }

    /// Ctrl+Shift+Z: re-applies the edit the last undo stepped back over.
    fn redo(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.editor.redo() {
            self.after_document_edit(window, cx);
        }
    }

    /// Shows the platform's Open dialog. The result arrives asynchronously on the oneshot the
    /// platform hands back, so the reply is applied from a task.
    fn prompt_open(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let receiver = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some("Open SVG".into()),
        });

        cx.spawn_in(window, async move |this, cx| {
            let outcome = match receiver.await {
                Ok(Ok(Some(paths))) => match paths.into_iter().next() {
                    Some(path) => Ok(Some(path)),
                    None => Ok(None),
                },
                Ok(Ok(None)) => Ok(None), // cancelled
                Ok(Err(e)) => Err(format!("could not show the file picker: {e}")),
                Err(_) => Err("the file picker closed unexpectedly".to_string()),
            };
            this.update_in(cx, |this, window, cx| this.apply_open(outcome, window, cx))
                .ok();
        })
        .detach();
    }

    fn apply_open(
        &mut self,
        outcome: Result<Option<PathBuf>, String>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        match outcome {
            // Cancelled: leave the current document exactly as it was.
            Ok(None) => {}
            // The picker has already been answered, so asking again here only guards the document
            // swap.
            Ok(Some(path)) => self.request(Pending::Open(path), window, cx),
            Err(message) => {
                self.status = Some((message, true));
                cx.notify();
            }
        }
    }

    /// Replaces the document with the file at `path`.
    fn load(&mut self, path: PathBuf, window: &mut Window, cx: &mut Context<Self>) {
        let (editor, status) = Document::open(Some(path));
        self.editor = editor;
        self.view.reset();
        self.status = status.map(|message| (message, true));
        self.after_document_edit(window, cx);
    }

    /// Shows the platform's Save As dialog.
    fn prompt_save_as(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let directory = self.editor.dialog_directory();
        let suggested = self.editor.suggested_file_name();

        let receiver = cx.prompt_for_new_path(&directory, Some(&suggested));
        cx.spawn_in(window, async move |this, cx| {
            let outcome = match receiver.await {
                Ok(Ok(Some(path))) => Ok(Some(path)),
                Ok(Ok(None)) => Ok(None), // cancelled
                Ok(Err(e)) => Err(format!("could not show the save dialog: {e}")),
                Err(_) => Err("the save dialog closed unexpectedly".to_string()),
            };
            this.update_in(cx, |this, window, cx| {
                match outcome {
                    Ok(None) => {}
                    Ok(Some(path)) => {
                        this.status = Some(match this.editor.save_as(path) {
                            Ok(path) => (format!("saved {}", path.display()), false),
                            Err(e) => (e, true),
                        });
                        // "Save" in the unsaved-changes prompt had no file to write to; now it does.
                        this.resume_pending(window, cx);
                        cx.notify();
                    }
                    Err(message) => {
                        this.status = Some((message, true));
                        cx.notify();
                    }
                }
            })
            .ok();
        })
        .detach();
    }

    /// Runs the request the unsaved-changes prompt was holding back, if the save has since landed.
    ///
    /// A still-dirty document means the write failed, so the request stays blocked and the prompt
    /// stays up.
    fn resume_pending(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.pending.is_none() || self.editor.dirty() {
            return;
        }
        let pending = self.pending.take().expect("checked above");
        self.run(pending, window, cx);
    }

    /// The status line: the parse error if the document is currently broken, otherwise the last
    /// transient message. The bool marks "this is an error".
    fn banner(&self) -> Option<(String, bool)> {
        if let Some(err) = self.editor.parse_error.as_ref() {
            return Some((format!("XML error — showing the last valid render: {err}"), true));
        }
        self.status.clone()
    }

    /// The "unsaved changes" prompt: an overlay above everything, so it cannot be missed and
    /// cannot be dismissed by accident. Answers are handled by [`Self::resolve`].
    fn unsaved_prompt(&self, cx: &mut Context<Self>) -> impl IntoElement {
        div()
            .absolute()
            .top_0()
            .left_0()
            .right_0()
            .bottom_0()
            .flex()
            .items_center()
            .justify_center()
            .bg(rgba(0x000000a6))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap_3()
                    .w(px(420.))
                    .p_5()
                    .rounded_lg()
                    .border_1()
                    .border_color(rgb(0x4a4a4a))
                    .bg(rgb(0x2d2d30))
                    .child(
                        div()
                            .text_size(px(14.))
                            .text_color(rgb(0xe6e6e6))
                            .child(format!("{} has unsaved changes.", self.editor.title())),
                    )
                    .child(
                        div()
                            .text_size(px(12.))
                            .text_color(rgb(0x9d9d9d))
                            .child("Save them before continuing?"),
                    )
                    .child(
                        div()
                            .flex()
                            .flex_row()
                            .justify_end()
                            .gap_2()
                            .child(
                                Button::new("prompt-cancel")
                                    .label("Cancel")
                                    .compact()
                                    .on_click(cx.listener(|this, _, window, cx| {
                                        this.resolve(Choice::Cancel, window, cx)
                                    })),
                            )
                            .child(
                                Button::new("prompt-discard")
                                    .label("Discard")
                                    .compact()
                                    .tooltip("Throw the unsaved changes away")
                                    .on_click(cx.listener(|this, _, window, cx| {
                                        this.resolve(Choice::Discard, window, cx)
                                    })),
                            )
                            .child(
                                Button::new("prompt-save")
                                    .label("Save")
                                    .compact()
                                    .on_click(cx.listener(|this, _, window, cx| {
                                        this.resolve(Choice::Save, window, cx)
                                    })),
                            ),
                    ),
            )
    }

    /// The layer panel: every element in the document, topmost first, with the restack buttons.
    ///
    /// Paint order runs the other way round from the source — the last element written is drawn on
    /// top — so the list is reversed, the way every layer panel reads.
    fn layers_panel(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let selection = self.editor.selection.clone();
        // A restack moves *one* element to a slot, so it needs exactly one selected.
        let restackable = selection.len() == 1;

        // Built eagerly: the listeners borrow the context, and `restack_button` below needs it back.
        let rows: Vec<AnyElement> = self
            .editor
            .layers()
            .into_iter()
            .map(|layer| {
                let Layer {
                    node_id,
                    label,
                    depth,
                } = layer;
                let selected = selection.contains(&node_id);
                div()
                    .id(("layer", node_id))
                    .flex()
                    .flex_row()
                    .items_center()
                    .h(px(22.))
                    .w_full()
                    .pl(px(6.0 + depth as f32 * LAYER_INDENT))
                    .pr_2()
                    .text_size(px(12.))
                    .text_color(if selected {
                        rgb(0xffffff)
                    } else {
                        rgb(0xcfcfcf)
                    })
                    .when(selected, |el| el.bg(theme::selection()))
                    .child(label)
                    .on_click(cx.listener(move |this, _, _window, cx| {
                        this.editor.select_only(node_id);
                        cx.notify();
                    }))
                    .into_any_element()
            })
            .collect();

        div()
            .flex()
            .flex_col()
            .size_full()
            .bg(theme::chrome_bg())
            .child(
                div()
                    .px_2()
                    .py_1()
                    .text_size(px(12.))
                    .text_color(rgb(0x9d9d9d))
                    .child("Layers"),
            )
            .child(
                div()
                    .flex()
                    .flex_row()
                    .items_center()
                    .gap_1()
                    .px_2()
                    .pb_2()
                    .child(self.restack_button(
                        "layer-front",
                        "Front",
                        "Paint the element last, over its siblings",
                        Stack::Front,
                        restackable,
                        cx,
                    ))
                    .child(self.restack_button(
                        "layer-forward",
                        "Up",
                        "Move the element one step up the stack",
                        Stack::Forward,
                        restackable,
                        cx,
                    ))
                    .child(self.restack_button(
                        "layer-backward",
                        "Down",
                        "Move the element one step down the stack",
                        Stack::Backward,
                        restackable,
                        cx,
                    ))
                    .child(self.restack_button(
                        "layer-back",
                        "Back",
                        "Paint the element first, behind its siblings",
                        Stack::Back,
                        restackable,
                        cx,
                    )),
            )
            .child(
                div()
                    .id("layer-list")
                    .flex()
                    .flex_col()
                    .flex_1()
                    .min_h(px(0.))
                    .px_1()
                    .overflow_y_scroll()
                    .children(rows),
            )
    }

    /// One of the four restack buttons, wired to [`Document::restack_selection`].
    fn restack_button(
        &self,
        id: &'static str,
        label: &'static str,
        tooltip: &'static str,
        to: Stack,
        enabled: bool,
        cx: &mut Context<Self>,
    ) -> Button {
        Button::new(id)
            .label(label)
            .compact()
            .tooltip(tooltip)
            .disabled(!enabled)
            .on_click(cx.listener(move |this, _, window, cx| {
                if this.editor.restack_selection(to) {
                    this.after_document_edit(window, cx);
                }
            }))
    }

    fn toolbar(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let title = self.editor.title();
        let zoom = self.view.zoom;
        let can_delete = self.editor.has_selection();
        let can_undo = self.editor.can_undo();
        let can_redo = self.editor.can_redo();

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
                Button::new("undo")
                    .label("Undo")
                    .compact()
                    .tooltip("Step back one edit (Ctrl+Z)")
                    .disabled(!can_undo)
                    .on_click(cx.listener(|this, _, window, cx| this.undo(window, cx))),
            )
            .child(
                Button::new("redo")
                    .label("Redo")
                    .compact()
                    .tooltip("Re-apply the undone edit (Ctrl+Shift+Z)")
                    .disabled(!can_redo)
                    .on_click(cx.listener(|this, _, window, cx| this.redo(window, cx))),
            )
            .child(div().w(px(1.)).h(px(18.)).bg(rgb(0x3c3c3c)))
            .child(
                Button::new("new")
                    .label("New")
                    .compact()
                    .tooltip("Start an empty document (Ctrl+N)")
                    .on_click(cx.listener(|this, _, window, cx| this.request_new(window, cx))),
            )
            .child(
                Button::new("open")
                    .label("Open…")
                    .compact()
                    .tooltip("Open an SVG file (Ctrl+O)")
                    .on_click(cx.listener(|this, _, window, cx| this.prompt_open(window, cx))),
            )
            .child(
                Button::new("save")
                    .label("Save")
                    .compact()
                    .tooltip("Write the document back to disk (Ctrl+S)")
                    .on_click(cx.listener(|this, _, window, cx| this.save(window, cx))),
            )
            .child(
                Button::new("save-as")
                    .label("Save As…")
                    .compact()
                    .tooltip("Write the document to a new path (Ctrl+Shift+S)")
                    .on_click(cx.listener(|this, _, window, cx| this.prompt_save_as(window, cx))),
            )
            .child(div().w(px(1.)).h(px(18.)).bg(rgb(0x3c3c3c)))
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
    }
}

impl Render for SvgEasyApp {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let toolbar = self.toolbar(cx);
        let canvas = self.canvas_view(cx);
        let banner = self.banner();
        let prompt = self.pending.is_some().then(|| self.unsaved_prompt(cx));

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
            .relative()
            .flex()
            .flex_col()
            .bg(theme::chrome_bg())
            // Document-level shortcuts live on the root so they fire no matter which pane has
            // focus: key events bubble up from the focused node. Undo/redo are claimed in the
            // capture phase instead, so the XML pane cannot shadow them with its own history.
            .capture_key_down(cx.listener(Self::on_capture_key))
            .on_key_down(cx.listener(Self::on_global_key))
            .child(toolbar)
            .child(
                div().flex_1().min_h(px(0.)).child(
                    h_resizable("svg-easy-split")
                        .child(
                            resizable_panel()
                                .size(px(LAYER_PANE_WIDTH))
                                .child(self.layers_panel(cx)),
                        )
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
            // Last child, so the prompt paints over the panes.
            .when_some(prompt, |el, prompt| el.child(prompt))
    }
}
