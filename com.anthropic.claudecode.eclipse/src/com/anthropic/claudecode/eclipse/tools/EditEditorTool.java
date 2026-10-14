package com.anthropic.claudecode.eclipse.tools;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextUtilities;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.anthropic.claudecode.eclipse.tools.ToolSupport.Refusal;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Changes the text of an editor the way typing into it would: the editor is asked for leave
 * first (in ADT that locks the object, and may ask for a transport request), the change is one
 * step on the editor's undo stack, and it is left unsaved unless the call asks for a save. This
 * is what reaches the sources a file-system edit cannot, such as ABAP sources on
 * {@code semanticfs:}, and what keeps an editor and its unsaved changes in step everywhere else.
 */
public class EditEditorTool implements McpTool {

    /** One replacement, as the Edit tool takes it. */
    private record Edit(String oldString, String newString, boolean replaceAll) {
    }

    @Override
    public String toolName() {
        return "editEditor";
    }

    @Override
    public String description() {
        return "Change the text of an editor in Eclipse, the way typing into it would. Use it for files "
                + "the file system cannot reach, above all ABAP sources opened in ADT (paths starting with "
                + "semanticfs:); there the editor locks the object first, which may ask the user for a "
                + "transport request: the call then waits until the user has answered that dialog "
                + "(choosing or creating the request is theirs; do not try to answer it) and reports "
                + "whether the change was made. Give 'edits' (each an exact old_string, unique in the text unless "
                + "replace_all, and its new_string; applied in order, all or none) or 'new_content' to "
                + "replace the whole text. Read the text with readEditor first: old_string must match it "
                + "character for character. The change is one undo step and stays unsaved unless 'save' "
                + "is true; saving an ABAP source stores an inactive version, which adtCommand activates. "
                + "Without file_path the active editor is changed; a file that is not open is opened.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject props = new JsonObject();
        props.add("file_path", EditorTargets.fileProp());
        props.add("page", EditorTargets.pageProp());

        JsonObject edit = new JsonObject();
        edit.addProperty("type", "object");
        JsonObject editProps = new JsonObject();
        editProps.add("old_string", ToolSupport.prop("string", "The exact text to replace."));
        editProps.add("new_string", ToolSupport.prop("string", "The text to put in its place."));
        editProps.add("replace_all", ToolSupport.prop("boolean",
                "Replace every occurrence instead of requiring exactly one. Default false."));
        edit.add("properties", editProps);
        JsonArray editRequired = new JsonArray();
        editRequired.add("old_string");
        editRequired.add("new_string");
        edit.add("required", editRequired);
        JsonObject edits = ToolSupport.prop("array", "The replacements, applied one after another.");
        edits.add("items", edit);
        props.add("edits", edits);

        props.add("old_string", ToolSupport.prop("string", "A single replacement's old text, instead of 'edits'."));
        props.add("new_string", ToolSupport.prop("string", "A single replacement's new text, instead of 'edits'."));
        props.add("replace_all", ToolSupport.prop("boolean", "For old_string: replace every occurrence."));
        props.add("new_content", ToolSupport.prop("string",
                "The whole new text of the editor, instead of edits."));
        props.add("save", ToolSupport.prop("boolean",
                "Save the editor after the change (for ABAP: store the inactive version on the server). "
                + "Default false: the change is left unsaved for the user to review."));
        return ToolSupport.schema(props);
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        String path = ToolSupport.str(params, "file_path");
        if (path == null) path = ToolSupport.str(params, "filePath");
        String page = ToolSupport.str(params, "page");
        boolean save = ToolSupport.bool(params, "save", false);
        String newContent = raw(params, "new_content");
        List<Edit> edits;
        try {
            edits = edits(params);
        } catch (Refusal r) {
            return McpToolResult.error(r.getMessage());
        }
        if (newContent == null && edits.isEmpty()) {
            return McpToolResult.error("Give 'edits', 'old_string' and 'new_string', or 'new_content'.");
        }
        if (newContent != null && !edits.isEmpty()) {
            return McpToolResult.error("Give either edits or new_content, not both.");
        }
        String name = path;

        return UiHelper.syncCall(() -> {
            try {
                EditorTargets.Target target = EditorTargets.find(name, page, true);
                IDocument doc = target.document();
                if (doc == null) return McpToolResult.error("The editor " + target.label() + " has no document.");

                // Every replacement is checked before the editor is asked for leave, so a call that
                // cannot be carried out does not lock an ABAP object for nothing.
                String before = doc.get();
                String after = apply(before, doc, edits, newContent);
                if (after.equals(before)) {
                    JsonObject out = new JsonObject();
                    target.describe(out);
                    out.addProperty("changed", false);
                    out.addProperty("message", "The text already reads that way.");
                    return McpToolResult.success(out);
                }

                if (!target.validateForEdit()) {
                    return McpToolResult.error("The editor " + target.label() + " cannot be changed: it is "
                            + "read-only, the object is locked by someone else, or the lock was cancelled.");
                }
                // Taking the lock can load the server's newer text into the editor.
                String current = doc.get();
                if (!current.equals(before)) {
                    before = current;
                    after = apply(before, doc, edits, newContent);
                }

                int prefix = commonPrefix(before, after);
                int suffix = commonSuffix(before, after, prefix);
                String inserted = after.substring(prefix, after.length() - suffix);
                doc.replace(prefix, before.length() - prefix - suffix, inserted);
                target.textEditor().selectAndReveal(prefix, inserted.length());

                boolean saved = false;
                if (save) {
                    target.editor().doSave(new NullProgressMonitor());
                    saved = !target.editor().isDirty();
                }

                JsonObject out = new JsonObject();
                target.describe(out);
                out.addProperty("changed", true);
                int firstLine = doc.getLineOfOffset(prefix) + 1;
                int lastLine = doc.getLineOfOffset(prefix + inserted.length()) + 1;
                out.addProperty("startLine", firstLine);
                out.addProperty("endLine", lastLine);
                out.addProperty("totalLines", ReadEditorTool.lineCount(doc));
                out.addProperty("dirty", target.dirty());
                if (save) {
                    out.addProperty("saved", saved);
                    if (!saved) out.addProperty("message", "The editor still has unsaved changes after saving; check the editor and the Error Log.");
                }
                out.addProperty("snippet", snippet(doc, firstLine, lastLine));
                return McpToolResult.success(out);
            } catch (Refusal r) {
                return McpToolResult.error(r.getMessage());
            } catch (Exception e) {
                return McpToolResult.error("Failed to edit: " + e.getMessage());
            }
        });
    }

    /** The text after every replacement, or a refusal naming the one that does not fit. */
    private static String apply(String text, IDocument doc, List<Edit> edits, String newContent) throws Refusal {
        String delimiter = TextUtilities.getDefaultLineDelimiter(doc);
        if (newContent != null) return toDelimiter(newContent, delimiter);
        String result = text;
        for (int i = 0; i < edits.size(); i++) {
            Edit edit = edits.get(i);
            String label = edits.size() > 1 ? "Edit " + (i + 1) + ": " : "";
            String oldString = edit.oldString();
            int count = count(result, oldString);
            if (count == 0 && !"\n".equals(delimiter)) {
                // The model writes \n; the editor may hold \r\n.
                String converted = toDelimiter(oldString, delimiter);
                if (!converted.equals(oldString)) {
                    oldString = converted;
                    count = count(result, oldString);
                }
            }
            if (count == 0) {
                throw new Refusal(label + "old_string was not found in the editor's text. Read it again with "
                        + "readEditor and copy the text exactly, indentation and case included.");
            }
            if (count > 1 && !edit.replaceAll()) {
                throw new Refusal(label + "old_string occurs " + count + " times. Include more of the "
                        + "surrounding text to make it unique, or set replace_all.");
            }
            String newString = toDelimiter(edit.newString(), delimiter);
            if (edit.replaceAll()) {
                result = result.replace(oldString, newString);
            } else {
                int at = result.indexOf(oldString);
                result = result.substring(0, at) + newString + result.substring(at + oldString.length());
            }
        }
        return result;
    }

    private static List<Edit> edits(JsonObject params) throws Refusal {
        List<Edit> edits = new ArrayList<>();
        if (params.has("edits") && params.get("edits").isJsonArray()) {
            int i = 0;
            for (JsonElement e : params.getAsJsonArray("edits")) {
                i++;
                if (!e.isJsonObject()) throw new Refusal("Edit " + i + " is not an object.");
                edits.add(edit(e.getAsJsonObject(), "Edit " + i + ": "));
            }
        }
        if (params.has("old_string")) {
            if (!edits.isEmpty()) throw new Refusal("Give either edits or old_string, not both.");
            edits.add(edit(params, ""));
        }
        return edits;
    }

    private static Edit edit(JsonObject o, String label) throws Refusal {
        String oldString = raw(o, "old_string");
        String newString = raw(o, "new_string");
        if (oldString == null || oldString.isEmpty()) throw new Refusal(label + "old_string is missing or empty.");
        if (newString == null) throw new Refusal(label + "new_string is missing.");
        if (oldString.equals(newString)) throw new Refusal(label + "old_string and new_string are the same.");
        return new Edit(oldString, newString, ToolSupport.bool(o, "replace_all", false));
    }

    /** A string parameter exactly as sent: unlike ToolSupport.str, not trimmed. */
    private static String raw(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) return null;
        return o.get(key).getAsString();
    }

    private static String toDelimiter(String s, String delimiter) {
        if ("\n".equals(delimiter)) return s;
        return s.replace("\r\n", "\n").replace("\n", delimiter);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) n++;
        return n;
    }

    private static int commonPrefix(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) i++;
        // never split a \r\n pair
        if (i > 0 && i < a.length() && a.charAt(i - 1) == '\r' && a.charAt(i) == '\n') i--;
        return i;
    }

    private static int commonSuffix(String a, String b, int prefix) {
        int max = Math.min(a.length(), b.length()) - prefix;
        int i = 0;
        while (i < max && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) i++;
        return i;
    }

    /** The changed lines with three lines either side, numbered as readEditor numbers them. */
    private static String snippet(IDocument doc, int firstLine, int lastLine) throws BadLocationException {
        int lines = doc.getNumberOfLines();
        int from = Math.max(1, firstLine - 3);
        int to = Math.min(lines, Math.min(lastLine + 3, from + 60));
        StringBuilder sb = new StringBuilder();
        for (int line = from; line <= to; line++) {
            int offset = doc.getLineOffset(line - 1);
            int length = doc.getLineLength(line - 1);
            String text = doc.get(offset, length);
            sb.append(line).append('\t').append(text.replace("\r", ""));
            if (!text.endsWith("\n")) sb.append('\n');
        }
        return sb.toString();
    }
}
