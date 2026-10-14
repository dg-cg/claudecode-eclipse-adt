package com.anthropic.claudecode.eclipse.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.IHandlerService;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.anthropic.claudecode.eclipse.tools.ToolSupport.Refusal;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Runs an ABAP Development Tools command on an editor, as its key binding would: activate
 * (Ctrl+F3), check (Ctrl+F2) or format (Shift+F1). The commands are ADT's own, reached by id,
 * so this plug-in does not compile against ADT and the tool is only offered where ADT is
 * installed.
 */
public class AdtCommandTool implements McpTool {

    /** The bundle whose presence offers the tool. */
    public static final String ADT_ACTIVATION = "com.sap.adt.activation.ui";

    private static final Map<String, String> COMMANDS = new LinkedHashMap<>();
    static {
        COMMANDS.put("activate", "com.sap.adt.activation.ui.command.singleActivation");
        COMMANDS.put("checkSyntax", "com.sap.adt.activation.ui.command.check");
        COMMANDS.put("prettyPrint", "com.sap.adt.tools.abapsource.ui.prettyPrint");
    }

    @Override
    public String toolName() {
        return "adtCommand";
    }

    @Override
    public String description() {
        return "Run an ABAP Development Tools (ADT) command on an ABAP editor, as its key binding would: "
                + "'activate' (Ctrl+F3: saves and activates the object), 'checkSyntax' (Ctrl+F2) or "
                + "'prettyPrint' (Shift+F1: formats the source; the change is unsaved). The editor is "
                + "brought to the front first. After activate and checkSyntax the call waits for ADT to "
                + "finish (up to wait_seconds) and returns the file's problems; activate also reports "
                + "'activated', false with 'cancelled' when the editor kept its unsaved changes (a "
                + "dialog was cancelled). A dialog ADT opens, above all 'Select a Transport Request' "
                + "for an object that needs one, is the user's to answer: the call waits until they "
                + "have chosen or created the request (or cancelled), which can take minutes, and then "
                + "reports the outcome. Do not try to answer such a dialog; tell the user it is waiting "
                + "for them if they ask.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject props = new JsonObject();
        props.add("action", ToolSupport.choice("What to run.", List.copyOf(COMMANDS.keySet())));
        props.add("file_path", EditorTargets.fileProp());
        props.add("page", EditorTargets.pageProp());
        props.add("wait_seconds", ToolSupport.prop("integer",
                "How long to wait at most for an activation or check to finish before reporting the "
                + "problems (default 20, 0 to return at once)."));
        return ToolSupport.schema(props, "action");
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        String action = ToolSupport.str(params, "action");
        String commandId = action == null ? null : COMMANDS.get(action);
        if (commandId == null) {
            return McpToolResult.error("Unknown action " + action + "; one of " + String.join(", ", COMMANDS.keySet()));
        }
        String path = ToolSupport.str(params, "file_path");
        if (path == null) path = ToolSupport.str(params, "filePath");
        String page = ToolSupport.str(params, "page");
        boolean waits = !"prettyPrint".equals(action);
        int waitSeconds = waits ? ToolSupport.integer(params, "wait_seconds", 20, 0, 300) : 0;
        String name = path;

        long started = System.currentTimeMillis();
        JobWatch jobs = new JobWatch();
        Job.getJobManager().addJobChangeListener(jobs);
        try {
            return run(action, commandId, name, page, waits, waitSeconds, started, jobs);
        } finally {
            Job.getJobManager().removeJobChangeListener(jobs);
        }
    }

    private McpToolResult run(String action, String commandId, String name, String page, boolean waits,
            int waitSeconds, long started, JobWatch jobs) {
        Object ran = UiHelper.syncCall(() -> {
            try {
                EditorTargets.Target target = EditorTargets.find(name, page, true);
                IWorkbenchPage wp = target.editor().getSite().getPage();
                wp.activate(target.editor());
                if (target.page() != null) EditorTargets.showPage(target);
                IHandlerService handlers = wp.getWorkbenchWindow().getService(IHandlerService.class);
                handlers.executeCommand(commandId, null);
                return target;
            } catch (Refusal r) {
                return McpToolResult.error(r.getMessage());
            } catch (NotDefinedException e) {
                return McpToolResult.error("ADT does not define " + commandId + " in this Eclipse.");
            } catch (NotEnabledException | NotHandledException e) {
                return McpToolResult.error("ADT's " + action + " command is not available for this editor "
                        + "(is it an ABAP source editor?): " + e.getMessage());
            } catch (Exception e) {
                return McpToolResult.error("Failed to run " + action + ": " + e.getMessage());
            }
        });
        if (ran instanceof McpToolResult result) return result;
        if (!(ran instanceof EditorTargets.Target target)) return McpToolResult.error("The workbench is not available.");

        boolean waited = waits && waitSeconds > 0;
        List<String> stillRunning = waited ? jobs.awaitStarted(started + waitSeconds * 1000L) : List.of();
        boolean finished = waited && stillRunning.isEmpty();

        JsonObject out = new JsonObject();
        out.addProperty("action", action);
        target.describe(out);
        Boolean dirty = UiHelper.syncCall(target::dirty);
        out.addProperty("dirty", Boolean.TRUE.equals(dirty));
        if (waits) {
            out.addProperty("finished", finished);
            JsonArray problems = new JsonArray();
            try {
                IFile file = EditorTargets.resolveFile(target.filePath());
                if (file != null && file.exists()) {
                    GetDiagnosticsTool.addMarkers(
                            file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO), problems);
                }
            } catch (Exception ignored) {
                // no markers to report
            }
            GetDiagnosticsTool.collectEditorProblems(target.filePath(), problems);
            out.add("problems", problems);
            if ("activate".equals(action)) {
                // ADT says nothing of a cancelled save or activation; what tells is the editor:
                // an activation saves first, so unsaved changes left mean neither happened.
                boolean errors = false;
                for (var p : problems) {
                    if ("error".equals(p.getAsJsonObject().get("severity").getAsString())) errors = true;
                }
                boolean isDirty = Boolean.TRUE.equals(dirty);
                out.addProperty("activated", finished && !isDirty && !errors);
                if (isDirty) {
                    out.addProperty("cancelled", true);
                    out.addProperty("message", "Nothing was saved or activated: the editor still has unsaved "
                            + "changes. A dialog (such as the transport request) was probably cancelled.");
                } else if (errors) {
                    out.addProperty("message", "Not activated: ADT found errors (see problems).");
                }
            }
            if (waited && !finished) {
                JsonArray running = new JsonArray();
                stillRunning.forEach(running::add);
                out.add("stillRunning", running);
                out.addProperty("message", "ADT was still busy after " + waitSeconds
                        + " s; call getDiagnostics later for the outcome.");
            }
        }
        return McpToolResult.success(out);
    }

    /**
     * The jobs a command starts, so that the call waits for exactly those: ADT runs an
     * activation or a check as a job and reports the outcome as markers once it ends, while
     * other jobs in the workbench (some of them never ending) are none of the call's business.
     * Jobs are collected from just before the command until {@link #SETTLE_MS} after it
     * returned, since a command may schedule its job a moment late.
     */
    private static final class JobWatch extends JobChangeAdapter {

        private final Set<Job> started = ConcurrentHashMap.newKeySet();
        private final Set<Job> done = ConcurrentHashMap.newKeySet();
        private volatile boolean collecting = true;

        @Override
        public void scheduled(IJobChangeEvent event) {
            if (collecting) started.add(event.getJob());
        }

        @Override
        public void running(IJobChangeEvent event) {
            if (collecting) started.add(event.getJob());
        }

        @Override
        public void done(IJobChangeEvent event) {
            done.add(event.getJob());
        }

        /**
         * Waits until every job the command started has ended, or the deadline passes.
         * Returns the names of those still running then; empty when all ended.
         */
        List<String> awaitStarted(long deadline) {
            try {
                Thread.sleep(SETTLE_MS);
                collecting = false;
                while (true) {
                    List<String> pending = pending();
                    if (pending.isEmpty() || System.currentTimeMillis() >= deadline) {
                        if (pending.isEmpty()) Thread.sleep(MARKER_MS);
                        return pending;
                    }
                    Thread.sleep(200);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return pending();
            } finally {
                collecting = false;
            }
        }

        private List<String> pending() {
            List<String> names = new ArrayList<>();
            for (Job job : started) {
                if (done.contains(job)) continue;
                int state = job.getState();
                if (state == Job.RUNNING || state == Job.WAITING) names.add(job.getName());
            }
            return names;
        }
    }

    /** How long after the command returns a job it schedules is still taken for its own. */
    private static final long SETTLE_MS = 1000;
    /** How long after the jobs end their markers are given to arrive. */
    private static final long MARKER_MS = 300;
}
