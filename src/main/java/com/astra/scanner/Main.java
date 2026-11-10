package com.astra.scanner;

import com.astra.scanner.config.AppConfig;
import com.astra.scanner.ingest.AppIngestor;
import com.astra.scanner.ingest.Chunk;
import com.astra.scanner.ingest.IngestedApp;
import com.astra.scanner.ingest.ManifestInfo;
import com.astra.scanner.ingest.SourceFile;
import com.astra.scanner.llm.LlmClientFactory;
import com.astra.scanner.llm.LlmException;
import com.astra.scanner.llm.LlmResponse;
import io.github.cdimascio.dotenv.Dotenv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** CLI entry point: parses arguments and delegates to Pipeline. */
public final class Main {

    private static final String USAGE = """
            usage: astra-scanner <command> [--config config.yaml]
              run [--repeats N]          everything end to end: teacher -> lessons -> N x (baseline, +lessons) -> report
              check-models               ping every configured model
              ingest                     decompile + filter + chunk; saves what the models will see
              scan --role teacher|student [--model name] [--label name] [--lessons lessons.md]
              learn --from <teacher-scan-label> [--model name] [--label name]
              evaluate [--scans a,b] [--reference teacher-label]   (no ground truth -> newest teacher scan)""";

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "help";
        Map<String, String> flags = parseFlags(args);
        Path configPath = Path.of(flags.getOrDefault("config", "config.yaml"));

        switch (command) {
            case "check-models" -> checkModels(configPath);
            case "ingest" -> ingest(configPath);
            case "scan" -> {
                String role = required(flags, "role");
                String label = flags.getOrDefault("label", role + (flags.containsKey("lessons") ? "-lessons" : ""));
                pipeline(configPath).scan(role, flags.get("model"), label,
                        flags.containsKey("lessons") ? Path.of(flags.get("lessons")) : null);
            }
            case "learn" -> {
                String from = required(flags, "from");
                pipeline(configPath).learn(from, flags.getOrDefault("label", "lessons-from-" + from), flags.get("model"));
            }
            case "evaluate" -> pipeline(configPath).evaluate(
                    flags.containsKey("scans") ? Arrays.stream(flags.get("scans").split(",")).map(String::strip).toList() : null,
                    flags.get("reference"), null);
            case "run" -> {
                AppConfig cfg = AppConfig.load(configPath);
                int repeats = flags.containsKey("repeats") ? Integer.parseInt(flags.get("repeats")) : cfg.run().repeats();
                new Pipeline(cfg, env()).run(repeats);
            }
            default -> System.out.println(USAGE);
        }
    }

    private static Pipeline pipeline(Path configPath) throws Exception {
        return new Pipeline(AppConfig.load(configPath), env());
    }

    private static String required(Map<String, String> flags, String name) {
        String v = flags.get(name);
        if (v == null) throw new IllegalArgumentException("--" + name + " is required\n" + USAGE);
        return v;
    }

    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) flags.put(args[i].substring(2), args[++i]);
            else throw new IllegalArgumentException("unexpected argument '" + args[i] + "'\n" + USAGE);
        }
        return flags;
    }

    private static Function<String, String> env() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load(); // .env first, then real env vars
        return dotenv::get;
    }

    private static void ingest(Path configPath) throws Exception {
        AppConfig cfg = AppConfig.load(configPath);
        IngestedApp app = new AppIngestor(cfg).ingest(cfg.app().apkPath());
        ManifestInfo m = app.manifest();

        System.out.println("App:        " + app.appId() + "  (package " + m.packageName() + ", targetSdk " + m.targetSdk() + ")");
        System.out.println("Code roots: " + app.codeRoots() + "  <- " + app.rootStrategy());
        System.out.println("Components: " + m.components().size() + " declared, " + m.exportedComponents().size() + " exported");
        for (SourceFile.Kind k : SourceFile.Kind.values()) {
            var files = app.files().stream().filter(f -> f.kind() == k).toList();
            System.out.printf("%-10s  %3d files, ~%,d tokens%n", k, files.size(),
                    files.stream().mapToInt(SourceFile::approxTokens).sum());
        }
        System.out.printf("Chunks:     %d (max %d tokens each), ~%,d tokens total%n",
                app.chunks().size(), cfg.ingest().maxChunkTokens(), app.totalTokens());

        // Save what the model will see, so a human can inspect it.
        Path out = app.workDir().resolve("ingest");
        Files.createDirectories(out);
        Files.writeString(out.resolve("attack-surface.txt"), m.toPromptSummary());
        for (Chunk c : app.chunks()) Files.writeString(out.resolve(c.id() + ".txt"), c.text());
        System.out.println("\nAttack surface:\n" + m.toPromptSummary());
        System.out.println("Saved to " + out);
    }

    private static void checkModels(Path configPath) throws Exception {
        AppConfig cfg = AppConfig.load(configPath);
        var factory = new LlmClientFactory(cfg, env());
        for (String name : cfg.models().keySet()) {
            try {
                LlmResponse r = factory.create(name).complete("You are terse.", "Reply with exactly this JSON: {\"ok\": true}");
                System.out.printf("OK    %-22s %-6s %d+%d tok  %.1fs%n",
                        name, r.text().strip(), r.inputTokens(), r.outputTokens(), r.latencySeconds());
            } catch (LlmException e) {
                System.out.printf("FAIL  %-22s %s%n", name, e.getMessage());
            }
        }
    }
}
