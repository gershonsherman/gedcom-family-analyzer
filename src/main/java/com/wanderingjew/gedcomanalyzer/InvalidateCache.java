package com.wanderingjew.gedcomanalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delete the geni-cache file(s) for one or more people, by guid, so the next
 * GeniFetch re-fetches their updated data from Geni. Matches on each file's
 * focus guid, so it removes exactly the file where that person is the subject.
 *
 * Usage: InvalidateCache <guid>[,<guid>...] [<guid>[,<guid>...] ...] [--cache-dir <dir>]
 *   guid may be given as 6000000..., I6000000..., or @I6000000...@. Multiple guids can
 *   be comma-separated within one arg, space-separated as multiple args, or both.
 *   cache-dir defaults to GeniClient.cacheDirFromEnv() (./geni-cache, or GENI_CACHE_DIR).
 *
 * Matches any cache-version filename (*.v*.json), not just the current
 * CACHE_VERSION, so it also cleans up orphaned files left behind by a past
 * version bump.
 */
public class InvalidateCache {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: InvalidateCache <guid>[,<guid>...] [<guid>[,<guid>...] ...] [--cache-dir <dir>]");
            System.out.println("  Deletes the cache file(s) for the given Geni guid(s) so they are refetched.");
            System.out.println("  guid may be 6000000..., I6000000..., or @I6000000...@.");
            System.out.println("  Multiple guids may be comma-separated within one arg, space-separated as");
            System.out.println("  multiple args, or both.");
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
            for (String guid : args[i].split(",")) {
                if (!guid.trim().isEmpty()) {
                    targets.add(normalizeGuid(guid));
                }
            }
        }

        if (!Files.isDirectory(cacheDir)) {
            System.out.println("No cache directory at " + cacheDir.toAbsolutePath() + " — nothing to invalidate.");
            return;
        }
        System.out.println("Cache directory: " + cacheDir.toAbsolutePath());

        ObjectMapper mapper = new ObjectMapper();
        // Use the guid->filename index so we don't open every cache file (files are named by Geni's
        // internal id, not the guid, so without an index every file must be read to check its
        // focus.guid). A guid can map to more than one file (e.g. an orphan from a past
        // CACHE_VERSION bump), so we collect ALL of a guid's files, not just the first.
        GuidCacheIndex index = new GuidCacheIndex(cacheDir);
        index.load();
        Map<String, List<String>> byGuid = index.guidToFiles();

        // Fast path: if the already-saved index covers every target, delete straight away and skip
        // reconcile entirely — no directory listing, no file reads. Only reconcile (which reads any
        // files a later fetch added, so it has to scan) when a target isn't in the index yet.
        boolean allKnown = true;
        for (String target : targets) {
            if (!byGuid.containsKey(target)) {
                allKnown = false;
                break;
            }
        }
        if (!allKnown) {
            index.reconcile(mapper);
            byGuid = index.guidToFiles();
        } else {
            System.out.println("All target guid(s) already in the index — no scan needed.");
        }

        int deleted = 0;
        boolean indexChanged = false;
        for (String target : targets) {
            List<String> filenames = byGuid.get(target);
            if (filenames == null || filenames.isEmpty()) {
                System.out.println("No cache file found for guid " + target
                        + " (not fetched yet, or already removed).");
                continue;
            }
            for (String filename : filenames) {
                try {
                    boolean existed = Files.deleteIfExists(cacheDir.resolve(filename));
                    index.forget(filename); // drop the entry either way (file is gone now)
                    indexChanged = true;
                    if (existed) {
                        System.out.println("Deleted " + filename + " (guid " + target + ")");
                        deleted++;
                    } else {
                        System.out.println("Already gone: " + filename + " (guid " + target
                                + ") — removed stale index entry");
                    }
                } catch (IOException e) {
                    System.err.println("Couldn't delete " + filename + ": " + e.getMessage());
                }
            }
        }
        if (indexChanged) {
            index.save(); // keep the index consistent with what we just removed
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
