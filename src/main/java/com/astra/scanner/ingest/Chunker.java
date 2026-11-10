package com.astra.scanner.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Packs files into chunks under a token budget. Files are kept whole when possible
 * (bugs often need the whole class); a file bigger than the budget is split into line windows.
 */
public final class Chunker {

    private static final int OVERLAP_LINES = 20; // so a bug on a window boundary is seen whole at least once

    private final int maxTokens;

    public Chunker(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public List<Chunk> chunk(List<SourceFile> files) {
        List<Chunk> chunks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        var text = new StringBuilder();
        int tokens = 0;

        for (SourceFile f : files) {
            for (String piece : render(f)) {
                int t = piece.length() / 4 + 1;
                if (tokens + t > maxTokens && !names.isEmpty()) {
                    chunks.add(new Chunk("chunk-" + (chunks.size() + 1), List.copyOf(names), text.toString(), tokens));
                    names.clear();
                    text.setLength(0);
                    tokens = 0;
                }
                if (!names.contains(f.path())) names.add(f.path());
                text.append(piece);
                tokens += t;
            }
        }
        if (!names.isEmpty()) {
            chunks.add(new Chunk("chunk-" + (chunks.size() + 1), List.copyOf(names), text.toString(), tokens));
        }
        return chunks;
    }

    /** One file -> one or more rendered pieces, each with a header and line numbers. */
    private List<String> render(SourceFile f) {
        String[] lines = f.content().split("\n", -1);
        int budgetChars = maxTokens * 4 - 200;
        List<String> pieces = new ArrayList<>();

        int start = 0;
        while (start < lines.length) {
            var body = new StringBuilder();
            int end = start;
            while (end < lines.length) {
                String numbered = String.format("%5d| %s%n", end + 1, lines[end]);
                if (body.length() + numbered.length() > budgetChars && end > start) break;
                body.append(numbered);
                end++;
            }
            String header = end - start == lines.length
                    ? "=== FILE: " + f.path() + " ===\n"
                    : "=== FILE: " + f.path() + " (lines " + (start + 1) + "-" + end + ") ===\n";
            pieces.add(header + body + "\n");
            if (end >= lines.length) break;
            start = Math.max(end - OVERLAP_LINES, start + 1);
        }
        return pieces;
    }
}
