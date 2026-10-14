package com.anthropic.claudecode.eclipse.tools;

import java.io.InputStream;
import java.nio.charset.Charset;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.claudecode.eclipse.editor.EditorParts;
import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.anthropic.claudecode.eclipse.tools.ToolSupport.Refusal;
import com.google.gson.JsonObject;

/**
 * Reads the text an editor holds, unsaved changes included. Made for the sources that cannot
 * be read from disk, such as ABAP sources in ADT, which live on the {@code semanticfs:} file
 * system; works for any text editor.
 */
public class ReadEditorTool implements McpTool {

    private static final int DEFAULT_MAX_CHARS = 200_000;

    @Override
    public String toolName() {
        return "readEditor";
    }

    @Override
    public String description() {
        return "Read the text of an editor in Eclipse exactly as the editor holds it, unsaved changes "
                + "included. Use it for files the file system cannot reach, such as ABAP sources opened "
                + "in ADT (paths starting with semanticfs:), and before editEditor, whose old_string "
                + "must match this text. Without file_path it reads the active editor; a file that is "
                + "not open is read from the workspace without opening it. Reports the file's path, "
                + "the editor's title, its tabs (ABAP classes), whether it has unsaved changes, and "
                + "whether it can be edited right now (an ADT editor becomes editable when editEditor "
                + "locks the object).";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject props = new JsonObject();
        props.add("file_path", EditorTargets.fileProp());
        props.add("page", EditorTargets.pageProp());
        props.add("start_line", ToolSupport.prop("integer", "First line to return, 1-based. Default 1."));
        props.add("end_line", ToolSupport.prop("integer", "Last line to return, inclusive. Default the last line."));
        props.add("line_numbers", ToolSupport.prop("boolean",
                "Prefix each line with its number and a tab, as the Read tool does. Default false: "
                + "the text exactly as it is."));
        props.add("max_chars", ToolSupport.prop("integer",
                "Most characters of text to return (default " + DEFAULT_MAX_CHARS + "). Longer text is cut "
                + "and the result says so; read the rest with start_line."));
        return ToolSupport.schema(props);
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        String path = ToolSupport.str(params, "file_path");
        if (path == null) path = ToolSupport.str(params, "filePath");
        String page = ToolSupport.str(params, "page");
        int startLine = ToolSupport.integer(params, "start_line", 1, 1, Integer.MAX_VALUE);
        int endLine = ToolSupport.integer(params, "end_line", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);
        boolean numbered = ToolSupport.bool(params, "line_numbers", false);
        int maxChars = ToolSupport.integer(params, "max_chars", DEFAULT_MAX_CHARS, 1, 2_000_000);
        String name = path;

        // The editor's own text when one has it open; the editor is found on the UI thread.
        Object found = UiHelper.syncCall(() -> {
            try {
                IWorkbenchPage wp = UiHelper.getActivePage();
                if (wp == null) return McpToolResult.error("No active workbench page");
                if (name != null && EditorTargets.findOpen(wp, name) == null) return null;
                EditorTargets.Target target = EditorTargets.find(name, page, false);
                IDocument doc = target.document();
                if (doc == null) return McpToolResult.error("The editor " + target.label() + " has no document.");
                JsonObject out = new JsonObject();
                target.describe(out);
                out.addProperty("source", "editor");
                out.addProperty("dirty", target.dirty());
                out.addProperty("editable", target.modifiable());
                return new Object[] { out, doc.get() };
            } catch (Refusal r) {
                return McpToolResult.error(r.getMessage());
            } catch (Exception e) {
                return McpToolResult.error("Failed to read the editor: " + e.getMessage());
            }
        });

        try {
            JsonObject out;
            String text;
            if (found instanceof McpToolResult result) return result;
            if (found instanceof Object[] pair) {
                out = (JsonObject) pair[0];
                text = (String) pair[1];
            } else {
                // Not open in an editor: read the workspace file (for ADT, from the server).
                IFile file = EditorTargets.resolveFile(path);
                if (file == null) return McpToolResult.error("No open editor and no workspace file for " + path);
                if (page != null) return McpToolResult.error("A tab can only be chosen in an open editor; open " + path + " first.");
                text = contentsOf(file);
                out = new JsonObject();
                out.addProperty("filePath", EditorParts.pathOf(file));
                out.addProperty("label", file.getName());
                out.addProperty("source", "workspace");
                out.addProperty("dirty", false);
            }
            slice(out, text, startLine, endLine, numbered, maxChars);
            return McpToolResult.success(out);
        } catch (Exception e) {
            return McpToolResult.error("Failed to read " + (path != null ? path : "the editor") + ": " + e.getMessage());
        }
    }

    private static String contentsOf(IFile file) throws Exception {
        String charset;
        try {
            charset = file.getCharset();
        } catch (Exception e) {
            charset = "UTF-8";
        }
        try (InputStream in = file.getContents(true)) {
            return new String(in.readAllBytes(), Charset.forName(charset));
        }
    }

    /** The number of lines, where a final line break opens no line of its own. */
    static int lineCount(IDocument doc) {
        int total = doc.getNumberOfLines();
        String text = doc.get();
        return text.endsWith("\n") && total > 1 ? total - 1 : total;
    }

    /** Puts the asked-for lines of {@code text} into {@code out}, with the counts around them. */
    static void slice(JsonObject out, String text, int startLine, int endLine, boolean numbered, int maxChars)
            throws BadLocationException {
        IDocument doc = new Document(text);
        int total = lineCount(doc);
        int first = Math.min(startLine, Math.max(total, 1));
        int last = Math.min(endLine, total);
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        int lastShown = first - 1;
        for (int line = first; line <= last; line++) {
            int offset = doc.getLineOffset(line - 1);
            int length = doc.getLineLength(line - 1);
            String lineText = doc.get(offset, length);
            String piece = numbered ? line + "\t" + lineText : lineText;
            if (sb.length() + piece.length() > maxChars) {
                truncated = true;
                break;
            }
            sb.append(piece);
            lastShown = line;
        }
        out.addProperty("totalLines", total);
        out.addProperty("startLine", first);
        out.addProperty("endLine", lastShown);
        if (truncated) out.addProperty("truncated", true);
        out.addProperty("content", sb.toString());
    }
}
