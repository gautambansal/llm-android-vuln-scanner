package com.astra.scanner.ingest;

import java.util.List;

/**
 * A group of files (or one slice of a big file) small enough for one model call.
 * `text` has a header per file and line numbers, so the model can cite exact locations.
 */
public record Chunk(String id, List<String> files, String text, int approxTokens) {}
