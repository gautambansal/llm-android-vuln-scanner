package com.astra.scanner.eval;

import com.astra.scanner.scan.Finding;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** The answer key: known vulnerabilities (counted in recall) + accepted extras (not counted). */
public record GroundTruth(String app, String source, List<Entry> vulnerabilities, List<Entry> extras) {

    public record Entry(String id, String title, String readme, List<String> categories, List<Location> locations) {}

    /** `lines` = [start, end], or null to accept any line in the file. */
    public record Location(String file, List<Integer> lines) {}

    public GroundTruth {
        if (vulnerabilities == null) vulnerabilities = List.of();
        if (extras == null) extras = List.of();
    }

    /**
     * No answer key (e.g. an unseen app)? Use the teacher's findings as the reference.
     * Then "recall" means: how much of the teacher's work the student reproduces (agreement, not truth).
     */
    public static GroundTruth fromFindings(String label, List<Finding> reference) {
        List<Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < reference.size(); i++) {
            Finding f = reference.get(i);
            int start = f.startLine() == null ? 1 : f.startLine();
            int end = f.endLine() == null ? start : Math.max(start, f.endLine());
            entries.add(new Entry(String.format("T%02d", i + 1), f.title(), f.explanation(),
                    List.of(f.category()), List.of(new Location(f.file(), List.of(start, end)))));
        }
        return new GroundTruth("reference: " + label, "findings of scan '" + label + "' (agreement, not truth)", entries, List.of());
    }

    public static GroundTruth load(Path file) throws IOException {
        return new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true) // typos fail fast
                .readValue(file.toFile(), GroundTruth.class);
    }
}
