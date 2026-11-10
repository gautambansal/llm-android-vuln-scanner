package com.astra.scanner.eval;

import com.astra.scanner.scan.Finding;
import com.astra.scanner.scan.ScanResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders scan scores as Markdown: a summary table, a bug-by-scan matrix, and per-scan finding lists. */
public final class ReportWriter {

    public record Scored(ScanResult scan, Matcher.Report report) {}

    private ReportWriter() {}

    /** Scans with the same model, lessons yes/no and prompt version are repeats of one condition. */
    record Condition(String model, boolean lessons, String prompt) {
        String name() { return model + (lessons ? " + lessons" : " baseline") + " [prompt " + prompt + "]"; }
    }

    record Stats(int n, double recallMean, double recallMin, double recallMax, double precisionMean,
                 double hallucinatedMean, double tokensMean) {}

    static Map<Condition, Stats> conditions(List<Scored> scored) {
        Map<Condition, List<Scored>> groups = new LinkedHashMap<>();
        for (Scored s : scored) {
            if (!"student".equals(s.scan().role())) continue;
            if (s.scan().failedChunks() > 0) continue; // incomplete scan: part of the app was never reviewed
            var c = new Condition(s.scan().model(), s.scan().lessonsFile() != null,
                    s.scan().promptVersion() == null ? "old" : s.scan().promptVersion());
            groups.computeIfAbsent(c, k -> new ArrayList<>()).add(s);
        }
        Map<Condition, Stats> out = new LinkedHashMap<>();
        groups.forEach((c, list) -> {
            var recalls = list.stream().mapToDouble(s -> s.report().recall()).toArray();
            out.put(c, new Stats(list.size(),
                    Arrays.stream(recalls).average().orElse(0), Arrays.stream(recalls).min().orElse(0),
                    Arrays.stream(recalls).max().orElse(0),
                    list.stream().mapToDouble(s -> s.report().strictPrecision()).average().orElse(0),
                    list.stream().mapToDouble(s -> s.scan().rejected().size()).average().orElse(0),
                    list.stream().mapToDouble(s -> s.scan().totalInputTokens() + s.scan().totalOutputTokens()).average().orElse(0)));
        });
        return out;
    }

    /** Plain-text version for the terminal: per-condition means and the lift. */
    public static String conditionsText(List<Scored> scored) {
        var stats = conditions(scored);
        if (stats.isEmpty()) return "";
        var sb = new StringBuilder("Student conditions (mean over COMPLETE repeats, recall range in brackets):\n");
        long incomplete = scored.stream().filter(s -> s.scan().failedChunks() > 0).count();
        if (incomplete > 0) {
            sb.append("  ! excluded ").append(incomplete).append(" incomplete scan(s) with failed chunks: ")
              .append(scored.stream().filter(s -> s.scan().failedChunks() > 0)
                      .map(s -> s.scan().label() + " (" + s.scan().failedChunks() + "/" + s.scan().chunks().size() + " failed)").toList())
              .append('\n');
        }
        stats.forEach((c, s) -> sb.append(String.format(Locale.ROOT, "  %-60s n=%d  recall %3.0f%% [%3.0f-%3.0f]  strict prec %3.0f%%  halluc %.1f%n",
                c.name(), s.n(), 100 * s.recallMean(), 100 * s.recallMin(), 100 * s.recallMax(), 100 * s.precisionMean(), s.hallucinatedMean())));
        for (String line : lifts(stats)) sb.append("  ").append(line).append('\n');
        return sb.toString();
    }

    private static List<String> lifts(Map<Condition, Stats> stats) {
        List<String> out = new ArrayList<>();
        stats.forEach((c, withL) -> {
            if (!c.lessons()) return;
            Stats base = stats.get(new Condition(c.model(), false, c.prompt()));
            if (base == null) return;
            out.add(String.format(Locale.ROOT,
                    "LIFT %s [prompt %s]: recall %+.0f pts (%.0f%% -> %.0f%%), strict precision %+.0f pts, n=%d vs %d%s",
                    c.model(), c.prompt(), 100 * (withL.recallMean() - base.recallMean()), 100 * base.recallMean(),
                    100 * withL.recallMean(), 100 * (withL.precisionMean() - base.precisionMean()), base.n(), withL.n(),
                    base.n() < 2 || withL.n() < 2 ? "  (single runs: within noise)" : ""));
        });
        return out;
    }

    public static String markdown(GroundTruth gt, List<Scored> scored) {
        var sb = new StringBuilder();
        sb.append("# Scan report: ").append(gt.app()).append("\n\n");
        sb.append("Ground truth: ").append(gt.vulnerabilities().size()).append(" known vulnerabilities (")
          .append(gt.source()).append(").\n");
        sb.append("Recall counts bugs found; precision counts findings. Strict precision treats every finding not tied")
          .append(" to a known bug as wrong; lenient precision also accepts listed extras.\n\n");

        var stats = conditions(scored);
        if (!stats.isEmpty()) {
            sb.append("## Student conditions (repeats aggregated)\n\n");
            sb.append("Only complete scans (no failed chunks) are aggregated: a failed chunk is code that was never reviewed.\n\n");
            sb.append("| Condition | Runs | Recall mean | Recall range | Strict prec. mean | Hallucinated (mean) | Tokens (mean) |\n");
            sb.append("|---|---|---|---|---|---|---|\n");
            stats.forEach((c, s) -> sb.append(String.format(Locale.ROOT, "| %s | %d | %.0f%% | %.0f–%.0f%% | %.0f%% | %.1f | %,.0f |%n",
                    c.name(), s.n(), 100 * s.recallMean(), 100 * s.recallMin(), 100 * s.recallMax(),
                    100 * s.precisionMean(), s.hallucinatedMean(), s.tokensMean())));
            sb.append('\n');
            for (String line : lifts(stats)) sb.append("- **").append(line).append("**\n");
            sb.append('\n');
        }

        sb.append("## Summary\n\n");
        sb.append("| Scan | Model | Lessons | Recall | Strict prec. | Lenient prec. | Findings | Dup | Extra | Near-miss | Unmatched | Halluc. dropped | Failed chunks | Tokens in/out | Wall s |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Scored s : scored) {
            ScanResult r = s.scan();
            Matcher.Report m = s.report();
            sb.append(String.format(Locale.ROOT,
                    "| %s | %s | %s | **%d/%d (%.0f%%)** | %.0f%% | %.0f%% | %d | %d | %d | %d | %d | %d | %d/%d | %,d / %,d | %.0f |%n",
                    r.label(), r.model(), r.lessonsFile() == null ? "-" : "yes",
                    m.foundIds().size(), m.totalEntries(), 100 * m.recall(),
                    100 * m.strictPrecision(), 100 * m.lenientPrecision(),
                    r.findings().size(), m.count(Matcher.Status.DUPLICATE), m.count(Matcher.Status.EXTRA),
                    m.count(Matcher.Status.NEAR_MISS), m.count(Matcher.Status.UNMATCHED), r.rejected().size(),
                    r.failedChunks(), r.chunks().size(), r.totalInputTokens(), r.totalOutputTokens(), r.wallSeconds()));
        }

        sb.append("\n## Which bugs each scan found\n\n| Bug |");
        scored.forEach(s -> sb.append(' ').append(s.scan().label()).append(" |"));
        sb.append("\n|---|");
        scored.forEach(s -> sb.append("---|"));
        sb.append('\n');
        for (GroundTruth.Entry e : gt.vulnerabilities()) {
            sb.append("| ").append(e.id()).append(' ').append(e.title()).append(" |");
            for (Scored s : scored) sb.append(s.report().foundIds().contains(e.id()) ? " ✅ |" : " · |");
            sb.append('\n');
        }

        for (Scored s : scored) {
            sb.append("\n## Findings: ").append(s.scan().label()).append("\n\n");
            sb.append("| Status | Bug | Category | Location | Title |\n|---|---|---|---|---|\n");
            for (Matcher.FindingResult fr : s.report().findings()) {
                Finding f = fr.finding();
                sb.append("| ").append(fr.status()).append(" | ").append(fr.entryId() == null ? "" : fr.entryId())
                  .append(" | ").append(f.category()).append(" | ")
                  .append(f.file().replaceFirst(".*/", "")).append(':').append(f.startLine())
                  .append(" | ").append(f.title().replace("|", "/")).append(" |\n");
            }
        }
        return sb.toString();
    }
}
