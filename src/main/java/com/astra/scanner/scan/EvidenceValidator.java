package com.astra.scanner.scan;

import com.astra.scanner.ingest.SourceFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Guardrail against hallucinated findings: the model's evidence quote must really exist in the code.
 * Proves the code EXISTS, not that the bug is REAL; that's what precision against ground truth measures.
 */
public final class EvidenceValidator {

    private final Map<String, SourceFile> filesByPath;
    private final int lineTolerance;
    private final int minChars;

    public EvidenceValidator(List<SourceFile> files, int lineTolerance, int minChars) {
        this.filesByPath = files.stream().collect(Collectors.toMap(SourceFile::path, Function.identity()));
        this.lineTolerance = lineTolerance;
        this.minChars = minChars;
    }

    public Finding validate(Finding f) {
        List<String> quote = significantLines(f.evidence());
        if (quote.isEmpty()) return f.withVerification(Finding.Verification.NOT_FOUND, f.file(), f.startLine(), f.endLine());

        // 1. Look in the cited file first.
        SourceFile cited = resolve(f.file());
        if (cited != null) {
            int line = locate(cited, quote);
            if (line > 0) {
                boolean close = f.startLine() != null && Math.abs(f.startLine() - line) <= lineTolerance;
                if (close) return f.withVerification(Finding.Verification.VERIFIED, cited.path(), f.startLine(), f.endLine());
                return f.withVerification(Finding.Verification.LINE_CORRECTED, cited.path(), line, shiftEnd(f, line));
            }
        }
        // 2. Wrong file? Search all ingested files.
        for (SourceFile other : filesByPath.values()) {
            if (other == cited) continue;
            int line = locate(other, quote);
            if (line > 0) return f.withVerification(Finding.Verification.FILE_CORRECTED, other.path(), line, shiftEnd(f, line));
        }
        return f.withVerification(Finding.Verification.NOT_FOUND, f.file(), f.startLine(), f.endLine());
    }

    /** Models cite paths loosely ("WebViewActivity.java", "com/x/Y.java"): match by exact path, then suffix. */
    private SourceFile resolve(String path) {
        if (path == null || path.isBlank()) return null;
        String p = path.strip().replace('\\', '/').replaceFirst("^/+", "");
        if (filesByPath.containsKey(p)) return filesByPath.get(p);
        List<SourceFile> hits = filesByPath.values().stream()
                .filter(s -> s.path().endsWith("/" + p) || s.path().equals(p)).toList();
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /** First line (1-based) where every significant quote line appears, in order; 0 if not found. */
    private int locate(SourceFile file, List<String> quote) {
        String[] lines = file.content().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!norm(lines[i]).contains(quote.get(0))) continue;
            int cursor = i;
            boolean all = true;
            for (int q = 1; q < quote.size() && all; q++) {
                all = false;
                // Next piece may be on the SAME line (split at "…") or a later one (elided "..." lines).
                for (int j = cursor; j < Math.min(lines.length, cursor + 40); j++) {
                    if (norm(lines[j]).contains(quote.get(q))) { cursor = j; all = true; break; }
                }
            }
            if (all) return i + 1;
        }
        return locateAcrossLines(lines, String.join(" ", quote));
    }

    /** Fallback: the quote may span several real lines (e.g. XML attributes split by jadx). */
    private static int locateAcrossLines(String[] lines, String joinedQuote) {
        final int span = 15;
        for (int i = 0; i < lines.length; i++) {
            if (!window(lines, i, span).contains(joinedQuote)) continue;
            while (i + 1 < lines.length && window(lines, i + 1, span).contains(joinedQuote)) i++; // move to where it starts
            return i + 1;
        }
        return 0;
    }

    private static String window(String[] lines, int from, int span) {
        var sb = new StringBuilder();
        for (int j = from; j < Math.min(lines.length, from + span); j++) sb.append(lines[j]).append(' ');
        return norm(sb.toString());
    }

    /**
     * Evidence pieces worth checking: strip our "  12| " prefixes, split on "..." elisions,
     * drop pieces that are pure punctuation ("}", ");"). The quote AS A WHOLE must be at least
     * minChars long; individual lines may be short (XML attributes split over lines are).
     */
    List<String> significantLines(String evidence) {
        if (evidence == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String raw : evidence.split("\n")) {
            String s = norm(raw.replaceFirst("^\\s*\\d+\\|\\s?", ""));
            for (String part : s.split("\\.\\.\\.|…")) {
                String p = part.strip();
                if (p.chars().filter(Character::isLetterOrDigit).count() >= 2) out.add(p);
            }
        }
        int total = out.stream().mapToInt(p -> p.replace(" ", "").length()).sum();
        return total >= minChars ? out : List.of();
    }

    private static int shiftEnd(Finding f, int newStart) {
        if (f.startLine() == null || f.endLine() == null) return newStart;
        return newStart + Math.max(0, f.endLine() - f.startLine());
    }

    private static String norm(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}
