package com.anthropic.claudecode.eclipse.tools;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorExtension2;

import com.anthropic.claudecode.eclipse.editor.EditorParts;
import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.tools.ToolSupport.Refusal;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Finds the editor a call names, for the tools that read and change what an editor holds
 * rather than what is on disk. A name can be any of the ways a file is shown to the model:
 * the path {@code getOpenEditors} lists (for an ABAP source that is a {@code semanticfs:}
 * URI, which no disk path reaches), the workspace path ({@code /Project/folder/file}), a
 * disk path, or the editor's title ({@code ZCL_FOO}, {@code [E10] ZCL_FOO}). No name means
 * the active editor.
 */
public final class EditorTargets {

    private EditorTargets() {}

    /** A URI scheme of two letters or more, so that a drive letter is not taken for one. */
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]+:/*");
    private static final Pattern SLASH_DRIVE = Pattern.compile("^/([a-zA-Z]:/)");
    /** The system an ADT editor's title starts with: "[E10] ZCL_FOO". */
    private static final Pattern TITLE_PREFIX = Pattern.compile("^\\[[^\\]]*\\]\\s*");

    /** An editor found for a call, and the text editor in it that the call works on. */
    public record Target(IEditorPart editor, ITextEditor textEditor, String filePath, String label,
            String page, List<String> pages) {

        public IDocument document() {
            IDocumentProvider provider = textEditor.getDocumentProvider();
            return provider == null ? null : provider.getDocument(textEditor.getEditorInput());
        }

        /** Whether the editor would take a change now, before any lock is asked for. */
        public boolean modifiable() {
            if (textEditor instanceof ITextEditorExtension2 ext) return ext.isEditorInputModifiable();
            return textEditor.isEditable();
        }

        /**
         * Asks the editor for leave to change its input, as typing the first character does.
         * In an ADT editor this is what locks the object on the server, and it may ask the
         * user for a transport request. False when the editor stays read-only.
         */
        public boolean validateForEdit() {
            if (textEditor instanceof ITextEditorExtension2 ext) {
                if (!ext.validateEditorInputState()) return false;
                return ext.isEditorInputModifiable();
            }
            return textEditor.isEditable();
        }

        public boolean dirty() {
            return editor.isDirty();
        }

        public void describe(JsonObject out) {
            out.addProperty("filePath", filePath);
            out.addProperty("label", label);
            if (page != null && !page.isBlank()) out.addProperty("page", page);
            if (!pages.isEmpty()) {
                JsonArray all = new JsonArray();
                for (String p : pages) all.add(p);
                out.add("pages", all);
            }
        }
    }

    /** The {@code file_path} property the editor tools share. */
    public static JsonObject fileProp() {
        return ToolSupport.prop("string",
                "The file whose editor to use: the path getOpenEditors lists (for ABAP sources a "
                + "semanticfs: URI), a workspace path such as /Project/folder/file, a disk path, or "
                + "the editor's title such as ZCL_FOO. Left out, the active editor.");
    }

    /** The {@code page} property: a tab of a multi-page editor such as an ABAP class. */
    public static JsonObject pageProp() {
        return ToolSupport.prop("string",
                "For an editor with several tabs (an ABAP class: Global Class, Class-relevant Local "
                + "Types, Local Types, Test Classes, Macros), the tab to use, by its title. Left out, "
                + "the tab that is showing.");
    }

    /**
     * The editor a call names, with its text editor. UI thread only.
     *
     * @param path a name as described on the class, or null for the active editor
     * @param page the title of a tab of a multi-page editor, or null for the one showing
     * @param open whether to open the file when no editor has it open
     */
    public static Target find(String path, String page, boolean open) throws Refusal {
        IWorkbenchPage wp = UiHelper.getActivePage();
        if (wp == null) throw new Refusal("No active workbench page");

        IEditorPart editor;
        if (path == null) {
            editor = wp.getActiveEditor();
            if (editor == null) throw new Refusal("No editor is open.");
        } else {
            editor = findOpen(wp, path);
            if (editor == null && open) editor = open(wp, path);
            if (editor == null) {
                throw new Refusal("No open editor for " + path + ". Open editors: " + openEditorNames(wp));
            }
        }
        return targetOf(editor, page);
    }

    /** The open editor a name matches, or null. UI thread only. */
    public static IEditorPart findOpen(IWorkbenchPage wp, String path) throws Refusal {
        Set<String> wanted = keys(path);
        List<IEditorReference> byTitle = new ArrayList<>();
        String bare = path.trim();
        boolean mayBeTitle = !bare.contains("/") && !bare.contains("\\");
        for (IEditorReference ref : wp.getEditorReferences()) {
            IEditorInput input;
            try {
                input = ref.getEditorInput();
            } catch (Exception e) {
                continue;
            }
            if (input == null) continue;
            for (String key : keysOf(input)) {
                if (wanted.contains(key)) return ref.getEditor(true);
            }
            if (mayBeTitle && titleMatches(ref, input, bare)) byTitle.add(ref);
        }
        if (byTitle.size() == 1) return byTitle.get(0).getEditor(true);
        if (byTitle.size() > 1) {
            List<String> names = new ArrayList<>();
            for (IEditorReference ref : byTitle) names.add(pathOf(ref));
            throw new Refusal("More than one editor is titled " + bare + "; name one by its path: "
                    + String.join(", ", names));
        }
        return null;
    }

    /**
     * The workspace file a name stands for, or null when it names none. A {@code semanticfs:}
     * URI and a workspace path are looked up in the workspace; a disk path through its location.
     */
    public static IFile resolveFile(String path) {
        if (path == null || path.isBlank()) return null;
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        String t = path.trim().replace('\\', '/');

        if (SCHEME.matcher(t).find()) {
            try {
                URI uri = new URI(t.replace(" ", "%20"));
                for (IFile f : root.findFilesForLocationURI(uri)) {
                    if (f.exists()) return f;
                }
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    t = new File(uri).getPath().replace('\\', '/');
                } else if (uri.getPath() != null) {
                    t = uri.getPath();
                }
            } catch (Exception ignored) {
                t = SCHEME.matcher(t).replaceFirst("/");
            }
        }

        IPath workspacePath = Path.forPosix(t);
        if (workspacePath.segmentCount() >= 2) {
            IProject project = root.getProject(workspacePath.segment(0));
            if (project.exists()) {
                IFile f = root.getFile(workspacePath.makeAbsolute().setDevice(null));
                if (f.exists()) return f;
            }
        }

        try {
            IFile f = ToolSupport.workspaceFile(new File(t).getAbsolutePath());
            if (f != null && f.exists()) return f;
        } catch (Exception ignored) {
            // not a disk path
        }
        return null;
    }

    /** The editor and its text editor (the page asked for, if any). UI thread only. */
    public static Target targetOf(IEditorPart editor, String page) throws Refusal {
        List<String> pages = new ArrayList<>();
        String pageTitle = null;
        ITextEditor text;
        // A multi-page editor whose tabs have no titles (ADT's editor of a program has one
        // untitled tab) is treated as one without tabs.
        List<Object[]> all = editor instanceof MultiPageEditorPart multi ? pagesOf(multi) : List.of();
        if (all.stream().allMatch(p -> ((String) p[0]).isBlank())) all = List.of();
        if (editor instanceof MultiPageEditorPart multi && !all.isEmpty()) {
            Object selected = multi.getSelectedPage();
            for (Object[] p : all) pages.add((String) p[0]);
            if (page != null) {
                Object[] chosen = null;
                for (Object[] p : all) {
                    if (((String) p[0]).equalsIgnoreCase(page.trim())) chosen = p;
                }
                if (chosen == null) {
                    for (Object[] p : all) {
                        if (((String) p[0]).toLowerCase(Locale.ROOT).contains(page.trim().toLowerCase(Locale.ROOT))) {
                            chosen = p;
                            break;
                        }
                    }
                }
                if (chosen == null) {
                    throw new Refusal("The editor " + editor.getTitle() + " has no tab " + page
                            + ". Its tabs: " + String.join(", ", pages));
                }
                pageTitle = (String) chosen[0];
                text = chosen[1] instanceof IEditorPart part ? EditorParts.textEditorOf(part) : null;
            } else {
                for (Object[] p : all) {
                    if (p[1] != null && p[1] == selected) pageTitle = (String) p[0];
                }
                text = EditorParts.textEditorOf(editor);
            }
        } else {
            if (page != null) throw new Refusal("The editor " + editor.getTitle() + " has no tabs.");
            text = EditorParts.textEditorOf(editor);
            if (text == null) text = anyPageText(editor);
            if (text == null) {
                // An editor restored at startup builds its pages once it is shown (ADT's
                // only once it is activated).
                editor.getSite().getPage().bringToTop(editor);
                text = EditorParts.textEditorOf(editor);
                if (text == null) text = anyPageText(editor);
                if (text == null) {
                    editor.getSite().getPage().activate(editor);
                    text = EditorParts.textEditorOf(editor);
                    if (text == null) text = anyPageText(editor);
                }
            }
        }
        if (text == null) {
            throw new Refusal("The editor " + editor.getTitle()
                    + (pageTitle != null ? " (tab " + pageTitle + ")" : "") + " is not a text editor ("
                    + describeParts(editor) + "). If it is an ABAP editor, the user is probably not "
                    + "logged on to its SAP system yet: ask them to click into the editor and log on, "
                    + "then try again.");
        }
        String filePath = EditorParts.pathOf(text.getEditorInput());
        if (filePath == null) filePath = EditorParts.pathOf(editor.getEditorInput());
        return new Target(editor, text, filePath, editor.getTitle(), pageTitle, pages);
    }

    /** "label (path)" of every open editor, for a refusal to list. UI thread only. */
    static String openEditorNames(IWorkbenchPage wp) {
        List<String> names = new ArrayList<>();
        for (IEditorReference ref : wp.getEditorReferences()) {
            names.add(ref.getTitle() + " (" + pathOf(ref) + ")");
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    // ── matching ───────────────────────────────────────────────────────────────

    private static IEditorPart open(IWorkbenchPage wp, String path) throws Refusal {
        try {
            IFile file = resolveFile(path);
            if (file != null) return IDE.openEditor(wp, file, true);
            File disk = new File(path);
            if (disk.isFile()) {
                return IDE.openEditorOnFileStore(wp, EFS.getLocalFileSystem().getStore(disk.toURI()));
            }
            return null;
        } catch (Exception e) {
            throw new Refusal("Could not open " + path + ": " + e.getMessage());
        }
    }

    private static boolean titleMatches(IEditorReference ref, IEditorInput input, String name) {
        List<String> candidates = new ArrayList<>();
        candidates.add(ref.getTitle());
        candidates.add(ref.getName());
        candidates.add(input.getName());
        IFile file = input.getAdapter(IFile.class);
        if (file != null) {
            candidates.add(file.getName());
            String ext = file.getFileExtension();
            if (ext != null) candidates.add(file.getName().substring(0, file.getName().length() - ext.length() - 1));
        }
        for (String c : candidates) {
            if (c == null) continue;
            if (c.equalsIgnoreCase(name)) return true;
            if (TITLE_PREFIX.matcher(c).replaceFirst("").equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static String pathOf(IEditorReference ref) {
        try {
            String p = EditorParts.pathOf(ref.getEditorInput());
            return p != null ? p : ref.getTitle();
        } catch (Exception e) {
            return ref.getTitle();
        }
    }

    private static Set<String> keysOf(IEditorInput input) {
        Set<String> keys = new LinkedHashSet<>();
        addKey(keys, EditorParts.pathOf(input));
        IFile file = input.getAdapter(IFile.class);
        if (file != null) {
            addKey(keys, file.getFullPath().toString());
            if (file.getLocationURI() != null) addKey(keys, file.getLocationURI().toString());
            if (file.getLocation() != null) addKey(keys, file.getLocation().toOSString());
        }
        if (input instanceof IURIEditorInput uriInput && uriInput.getURI() != null) {
            addKey(keys, uriInput.getURI().toString());
        }
        return keys;
    }

    private static Set<String> keys(String path) {
        Set<String> keys = new LinkedHashSet<>();
        addKey(keys, path);
        return keys;
    }

    /**
     * Adds the forms one name is compared in: with its scheme dropped (a {@code semanticfs:}
     * URI then reads as the workspace path it is), with forward slashes, decoded, and in
     * lower case, since a Windows disk path and an ABAP name are both case-insensitive.
     */
    private static void addKey(Set<String> keys, String raw) {
        if (raw == null || raw.isBlank()) return;
        String t = raw.trim().replace('\\', '/');
        try {
            t = URLDecoder.decode(t.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // not percent-encoded
        }
        t = SCHEME.matcher(t).replaceFirst("/");
        t = SLASH_DRIVE.matcher(t).replaceFirst("$1");
        t = t.replaceAll("/{2,}", "/");
        if (t.length() > 1 && t.endsWith("/")) t = t.substring(0, t.length() - 1);
        keys.add(t.toLowerCase(Locale.ROOT));
    }

    // ── multi-page editors ─────────────────────────────────────────────────────

    /**
     * The text editor of the first page that has one, for a multi-page editor that names no
     * selected page (ADT's editor of a program has one untitled page, and right after a
     * restart reports none as selected). Null for any other editor.
     */
    private static ITextEditor anyPageText(IEditorPart editor) {
        if (!(editor instanceof MultiPageEditorPart multi)) return null;
        for (Object[] p : pagesOf(multi)) {
            if (p[1] instanceof IEditorPart part) {
                ITextEditor text = EditorParts.textEditorOf(part);
                if (text != null) return text;
            }
        }
        return null;
    }

    /** The editor's class and its pages' classes, for a refusal to name. */
    private static String describeParts(IEditorPart editor) {
        StringBuilder sb = new StringBuilder(editor.getClass().getName());
        if (editor instanceof MultiPageEditorPart multi) {
            List<Object[]> pages = pagesOf(multi);
            Object selected = multi.getSelectedPage();
            sb.append(", ").append(pages.size()).append(" page(s): ");
            for (int i = 0; i < pages.size(); i++) {
                Object part = pages.get(i)[1];
                if (i > 0) sb.append(", ");
                sb.append('\'').append(pages.get(i)[0]).append("' ")
                        .append(part == null ? "null" : part.getClass().getName());
            }
            sb.append("; selected: ").append(selected == null ? "null" : selected.getClass().getName());
        }
        return sb.toString();
    }

    /**
     * Brings a target's tab to the front, so that a command run on the editor acts on it.
     * UI thread only; does nothing for an editor without tabs.
     */
    public static void showPage(Target target) {
        if (target.page() == null || !(target.editor() instanceof MultiPageEditorPart multi)) return;
        List<Object[]> pages = pagesOf(multi);
        for (int i = 0; i < pages.size(); i++) {
            if (target.page().equals(pages.get(i)[0])) {
                try {
                    Method set = MultiPageEditorPart.class.getDeclaredMethod("setActivePage", int.class);
                    set.setAccessible(true);
                    set.invoke(multi, i);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // the tab stays as it is
                }
                return;
            }
        }
    }

    /**
     * The tabs of a multi-page editor as {title, the page's editor part or null}. The page
     * accessors are protected, so they are reached by reflection; an editor that refuses
     * answers with no tabs, and the call then works on the tab that is showing.
     */
    private static List<Object[]> pagesOf(MultiPageEditorPart multi) {
        List<Object[]> pages = new ArrayList<>();
        try {
            Method count = MultiPageEditorPart.class.getDeclaredMethod("getPageCount");
            Method text = MultiPageEditorPart.class.getDeclaredMethod("getPageText", int.class);
            Method part = MultiPageEditorPart.class.getDeclaredMethod("getEditor", int.class);
            count.setAccessible(true);
            text.setAccessible(true);
            part.setAccessible(true);
            int n = (Integer) count.invoke(multi);
            for (int i = 0; i < n; i++) {
                String title = (String) text.invoke(multi, i);
                Object editor = part.invoke(multi, i);
                pages.add(new Object[] { title == null ? "" : title, editor });
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // see above
        }
        return pages;
    }
}
