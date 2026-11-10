package com.astra.scanner.eval;

import com.astra.scanner.scan.Finding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores findings against ground truth. Deterministic: no LLM, same input -> same score.
 *
 * A finding is COMPATIBLE with an entry when:
 *   its category is one of the entry's categories
 *   AND its file is one of the entry's locations
 *   AND (if that location has lines) its line range overlaps them, +/- tolerance.
 *
 * Recall counts BUGS: each ground-truth entry is "found" at most once, and one finding can find
 *   at most one entry. We pick the assignment that finds the MOST entries (bipartite matching),
 *   so the score never depends on the order findings arrive in.
 * Precision counts FINDINGS: a second finding of an already-found bug is still correct (DUPLICATE).
 */
public final class Matcher {

    public enum Status {
        MATCHED,     // used to find a ground-truth entry (counts for recall)
        DUPLICATE,   // compatible with an entry, but another finding already found it (correct, not counted twice)
        EXTRA,       // real issue not in the README list (accepted extra); counts only in lenient precision
        NEAR_MISS,   // right file + lines as an entry, but a category we didn't allow: shown for human review
        UNMATCHED    // nothing in the answer key: noise, or a real bug the key doesn't know about
    }

    public record FindingResult(Finding finding, Status status, String entryId) {}

    public record Report(
            int totalEntries,
            List<String> foundIds,               // entry ids found
            Map<String, Finding> foundBy,        // entry id -> the finding that found it
            List<FindingResult> findings
    ) {
        public double recall() { return totalEntries == 0 ? 0 : (double) foundIds.size() / totalEntries; }

        public long count(Status s) { return findings.stream().filter(r -> r.status() == s).count(); }

        /** Lower bound: every finding not tied to a known bug counts as wrong. */
        public double strictPrecision() {
            return findings.isEmpty() ? 0 : (double) (count(Status.MATCHED) + count(Status.DUPLICATE)) / findings.size();
        }

        /** Accepted extras count as correct too. */
        public double lenientPrecision() {
            return findings.isEmpty() ? 0
                    : (double) (count(Status.MATCHED) + count(Status.DUPLICATE) + count(Status.EXTRA)) / findings.size();
        }
    }

    private final int tolerance;

    public Matcher(int lineTolerance) {
        this.tolerance = lineTolerance;
    }

    public Report evaluate(List<Finding> findings, GroundTruth gt) {
        List<GroundTruth.Entry> entries = gt.vulnerabilities();

        // 1. Which finding could match which entry? "exact" = start line inside the range, no tolerance.
        boolean[][] exact = new boolean[entries.size()][findings.size()];
        boolean[][] compatible = new boolean[entries.size()][findings.size()];
        for (int e = 0; e < entries.size(); e++) {
            for (int f = 0; f < findings.size(); f++) {
                exact[e][f] = matchesExactly(findings.get(f), entries.get(e));
                compatible[e][f] = matches(findings.get(f), entries.get(e), true);
            }
        }

        // 2. Maximum one-to-one assignment (Kuhn's algorithm), in two passes:
        //    first exact location matches, then tolerant ones for entries still unfound.
        //    (A finding at line 91 belongs to the bug whose range contains 91, not a neighbour within tolerance.)
        int[] entryOfFinding = new int[findings.size()];
        Arrays.fill(entryOfFinding, -1);
        for (boolean[][] pass : List.of(exact, compatible)) {
            for (int e = 0; e < entries.size(); e++) {
                if (isAssigned(e, entryOfFinding)) continue;
                assign(e, pass, entryOfFinding, new boolean[findings.size()]);
            }
        }

        // 3. Label every finding.
        List<String> found = new ArrayList<>();
        Map<String, Finding> foundBy = new LinkedHashMap<>();
        List<FindingResult> results = new ArrayList<>();
        for (int f = 0; f < findings.size(); f++) {
            Finding fd = findings.get(f);
            if (entryOfFinding[f] >= 0) {
                String id = entries.get(entryOfFinding[f]).id();
                found.add(id);
                foundBy.put(id, fd);
                results.add(new FindingResult(fd, Status.MATCHED, id));
                continue;
            }
            String dupOf = firstMatch(fd, entries, true);
            if (dupOf != null) { results.add(new FindingResult(fd, Status.DUPLICATE, dupOf)); continue; }
            String extra = firstMatch(fd, gt.extras(), true);
            if (extra != null) { results.add(new FindingResult(fd, Status.EXTRA, extra)); continue; }
            String near = firstMatch(fd, entries, false);
            if (near != null) { results.add(new FindingResult(fd, Status.NEAR_MISS, near)); continue; }
            results.add(new FindingResult(fd, Status.UNMATCHED, null));
        }
        found.sort(String::compareTo);
        return new Report(entries.size(), found, foundBy, results);
    }

    /** Augmenting-path step of Kuhn's algorithm: try to give entry e a finding, re-routing earlier choices if needed. */
    private boolean assign(int e, boolean[][] compatible, int[] entryOfFinding, boolean[] visited) {
        for (int f = 0; f < entryOfFinding.length; f++) {
            if (!compatible[e][f] || visited[f]) continue;
            visited[f] = true;
            if (entryOfFinding[f] < 0 || assign(entryOfFinding[f], compatible, entryOfFinding, visited)) {
                entryOfFinding[f] = e;
                return true;
            }
        }
        return false;
    }

    private static boolean isAssigned(int e, int[] entryOfFinding) {
        for (int x : entryOfFinding) if (x == e) return true;
        return false;
    }

    /** Category OK, same file, and the finding's START line lies inside the range (no tolerance). */
    private boolean matchesExactly(Finding f, GroundTruth.Entry e) {
        if (f.category() == null || !e.categories().contains(f.category()) || f.file() == null || f.startLine() == null) return false;
        for (GroundTruth.Location loc : e.locations()) {
            if (!samePath(f.file(), loc.file())) continue;
            if (loc.lines() == null || loc.lines().size() < 2) return true;
            if (f.startLine() >= loc.lines().get(0) && f.startLine() <= loc.lines().get(1)) return true;
        }
        return false;
    }

    private String firstMatch(Finding f, List<GroundTruth.Entry> entries, boolean checkCategory) {
        for (GroundTruth.Entry e : entries) if (matches(f, e, checkCategory)) return e.id();
        return null;
    }

    boolean matches(Finding f, GroundTruth.Entry e, boolean checkCategory) {
        if (checkCategory && (f.category() == null || !e.categories().contains(f.category()))) return false;
        if (f.file() == null) return false;
        for (GroundTruth.Location loc : e.locations()) {
            if (!samePath(f.file(), loc.file())) continue;
            if (loc.lines() == null || loc.lines().size() < 2) return true;
            int fs = f.startLine() == null ? -1 : f.startLine();
            int fe = f.endLine() == null ? fs : Math.max(fs, f.endLine());
            if (fs < 0) continue;
            if (fs <= loc.lines().get(1) + tolerance && fe >= loc.lines().get(0) - tolerance) return true;
        }
        return false;
    }

    private static boolean samePath(String a, String b) {
        String x = a.replace('\\', '/'), y = b.replace('\\', '/');
        return x.equals(y) || x.endsWith("/" + y) || y.endsWith("/" + x);
    }
}
