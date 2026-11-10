package com.astra.scanner.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The whole config.yaml, loaded and validated once at startup. */
public record AppConfig(App app, Map<String, ModelConfig> models, Map<String, String> roles, Run run,
                        Ingest ingest, Scan scan, Lessons lessons) {

    public record Lessons(Integer maxLessons) {
        public Lessons {
            if (maxLessons == null) maxLessons = 12;
        }
    }

    public record Scan(Path promptsDir, Integer evidenceLineTolerance, Integer minEvidenceChars,
                       Map<String, String> categories) {
        public Scan {
            if (promptsDir == null) promptsDir = Path.of("prompts");
            if (evidenceLineTolerance == null) evidenceLineTolerance = 5;
            if (minEvidenceChars == null) minEvidenceChars = 12;
            if (categories == null || categories.isEmpty()) categories = Map.of("other", "any vulnerability");
        }
    }

    public record App(Path apkPath, Path workDir, Path groundTruth) {}

    public record Ingest(String jadxPath, Integer minAppFiles, List<String> libraryPrefixes,
                         List<String> excludePatterns, List<String> trimLinePatterns,
                         List<String> resourcePatterns, Integer maxChunkTokens) {
        public Ingest {
            if (trimLinePatterns == null) trimLinePatterns = List.of();
            if (jadxPath == null) jadxPath = "jadx";
            if (minAppFiles == null) minAppFiles = 3;
            if (libraryPrefixes == null) libraryPrefixes = List.of("android.", "androidx.", "kotlin.", "kotlinx.");
            if (excludePatterns == null) excludePatterns = List.of();
            if (resourcePatterns == null) resourcePatterns = List.of();
            if (maxChunkTokens == null) maxChunkTokens = 6000;
        }
    }

    public record Run(Integer repeats, Integer maxParallel) {
        public Run {
            if (repeats == null) repeats = 3;
            if (maxParallel == null) maxParallel = 4;
        }
    }

    public AppConfig {
        if (app == null || app.apkPath() == null) throw new IllegalArgumentException("app.apk_path is required");
        if (models == null || models.isEmpty()) throw new IllegalArgumentException("at least one model is required");
        if (roles == null) roles = Map.of();
        if (run == null) run = new Run(null, null);
        if (ingest == null) ingest = new Ingest(null, null, null, null, null, null, null);
        if (scan == null) scan = new Scan(null, null, null, null);
        if (lessons == null) lessons = new Lessons(null);
        for (var role : roles.entrySet()) {
            if (!models.containsKey(role.getValue())) {
                throw new IllegalArgumentException(
                        "role '" + role.getKey() + "' refers to unknown model '" + role.getValue() + "'");
            }
        }
    }

    public String modelNameFor(String role) {
        String name = roles.get(role);
        if (name == null) throw new IllegalArgumentException("no model configured for role '" + role + "'");
        return name;
    }

    public static AppConfig load(Path file) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE); // apk_path -> apkPath
        AppConfig raw = yaml.readValue(file.toFile(), AppConfig.class);

        // Resolve relative paths against the config file's folder, not the shell's cwd.
        Path base = file.toAbsolutePath().getParent();
        App a = raw.app();
        App resolved = new App(
                base.resolve(a.apkPath()).normalize(),
                base.resolve(a.workDir() != null ? a.workDir() : Path.of("work")).normalize(),
                a.groundTruth() != null ? base.resolve(a.groundTruth()).normalize() : null);
        Scan s = raw.scan();
        Scan scan = new Scan(base.resolve(s.promptsDir()).normalize(), s.evidenceLineTolerance(),
                s.minEvidenceChars(), s.categories());
        return new AppConfig(resolved, raw.models(), raw.roles(), raw.run(), raw.ingest(), scan, raw.lessons());
    }
}
