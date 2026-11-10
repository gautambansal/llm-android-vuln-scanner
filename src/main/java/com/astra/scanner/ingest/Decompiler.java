package com.astra.scanner.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs jadx on an APK. Output is cached: if it already exists, jadx is skipped.
 *
 * WE define the output layout (via jadx's -ds / -dr flags) instead of relying on jadx's defaults,
 * so a jadx upgrade can't silently move files. The layout lives only here: SOURCES_DIR and RESOURCES_DIR.
 * Swapping in another decompiler (e.g. apktool) means producing this same layout.
 */
public final class Decompiler {

    public static final String SOURCES_DIR = "sources";
    public static final String RESOURCES_DIR = "resources";
    /** Every APK has exactly one manifest; after decoding it sits at the root of the resources dir. */
    public static final String MANIFEST = RESOURCES_DIR + "/AndroidManifest.xml";

    private Decompiler() {}

    public static Path decompile(Path apk, Path outDir, String jadxPath) throws IOException, InterruptedException {
        Path manifest = outDir.resolve(MANIFEST);
        if (Files.exists(manifest) && Files.isDirectory(outDir.resolve(SOURCES_DIR))) {
            return outDir; // already decompiled
        }
        Files.createDirectories(outDir);
        Path log = outDir.resolveSibling("jadx.log");

        Process p = new ProcessBuilder(jadxPath,
                "--output-dir-src", outDir.resolve(SOURCES_DIR).toString(),
                "--output-dir-res", outDir.resolve(RESOURCES_DIR).toString(),
                apk.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        int exit = p.waitFor();

        // jadx exits non-zero when a few methods fail to decompile ("finished with errors").
        // That's normal; the output is still usable. Only fail if the manifest is missing.
        if (!Files.exists(manifest)) {
            throw new IOException("jadx produced no " + MANIFEST + " (exit " + exit + "). Is the input a single APK"
                    + " (not a split/.xapk/.aab bundle)? See " + log);
        }
        return outDir;
    }
}
