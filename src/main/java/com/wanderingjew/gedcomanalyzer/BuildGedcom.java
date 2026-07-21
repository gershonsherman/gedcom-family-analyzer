package com.wanderingjew.gedcomanalyzer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Step 2a on its own: assemble a GEDCOM from the geni-cache without any network
 * access. Safe to run while a GeniFetch is still filling the cache — it simply
 * builds a partial GEDCOM from whatever has been cached so far, so you can watch
 * intermediate results grow. No access token required.
 */
public class BuildGedcom {

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.out.println("Usage: BuildGedcom <start-id> <max-generations> <output.ged> [cache-dir]");
            System.out.println("  Assembles a GEDCOM from the cache only (no fetching, no token).");
            System.out.println("  cache-dir: optional cache directory (default ./geni-cache).");
            System.out.println("  Run it any time — even while GeniFetch is still running — for a partial tree.");
            System.exit(1);
        }

        String startId = args[0];
        int maxGenerations = Integer.parseInt(args[1]);
        String outputFile = args[2];

        Path cacheDir = args.length > 3 ? Paths.get(args[3]) : GeniClient.cacheDirFromEnv();
        System.out.println("Cache directory: " + cacheDir.toAbsolutePath());
        GeniClient client = new GeniClient("OFFLINE", cacheDir, 0);
        client.setOffline(true);

        GeniAncestorFetcher fetcher = new GeniAncestorFetcher(client);
        GedcomData data = fetcher.fetch(startId, maxGenerations);

        System.out.println("Assembled " + data.getPersonCount() + " persons and "
                + data.getFamilyCount() + " families from cache.");

        new GedcomWriter().write(data, outputFile);
        System.out.println("GEDCOM written to: " + outputFile);
    }
}
