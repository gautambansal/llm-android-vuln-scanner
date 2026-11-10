package com.astra.scanner;

import com.astra.scanner.config.AppConfig;
import com.astra.scanner.eval.GroundTruth;
import com.astra.scanner.eval.Matcher;
import com.astra.scanner.eval.ReportWriter;
import com.astra.scanner.ingest.AppIngestor;
import com.astra.scanner.ingest.Chunk;
import com.astra.scanner.ingest.Chunker;
import com.astra.scanner.ingest.IngestedApp;
import com.astra.scanner.lessons.LeakageChecker;
import com.astra.scanner.lessons.LessonGenerator;
import com.astra.scanner.llm.LlmClient;
import com.astra.scanner.llm.LlmClientFactory;
import com.astra.scanner.scan.Finding;
import com.astra.scanner.scan.FindingParser;
import com.astra.scanner.scan.ScanResult;
import com.astra.scanner.scan.Scanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The pipeline steps, reusable by single CLI commands and by `run` (everything end to end). */
public final class Pipeline {

    private final AppConfig cfg;
    private final IngestedApp app;
    private final LlmClientFactory factory;

    public Pipeline(AppConfig cfg, Function<String, String> env) throws Exception {
        this.cfg = cfg;
        this.app = new AppIngestor(cfg).ingest(cfg.app().apkPath());
        this.factory = new LlmClientFactory(cfg, env);
    }

    public IngestedApp app() { return app; }

    private Path scansDir() { return app.workDir().resolve("scans"); }

    /** One scan. modelName = null -> the model configured for the role. lessons = null -> plain scan. */
    public ScanResult scan(String role, String modelName, String label, Path lessons) throws Exception {
        LlmClient model = modelName != null ? factory.create(modelName) : factory.forRole(role);
        // Chunk size is a property of the model: the teacher can read big pieces, a free-tier student needs small ones.
        Integer modelBudget = cfg.models().get(model.name()).maxChunkTokens();
        int budget = modelBudget != null ? modelBudget : cfg.ingest().maxChunkTokens();
        List<Chunk> chunks = new Chunker(budget).chunk(app.files());
        String lessonsText = lessons == null ? null : Files.readString(lessons);

        System.out.printf("%nScanning %s with %s (%s), %d chunks of <=%d tokens%s...%n", app.appId(), model.name(), role,
                chunks.size(), budget, lessons == null ? "" : ", with lessons from " + lessons.getFileName());
        ScanResult r = new Scanner(cfg).scan(app, chunks, model, role, label, lessonsText,
                lessons == null ? null : lessons.toString());
        Scanner.save(r, scansDir());

        System.out.printf("Done in %.0fs: %d findings (%d hallucinated & dropped), %d/%d chunks failed, %,d in + %,d out tokens%n",
                r.wallSeconds(), r.findings().size(), r.rejected().size(), r.failedChunks(), r.chunks().size(),
                r.totalInputTokens(), r.totalOutputTokens());
        for (Finding f : r.findings()) {
            System.out.printf("  [%-8s] %-26s %s:%s  %s%n", f.severity(), f.category(),
                    f.file().replaceFirst(".*/", ""), f.startLine(), f.title());
        }
        System.out.println("Saved " + scansDir().resolve(label + ".json"));
        return r;
    }

    /** Teacher findings -> general lessons (ONE call) -> leakage check -> lessons/<label>.md. */
    public Path learn(String fromScan, String label, String modelName) throws Exception {
        ScanResult source = load(fromScan);
        LlmClient teacher = modelName != null ? factory.create(modelName) : factory.forRole("teacher");
        var checker = new LeakageChecker(app);
        System.out.printf("%nGenerating lessons from %d findings of '%s' with %s (leakage check blocks %d app-specific terms)...%n",
                source.findings().size(), fromScan, teacher.name(), checker.blockedTermCount());
        LessonGenerator.Result r = new LessonGenerator(cfg).generate(source.findings(), teacher, checker);

        Path dir = app.workDir().resolve("lessons");
        Files.createDirectories(dir);
        Path md = dir.resolve(label + ".md");
        Files.writeString(md, r.renderForStudent() + "\n");
        FindingParser.JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(label + ".json").toFile(),
                Map.of("from_scan", fromScan, "teacher", teacher.name(), "kept", r.kept(), "rejected", r.rejected(),
                        "leaks", r.leaks(), "input_tokens", r.inputTokens(), "output_tokens", r.outputTokens()));

        System.out.printf("%d lessons kept, %d rejected for leaking app-specific details (%,d in / %,d out tokens)%n",
                r.kept().size(), r.rejected().size(), r.inputTokens(), r.outputTokens());
        r.leaks().forEach(l -> System.out.println("  REJECTED: \"" + l.lessonTitle() + "\" mentions " + l.terms()));
        System.out.println("\n" + r.renderForStudent() + "\nSaved " + md);
        return md;
    }

    /**
     * Score scans. labels = null -> every saved scan. What we score against:
     *   reference given      -> that scan's findings (agreement with the teacher)
     *   else ground truth    -> the real answer key
     *   else                 -> newest teacher scan among the selected ones (unseen app, no answer key)
     */
    public Path evaluate(List<String> labels, String reference, String reportName) throws Exception {
        List<String> selected = labels != null ? labels : allScanLabels();
        if (reference == null && cfg.app().groundTruth() == null) {
            reference = newestTeacherScan(selected);
            System.out.println("\nNo ground truth configured: scoring against teacher scan '" + reference
                    + "' (agreement with the teacher, not true recall).");
        }
        GroundTruth gt;
        if (reference != null) {
            gt = GroundTruth.fromFindings(reference, load(reference).findings());
            final String ref = reference;
            selected = selected.stream().filter(l -> !l.equals(ref)).toList();
        } else {
            gt = GroundTruth.load(cfg.app().groundTruth());
        }

        var matcher = new Matcher(cfg.scan().evidenceLineTolerance());
        List<ReportWriter.Scored> scored = new ArrayList<>();
        for (String l : selected) {
            ScanResult r = load(l);
            scored.add(new ReportWriter.Scored(r, matcher.evaluate(r.findings(), gt)));
        }

        System.out.printf("%n%-28s %-22s %-8s %-15s %-7s %-7s%n", "scan", "model", "lessons", "recall", "strict", "lenient");
        for (var s : scored) {
            var m = s.report();
            System.out.printf("%-28s %-22s %-8s %2d/%-2d (%3.0f%%)   %4.0f%%   %4.0f%%%n", s.scan().label(), s.scan().model(),
                    s.scan().lessonsFile() == null ? "-" : "yes", m.foundIds().size(), m.totalEntries(),
                    100 * m.recall(), 100 * m.strictPrecision(), 100 * m.lenientPrecision());
        }
        System.out.println("\n" + ReportWriter.conditionsText(scored));

        Path out = app.workDir().resolve("reports");
        Files.createDirectories(out);
        if (reportName == null) reportName = reference == null ? "report.md" : "report-vs-" + reference + ".md";
        Path report = out.resolve(reportName);
        Files.writeString(report, ReportWriter.markdown(gt, scored));
        System.out.println("Full report: " + report);
        return report;
    }

    /**
     * Everything, end to end, on whatever APK the config points at:
     * teacher scan -> lessons -> N x (student baseline, student + lessons), interleaved -> report.
     * All labels share a run prefix, so a run never mixes with older scans.
     */
    public Path run(int repeats) throws Exception {
        String prefix = "run-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + "-";
        List<String> labels = new ArrayList<>();

        String teacher = prefix + "teacher";
        scan("teacher", null, teacher, null);
        labels.add(teacher);
        Path lessons = learn(teacher, prefix + "lessons", null);

        // Interleave baseline and lessons runs, so provider slowdowns or drift hit both conditions equally.
        for (int i = 1; i <= repeats; i++) {
            String b = prefix + "student-baseline-" + i, l = prefix + "student-lessons-" + i;
            scan("student", null, b, null);
            labels.add(b);
            scan("student", null, l, lessons);
            labels.add(l);
        }
        return evaluate(labels, null, prefix + "report.md");
    }

    private ScanResult load(String label) throws Exception {
        return FindingParser.JSON.readValue(scansDir().resolve(label + ".json").toFile(), ScanResult.class);
    }

    private List<String> allScanLabels() throws Exception {
        try (var s = Files.list(scansDir())) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .map(n -> n.replaceFirst("\\.json$", "")).sorted().toList();
        }
    }

    private String newestTeacherScan(List<String> labels) throws Exception {
        String best = null, bestTime = "";
        for (String l : labels) {
            ScanResult r = load(l);
            if ("teacher".equals(r.role()) && r.startedAt().compareTo(bestTime) > 0) { best = l; bestTime = r.startedAt(); }
        }
        if (best == null) throw new IllegalStateException("no ground truth and no teacher scan to compare against; run a teacher scan first");
        return best;
    }
}
