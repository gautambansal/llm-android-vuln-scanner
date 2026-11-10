package com.astra.scanner.eval;

import com.astra.scanner.scan.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.astra.scanner.eval.Matcher.Status.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MatcherTest {

    private static final String WV = "sources/com/app/WebViewActivity.java";

    // Two bugs in the same file with the same category: only line ranges tell them apart.
    private static final GroundTruth GT = new GroundTruth("app", "test", List.of(
            entry("GT02", List.of("webview_url_loading"), WV, 98, 107),
            entry("GT03", List.of("webview_url_loading"), WV, 86, 96),
            entry("GT10", List.of("cloud_misconfig", "hardcoded_secret"), "resources/res/values/strings.xml", 33, 33),
            new GroundTruth.Entry("GT17", "webview settings", "", List.of("webview_insecure_settings"), List.of(
                    new GroundTruth.Location(WV, List.of(59, 68)),
                    new GroundTruth.Location("sources/com/app/Other.java", List.of(64, 73))))
    ), List.of(entry("X01", List.of("manifest_misconfig"), "resources/AndroidManifest.xml", 24, 27)));

    private static GroundTruth.Entry entry(String id, List<String> cats, String file, int s, int e) {
        return new GroundTruth.Entry(id, id, "", cats, List.of(new GroundTruth.Location(file, List.of(s, e))));
    }

    private static Finding f(String category, String file, int line) {
        return new Finding("t", category, "high", file, line, line, "x", "e", Finding.Verification.VERIFIED, "c", "m");
    }

    private final Matcher matcher = new Matcher(5);

    @Test void categoryOnlyWouldInflate_lineRangesPreventIt() {
        // One finding at line 107 is near GT02 only. It must NOT also "find" GT03.
        var r = matcher.evaluate(List.of(f("webview_url_loading", WV, 107)), GT);
        assertEquals(List.of("GT02"), r.foundIds());
    }

    @Test void oneFindingCannotClaimTwoBugs_butTwoFindingsCanFindBoth() {
        // Line 99 overlaps both ranges (with tolerance). Alone it finds one bug; with line 91 both are found.
        assertEquals(1, matcher.evaluate(List.of(f("webview_url_loading", WV, 99)), GT).foundIds().size());
        var both = matcher.evaluate(List.of(f("webview_url_loading", WV, 99), f("webview_url_loading", WV, 91)), GT);
        assertEquals(List.of("GT02", "GT03"), both.foundIds());
    }

    @Test void assignmentIsOptimalNotGreedy() {
        // Order matters for a greedy matcher; Kuhn's algorithm still finds both bugs.
        var r = matcher.evaluate(List.of(f("webview_url_loading", WV, 91), f("webview_url_loading", WV, 99)), GT);
        assertEquals(2, r.foundIds().size());
    }

    @Test void exactLocationWinsOverToleranceNeighbour() {
        // Real case: teacher's "Flawed Domain Validation" at lines 91-95. Within tolerance of BOTH bugs,
        // but line 91 is inside GT03's range, so it must be credited to GT03 (weak host check), not GT02.
        var finding = new Finding("Flawed Domain Validation", "webview_url_loading", "high", WV, 91, 95,
                "x", "e", Finding.Verification.VERIFIED, "c", "m");
        assertEquals(List.of("GT03"), matcher.evaluate(List.of(finding), GT).foundIds());
    }

    @Test void eitherAcceptedCategoryMatches() {
        assertEquals(List.of("GT10"), matcher.evaluate(List.of(f("cloud_misconfig", "strings.xml", 33)), GT).foundIds());
        assertEquals(List.of("GT10"), matcher.evaluate(List.of(f("hardcoded_secret", "strings.xml", 33)), GT).foundIds());
    }

    @Test void secondInstanceOfFoundBugIsDuplicateNotFalsePositive() {
        var r = matcher.evaluate(List.of(
                f("webview_insecure_settings", WV, 68),
                f("webview_insecure_settings", "sources/com/app/Other.java", 73)), GT);
        assertEquals(List.of("GT17"), r.foundIds());
        assertEquals(1, r.count(DUPLICATE));
        assertEquals(1.0, r.strictPrecision());
    }

    @Test void statusesForExtrasNearMissesAndNoise() {
        var r = matcher.evaluate(List.of(
                f("manifest_misconfig", "AndroidManifest.xml", 24),   // real, not in README -> EXTRA
                f("insecure_logging", WV, 91),                        // right place, wrong category -> NEAR_MISS
                f("weak_crypto", "sources/com/app/Nope.java", 3)),    // nothing -> UNMATCHED
                GT);
        assertEquals(List.of(EXTRA, NEAR_MISS, UNMATCHED), r.findings().stream().map(Matcher.FindingResult::status).toList());
        assertEquals(0.0, r.recall());
        assertEquals(0.0, r.strictPrecision());
        assertEquals(1.0 / 3, r.lenientPrecision(), 1e-9);
    }

    @Test void wrongFileDoesNotMatch() {
        assertEquals(0, matcher.evaluate(List.of(f("webview_url_loading", "sources/com/app/Other.java", 91)), GT).foundIds().size());
    }
}
