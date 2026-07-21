package com.wanderingjew.gedcomanalyzer;

import java.nio.file.Path;

/**
 * Step 2a on its own: assemble a GEDCOM from the geni-cache without any network
 * access. Safe to run while a GeniFetch is still filling the cache — it simply
 * builds a partial GEDCOM from whatever has been cached so far, so you can watch
 * intermediate results grow. No access token required.
 */
public class BuildGedcom {

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.out.println("Usage: BuildGedcom <start-id> <max-generations> <output.ged>");
            System.out.println("  Assembles a GEDCOM from the geni-cache only (no fetching, no token).");
            System.out.println("  Run it any time — even while GeniFetch is still running — for a partial tree.");
            System.exit(1);
        }

        String startId = args[0];
        int maxGenerations = Integer.parseInt(args[1]);
        String outputFile = args[2];

        Path cacheDir = GeniClient.cacheDirFromEnv();
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
