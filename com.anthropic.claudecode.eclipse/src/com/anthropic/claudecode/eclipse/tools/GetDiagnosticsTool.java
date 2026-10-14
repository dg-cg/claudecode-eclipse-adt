package com.anthropic.claudecode.eclipse.tools;

import java.util.Iterator;
import java.util.Locale;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.MarkerAnnotation;

import com.anthropic.claudecode.eclipse.editor.EditorParts;
import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class GetDiagnosticsTool implements McpTool {

    @Override
    public String toolName() {
        return "getDiagnostics";
    }

    @Override
    public String description() {
        return "Get Eclipse workspace diagnostics (errors, warnings, info markers) for a specific file or all open projects. "
                + "A file can be named the way readEditor takes it (a semanticfs: URI for an ABAP source); "
                + "when it is open in an editor, the problems the editor shows without a marker (such as "
                + "ABAP syntax errors from the last check) are included.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Optional file path to filter diagnostics");
        props.add("file_path", filePath);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        try {
            String filePath = EditorUtils.getString(params, "file_path", "filePath");
            JsonArray diagnostics = new JsonArray();

            if (filePath != null) {
                collectFileMarkers(filePath, diagnostics);
                collectEditorProblems(filePath, diagnostics);
            } else {
                collectAllMarkers(diagnostics);
            }

            JsonObject result = new JsonObject();
            result.add("diagnostics", diagnostics);
            return McpToolResult.success(result);
        } catch (Exception e) {
            return McpToolResult.error("Failed to get diagnostics: " + e.getMessage());
        }
    }

    private void collectAllMarkers(JsonArray diagnostics) throws Exception {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen()) continue;
            IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            addMarkers(markers, diagnostics);
        }
    }

    private void collectFileMarkers(String filePath, JsonArray diagnostics) throws Exception {
        IResource resource = EditorTargets.resolveFile(filePath);
        if (resource == null) {
            var path = new org.eclipse.core.runtime.Path(filePath);
            resource = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(path);
        }
        if (resource != null && resource.exists()) {
            addMarkers(resource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO), diagnostics);
        }
    }

    /**
     * The problems an open editor shows that are not markers, which is where ADT puts the
     * findings of a syntax check. Annotations that stand for a marker are left out, since
     * the markers are already listed.
     */
    static void collectEditorProblems(String filePath, JsonArray diagnostics) {
        UiHelper.syncExec(() -> {
            try {
                var wp = UiHelper.getActivePage();
                if (wp == null || EditorTargets.findOpen(wp, filePath) == null) return;
                EditorTargets.Target target = EditorTargets.find(filePath, null, false);
                IDocumentProvider provider = target.textEditor().getDocumentProvider();
                if (provider == null) return;
                IAnnotationModel model = provider.getAnnotationModel(target.textEditor().getEditorInput());
                IDocument doc = target.document();
                if (model == null || doc == null) return;
                for (Iterator<Annotation> it = model.getAnnotationIterator(); it.hasNext();) {
                    Annotation a = it.next();
                    if (a instanceof MarkerAnnotation || a.isMarkedDeleted()) continue;
                    String type = a.getType() == null ? "" : a.getType().toLowerCase(Locale.ROOT);
                    String severity = type.contains("error") ? "error"
                            : type.contains("warning") ? "warning" : null;
                    if (severity == null || type.contains("spelling")) continue;
                    JsonObject diag = new JsonObject();
                    diag.addProperty("severity", severity);
                    diag.addProperty("message", a.getText() == null ? "" : a.getText());
                    Position p = model.getPosition(a);
                    if (p != null) {
                        try {
                            diag.addProperty("line", doc.getLineOfOffset(p.getOffset()) + 1);
                        } catch (BadLocationException ignored) {
                            // annotation outside the document
                        }
                        diag.addProperty("charStart", p.getOffset());
                        diag.addProperty("charEnd", p.getOffset() + p.getLength());
                    }
                    diag.addProperty("source", "editor:" + a.getType());
                    diag.addProperty("filePath", target.filePath());
                    diagnostics.add(diag);
                }
            } catch (Exception ignored) {
                // no editor problems to add
            }
        });
    }

    static void addMarkers(IMarker[] markers, JsonArray diagnostics) throws Exception {
        for (IMarker marker : markers) {
            JsonObject diag = new JsonObject();

            int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            diag.addProperty("severity", switch (severity) {
                case IMarker.SEVERITY_ERROR -> "error";
                case IMarker.SEVERITY_WARNING -> "warning";
                default -> "information";
            });

            diag.addProperty("message", marker.getAttribute(IMarker.MESSAGE, ""));
            diag.addProperty("line", marker.getAttribute(IMarker.LINE_NUMBER, 0));
            diag.addProperty("charStart", marker.getAttribute(IMarker.CHAR_START, 0));
            diag.addProperty("charEnd", marker.getAttribute(IMarker.CHAR_END, 0));
            diag.addProperty("source", marker.getType());

            IResource res = marker.getResource();
            if (res != null) {
                // A semanticfs: resource (ABAP) has no disk location; its URI names it instead.
                String path = EditorParts.pathOf(res);
                if (path != null) diag.addProperty("filePath", path);
            }

            diagnostics.add(diag);
        }
    }
}
