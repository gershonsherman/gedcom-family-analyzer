package com.wanderingjew.gedcomanalyzer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Step 2a on its own: assemble an ancestors-and-descendants GEDCOM from the
 * geni-cache without any network access. Safe to run while a GeniCousinFetch is
 * still filling the cache — it simply builds a partial tree from whatever has been
 * cached so far. No access token required.
 */
public class BuildCousinGedcom {

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.out.println("Usage: BuildCousinGedcom <start-id> <up-generations> <output.ged> [cache-dir]");
            System.out.println("  Assembles an ancestors-and-descendants GEDCOM from the cache only");
            System.out.println("  (no fetching, no token). cache-dir: optional (default ./geni-cache).");
            System.out.println("  Run it any time — even while GeniCousinFetch is still running — for a partial tree.");
            System.exit(1);
        }

        String startId = args[0];
        int upGenerations = Integer.parseInt(args[1]);
        String outputFile = args[2];

        Path cacheDir = args.length > 3 ? Paths.get(args[3]) : GeniClient.cacheDirFromEnv();
        System.out.println("Cache directory: " + cacheDir.toAbsolutePath());
        GeniClient client = new GeniClient("OFFLINE", cacheDir, 0);
        client.setOffline(true);

        GeniAncestorFetcher fetcher = new GeniAncestorFetcher(client);
        GedcomData data = fetcher.fetchWithDescendants(startId, upGenerations);

        System.out.println("Assembled " + data.getPersonCount() + " persons and "
                + data.getFamilyCount() + " families from cache.");

        new GedcomWriter().write(data, outputFile);
        System.out.println("GEDCOM written to: " + outputFile);
    }
}
