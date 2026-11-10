package com.astra.scanner.scan;

import com.astra.scanner.config.AppConfig;
import com.astra.scanner.ingest.Chunk;
import com.astra.scanner.ingest.IngestedApp;
import com.astra.scanner.llm.LlmClient;
import com.astra.scanner.llm.LlmException;
import com.astra.scanner.llm.LlmResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * Runs one model over every chunk of an app (in parallel, bounded), then
 * parses -> validates evidence -> deduplicates. Same code for teacher, student, and student+lessons.
 */
public final class Scanner {

    private final AppConfig.Scan cfg;
    private final int maxParallel;
    private final String systemTemplate;
    private final String userTemplate;

    public Scanner(AppConfig config) throws IOException {
        this.cfg = config.scan();
        this.maxParallel = config.run().maxParallel();
        this.systemTemplate = Files.readString(cfg.promptsDir().resolve("scan-system.md"));
        this.userTemplate = Files.readString(cfg.promptsDir().resolve("scan-user.md"));
    }

    /**
     * @param chunks  the app's files cut to THIS model's budget (big for the teacher, small for the student)
     * @param lessons general rules from the teacher, or null for a plain scan.
     */
    public ScanResult scan(IngestedApp app, List<Chunk> chunks, LlmClient model, String role, String label,
                           String lessons, String lessonsFile) throws InterruptedException {
        String system = buildSystemPrompt(lessons);
        String surface = app.manifest().toPromptSummary();
        var validator = new EvidenceValidator(app.files(), cfg.evidenceLineTolerance(), cfg.minEvidenceChars());

        Instant started = Instant.now();
        long t0 = System.nanoTime();
        var gate = new Semaphore(maxParallel);
        List<ChunkOutcome> outcomes = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ChunkOutcome>> futures = new ArrayList<>();
            for (Chunk chunk : chunks) {
                String user = userTemplate.replace("{{attack_surface}}", surface).replace("{{chunk}}", chunk.text());
                futures.add(pool.submit(() -> {
                    gate.acquire();
                    try { return scanChunk(model, system, user, chunk.id()); }
                    finally { gate.release(); }
                }));
            }
            for (Future<ChunkOutcome> f : futures) {
                try { outcomes.add(f.get()); }
                catch (Exception e) { throw new IllegalStateException("chunk task crashed", e); }
            }
        }

        List<Finding> verified = new ArrayList<>();
        List<Finding> rejected = new ArrayList<>();
        List<ScanResult.ChunkStat> stats = new ArrayList<>();
        for (ChunkOutcome o : outcomes) {
            stats.add(o.stat());
            for (Finding raw : o.findings()) {
                Finding v = validator.validate(raw.withSource(o.stat().chunkId(), model.name()));
                (v.verification() == Finding.Verification.NOT_FOUND ? rejected : verified).add(v);
            }
        }
        double wall = (System.nanoTime() - t0) / 1e9;
        return new ScanResult(label, role, model.name(), lessonsFile, promptVersion(), app.appId(), started.toString(), wall,
                stats, dedupe(verified), rejected);
    }

    private record ChunkOutcome(ScanResult.ChunkStat stat, List<Finding> findings) {}

    /** One chunk: call the model; if the reply isn't valid JSON, ask once more. Never throws. */
    private ChunkOutcome scanChunk(LlmClient model, String system, String user, String chunkId) {
        long in = 0, out = 0;
        double latency = 0;
        String lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                LlmResponse r = model.complete(system, user);
                in += r.inputTokens();
                out += r.outputTokens();
                latency += r.latencySeconds();
                List<Finding> findings = FindingParser.parse(r.text());
                return new ChunkOutcome(new ScanResult.ChunkStat(chunkId, in, out, latency, findings.size(), null), findings);
            } catch (FindingParser.ParseException e) {
                lastError = "parse: " + e.getMessage();
            } catch (LlmException e) {
                lastError = "llm: " + e.getMessage();
                break; // SDK already retried transient errors; don't double-retry
            }
        }
        System.err.println("  ! " + model.name() + " " + chunkId + " failed: " + lastError);
        return new ChunkOutcome(new ScanResult.ChunkStat(chunkId, in, out, latency, 0, lastError), List.of());
    }

    /** First 8 hex chars of SHA-256 over both prompt templates. */
    public String promptVersion() {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(systemTemplate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update(userTemplate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(md.digest()).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String buildSystemPrompt(String lessons) {
        String categories = cfg.categories().entrySet().stream()
                .map(e -> "- " + e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
        String lessonBlock = (lessons == null || lessons.isBlank()) ? "" :
                "Checklist of lessons from a senior reviewer. Apply each one to the code:\n" + lessons.strip() + "\n";
        return systemTemplate.replace("{{categories}}", categories).replace("{{lessons}}", lessonBlock);
    }

    /**
     * Overlapping chunks and repeated reports produce duplicates. Two findings are the same if they
     * share file + category and their line ranges are within the tolerance. Keep the most severe.
     */
    private List<Finding> dedupe(List<Finding> findings) {
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator.comparingInt(Finding::severityRank).reversed());
        List<Finding> kept = new ArrayList<>();
        for (Finding f : sorted) {
            boolean dup = kept.stream().anyMatch(k -> k.file().equals(f.file())
                    && String.valueOf(k.category()).equals(String.valueOf(f.category()))
                    && overlaps(k, f));
            if (!dup) kept.add(f);
        }
        kept.sort(Comparator.comparing(Finding::file).thenComparing(f -> f.startLine() == null ? 0 : f.startLine()));
        return kept;
    }

    private boolean overlaps(Finding a, Finding b) {
        int as = a.startLine() == null ? 0 : a.startLine(), bs = b.startLine() == null ? 0 : b.startLine();
        int ae = a.endLine() == null ? as : a.endLine(), be = b.endLine() == null ? bs : b.endLine();
        int tol = cfg.evidenceLineTolerance();
        return as <= be + tol && bs <= ae + tol;
    }

    public static void save(ScanResult result, Path dir) throws IOException {
        Files.createDirectories(dir);
        FindingParser.JSON.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve(result.label() + ".json").toFile(), result);
    }
}
