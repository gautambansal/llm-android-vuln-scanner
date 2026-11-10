package com.astra.scanner.ingest;

import java.util.List;
import java.util.Map;

/** The security-relevant facts from AndroidManifest.xml: the app's "route table". */
public record ManifestInfo(
        String packageName,
        int minSdk,
        int targetSdk,
        String applicationClass,
        Map<String, String> appFlags,              // debuggable, allowBackup, usesCleartextTraffic, ...
        List<Component> components,
        List<DeclaredPermission> declaredPermissions,
        List<String> usesPermissions
) {

    /** activity / activity-alias / service / receiver / provider. */
    public record Component(
            String type,
            String name,
            boolean exported,
            String exportedReason,     // why we think it is (not) exported
            List<String> permissions,  // permission / readPermission / writePermission
            List<String> intentFilters // compact summary of each filter
    ) {}

    /** A <permission> the app defines itself. protectionLevel = null when the attribute is absent (Android then uses "normal"). */
    public record DeclaredPermission(String name, String protectionLevel) {}

    public List<Component> exportedComponents() {
        return components.stream().filter(Component::exported).toList();
    }

    /** Short attack-surface summary, prepended to every scan chunk as context. */
    public String toPromptSummary() {
        var sb = new StringBuilder();
        sb.append("APP PACKAGE: ").append(packageName)
          .append("  (minSdk ").append(minSdk).append(", targetSdk ").append(targetSdk).append(")\n");
        sb.append("APPLICATION FLAGS: ").append(appFlags.isEmpty() ? "(none set)" : appFlags).append('\n');
        if (!declaredPermissions.isEmpty()) {
            sb.append("CUSTOM PERMISSIONS DECLARED:\n");
            declaredPermissions.forEach(p -> sb.append("  - ").append(p.name())
                    .append(p.protectionLevel() == null
                            ? " (no protectionLevel attribute; Android defaults to normal)"
                            : " protectionLevel=" + p.protectionLevel()).append('\n'));
        }
        sb.append("EXPORTED COMPONENTS (reachable by other apps):\n");
        for (Component c : exportedComponents()) {
            sb.append("  - ").append(c.type()).append(' ').append(c.name())
              .append(" [").append(c.exportedReason()).append(']');
            if (!c.permissions().isEmpty()) sb.append(" permissions=").append(c.permissions());
            sb.append('\n');
            c.intentFilters().forEach(f -> sb.append("      filter: ").append(f).append('\n'));
        }
        return sb.toString();
    }
}
