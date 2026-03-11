package com.optiportal.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.logging.Logger;

/**
 * Attempts to automatically locate warp and gravestone data files on the
 * host filesystem so the operator doesn't need to hard-code paths in config.json.
 *
 * <h3>Search strategy</h3>
 * <ol>
 *   <li>Walk up from the plugin's data folder to find a plausible server root
 *       (the highest directory that still looks like it's part of the game installation).
 *   <li>From that root, do a bounded depth-first walk looking for files that match
 *       known filename patterns for each target.
 *   <li>If multiple candidates are found, prefer the one with the shortest path
 *       (usually the most "central" location rather than a backup copy).
 * </ol>
 *
 * <h3>Candidate filename patterns</h3>
 * <ul>
 *   <li>Warps: {@code warps.json}
 *   <li>Gravestones: {@code gravestones.json}, {@code gravestone.json}, {@code graves.json}
 * </ul>
 *
 * The search is capped at {@link #MAX_DEPTH} directory levels and
 * {@link #MAX_FILES_VISITED} total entries to prevent runaway scans on large filesystems.
 */
public class PathDiscovery {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    /** How many directory levels to descend during the filesystem walk. */
    private static final int MAX_DEPTH = 8;

    /** Bail out after visiting this many filesystem entries (prevents hangs on huge trees). */
    private static final int MAX_FILES_VISITED = 50_000;

    /** Directory names that suggest we've wandered outside the server installation. */
    private static final Set<String> STOP_DIRS = Set.of(
            "proc", "sys", "dev", "run", "boot", "snap",
            "node_modules", ".git", "lost+found"
    );

    /** Filename patterns for warps data (case-insensitive). */
    private static final List<String> WARP_FILENAMES = List.of(
            "warps.json"
    );

    /** Filename patterns for gravestone data (case-insensitive). */
    private static final List<String> GRAVESTONE_FILENAMES = List.of(
            "gravestones.json",
            "gravestone.json",
            "graves.json"
    );

    /**
     * Result of a discovery run. Both fields are null if the file was not found.
     */
    public static class DiscoveryResult {
        public final String warpsPath;
        public final String gravestonesPath;
        public final String searchRoot;

        DiscoveryResult(String warpsPath, String gravestonesPath, String searchRoot) {
            this.warpsPath       = warpsPath;
            this.gravestonesPath = gravestonesPath;
            this.searchRoot      = searchRoot;
        }

        public boolean foundWarps()       { return warpsPath != null; }
        public boolean foundGravestones() { return gravestonesPath != null; }
        public boolean foundAll()         { return foundWarps() && foundGravestones(); }
    }

    /**
     * Run path discovery starting from the plugin's data folder.
     *
     * @param dataFolder the plugin's data folder (e.g. {@code plugins/OptiPortal/})
     * @return discovery result — check {@link DiscoveryResult#foundWarps()} etc. before using paths
     */
    public static DiscoveryResult discover(File dataFolder) {
        File searchRoot = findSearchRoot(dataFolder);
        LOG.info("[OptiPortal] PathDiscovery: scanning from " + searchRoot.getAbsolutePath());

        List<String> warpCandidates       = new ArrayList<>();
        List<String> gravestoneCandidates = new ArrayList<>();
        int[] visited = {0};

        try {
            Files.walkFileTree(searchRoot.toPath(), EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (visited[0]++ > MAX_FILES_VISITED) return FileVisitResult.TERMINATE;
                            String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                            if (STOP_DIRS.contains(name.toLowerCase())) return FileVisitResult.SKIP_SUBTREE;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (visited[0]++ > MAX_FILES_VISITED) return FileVisitResult.TERMINATE;
                            String name = file.getFileName().toString().toLowerCase();

                            if (WARP_FILENAMES.contains(name)) {
                                warpCandidates.add(file.toAbsolutePath().toString());
                            }
                            if (GRAVESTONE_FILENAMES.contains(name)) {
                                gravestoneCandidates.add(file.toAbsolutePath().toString());
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE; // skip unreadable paths silently
                        }
                    });
        } catch (IOException e) {
            LOG.warning("[OptiPortal] PathDiscovery: walk error: " + e.getMessage());
        }

        String warpsPath       = pickBest(warpCandidates);
        String gravestonesPath = pickBest(gravestoneCandidates);

        if (warpsPath != null) {
            LOG.info("[OptiPortal] PathDiscovery: found warps at " + warpsPath);
        } else {
            LOG.warning("[OptiPortal] PathDiscovery: warps.json not found under " + searchRoot.getAbsolutePath()
                    + " — set warps.sourcePath in config.json manually.");
        }

        if (gravestonesPath != null) {
            LOG.info("[OptiPortal] PathDiscovery: found gravestones at " + gravestonesPath);
        } else {
            LOG.info("[OptiPortal] PathDiscovery: gravestones file not found (Gravestones mod may not be installed).");
        }

        return new DiscoveryResult(warpsPath, gravestonesPath, searchRoot.getAbsolutePath());
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Walk upward from the plugin data folder until we hit the filesystem root
     * or a directory that looks like a reasonable scan boundary (e.g. contains
     * a {@code plugins/} or {@code mods/} subdirectory, suggesting it's the
     * server root).
     */
    private static File findSearchRoot(File dataFolder) {
        File candidate = dataFolder.getAbsoluteFile();

        // Walk up looking for a directory that contains known server-layout markers
        File current = candidate;
        while (current != null && current.getParentFile() != null) {
            File parent = current.getParentFile();
            if (looksLikeServerRoot(parent)) {
                return parent;
            }
            current = parent;
        }

        // Fallback: go up 4 levels from the data folder (plugins/PreloadPlugin -> server root is ~2 up)
        File root = dataFolder.getAbsoluteFile();
        for (int i = 0; i < 4 && root.getParentFile() != null; i++) {
            root = root.getParentFile();
        }
        return root;
    }

    /**
     * Returns true if a directory looks like a Hytale dedicated server root.
     *
     * <p>Expected server layout (from the Hytale server manual):
     * <pre>
     *   .cache/       optimized file cache
     *   logs/         server log files
     *   mods/         installed mods
     *   universe/     world and player save data
     *     worlds/     per-world chunk data
     *   bans.json
     *   config.json
     *   permissions.json
     *   whitelist.json
     * </pre>
     *
     * Requires at least 2 strong markers to avoid false-positives on
     * intermediate directories.
     */
    private static boolean looksLikeServerRoot(File dir) {
        if (!dir.isDirectory()) return false;
        String[] children = dir.list();
        if (children == null) return false;
        Set<String> names = new HashSet<>();
        for (String c : children) names.add(c.toLowerCase());

        int score = 0;
        if (names.contains("plugins"))        score++;
        if (names.contains("mods"))            score++;
        if (names.contains("universe"))        score++;
        if (names.contains("logs"))            score++;
        if (names.contains("config.json"))     score++;
        if (names.contains("whitelist.json"))  score++;
        if (names.contains("permissions.json")) score++;
        if (names.contains("bans.json"))       score++;
        return score >= 2;
    }

    /**
     * Given a list of candidate paths, return the "best" one.
     * Prefers shortest non-backup path — more central location wins.
     */
    private static String pickBest(List<String> candidates) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        List<String> nonBackup = candidates.stream().filter(p -> !isBackupPath(p)).toList();
        List<String> pool = nonBackup.isEmpty() ? candidates : nonBackup;
        return pool.stream().min(Comparator.comparingInt(String::length)).orElse(null);
    }

    private static boolean isBackupPath(String path) {
        String lower = path.toLowerCase();
        return lower.contains("backup") || lower.contains("_old") || lower.contains("archive");
    }
}