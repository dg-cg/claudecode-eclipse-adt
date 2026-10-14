package com.anthropic.claudecode.eclipse.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Thin Java wrapper over the Rust lock-file logic.
 *
 * Java is still responsible for querying the Eclipse workspace to collect
 * project paths; Rust handles the actual file I/O.
 */
public class LockFileManager {

    public void writeLockFile(int port, String authToken) {
        try {
            String workspaceRoot = ResourcesPlugin.getWorkspace().getRoot()
                    .getLocation().toOSString();

            // Collect open project paths (same as original implementation).
            JsonArray projectPaths = new JsonArray();
            projectPaths.add(workspaceRoot);
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.isOpen() && project.getLocation() != null) {
                    projectPaths.add(project.getLocation().toOSString());
                }
            }

            NativeCore.lockFileWrite(port, authToken, workspaceRoot, projectPaths.toString());
            renameIde(port);
            Activator.log("Lock file written (Rust) for port " + port
                    + " workspace=" + workspaceRoot
                    + " projects=" + projectPaths.size());
        } catch (Exception e) {
            Activator.logError("Failed to write lock file", e);
        }
    }

    public void removeLockFile() {
        NativeCore.lockFileRemove();
        Activator.log("Lock file removed (Rust)");
    }

    /**
     * The native core names every IDE it announces "Eclipse". Claude Code ADT runs next
     * to the original plug-in, so its lock file is given {@link Constants#IDE_NAME}
     * instead, for /ide to tell the two apart. The file is found where the core puts it:
     * {@code $CLAUDE_CONFIG_DIR/ide}, else {@code ~/.claude/ide}.
     */
    private static void renameIde(int port) {
        try {
            String configDir = System.getenv("CLAUDE_CONFIG_DIR");
            Path dir = configDir != null && !configDir.isBlank()
                    ? Paths.get(configDir, "ide")
                    : Paths.get(System.getProperty("user.home"), ".claude", "ide");
            Path lock = dir.resolve(port + ".lock");
            if (!Files.isRegularFile(lock)) return;
            JsonObject json = JsonParser.parseString(Files.readString(lock, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            json.addProperty("ideName", Constants.IDE_NAME);
            Files.writeString(lock, json.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Activator.logError("Could not set the IDE name in the lock file", e);
        }
    }
}
