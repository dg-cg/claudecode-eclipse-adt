package com.anthropic.claudecode.eclipse.tools;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonObject;

public class SaveDocumentTool implements McpTool {

    @Override
    public String toolName() {
        return "saveDocument";
    }

    @Override
    public String description() {
        return "Save a file that is currently open in the Eclipse editor.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Absolute path of the file to save");
        props.add("file_path", filePath);
        schema.add("properties", props);

        var required = new com.google.gson.JsonArray();
        required.add("file_path");
        schema.add("required", required);

        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        String targetPath = EditorUtils.requireString(params, "file_path", "filePath");

        return UiHelper.syncCall(() -> {
            try {
                IWorkbenchPage page = UiHelper.getActivePage();
                if (page == null) {
                    return McpToolResult.error("No active workbench page");
                }

                // Found the way the editor tools find an editor, so a semanticfs: URI (ABAP),
                // which is no disk path, names one too.
                IEditorPart editor = EditorTargets.findOpen(page, targetPath);
                if (editor == null) {
                    return McpToolResult.error("File not open in editor: " + targetPath);
                }
                if (editor.isDirty()) {
                    editor.doSave(new NullProgressMonitor());
                }
                JsonObject result = new JsonObject();
                result.addProperty("success", !editor.isDirty());
                result.addProperty("filePath", targetPath);
                if (editor.isDirty()) {
                    result.addProperty("message", "The editor still has unsaved changes after saving.");
                }
                return McpToolResult.success(result);
            } catch (ToolSupport.Refusal r) {
                return McpToolResult.error(r.getMessage());
            } catch (Exception e) {
                return McpToolResult.error("Failed to save document: " + e.getMessage());
            }
        });
    }
}
