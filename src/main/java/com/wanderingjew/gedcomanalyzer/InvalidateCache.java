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
 * Usage: InvalidateCache <guid> [<guid> ...]
 *   guid may be given as 6000000..., I6000000..., or @I6000000...@.
 */
public class InvalidateCache {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: InvalidateCache <guid> [<guid> ...]");
            System.out.println("  Deletes the cache file(s) for the given Geni guid(s) so they are refetched.");
            System.out.println("  guid may be 6000000..., I6000000..., or @I6000000...@.");
            System.exit(1);
        }

        Path cacheDir = Paths.get("geni-cache");
        if (!Files.isDirectory(cacheDir)) {
            System.out.println("No geni-cache directory here — nothing to invalidate.");
            return;
        }

        Set<String> targets = new HashSet<>();
        for (String a : args) {
            targets.add(normalizeGuid(a));
        }

        ObjectMapper mapper = new ObjectMapper();
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(cacheDir, "*.v2.json")) {
            for (Path file : files) {
                if (targets.isEmpty()) {
                    break;
                }
                String guid;
                try {
                    JsonNode focus = mapper.readTree(file.toFile()).get("focus");
                    guid = (focus == null) ? null : focus.path("guid").asText(null);
                } catch (IOException e) {
                    continue; // skip unreadable / partially-written files
                }
                if (guid != null && targets.remove(guid)) {
                    Files.delete(file);
                    System.out.println("Deleted " + file.getFileName() + " (guid " + guid + ")");
                    deleted++;
                }
            }
        }

        for (String missing : targets) {
            System.out.println("No cache file found for guid " + missing
                    + " (not fetched yet, or already removed).");
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
