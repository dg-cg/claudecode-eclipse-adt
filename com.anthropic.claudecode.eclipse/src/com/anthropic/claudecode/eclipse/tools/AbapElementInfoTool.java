package com.anthropic.claudecode.eclipse.tools;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import com.anthropic.claudecode.eclipse.editor.EditorParts;
import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.anthropic.claudecode.eclipse.tools.ToolSupport.Refusal;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * What ADT's Element Info (F2) shows for the element at a position of an ABAP source: a
 * variable's type, a structure's components, a class's methods and attributes, a method's
 * signature, with their documentation.
 *
 * <p>It asks the back end the way F2 does — {@code POST /sap/bc/adt/abapsource/codecompletion/elementinfo}
 * with the source URI and position as {@code uri=…#start=line,column} and the editor's text as
 * the body, so unsaved changes count — through ADT's own communication layer, which brings the
 * logon and the session. ADT is reached by reflection, as {@link ToolSupport#classOf} lets a
 * tool use a plug-in this one does not compile against: the tool is offered only where ADT is
 * installed, and the build needs no SAP repository.
 */
public class AbapElementInfoTool implements McpTool {

    /** The bundles the tool needs; it is registered only when all are installed. */
    public static final String[] BUNDLES = {
        "com.sap.adt.communication", "com.sap.adt.project", "com.sap.adt.tools.core.base",
        "com.sap.adt.tools.abapsource" };

    private static final String SERVICE = "/sap/bc/adt/abapsource/codecompletion/elementinfo";
    private static final String CONTENT_TYPE = "application/vnd.sap.adt.elementinfo+xml";
    private static final int MAX_XML_CHARS = 30_000;
    private static final int MAX_DOC_CHARS = 4_000;

    @Override
    public String toolName() {
        return "abapElementInfo";
    }

    @Override
    public String description() {
        return "What ADT's Element Info (F2) shows for an element of an ABAP source: for a variable "
                + "or constant its type, for a structure its components with their types, for a "
                + "class or interface its methods, attributes and events, for a method its signature "
                + "(parameters with kind and type), for a function module its interface, with the "
                + "element's documentation. Asks the SAP back end through the editor's ADT connection, "
                + "using the editor's current text (unsaved changes included). The element is the one "
                + "at 'line' and 'column', or at 'word' in 'line', or, with neither, at the cursor "
                + "position in the editor. Lines and columns count from 1, as readEditor numbers them.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject props = new JsonObject();
        props.add("file_path", EditorTargets.fileProp());
        props.add("page", EditorTargets.pageProp());
        props.add("line", ToolSupport.prop("integer", "The line of the element, from 1. Left out: the cursor's."));
        props.add("column", ToolSupport.prop("integer",
                "The column of the element in that line, from 1 (any character of the name will do)."));
        props.add("word", ToolSupport.prop("string",
                "Instead of 'column': the name to look up, found in 'line' (the first match, ignoring case)."));
        props.add("include_xml", ToolSupport.prop("boolean",
                "Also return the back end's XML as it came. Default false."));
        return ToolSupport.schema(props);
    }

    /** What is read from the editor on the UI thread. */
    private record Request(IFile file, String text, int line, int column0, String token, String label) {
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        String path = ToolSupport.str(params, "file_path");
        if (path == null) path = ToolSupport.str(params, "filePath");
        String page = ToolSupport.str(params, "page");
        int line = ToolSupport.integer(params, "line", 0, 0, Integer.MAX_VALUE);
        int column = ToolSupport.integer(params, "column", 0, 0, Integer.MAX_VALUE);
        String word = ToolSupport.str(params, "word");
        boolean includeXml = ToolSupport.bool(params, "include_xml", false);
        if (line == 0 && (column > 0 || word != null)) {
            return McpToolResult.error("'column' and 'word' need 'line'.");
        }
        String name = path;

        Object read = UiHelper.syncCall(() -> {
            try {
                return request(name, page, line, column, word);
            } catch (Refusal r) {
                return r.getMessage();
            } catch (Exception e) {
                return "Failed to read the editor: " + e.getMessage();
            }
        });
        if (read instanceof String problem) return McpToolResult.error(problem);
        if (!(read instanceof Request request)) return McpToolResult.error("The workbench is not available.");

        try {
            Object reference = adapt(request.file(), "com.sap.adt.tools.core.base", "com.sap.adt.tools.core.IAdtObjectReference");
            if (reference == null) {
                return McpToolResult.error(request.label() + " is not an ABAP object ADT knows (no object reference).");
            }
            Class<?> referenceType = ToolSupport.classOf("com.sap.adt.tools.core.base", "com.sap.adt.tools.core.IAdtObjectReference");
            URI objectUri = (URI) ToolSupport.call(referenceType, reference, "getUri");
            String destination = destinationOf(request.file());

            List<String> sourceUris = sourceUris(objectUri);
            String fragment = "#start=" + request.line() + "," + request.column0();
            Exception last = null;
            for (String sourceUri : sourceUris) {
                try {
                    String xml = post(destination, sourceUri + fragment, request.text());
                    JsonObject out = new JsonObject();
                    out.addProperty("filePath", EditorParts.pathOf(request.file()));
                    out.addProperty("sourceUri", sourceUri);
                    JsonObject position = new JsonObject();
                    position.addProperty("line", request.line());
                    position.addProperty("column", request.column0() + 1);
                    if (request.token() != null) position.addProperty("token", request.token());
                    out.add("position", position);
                    JsonObject element = parse(xml);
                    if (element != null) out.add("element", element);
                    else out.addProperty("message", "The back end returned no element information for this position.");
                    if (includeXml || element == null) {
                        out.addProperty("xml", xml.length() <= MAX_XML_CHARS ? xml : xml.substring(0, MAX_XML_CHARS) + "…");
                    }
                    return McpToolResult.success(out);
                } catch (Exception e) {
                    last = e;
                }
            }
            return McpToolResult.error("The back end gave no element information for " + request.label()
                    + " (tried " + String.join(", ", sourceUris) + "): " + describe(last));
        } catch (Exception e) {
            return McpToolResult.error("Element information failed: " + describe(e));
        }
    }

    // ── The editor ─────────────────────────────────────────────────────────────────

    /** The file, text and position to ask about. UI thread only. */
    private static Request request(String path, String page, int line, int column, String word) throws Exception {
        EditorTargets.Target target = EditorTargets.find(path, page, false);
        IDocument doc = target.document();
        if (doc == null) throw new Refusal("The editor " + target.label() + " has no document.");
        IFile file = target.textEditor().getEditorInput().getAdapter(IFile.class);
        if (file == null) file = target.editor().getEditorInput().getAdapter(IFile.class);
        if (file == null) throw new Refusal("The editor " + target.label() + " is not on a workspace file.");

        int offset;
        if (line == 0) {
            ISelection selection = target.textEditor().getSelectionProvider().getSelection();
            if (!(selection instanceof ITextSelection text)) throw new Refusal("The editor has no cursor position.");
            offset = text.getOffset();
        } else {
            if (line > doc.getNumberOfLines()) {
                throw new Refusal(target.label() + " has " + doc.getNumberOfLines() + " lines; there is no line " + line + ".");
            }
            IRegion region = doc.getLineInformation(line - 1);
            String lineText = doc.get(region.getOffset(), region.getLength());
            int col0;
            if (word != null) {
                col0 = lineText.toLowerCase(Locale.ROOT).indexOf(word.toLowerCase(Locale.ROOT));
                if (col0 < 0) throw new Refusal("Line " + line + " does not contain '" + word + "': " + lineText.trim());
            } else {
                col0 = Math.max(0, Math.min(column - 1, lineText.length()));
            }
            offset = region.getOffset() + col0;
        }
        int line0 = doc.getLineOfOffset(offset);
        int col0 = offset - doc.getLineOffset(line0);
        return new Request(file, doc.get(), line0 + 1, col0, tokenAt(doc, offset), target.label());
    }

    /** The ABAP name around an offset, for the answer to say what was looked up. */
    private static String tokenAt(IDocument doc, int offset) {
        try {
            String text = doc.get();
            int start = offset;
            int end = offset;
            while (start > 0 && isNameChar(text.charAt(start - 1))) start--;
            while (end < text.length() && isNameChar(text.charAt(end))) end++;
            return end > start ? text.substring(start, end) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A character of an ABAP name; namespaces (/ABC/) included, component selectors not. */
    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '/';
    }

    // ── ADT ────────────────────────────────────────────────────────────────────────

    private static Object adapt(Object adaptable, String bundle, String type) throws Exception {
        ToolSupport.classOf(bundle, type); // starts the bundle, and fails when it is missing
        return Platform.getAdapterManager().loadAdapter(adaptable, type);
    }

    /** The ADT destination of the file's project, which the connection is opened for. */
    private static String destinationOf(IFile file) throws Exception {
        Object project = adapt(file.getProject(), "com.sap.adt.project", "com.sap.adt.project.IAdtCoreProject");
        if (project == null) throw new Refusal(file.getProject().getName() + " is not an ABAP project.");
        Class<?> type = ToolSupport.classOf("com.sap.adt.project", "com.sap.adt.project.IAdtCoreProject");
        return (String) ToolSupport.call(type, project, "getDestinationId");
    }

    /**
     * The source URIs to try for an object URI, most likely first. The reference of a source
     * file is usually the object's own URI, its source being {@code …/source/main}; one that
     * already names a source or a class include is taken as it is.
     */
    static List<String> sourceUris(URI objectUri) {
        List<String> uris = new ArrayList<>();
        String uri = objectUri.toString();
        if (uri.contains("/source/") || uri.contains("/includes/")) {
            uris.add(uri);
        } else {
            uris.add(uri + "/source/main");
            uris.add(uri);
        }
        return uris;
    }

    /** POSTs the source to the element info service and returns the XML it answers with. */
    private static String post(String destination, String uriWithPosition, String source) throws Exception {
        String bundle = "com.sap.adt.communication";
        Class<?> factoryType = ToolSupport.classOf(bundle, "com.sap.adt.communication.resources.AdtRestResourceFactory");
        Class<?> iFactory = ToolSupport.classOf(bundle, "com.sap.adt.communication.resources.IRestResourceFactory");
        Class<?> iResource = ToolSupport.classOf(bundle, "com.sap.adt.communication.resources.IRestResource");
        Class<?> headersFactory = ToolSupport.classOf(bundle, "com.sap.adt.communication.message.HeadersFactory");
        Class<?> iHeaders = ToolSupport.classOf(bundle, "com.sap.adt.communication.message.IHeaders");
        Class<?> iField = ToolSupport.classOf(bundle, "com.sap.adt.communication.message.IHeaders$IField");
        Class<?> iResponse = ToolSupport.classOf(bundle, "com.sap.adt.communication.message.IResponse");
        Class<?> iMessageBody = ToolSupport.classOf(bundle, "com.sap.adt.communication.message.IMessageBody");
        Class<?> bodyType = ToolSupport.classOf(bundle, "com.sap.adt.communication.message.ByteArrayMessageBody");
        Class<?> queryParameter = ToolSupport.classOf(bundle, "com.sap.adt.communication.resources.IQueryParameter");

        URI service = URI.create(SERVICE + "?uri=" + URLEncoder.encode(uriWithPosition, StandardCharsets.UTF_8));
        Object factory = ToolSupport.call(factoryType, null, "createRestResourceFactory");
        Object resource = ToolSupport.call(iFactory, factory, "createResourceWithStatelessSession", service, destination);

        Object headers = ToolSupport.call(headersFactory, null, "newHeaders");
        Object accept = headersFactory.getMethod("newField", String.class, String[].class)
                .invoke(null, "Accept", new String[] { CONTENT_TYPE + ", text/plain;q=0.1" });
        iHeaders.getMethod("setField", iField).invoke(headers, accept);

        Constructor<?> bodyConstructor = bodyType.getConstructor(String.class, byte[].class);
        Object body = bodyConstructor.newInstance("text/plain; charset=utf-8", source.getBytes(StandardCharsets.UTF_8));

        Object response = iResource.getMethod("post", org.eclipse.core.runtime.IProgressMonitor.class, iHeaders,
                Class.class, Object.class, Array.newInstance(queryParameter, 0).getClass())
                .invoke(resource, new NullProgressMonitor(), headers, iResponse, body, Array.newInstance(queryParameter, 0));

        int status = (Integer) ToolSupport.call(iResponse, response, "getStatus");
        Object messageBody = ToolSupport.call(ToolSupport.classOf(bundle, "com.sap.adt.communication.message.IMessage"), response, "getBody");
        String text = "";
        if (messageBody != null) {
            try (InputStream in = (InputStream) ToolSupport.call(iMessageBody, messageBody, "getContent")) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (status >= 300) throw new IllegalStateException("HTTP " + status + ": " + abbreviate(text, 500));
        return text;
    }

    private static String describe(Throwable e) {
        if (e == null) return "no answer";
        Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + abbreviate(message, 500));
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ── The answer ─────────────────────────────────────────────────────────────────

    /**
     * The element info XML as JSON: an element's name, type and URI, its properties
     * ({@code abapType}, {@code visibility}, {@code paramType}, …), its documentation and links,
     * and the elements below it (components, methods, parameters) in the same shape. Null when
     * the answer holds no element.
     */
    static JsonObject parse(String xml) throws Exception {
        if (xml == null || xml.isBlank()) return null;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Element root = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        return root == null ? null : element(root);
    }

    private static JsonObject element(Element e) {
        JsonObject out = new JsonObject();
        NamedNodeMap attributes = e.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node a = attributes.item(i);
            String name = localName(a);
            if (a.getNodeName().startsWith("xmlns")) continue;
            out.addProperty(name, a.getNodeValue());
        }
        JsonObject properties = new JsonObject();
        JsonObject documentation = new JsonObject();
        JsonArray children = new JsonArray();
        JsonArray links = new JsonArray();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element child)) continue;
            String tag = localName(child);
            switch (tag) {
                case "elementInfo" -> children.add(element(child));
                case "properties" -> entries(child, properties);
                case "entry" -> entry(child, properties);
                case "documentation" -> {
                    // One per kind: the short text, the ABAP Doc comment, the long text.
                    String text = child.getTextContent().trim();
                    String rel = attribute(child, "rel");
                    if (!text.isEmpty()) {
                        documentation.addProperty(rel == null || rel.isEmpty() ? "text" : rel,
                                abbreviate(stripTags(text), MAX_DOC_CHARS));
                    }
                }
                case "link", "docLink" -> links.add(link(child));
                case "docLinks", "links" -> {
                    for (Node l = child.getFirstChild(); l != null; l = l.getNextSibling()) {
                        if (l instanceof Element le) links.add(link(le));
                    }
                }
                default -> {
                    String text = child.getTextContent().trim();
                    if (child.getChildNodes().getLength() == 1 && !text.isEmpty()) {
                        out.addProperty(tag, abbreviate(text, MAX_DOC_CHARS));
                    } else {
                        JsonObject nested = element(child);
                        if (nested.size() > 0) {
                            if (!out.has(tag)) out.add(tag, new JsonArray());
                            if (out.get(tag).isJsonArray()) out.getAsJsonArray(tag).add(nested);
                        }
                    }
                }
            }
        }
        if (properties.size() > 0) out.add("properties", properties);
        if (documentation.size() > 0) out.add("documentation", documentation);
        if (children.size() > 0) out.add("children", children);
        if (links.size() > 0) out.add("links", links);
        return out;
    }

    private static void entries(Element properties, JsonObject into) {
        for (Node n = properties.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element entry) entry(entry, into);
        }
    }

    private static void entry(Element entry, JsonObject into) {
        // The key is a namespaced attribute (abapsource:key), so it is looked up by local name.
        String key = attribute(entry, "key");
        if (key == null || key.isEmpty()) key = localName(entry);
        String value = entry.getTextContent().trim();
        if (!value.isEmpty()) into.addProperty(key, value);
    }

    private static JsonObject link(Element e) {
        JsonObject l = new JsonObject();
        for (String a : new String[] { "href", "rel", "title", "type", "id" }) {
            String v = attribute(e, a);
            if (v != null && !v.isEmpty()) l.addProperty(a, v);
        }
        String text = e.getTextContent().trim();
        if (!text.isEmpty() && !l.has("title")) l.addProperty("title", abbreviate(text, 200));
        return l;
    }

    private static String attribute(Element e, String local) {
        NamedNodeMap attributes = e.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            if (local.equals(localName(attributes.item(i)))) return attributes.item(i).getNodeValue();
        }
        return null;
    }

    private static String localName(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    private static String stripTags(String html) {
        return html.replaceAll("(?s)<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("[ \\t]+", " ")
                .replaceAll("\\s*\\n\\s*", "\n").trim();
    }
}
