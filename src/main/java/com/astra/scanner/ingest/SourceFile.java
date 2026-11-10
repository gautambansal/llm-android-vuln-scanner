package com.astra.scanner.ingest;

/** One input file for the scanner. `path` is relative to the decompiled folder. */
public record SourceFile(String path, Kind kind, String content) {

    public enum Kind { MANIFEST, CODE, RESOURCE }

    /** Rough token estimate (~4 chars per token). Real tokenizers differ per model, so chunks keep headroom. */
    public int approxTokens() {
        return content.length() / 4 + 1;
    }
}
