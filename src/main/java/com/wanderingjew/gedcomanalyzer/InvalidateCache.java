package com.wanderingjew.gedcomanalyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * Delete the geni-cache file(s) for one or more people, by guid, so the next
 * GeniFetch re-fetches their updated data from Geni. Matches on each file's
 * focus guid, so it removes exactly the file where that person is the subject.
 *
 * Usage: InvalidateCache <guid> [<guid> ...] [--cache-dir <dir>]
 *   guid may be given as 6000000..., I6000000..., or @I6000000...@.
 *   cache-dir defaults to GeniClient.cacheDirFromEnv() (./geni-cache, or GENI_CACHE_DIR).
 *
 * Matches any cache-version filename (*.v*.json), not just the current
 * CACHE_VERSION, so it also cleans up orphaned files left behind by a past
 * version bump.
 */
public class InvalidateCache {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: InvalidateCache <guid> [<guid> ...] [--cache-dir <dir>]");
            System.out.println("  Deletes the cache file(s) for the given Geni guid(s) so they are refetched.");
            System.out.println("  guid may be 6000000..., I6000000..., or @I6000000...@.");
            System.out.println("  --cache-dir: optional cache directory (default ./geni-cache, or GENI_CACHE_DIR).");
            System.exit(1);
        }

        Path cacheDir = GeniClient.cacheDirFromEnv();
        Set<String> targets = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            if ("--cache-dir".equals(args[i]) && i + 1 < args.length) {
                cacheDir = Paths.get(args[++i]);
                continue;
            }
            targets.add(normalizeGuid(args[i]));
        }

        if (!Files.isDirectory(cacheDir)) {
            System.out.println("No cache directory at " + cacheDir.toAbsolutePath() + " — nothing to invalidate.");
            return;
        }
        System.out.println("Cache directory: " + cacheDir.toAbsolutePath());

        ObjectMapper mapper = new ObjectMapper();
        // Track which target guids were actually matched, rather than removing from
        // targets on first match — a guid can have more than one matching file (e.g. an
        // orphaned file from a past CACHE_VERSION bump alongside the current one), and
        // all of them need deleting, not just the first one found.
        Set<String> found = new HashSet<>();
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(cacheDir, "*.v*.json")) {
            for (Path file : files) {
                String guid;
                try {
                    JsonNode focus = mapper.readTree(file.toFile()).get("focus");
                    guid = (focus == null) ? null : focus.path("guid").asText(null);
                } catch (IOException e) {
                    continue; // skip unreadable / partially-written files
                }
                if (guid != null && targets.contains(guid)) {
                    Files.delete(file);
                    System.out.println("Deleted " + file.getFileName() + " (guid " + guid + ")");
                    found.add(guid);
                    deleted++;
                }
            }
        }

        for (String target : targets) {
            if (!found.contains(target)) {
                System.out.println("No cache file found for guid " + target
                        + " (not fetched yet, or already removed).");
            }
        }
        System.out.println("Deleted " + deleted + " cache file(s). Re-run GeniFetch with a valid token to refetch.");
    }

    private static String normalizeGuid(String s) {
        s = s.trim();
        if (s.startsWith("@") && s.endsWith("@")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.startsWith("I") || s.startsWith("i")) {
            s = s.substring(1);
        }
        return s;
    }
}
