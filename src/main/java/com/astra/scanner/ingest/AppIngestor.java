package com.astra.scanner.ingest;

import com.astra.scanner.config.AppConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** APK -> decompiled -> manifest + filtered app code + selected resources -> chunks. */
public final class AppIngestor {

    private final AppConfig.Ingest cfg;
    private final Path workRoot;
    private final List<Pattern> excludes;
    private final List<Pattern> resources;
    private final List<Pattern> trims;

    public AppIngestor(AppConfig config) {
        this.cfg = config.ingest();
        this.workRoot = config.app().workDir();
        this.excludes = cfg.excludePatterns().stream().map(Pattern::compile).toList();
        this.trims = cfg.trimLinePatterns().stream().map(Pattern::compile).toList();
        this.resources = cfg.resourcePatterns().stream().map(Pattern::compile).toList();
    }

    public IngestedApp ingest(Path apk) throws Exception {
        if (!Files.isRegularFile(apk)) throw new IOException("APK not found: " + apk);

        // Work folder is keyed by name + content hash: a changed APK never reuses stale output.
        String name = apk.getFileName().toString().replaceFirst("\\.apk$", "");
        String appId = name + "-" + sha256(apk).substring(0, 12);
        Path workDir = workRoot.resolve(appId);
        Path decompiled = Decompiler.decompile(apk, workDir.resolve("decompiled"), cfg.jadxPath());

        Path manifestPath = decompiled.resolve(Decompiler.MANIFEST);
        ManifestInfo manifest = ManifestParser.parse(manifestPath);

        // 1. Which packages are "the app's own code"?
        List<String> roots = appRoots(manifest);
        String strategy = "manifest package + same-org component packages";
        List<SourceFile> code = collectCode(decompiled, roots, false);

        // 2. Fallback for obfuscated / oddly packaged apps: everything that isn't a known library.
        if (code.size() < cfg.minAppFiles()) {
            strategy = "fallback: all code not under library_prefixes (found only " + code.size() + " files under " + roots + ")";
            roots = List.of("*");
            code = collectCode(decompiled, roots, true);
        }

        // 3. Assemble: manifest first, then resources, then code.
        List<SourceFile> files = new ArrayList<>();
        files.add(new SourceFile(Decompiler.MANIFEST, SourceFile.Kind.MANIFEST, Files.readString(manifestPath)));
        files.addAll(collectResources(decompiled));
        files.addAll(code);

        List<Chunk> chunks = new Chunker(cfg.maxChunkTokens()).chunk(files);
        return new IngestedApp(appId, workDir, manifest, roots, strategy, files, chunks);
    }

    /** Manifest package + packages of declared components from the same organisation, nested ones merged. */
    static List<String> appRoots(ManifestInfo m) {
        String org = firstSegments(m.packageName(), 2);
        var candidates = new TreeSet<String>();
        candidates.add(m.packageName());
        List<String> classes = new ArrayList<>();
        m.components().forEach(c -> classes.add(c.name().replaceFirst(" -> .*", "")));
        if (m.applicationClass() != null) classes.add(m.applicationClass());
        for (String cls : classes) {
            String pkg = cls.contains(".") ? cls.substring(0, cls.lastIndexOf('.')) : cls;
            if (pkg.equals(org) || pkg.startsWith(org + ".")) candidates.add(pkg);
        }
        // Drop roots nested inside another root: com.a.b.c is covered by com.a.b
        List<String> roots = new ArrayList<>();
        for (String c : candidates) {
            if (roots.stream().noneMatch(r -> c.startsWith(r + "."))) roots.add(c);
        }
        return roots;
    }

    private List<SourceFile> collectCode(Path decompiled, List<String> roots, boolean allNonLibrary) throws IOException {
        Path sources = decompiled.resolve(Decompiler.SOURCES_DIR);
        List<SourceFile> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(sources)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String rel = sources.relativize(p).toString().replace('\\', '/');
                String className = rel.replaceFirst("\\.java$", "").replace('/', '.');
                boolean inScope = allNonLibrary
                        ? cfg.libraryPrefixes().stream().noneMatch(className::startsWith)
                        : roots.stream().anyMatch(r -> className.startsWith(r + "."));
                String path = Decompiler.SOURCES_DIR + "/" + rel;
                if (inScope && !isExcluded(path)) {
                    out.add(new SourceFile(path, SourceFile.Kind.CODE, trimNoise(Files.readString(p, StandardCharsets.UTF_8))));
                }
            }
        }
        return out;
    }

    private List<SourceFile> collectResources(Path decompiled) throws IOException {
        List<SourceFile> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(decompiled.resolve(Decompiler.RESOURCES_DIR))) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = decompiled.relativize(p).toString().replace('\\', '/');
                if (resources.stream().anyMatch(r -> r.matcher(rel).find())) {
                    out.add(new SourceFile(rel, SourceFile.Kind.RESOURCE, Files.readString(p, StandardCharsets.UTF_8)));
                }
            }
        }
        return out;
    }

    /** Replace noisy lines with a placeholder; never delete lines, so line numbers match the real file. */
    private String trimNoise(String content) {
        if (trims.isEmpty()) return content;
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (trims.stream().anyMatch(t -> t.matcher(line).find())) {
                lines[i] = line.substring(0, line.length() - line.stripLeading().length()) + "/* [trimmed by ingest] */";
            }
        }
        return String.join("\n", lines);
    }

    private boolean isExcluded(String path) {
        return excludes.stream().anyMatch(x -> x.matcher(path).find());
    }

    private static String firstSegments(String pkg, int n) {
        String[] parts = pkg.split("\\.");
        return String.join(".", java.util.Arrays.copyOf(parts, Math.min(n, parts.length)));
    }

    private static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int r; (r = in.read(buf)) > 0; ) digest.update(buf, 0, r);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
