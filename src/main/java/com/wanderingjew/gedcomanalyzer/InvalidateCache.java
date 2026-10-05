package com.wanderingjew.gedcomanalyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        // Track which target guids were actually matched, rather than removing from
        // targets on first match — a guid can have more than one matching file (e.g. an
        // orphaned file from a past CACHE_VERSION bump alongside the current one), and
        // all of them need deleting, not just the first one found.
        // Collect the matching filenames first so we can report progress. This is a name-only
        // directory listing — cheap even on a slow cloud mount; the slow part is reading each
        // file's contents below (every file must be opened because cache files are named by
        // Geni's internal id, not the guid, so the focus guid has to be read out of each one).
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.v*.json")) {
            for (Path p : stream) {
                files.add(p);
            }
        }
        int total = files.size();
        System.out.println("Scanning " + total + " cache file(s) for " + targets.size()
                + " guid(s)... (reads every file off disk, so this can take a while on a cloud mount)");

        Set<String> found = new HashSet<>();
        int deleted = 0;
        int step = Math.max(1, total / 20); // report roughly every 5%
        for (int i = 0; i < total; i++) {
            Path file = files.get(i);
            String guid;
            try {
                JsonNode focus = mapper.readTree(file.toFile()).get("focus");
                guid = (focus == null) ? null : focus.path("guid").asText(null);
            } catch (IOException e) {
                guid = null; // skip unreadable / partially-written files
            }
            if (guid != null && targets.contains(guid)) {
                Files.delete(file);
                System.out.println("  Deleted " + file.getFileName() + " (guid " + guid + ")");
                found.add(guid);
                deleted++;
            }
            int done = i + 1;
            if (done % step == 0 || done == total) {
                System.out.println("  ...scanned " + done + "/" + total + " (" + (done * 100 / total)
                        + "%), " + deleted + " deleted");
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
