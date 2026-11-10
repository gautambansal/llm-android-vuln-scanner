package com.astra.scanner.scan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One vulnerability reported by a model. The first fields come from the model's JSON;
 * `verification`, `chunkId` and `model` are filled in by us afterwards.
 */
@JsonIgnoreProperties(ignoreUnknown = true) // models sometimes add extra keys; ignore them
public record Finding(
        String title,
        String category,
        String severity,       // critical | high | medium | low | info
        String file,
        Integer startLine,
        Integer endLine,
        String evidence,       // verbatim code quote
        String explanation,
        Verification verification,
        String chunkId,
        String model
) {
    public enum Verification {
        VERIFIED,        // quote found in the cited file near the cited lines
        LINE_CORRECTED,  // quote found in the cited file, but at another line: we fixed the line
        FILE_CORRECTED,  // quote found in a different file than cited: we fixed the path
        NOT_FOUND        // quote is nowhere in the input -> hallucinated, dropped
    }

    public Finding withVerification(Verification v, String file, Integer start, Integer end) {
        return new Finding(title, category, severity, file, start, end, evidence, explanation, v, chunkId, model);
    }

    public Finding withSource(String chunkId, String model) {
        return new Finding(title, category, severity, file, startLine, endLine, evidence, explanation,
                verification, chunkId, model);
    }

    public int severityRank() {
        return switch (severity == null ? "" : severity.toLowerCase()) {
            case "critical" -> 4;
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }
}
