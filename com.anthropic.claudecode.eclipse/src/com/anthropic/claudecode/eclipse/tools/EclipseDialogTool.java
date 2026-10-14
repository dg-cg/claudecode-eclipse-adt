package com.anthropic.claudecode.eclipse.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.anthropic.claudecode.eclipse.ui.ClaudeCodeView;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Lists the dialogs that are open and presses a button in one — what clicking the button
 * does. Eclipse's own dialogs are found as SWT shells and buttons, so any plug-in's are
 * reachable; native dialogs and those of other Eclipse instances come from the native core
 * ({@link NativeDialogs}).
 *
 * <p>A button is pressed only when named in full; nothing here picks a default. Relies only
 * on SWT and JFace, re-exported by {@code org.eclipse.ui}, a hard {@code Require-Bundle} — no
 * availability guard.
 */
public class EclipseDialogTool implements McpTool {

    private static final long UI_ANSWER_MS = 2000;
    /** How long a press waits to see its dialog close. */
    private static final long CLOSE_WAIT_MS = 1500;
    /** How long a press waits for a call that was parked behind the dialog to finish. */
    private static final long RESUME_WAIT_MS = 10_000;

    @Override
    public String toolName() {
        return "eclipseDialog";
    }

    @Override
    public String description() {
        return "See and answer the dialogs that are open — a confirmation, an error, a wizard, a "
                + "file chooser — in this Eclipse and in other Eclipse instances, such as one "
                + "started with 'runAs'. action='list' (default) shows each dialog's id, title, "
                + "text, buttons, radio/checkbox 'options', the rows of its 'tables' (tables, trees "
                + "and lists, each row numbered) and its input 'fields'. action='press' clicks the "
                + "button whose label is 'button', exactly as listed; name the dialog with 'dialog' "
                + "when more than one is open. action='select' picks a row ('row', in table "
                + "'table', default 0) or ticks an option ('option', its label; 'checked' false to "
                + "untick a checkbox). action='type' puts 'text' into input field 'field' (default "
                + "0), or picks that item of a combo. select and type only fill the dialog in; a "
                + "press of its OK/Finish button is still needed. A transport request dialog (ADT's "
                + "'Select a Transport Request') is the user's: it is listed with 'forUserOnly' and "
                + "cannot be filled in or pressed; the ADT tool that raised it waits for the user. "
                + "Always list first and read the dialog before pressing: a press is the same as "
                + "the user's click and cannot be undone. When another tool reports "
                + "'dialogOpened', its call is still waiting on that dialog; after the press its "
                + "result is returned here under 'finishedCalls'. If that dialog was answered "
                + "some other way, pass the 'call' value that tool reported to collect the "
                + "result. A dialog listed with 'forUserOnly' is the user's to answer.";
    }

    /**
     * Marks a dialog as one that asks the person at the keyboard, such as this plug-in's own
     * confirmations: it is still listed, and no press reaches it. Call between
     * {@code create()} and {@code open()}.
     */
    public static void forUserOnly(Shell shell) {
        Dialogs.markForUserOnly(shell);
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "list (default), press, select or type");
        JsonArray actions = new JsonArray();
        actions.add("list");
        actions.add("press");
        actions.add("select");
        actions.add("type");
        action.add("enum", actions);
        props.add("action", action);

        props.add("table", ToolSupport.prop("integer",
                "For 'select': the 'table' number of the table, tree or list, as listed. Default 0."));
        props.add("row", ToolSupport.prop("integer",
                "For 'select': the 'row' number of the row to select, as listed."));
        props.add("option", ToolSupport.prop("string",
                "For 'select': the label of a radio button or checkbox to tick, as listed under 'options'."));
        props.add("checked", ToolSupport.prop("boolean",
                "For 'select' with 'option': false unticks a checkbox. Default true."));
        props.add("field", ToolSupport.prop("integer",
                "For 'type': the 'field' number of the input field or combo, as listed. Default 0."));
        props.add("text", ToolSupport.prop("string",
                "For 'type': the text to put into the field, or the item of a combo to pick."));

        JsonObject button = new JsonObject();
        button.addProperty("type", "string");
        button.addProperty("description",
                "For 'press': the button's full label as shown by action='list', e.g. 'Cancel'.");
        props.add("button", button);

        JsonObject dialog = new JsonObject();
        dialog.addProperty("type", "string");
        dialog.addProperty("description",
                "For 'press': the dialog's id as shown by action='list'. Required when more than "
                        + "one dialog is open.");
        props.add("dialog", dialog);

        JsonObject call = new JsonObject();
        call.addProperty("type", "string");
        call.addProperty("description",
                "The 'call' value another tool reported with 'dialogOpened': returns that call's "
                        + "result once it has finished, whoever answered the dialog.");
        props.add("call", call);

        schema.add("properties", props);
        return schema;   // no args means "list"
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        try {
            String action = str(params, "action");
            String button = str(params, "button");
            String dialog = str(params, "dialog");
            String call = str(params, "call");
            ClaudeCodeView.debug("[eclipseDialog] action=" + action + " dialog=" + dialog
                    + " button=" + button + " call=" + call);

            if (action == null || "list".equalsIgnoreCase(action)) return list(call);
            if ("press".equalsIgnoreCase(action)) {
                if (button == null) return McpToolResult.error("'button' is required for action='press'.");
                return press(dialog, button, call);
            }
            if ("select".equalsIgnoreCase(action) || "type".equalsIgnoreCase(action)) {
                return fillIn(action.toLowerCase(java.util.Locale.ROOT), dialog, params);
            }
            return McpToolResult.error("Unknown action: " + action + ". Use list, press, select or type.");
        } catch (Exception e) {
            ClaudeCodeView.debug("[eclipseDialog] failed: " + e);
            return McpToolResult.error("eclipseDialog failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────────────────

    private McpToolResult list(String call) {
        JsonObject out = new JsonObject();
        JsonArray dialogs = openDialogs(out);
        out.addProperty("count", dialogs.size());
        out.add("dialogs", dialogs);
        addParkedCalls(out, null, call, 0);
        ClaudeCodeView.debug("[eclipseDialog] list → " + dialogs.size());
        return McpToolResult.success(out);
    }

    private McpToolResult press(String dialogId, String wanted, String call) {
        String id = dialogId;
        // What is open before the press is listed only when it is needed: to find the one
        // dialog there is, or to tell afterwards what a press with a call waiting behind it
        // brought up. Otherwise a press costs no listing beyond its own.
        JsonArray open = null;
        if (id == null) {
            open = openDialogs(new JsonObject());
            if (open.size() == 0) return McpToolResult.error("No dialog is open.");
            if (open.size() > 1) {
                return McpToolResult.error(open.size() + " dialogs are open. Name one with 'dialog': "
                        + open);
            }
            id = open.get(0).getAsJsonObject().get("id").getAsString();
        } else if (ToolDialogWatch.hasCallBehind(id)) {
            open = openDialogs(new JsonObject());
        }
        Pressed done = NativeDialogs.isNativeId(id) ? pressNative(id, wanted) : pressSwt(id, wanted);
        if (done.problem() != null) return McpToolResult.error(done.problem());
        return pressed(done, id, call, open == null ? null : ToolDialogWatch.idsOf(open));
    }

    /** A press that went through, or why it did not. */
    private record Pressed(String label, String title, boolean closed, String problem) {
        static Pressed failed(String problem) {
            return new Pressed(null, null, false, problem);
        }
    }

    /** What the UI thread decided about a press: the shell it went to, or why not. */
    private record Aimed(Shell shell, String title, String label, String problem) {
    }

    private Pressed pressSwt(String dialogId, String wanted) {
        Display display = Dialogs.display();
        if (display == null) return Pressed.failed("The workbench is not available.");

        Aimed aimed = Dialogs.onUi(() -> aim(display, dialogId, wanted), UI_ANSWER_MS);
        if (aimed == null) return Pressed.failed(UI_BUSY);
        if (aimed.problem() != null) return Pressed.failed(aimed.problem());

        Shell shell = aimed.shell();
        boolean closed = waitUntil(() -> Boolean.TRUE.equals(Dialogs.onUi(shell::isDisposed, UI_ANSWER_MS)));
        return new Pressed(aimed.label(), aimed.title(), closed, null);
    }

    /** Finds the dialog and its button and queues the click. UI thread only. */
    private static Aimed aim(Display display, String dialogId, String wanted) {
        Shell shell = null;
        for (Shell candidate : Dialogs.open(display)) {
            if (dialogId.equals(Dialogs.idOf(candidate))) shell = candidate;
        }
        if (shell == null) {
            return problem("No open dialog has id '" + dialogId + "'. Use action='list' to see "
                    + "the dialogs open now.");
        }
        // describe, not the marker alone: it also knows a prompt about trust when it reads one.
        if (Dialogs.describe(shell).has("forUserOnly")) {
            ClaudeCodeView.debug("[eclipseDialog] press refused: '" + shell.getText() + "' is for the user only");
            return problem("'" + shell.getText() + "' asks the user, and is left for them to answer.");
        }
        Shell over = Dialogs.blockedBy(shell);
        if (over != null) {
            return problem("'" + shell.getText() + "' is waiting on '" + over.getText()
                    + "', which is open over it. Answer that one first.");
        }

        List<Button> buttons = Dialogs.pushButtons(shell);
        List<String> labels = new ArrayList<>();
        for (Button candidate : buttons) labels.add(Dialogs.plain(candidate.getText()));
        int index = Dialogs.match(labels, wanted);
        if (index == -2) {
            return problem("More than one button in '" + shell.getText() + "' is labelled '"
                    + wanted + "'.");
        }
        if (index < 0) {
            return problem("'" + shell.getText() + "' has no button labelled '" + wanted
                    + "'. Its buttons: " + String.join(", ", labels));
        }
        Button button = buttons.get(index);
        if (!button.isEnabled()) {
            return problem("The '" + labels.get(index) + "' button in '" + shell.getText()
                    + "' is disabled.");
        }

        // Queued rather than run here: a button that opens a further dialog would otherwise
        // hold this call inside that dialog's event loop.
        display.asyncExec(() -> {
            if (!button.isDisposed() && button.isEnabled()) button.notifyListeners(SWT.Selection, new Event());
        });
        return new Aimed(shell, shell.getText(), labels.get(index), null);
    }

    private Pressed pressNative(String dialogId, String wanted) {
        // Looked up first: a prompt about trust is the user's in another Eclipse as in this one,
        // and the native core presses whatever it is told to.
        for (JsonElement listed : NativeDialogs.list(false).getAsJsonArray("dialogs")) {
            JsonObject dialog = listed.getAsJsonObject();
            if (dialogId.equals(dialog.get("id").getAsString()) && dialog.has("forUserOnly")) {
                String title = dialog.has("title") ? dialog.get("title").getAsString() : dialogId;
                ClaudeCodeView.debug("[eclipseDialog] press refused: '" + title + "' is for the user only");
                return Pressed.failed("'" + title + "' asks the user, and is left for them to answer.");
            }
        }
        JsonObject reply = NativeDialogs.press(dialogId, wanted);
        if (reply.has("error")) return Pressed.failed(reply.get("error").getAsString());
        boolean closed = waitUntil(() -> {
            for (JsonElement dialog : NativeDialogs.list(false).getAsJsonArray("dialogs")) {
                if (dialogId.equals(dialog.getAsJsonObject().get("id").getAsString())) return false;
            }
            return true;
        });
        return new Pressed(reply.get("pressed").getAsString(), reply.get("dialog").getAsString(), closed, null);
    }

    /**
     * The reply to a press that went through. {@code openBefore} is the ids of the dialogs
     * that were open before it, when that was looked at: what has come up since is the
     * pressed dialog's doing.
     */
    private McpToolResult pressed(Pressed pressed, String dialogId, String call, Set<String> openBefore) {
        JsonObject out = new JsonObject();
        out.addProperty("pressed", pressed.label());
        out.addProperty("dialog", pressed.title());
        out.addProperty("closed", pressed.closed());

        JsonArray now = openDialogs(new JsonObject());
        if (openBefore != null) {
            Set<String> opened = ToolDialogWatch.idsOf(now);
            opened.removeAll(openBefore);
            ToolDialogWatch.follow(dialogId, opened);
        }

        addParkedCalls(out, dialogId, call, RESUME_WAIT_MS);
        if (now.size() > 0) out.add("dialogs", now);
        ClaudeCodeView.debug("[eclipseDialog] pressed '" + pressed.label() + "' in '" + pressed.title()
                + "' closed=" + pressed.closed());
        return McpToolResult.success(out);
    }

    // ── Filling a dialog in ─────────────────────────────────────────────────────────

    /** How long after a select or type the dialog is read again, for its buttons to follow. */
    private static final long SETTLE_MS = 300;

    /**
     * Selects a row or an option, or types into a field, of an SWT dialog, as the user's click
     * or typing would, and answers with the dialog as it reads afterwards.
     */
    private McpToolResult fillIn(String action, String dialogId, JsonObject params) {
        Display display = Dialogs.display();
        if (display == null) return McpToolResult.error("The workbench is not available.");

        String option = str(params, "option");
        String text = params.has("text") && params.get("text").isJsonPrimitive() ? params.get("text").getAsString() : null;
        int table = ToolSupport.integer(params, "table", 0, 0, Integer.MAX_VALUE);
        int field = ToolSupport.integer(params, "field", 0, 0, Integer.MAX_VALUE);
        Integer row = params.has("row") && params.get("row").isJsonPrimitive() ? params.get("row").getAsInt() : null;
        boolean checked = ToolSupport.bool(params, "checked", true);
        if ("select".equals(action) && option == null && row == null) {
            return McpToolResult.error("'select' needs 'row' (with 'table') or 'option'.");
        }
        if ("type".equals(action) && text == null) return McpToolResult.error("'type' needs 'text'.");

        Object aimed = Dialogs.onUi(() -> {
            Object shell = dialogFor(display, dialogId);
            if (shell instanceof String problem) return problem;
            Shell s = (Shell) shell;
            Runnable change = "select".equals(action)
                    ? (option != null ? selectOption(s, option, checked) : selectRow(s, table, row))
                    : typeInto(s, field, text);
            if (change instanceof Refused refused) return refused.reason();
            // Queued, as a press is: a listener that opens a further dialog would otherwise
            // hold this call inside that dialog's event loop.
            display.asyncExec(change);
            return s;
        }, UI_ANSWER_MS);
        if (aimed == null) return McpToolResult.error(UI_BUSY);
        if (aimed instanceof String problem) return McpToolResult.error(problem);
        Shell shell = (Shell) aimed;

        try {
            Thread.sleep(SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        JsonObject now = Dialogs.onUi(() -> shell.isDisposed() ? new JsonObject() : Dialogs.describe(shell), UI_ANSWER_MS);
        JsonObject out = new JsonObject();
        out.addProperty("done", action);
        if (now == null) {
            out.addProperty("note", UI_BUSY);
        } else if (now.size() == 0) {
            out.addProperty("closed", true);
        } else {
            out.add("dialog", now);
        }
        ClaudeCodeView.debug("[eclipseDialog] " + action + " in " + (dialogId == null ? "the dialog" : dialogId));
        return McpToolResult.success(out);
    }

    /** A change that is not made, and why. */
    private record Refused(String reason) implements Runnable {
        @Override
        public void run() {
        }
    }

    /**
     * The SWT dialog to fill in: the one named, or the only one open that is neither a
     * progress dialog nor the user's to answer. A String when there is none to use. UI thread only.
     */
    private static Object dialogFor(Display display, String dialogId) {
        List<Shell> candidates = new ArrayList<>();
        for (Shell shell : Dialogs.open(display)) {
            if (dialogId != null ? dialogId.equals(Dialogs.idOf(shell)) : !Dialogs.isProgress(shell)) {
                candidates.add(shell);
            }
        }
        if (candidates.isEmpty()) {
            return dialogId != null
                    ? "No open Eclipse dialog has id '" + dialogId + "'. Use action='list'."
                    : "No Eclipse dialog is open.";
        }
        if (candidates.size() > 1) {
            List<String> ids = new ArrayList<>();
            for (Shell shell : candidates) ids.add(Dialogs.idOf(shell) + " '" + shell.getText() + "'");
            return candidates.size() + " dialogs are open. Name one with 'dialog': " + String.join(", ", ids);
        }
        Shell shell = candidates.get(0);
        if (Dialogs.describe(shell).has("forUserOnly")) {
            return "'" + shell.getText() + "' asks the user, and is left for them to answer.";
        }
        Shell over = Dialogs.blockedBy(shell);
        if (over != null) {
            return "'" + shell.getText() + "' is waiting on '" + over.getText() + "', which is open over it.";
        }
        return shell;
    }

    private static Runnable selectRow(Shell shell, int tableIndex, int row) {
        List<org.eclipse.swt.widgets.Control> choices = Dialogs.choices(shell);
        if (tableIndex >= choices.size()) {
            return new Refused("'" + shell.getText() + "' has " + choices.size() + " table(s); there is no table " + tableIndex + ".");
        }
        org.eclipse.swt.widgets.Control control = choices.get(tableIndex);
        if (!control.getEnabled()) return new Refused("Table " + tableIndex + " of '" + shell.getText() + "' is disabled.");
        if (control instanceof org.eclipse.swt.widgets.List list) {
            if (row < 0 || row >= list.getItemCount()) return new Refused("List " + tableIndex + " has no row " + row + ".");
            return () -> {
                if (list.isDisposed()) return;
                list.setSelection(row);
                list.notifyListeners(SWT.Selection, new Event());
            };
        }
        List<org.eclipse.swt.widgets.Item> rows = Dialogs.rowsOf(control);
        if (row < 0 || row >= rows.size()) return new Refused("Table " + tableIndex + " has no row " + row + ".");
        org.eclipse.swt.widgets.Item item = rows.get(row);
        return () -> {
            if (control.isDisposed() || item.isDisposed()) return;
            if (control instanceof org.eclipse.swt.widgets.Table t) {
                t.setSelection((org.eclipse.swt.widgets.TableItem) item);
                t.showItem((org.eclipse.swt.widgets.TableItem) item);
            } else if (control instanceof org.eclipse.swt.widgets.Tree t) {
                t.setSelection((org.eclipse.swt.widgets.TreeItem) item);
                t.showItem((org.eclipse.swt.widgets.TreeItem) item);
            }
            // The event a click sends, which is what JFace viewers listen for.
            Event event = new Event();
            event.item = item;
            control.notifyListeners(SWT.Selection, event);
        };
    }

    private static Runnable selectOption(Shell shell, String label, boolean checked) {
        List<Button> buttons = Dialogs.optionButtons(shell);
        List<String> labels = new ArrayList<>();
        for (Button b : buttons) labels.add(Dialogs.plain(b.getText()));
        int index = Dialogs.match(labels, label);
        if (index == -2) return new Refused("More than one option in '" + shell.getText() + "' is labelled '" + label + "'.");
        if (index < 0) {
            return new Refused("'" + shell.getText() + "' has no option labelled '" + label + "'. Its options: "
                    + String.join(", ", labels));
        }
        Button button = buttons.get(index);
        if (!button.isEnabled()) return new Refused("The option '" + labels.get(index) + "' is disabled.");
        boolean radio = (button.getStyle() & SWT.RADIO) != 0;
        return () -> {
            if (button.isDisposed()) return;
            if (radio) {
                // A click on a radio button deselects its siblings; setSelection does not.
                for (org.eclipse.swt.widgets.Control sibling : button.getParent().getChildren()) {
                    if (sibling != button && sibling instanceof Button other
                            && (other.getStyle() & SWT.RADIO) != 0 && other.getSelection()) {
                        other.setSelection(false);
                        other.notifyListeners(SWT.Selection, new Event());
                    }
                }
                button.setSelection(true);
            } else {
                button.setSelection(checked);
            }
            button.notifyListeners(SWT.Selection, new Event());
        };
    }

    private static Runnable typeInto(Shell shell, int fieldIndex, String text) {
        List<org.eclipse.swt.widgets.Control> fields = Dialogs.fields(shell);
        if (fieldIndex >= fields.size()) {
            return new Refused("'" + shell.getText() + "' has " + fields.size() + " input field(s); there is no field " + fieldIndex + ".");
        }
        org.eclipse.swt.widgets.Control control = fields.get(fieldIndex);
        if (!control.getEnabled()) {
            return new Refused("Field " + fieldIndex + " of '" + shell.getText() + "' is disabled; an option may enable it.");
        }
        if (control instanceof org.eclipse.swt.widgets.Combo combo && (combo.getStyle() & SWT.READ_ONLY) != 0) {
            int item = combo.indexOf(text);
            if (item < 0) {
                return new Refused("Field " + fieldIndex + " offers only: " + String.join(", ", combo.getItems()));
            }
            return () -> {
                if (combo.isDisposed()) return;
                combo.select(item);
                combo.notifyListeners(SWT.Selection, new Event());
            };
        }
        return () -> {
            if (control.isDisposed()) return;
            // setText sends the Modify event a typed change sends.
            if (control instanceof org.eclipse.swt.widgets.Text field) field.setText(text);
            else if (control instanceof org.eclipse.swt.widgets.Combo combo) combo.setText(text);
        };
    }

    // ── Permission requests while a dialog is open ─────────────────────────────────

    /**
     * Whether a call only reads and needs no permission: {@code eclipseDialog} listing the
     * dialogs. Such a call is the way to see a dialog that blocks the workbench, so asking for
     * permission to make it would ask where the user cannot answer.
     */
    public static boolean isReadOnlyCall(String toolName, JsonElement input) {
        if (toolName == null || !(toolName.equals("eclipseDialog") || toolName.endsWith("__eclipseDialog"))) {
            return false;
        }
        if (input == null || !input.isJsonObject()) return true;
        JsonObject in = input.getAsJsonObject();
        if (!in.has("action") || in.get("action").isJsonNull()) return true;
        return "list".equalsIgnoreCase(in.get("action").getAsString().trim());
    }

    /**
     * Asks for permission in a dialog of its own when a modal dialog is open, since that
     * dialog blocks the workbench window and with it the permission card in the Claude Code
     * view. The question is opened over the dialog in front, and is the user's to answer
     * ({@link #forUserOnly}): no tool can press it. Returns "allow" or "deny", or null when no
     * modal dialog is open and the card is to be used.
     */
    public static String approveOverModalDialog(String toolName, String detail) {
        Display display = Dialogs.display();
        if (display == null) return null;
        String[] decision = { null };
        Runnable ask = () -> {
            Shell front = Dialogs.frontModal(display);
            if (front == null) return;
            String message = "Claude wants to run " + toolName
                    + (detail == null || detail.isBlank() ? "" : ":\n\n" + detail)
                    + "\n\nThis is asked here because a dialog is open, which blocks the Claude Code view.";
            org.eclipse.jface.dialogs.MessageDialog ask2 = new org.eclipse.jface.dialogs.MessageDialog(front,
                    "Claude Code ADT – Permission", null, message,
                    org.eclipse.jface.dialogs.MessageDialog.QUESTION, 0, "Allow", "Deny");
            ask2.create();
            forUserOnly(ask2.getShell());
            decision[0] = ask2.open() == 0 ? "allow" : "deny";
        };
        if (Display.getCurrent() == display) ask.run();
        else display.syncExec(ask);
        return decision[0];
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────

    private static final String UI_BUSY = "Eclipse's UI thread did not answer within "
            + (UI_ANSWER_MS / 1000) + " s — it is busy. Try again.";

    /**
     * Every dialog that is open: this Eclipse's SWT ones, then the native core's. Why either
     * half is missing, when it is, goes into {@code notes}.
     */
    private static JsonArray openDialogs(JsonObject notes) {
        Display display = Dialogs.display();
        // Where the native core reports this Eclipse's own shells as well (Windows), these
        // are the ids it does so under, so that each is listed once.
        java.util.concurrent.atomic.AtomicReference<Set<String>> ownShells =
                new java.util.concurrent.atomic.AtomicReference<>(Set.of());
        JsonArray swt = display == null ? null : Dialogs.onUi(() -> {
            List<Shell> shells = Dialogs.open(display);
            ownShells.set(Dialogs.nativeIdsOf(shells));
            return Dialogs.describeAll(shells);
        }, UI_ANSWER_MS);
        if (swt == null) {
            notes.addProperty("eclipseDialogsUnavailable", UI_BUSY);
            swt = new JsonArray();
        }
        JsonObject natives = NativeDialogs.list(false);
        if (natives.has("unavailable")) {
            notes.addProperty("otherDialogsUnavailable", natives.get("unavailable").getAsString());
        }
        JsonArray all = new JsonArray();
        all.addAll(swt);
        all.addAll(NativeDialogs.withoutThoseIn(swt, natives.getAsJsonArray("dialogs"), ownShells.get()));
        return all;
    }

    private interface Check {
        boolean holds();
    }

    private static boolean waitUntil(Check closed) {
        long deadline = System.currentTimeMillis() + CLOSE_WAIT_MS;
        while (true) {
            if (closed.holds()) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static Aimed problem(String message) {
        return new Aimed(null, null, null, message);
    }

    /**
     * Adds the results of the calls that were parked behind {@code dialogId} or are named by
     * {@code call}, waiting up to {@code waitMs} for one of those. Other parked calls are
     * named, not answered for: they may be another conversation's.
     */
    private static void addParkedCalls(JsonObject out, String dialogId, String call, long waitMs) {
        JsonArray finished = ToolDialogWatch.takeFinished(dialogId, call, waitMs);
        if (finished.size() > 0) out.add("finishedCalls", finished);
        JsonArray waiting = ToolDialogWatch.stillParked();
        if (waiting.size() > 0) out.add("callsStillWaiting", waiting);
        // Named so a finished call is not simply gone from view; its result still needs
        // the 'call' value its own caller was given.
        JsonArray uncollected = ToolDialogWatch.finishedUncollected();
        if (uncollected.size() > 0) out.add("callsFinishedUncollected", uncollected);
    }

    private static String str(JsonObject params, String key) {
        if (!params.has(key) || params.get(key).isJsonNull()) return null;
        String v = params.get(key).getAsString().trim();
        return v.isEmpty() ? null : v;
    }
}
