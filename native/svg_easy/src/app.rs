//! The application view: toolbar, split layout, layer panel, XML pane and canvas — plus the
//! wiring that keeps the panes in step.

use std::path::PathBuf;

use gpui::{
    AppContext as _, Bounds, Context, Entity, FocusHandle, IntoElement, KeyDownEvent, ParentElement,
    PathPromptOptions, Pixels, Render, Styled, Subscription, Window, div, point, prelude::*, px,
    rgba, rgb,
};
use gpui_component::{
    Disableable, Selectable, h_resizable,
    button::Button,
    input::{Editor, EditorState, InputEvent},
    menu::{PopupMenu, PopupMenuItem},
    resizable_panel,
};
use resvg_bridge::dom::Stack;

use crate::canvas::{self, Mapping, Shared, Tool, View};
use crate::document::{self, Editor as Document};
use crate::icons::ToolbarIcon;
use crate::theme;

/// Width of the XML pane at startup. The divider is draggable, so this is only the initial split.
const XML_PANE_WIDTH: f32 = 520.0;

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
            Ok(path) => (format!("已保存 {}", path.display()), false),
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
            "1" => self.actual_size(cx),
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
            prompt: Some("打开 SVG".into()),
        });

        cx.spawn_in(window, async move |this, cx| {
            let outcome = match receiver.await {
                Ok(Ok(Some(paths))) => match paths.into_iter().next() {
                    Some(path) => Ok(Some(path)),
                    None => Ok(None),
                },
                Ok(Ok(None)) => Ok(None), // cancelled
                Ok(Err(e)) => Err(format!("无法打开文件选择器：{e}")),
                Err(_) => Err("文件选择器意外关闭".to_string()),
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
                Ok(Err(e)) => Err(format!("无法打开保存对话框：{e}")),
                Err(_) => Err("保存对话框意外关闭".to_string()),
            };
            this.update_in(cx, |this, window, cx| {
                match outcome {
                    Ok(None) => {}
                    Ok(Some(path)) => {
                        this.status = Some(match this.editor.save_as(path) {
                            Ok(path) => (format!("已保存 {}", path.display()), false),
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
            return Some((format!("XML 错误 —— 当前显示的是最后一次成功渲染：{err}"), true));
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
                            .child(format!("{} 有未保存的更改。", self.editor.title())),
                    )
                    .child(
                        div()
                            .text_size(px(12.))
                            .text_color(rgb(0x9d9d9d))
                            .child("是否先保存再继续？"),
                    )
                    .child(
                        div()
                            .flex()
                            .flex_row()
                            .justify_end()
                            .gap_2()
                            .child(
                                Button::new("prompt-cancel")
                                    .label("取消")
                                    .compact()
                                    .on_click(cx.listener(|this, _, window, cx| {
                                        this.resolve(Choice::Cancel, window, cx)
                                    })),
                            )
                            .child(
                                Button::new("prompt-discard")
                                    .label("不保存")
                                    .compact()
                                    .tooltip("放弃未保存的更改")
                                    .on_click(cx.listener(|this, _, window, cx| {
                                        this.resolve(Choice::Discard, window, cx)
                                    })),
                            )
                            .child(
                                Button::new("prompt-save")
                                    .label("保存")
                                    .compact()
                                    .on_click(cx.listener(|this, _, window, cx| {
                                        this.resolve(Choice::Save, window, cx)
                                    })),
                            ),
                    ),
            )
    }

    /// The canvas's right-click menu: the four stack moves for the selected element.
    ///
    /// These used to be a permanent "Layers" panel down the left. The panel spent a column of
    /// width listing every element in the document, when the only question anyone asks of that
    /// list is *"put this one in front of that one"* — and that question is always asked about
    /// the element already under the pointer. So the list is gone and the four moves live where
    /// the pointer is.
    ///
    /// The menu is attached to the canvas whether or not anything is selected, because the
    /// right-click that opens it is also what makes the selection (`on_canvas_right_down`). Hence
    /// the check here rather than at attach time: the builder runs a frame after that click, so it
    /// sees the selection the click just made. Fewer than one element, or more than one, and there
    /// is nothing a stack move could act on — an empty menu, which is never opened at all.
    pub fn restack_menu(
        &self,
        cx: &mut Context<Self>,
    ) -> impl Fn(PopupMenu, &mut Window, &mut Context<PopupMenu>) -> PopupMenu + 'static {
        let view = cx.weak_entity();
        move |menu, _window, cx| {
            let single = view
                .read_with(cx, |view, _| view.editor.selection.len() == 1)
                .unwrap_or(false);
            if !single {
                return menu;
            }
            [
                ("置顶", Stack::Front),
                ("上移一层", Stack::Forward),
                ("下移一层", Stack::Backward),
                ("置底", Stack::Back),
            ]
            .into_iter()
            .fold(menu, |menu, (label, to)| {
                let view = view.clone();
                menu.item(PopupMenuItem::new(label).on_click(move |_, window, cx| {
                    view.update(cx, |this, cx| {
                        if this.editor.restack_selection(to) {
                            this.after_document_edit(window, cx);
                        }
                    })
                    .ok();
                }))
            })
        }
    }

    /// One icon action on the toolbar.
    ///
    /// Icon-only and compact, so every entry is the same size and the row sits on one baseline —
    /// the widths came out ragged when the buttons carried words of different lengths.
    fn tool_button(
        &self,
        id: &'static str,
        icon: ToolbarIcon,
        tooltip: &'static str,
        enabled: bool,
        cx: &mut Context<Self>,
        action: impl Fn(&mut Self, &mut Window, &mut Context<Self>) + 'static,
    ) -> Button {
        Button::new(id)
            .icon(icon)
            .compact()
            .tooltip(tooltip)
            .disabled(!enabled)
            .on_click(cx.listener(move |this, _, window, cx| action(this, window, cx)))
    }

    /// A toolbar button that stays pressed, for the tool group and the view toggles.
    fn toggle_button(
        &self,
        id: &'static str,
        icon: ToolbarIcon,
        tooltip: &'static str,
        active: bool,
        cx: &mut Context<Self>,
        action: impl Fn(&mut Self, &mut Window, &mut Context<Self>) + 'static,
    ) -> Button {
        Button::new(id)
            .icon(icon)
            .compact()
            .tooltip(tooltip)
            .selected(active)
            .toggled(active)
            .on_click(cx.listener(move |this, _, window, cx| action(this, window, cx)))
    }

    /// Zoom to 1 document unit per pixel — what the toolbar reports as 100%.
    ///
    /// `view.zoom` is relative to "fit", so the factor has to go through the fit scale for the
    /// viewport this moment.
    fn actual_size(&mut self, cx: &mut Context<Self>) {
        if let Some(bounds) = self.bounds() {
            let fit = canvas::view_scale(bounds, self.editor.doc_size(), 1.0);
            self.view.zoom = (1.0 / fit).clamp(0.05, 32.0);
            self.view.pan = point(px(0.), px(0.));
        }
        cx.notify();
    }

    /// The action bar.
    ///
    /// The order is the one the IntelliJ-side toolbar lays out (`core/EditorToolbar.kt`): the
    /// interaction tools, then the file actions, then zoom, then the view toggles. The file,
    /// history and delete groups are the standalone app's own — there is no IDE menu to fall back
    /// on here — but they keep the same icon-button shape and separators.
    fn toolbar(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let title = self.editor.title();
        // The real scale, not the fit-relative zoom, so "100%" means one unit per pixel.
        let scale = self.mapping().map(|m| m.scale).unwrap_or(self.view.zoom);
        let can_delete = self.editor.has_selection();
        let can_undo = self.editor.can_undo();
        let can_redo = self.editor.can_redo();
        let tool = self.view.tool;
        let (grid, chessboard) = (self.view.grid, self.view.chessboard);

        let separator = || div().w(px(1.)).h(px(18.)).mx_1().bg(rgb(0x3c3c3c));

        div()
            .flex()
            .flex_row()
            .items_center()
            .gap_1()
            .px_2()
            .h(px(40.))
            .bg(theme::chrome_bg())
            .child(
                div()
                    .px_2()
                    .text_size(px(13.))
                    .text_color(rgb(0xe6e6e6))
                    .child(title),
            )
            .child(div().flex_1())
            .child(self.toggle_button(
                "tool-move",
                ToolbarIcon::MoveTool,
                "移动：选择、拖动、缩放和旋转元素",
                tool == Tool::Move,
                cx,
                |this, _window, cx| {
                    this.view.tool = Tool::Move;
                    cx.notify();
                },
            ))
            .child(self.toggle_button(
                "tool-marquee",
                ToolbarIcon::BoxSelect,
                "框选：拖出矩形来选中元素",
                tool == Tool::Marquee,
                cx,
                |this, _window, cx| {
                    this.view.tool = Tool::Marquee;
                    cx.notify();
                },
            ))
            .child(separator())
            .child(self.tool_button(
                "undo",
                ToolbarIcon::Undo,
                "撤销上一步编辑（Ctrl+Z）",
                can_undo,
                cx,
                |this, window, cx| this.undo(window, cx),
            ))
            .child(self.tool_button(
                "redo",
                ToolbarIcon::Redo,
                "重做已撤销的编辑（Ctrl+Shift+Z）",
                can_redo,
                cx,
                |this, window, cx| this.redo(window, cx),
            ))
            .child(separator())
            .child(self.tool_button(
                "new",
                ToolbarIcon::New,
                "新建空白文档（Ctrl+N）",
                true,
                cx,
                |this, window, cx| this.request_new(window, cx),
            ))
            .child(self.tool_button(
                "open",
                ToolbarIcon::Open,
                "打开 SVG 文件（Ctrl+O）",
                true,
                cx,
                |this, window, cx| this.prompt_open(window, cx),
            ))
            .child(self.tool_button(
                "save",
                ToolbarIcon::Save,
                "把文档写回磁盘（Ctrl+S），另存为 Ctrl+Shift+S",
                true,
                cx,
                |this, window, cx| this.save(window, cx),
            ))
            .child(separator())
            .child(self.tool_button(
                "zoom-out",
                ToolbarIcon::ZoomOut,
                "缩小（Ctrl+-）",
                true,
                cx,
                |this, _window, cx| {
                    this.view.zoom = (this.view.zoom * 0.8).clamp(0.05, 32.0);
                    cx.notify();
                },
            ))
            .child(self.tool_button(
                "zoom-in",
                ToolbarIcon::ZoomIn,
                "放大（Ctrl+=）",
                true,
                cx,
                |this, _window, cx| {
                    this.view.zoom = (this.view.zoom * 1.25).clamp(0.05, 32.0);
                    cx.notify();
                },
            ))
            // A fixed width, so the buttons either side do not shift as the number changes.
            .child(
                div()
                    .w(px(44.))
                    .flex()
                    .justify_center()
                    .text_size(px(12.))
                    .text_color(rgb(0x9d9d9d))
                    .child(format!("{:.0}%", scale * 100.0)),
            )
            .child(self.tool_button(
                "actual-size",
                ToolbarIcon::ActualSize,
                "实际大小 —— 100%，一单位一像素（Ctrl+1）",
                true,
                cx,
                |this, _window, cx| this.actual_size(cx),
            ))
            .child(self.tool_button(
                "fit",
                ToolbarIcon::Fit,
                "让文档适应窗口（Ctrl+0）",
                true,
                cx,
                |this, _window, cx| {
                    this.view.reset();
                    cx.notify();
                },
            ))
            .child(separator())
            .child(self.toggle_button(
                "grid",
                ToolbarIcon::Grid,
                "显示或隐藏像素网格",
                grid,
                cx,
                |this, _window, cx| {
                    this.view.grid = !this.view.grid;
                    cx.notify();
                },
            ))
            .child(self.toggle_button(
                "chessboard",
                ToolbarIcon::Chessboard,
                "显示或隐藏透明棋盘格",
                chessboard,
                cx,
                |this, _window, cx| {
                    this.view.chessboard = !this.view.chessboard;
                    cx.notify();
                },
            ))
            .child(separator())
            .child(self.tool_button(
                "delete",
                ToolbarIcon::Delete,
                "删除选中的元素（Del）",
                can_delete,
                cx,
                |this, window, cx| {
                    if this.editor.delete_selection() {
                        this.after_document_edit(window, cx);
                    }
                },
            ))
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
