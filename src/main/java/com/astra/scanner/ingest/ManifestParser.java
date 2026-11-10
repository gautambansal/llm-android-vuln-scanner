package com.astra.scanner.ingest;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses jadx's decoded AndroidManifest.xml into a ManifestInfo. */
public final class ManifestParser {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final List<String> COMPONENT_TAGS =
            List.of("activity", "activity-alias", "service", "receiver", "provider");
    private static final List<String> SECURITY_FLAGS = List.of(
            "debuggable", "allowBackup", "usesCleartextTraffic", "networkSecurityConfig", "fullBackupContent");

    private ManifestParser() {}

    public static ManifestInfo parse(Path manifestFile) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Untrusted input: disable external entities (XXE).
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(manifestFile.toFile());
        Element root = doc.getDocumentElement();

        String pkg = root.getAttribute("package");
        Element usesSdk = first(root, "uses-sdk");
        int minSdk = intAttr(usesSdk, "minSdkVersion", 1);
        int targetSdk = intAttr(usesSdk, "targetSdkVersion", minSdk);

        Element app = first(root, "application");
        Map<String, String> flags = new LinkedHashMap<>();
        String appClass = null;
        if (app != null) {
            for (String f : SECURITY_FLAGS) {
                String v = android(app, f);
                if (v != null) flags.put(f, v);
            }
            String n = android(app, "name");
            if (n != null) appClass = fullName(pkg, n);
        }

        List<ManifestInfo.Component> components = new ArrayList<>();
        if (app != null) {
            for (Element e : children(app)) {
                if (COMPONENT_TAGS.contains(e.getLocalName())) components.add(component(e, pkg, targetSdk));
            }
        }

        List<ManifestInfo.DeclaredPermission> declared = new ArrayList<>();
        List<String> uses = new ArrayList<>();
        for (Element e : children(root)) {
            switch (e.getLocalName()) {
                case "permission" -> {
                    String level = android(e, "protectionLevel");
                    declared.add(new ManifestInfo.DeclaredPermission(android(e, "name"), level)); // null = attribute absent
                }
                case "uses-permission" -> uses.add(android(e, "name"));
                default -> { }
            }
        }
        return new ManifestInfo(pkg, minSdk, targetSdk, appClass, flags, components, declared, uses);
    }

    private static ManifestInfo.Component component(Element e, String pkg, int targetSdk) {
        String type = e.getLocalName();
        String name = fullName(pkg, android(e, "activity-alias".equals(type) ? "targetActivity" : "name"));
        if ("activity-alias".equals(type)) name = fullName(pkg, android(e, "name")) + " -> " + name;

        List<String> filters = new ArrayList<>();
        for (Element f : children(e)) {
            if ("intent-filter".equals(f.getLocalName())) filters.add(describeFilter(f));
        }

        // Effective "exported" value, following Android's rules:
        String explicit = android(e, "exported");
        boolean exported;
        String reason;
        if (explicit != null) {
            exported = Boolean.parseBoolean(explicit);
            reason = "explicit exported=" + explicit;
        } else if (!filters.isEmpty()) {
            // Before Android 12 (SDK 31) an intent-filter made a component exported by default;
            // from 31 the attribute is mandatory, so a missing one only exists in older apps.
            exported = true;
            reason = "implicit: has intent-filter, no exported attribute";
        } else if ("provider".equals(type) && targetSdk <= 16) {
            exported = true;
            reason = "implicit: provider default for targetSdk <= 16";
        } else {
            exported = false;
            reason = "default: not exported";
        }

        List<String> perms = new ArrayList<>();
        for (String a : List.of("permission", "readPermission", "writePermission")) {
            String v = android(e, a);
            if (v != null) perms.add(a + "=" + v);
        }
        return new ManifestInfo.Component(type, name, exported, reason, perms, filters);
    }

    private static String describeFilter(Element filter) {
        List<String> parts = new ArrayList<>();
        for (Element c : children(filter)) {
            switch (c.getLocalName()) {
                case "action" -> parts.add("action=" + android(c, "name"));
                case "category" -> parts.add("category=" + android(c, "name"));
                case "data" -> {
                    var d = new StringBuilder("data(");
                    for (String a : List.of("scheme", "host", "port", "path", "pathPrefix", "pathPattern", "mimeType")) {
                        String v = android(c, a);
                        if (v != null) d.append(a).append('=').append(v).append(' ');
                    }
                    parts.add(d.toString().strip() + ")");
                }
                default -> { }
            }
        }
        return String.join(", ", parts);
    }

    /** ".Foo" and "Foo" are relative to the package; "a.b.Foo" is already absolute. */
    static String fullName(String pkg, String name) {
        if (name == null) return null;
        if (name.startsWith(".")) return pkg + name;
        if (!name.contains(".")) return pkg + "." + name;
        return name;
    }

    private static String android(Element e, String attr) {
        if (e == null) return null;
        String v = e.getAttributeNS(ANDROID_NS, attr);
        return v.isEmpty() ? null : v;
    }

    private static int intAttr(Element e, String attr, int dflt) {
        String v = android(e, attr);
        try { return v == null ? dflt : Integer.parseInt(v); } catch (NumberFormatException ex) { return dflt; }
    }

    private static Element first(Element parent, String tag) {
        return children(parent).stream().filter(c -> tag.equals(c.getLocalName())).findFirst().orElse(null);
    }

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) out.add((Element) nodes.item(i));
        }
        return out;
    }
}
