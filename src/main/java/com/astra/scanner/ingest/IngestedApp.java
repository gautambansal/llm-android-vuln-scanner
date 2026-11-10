package com.astra.scanner.ingest;

import java.nio.file.Path;
import java.util.List;

/** Everything the scanners need about one app. */
public record IngestedApp(
        String appId,             // e.g. "InsecureShop-a83298ae4a37": APK name + content hash
        Path workDir,             // where decompiled output and results for this APK live
        ManifestInfo manifest,
        List<String> codeRoots,   // packages treated as "the app's own code"
        String rootStrategy,      // how codeRoots were chosen (for the report)
        List<SourceFile> files,
        List<Chunk> chunks
) {
    public int totalTokens() {
        return chunks.stream().mapToInt(Chunk::approxTokens).sum();
    }
}
