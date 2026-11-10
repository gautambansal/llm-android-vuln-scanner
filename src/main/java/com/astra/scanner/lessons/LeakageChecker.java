package com.astra.scanner.lessons;

import com.astra.scanner.ingest.IngestedApp;
import com.astra.scanner.ingest.ManifestInfo;
import com.astra.scanner.ingest.SourceFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects lessons that are instance-specific. Built automatically from the ingested app, so it
 * works on any APK: no hand-written list of InsecureShop names.
 *
 * Blocked:
 *   - the app's package name and its distinctive segments (e.g. "insecureshop")
 *   - simple names of the app's own classes (e.g. "WebViewActivity") and its file names
 *   - distinctive string literals from the app's code/resources (action strings, extra keys,
 *     domains, credentials), but not standard "android.*" constants
 *   - line references ("line 91", "L91", ":91")
 */
public final class LeakageChecker {

    private static final Pattern LINE_REF = Pattern.compile("(?i)\\b(line|lines|ln|l)\\s*\\d+|\\.(java|kt|xml):\\d+");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\\\n]{4,80})\"");
    private static final Set<String> GENERIC_PACKAGE_PARTS = Set.of(
            "com", "org", "net", "io", "app", "android", "example", "main", "core", "mobile");

    public record Leak(int lessonIndex, String lessonTitle, List<String> terms) {}

    private final Set<String> blocked = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    public LeakageChecker(IngestedApp app) {
        this(app.manifest().packageName(), app.files());
    }

    LeakageChecker(String packageName, List<SourceFile> files) {
        blocked.add(packageName);
        for (String part : packageName.split("\\.")) {
            if (part.length() >= 4 && !GENERIC_PACKAGE_PARTS.contains(part.toLowerCase(Locale.ROOT))) blocked.add(part);
        }
        for (SourceFile f : files) {
            String base = f.path().replaceFirst(".*/", "");
            if (f.kind() == SourceFile.Kind.CODE) {
                String cls = base.replaceFirst("\\.java$", "");
                if (cls.length() >= 4) blocked.add(cls);
                addLiterals(f.content());
            } else if (f.kind() == SourceFile.Kind.RESOURCE) {
                addLiterals(f.content());
            }
        }
    }

    /** Distinctive literals: contain a dot, underscore, digit or symbol (action strings, keys, domains, passwords). */
    private void addLiterals(String content) {
        Matcher mt = STRING_LITERAL.matcher(content);
        while (mt.find()) {
            String lit = mt.group(1).strip();
            if (lit.startsWith("android.") || lit.startsWith("http://schemas.android.com") || lit.contains(" ")) continue;
            boolean distinctive = lit.matches(".*[._\\d!@#$%^&*:/-].*") && lit.matches(".*[A-Za-z].*");
            if (distinctive && lit.length() >= 5) blocked.add(lit);
        }
    }

    /** Returns one Leak per offending lesson (empty list = all clean). */
    public List<Leak> check(List<Lesson> lessons) {
        List<Leak> leaks = new ArrayList<>();
        for (int i = 0; i < lessons.size(); i++) {
            String text = lessons.get(i).allText();
            String lower = text.toLowerCase(Locale.ROOT);
            List<String> hits = new ArrayList<>();
            for (String term : blocked) {
                if (containsWord(lower, term.toLowerCase(Locale.ROOT))) hits.add(term);
            }
            Matcher lr = LINE_REF.matcher(text);
            if (lr.find()) hits.add(lr.group());
            if (!hits.isEmpty()) leaks.add(new Leak(i, lessons.get(i).title(), hits));
        }
        return leaks;
    }

    private static final List<String> PLATFORM_PREFIXES = List.of("android.", "androidx.", "java.", "javax.", "kotlin.", "dalvik.");

    /**
     * Whole-word match, so "Prefs" doesn't fire inside "SharedPreferences".
     * Ignored when the word is part of a standard platform name: "util" in "android.util.Log" is not the app's Util class.
     */
    private static boolean containsWord(String text, String term) {
        int from = 0;
        while (true) {
            int i = text.indexOf(term, from);
            if (i < 0) return false;
            boolean startOk = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            int end = i + term.length();
            boolean endOk = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startOk && endOk && !insidePlatformName(text, i, end)) return true;
            from = i + 1;
        }
    }

    /** Expand to the surrounding dotted identifier (e.g. "android.util.log") and check its prefix. */
    private static boolean insidePlatformName(String text, int start, int end) {
        int s = start, e = end;
        while (s > 0 && (Character.isLetterOrDigit(text.charAt(s - 1)) || text.charAt(s - 1) == '.' || text.charAt(s - 1) == '_')) s--;
        while (e < text.length() && (Character.isLetterOrDigit(text.charAt(e)) || text.charAt(e) == '.' || text.charAt(e) == '_')) e++;
        String token = text.substring(s, e);
        return token.contains(".") && PLATFORM_PREFIXES.stream().anyMatch(token::startsWith);
    }

    public int blockedTermCount() { return blocked.size(); }
}
