package com.astra.scanner.scan;

import java.util.List;

/** Everything about one scan run: findings plus the numbers we report (cost, latency, failures). */
public record ScanResult(
        String label,             // e.g. "student-baseline-1"
        String role,              // teacher | student
        String model,             // config entry name
        String lessonsFile,       // null for a scan without lessons
        String promptVersion,     // short hash of the scan prompt templates (results from different prompts aren't comparable)
        String appId,
        String startedAt,
        double wallSeconds,
        List<ChunkStat> chunks,
        List<Finding> findings,   // verified (incl. corrected), deduplicated
        List<Finding> rejected    // evidence NOT_FOUND: hallucinated
) {
    public record ChunkStat(String chunkId, long inputTokens, long outputTokens, double latencySeconds,
                            int rawFindings, String error) {}

    public long totalInputTokens() { return chunks.stream().mapToLong(ChunkStat::inputTokens).sum(); }
    public long totalOutputTokens() { return chunks.stream().mapToLong(ChunkStat::outputTokens).sum(); }
    public long failedChunks() { return chunks.stream().filter(c -> c.error() != null).count(); }
}
