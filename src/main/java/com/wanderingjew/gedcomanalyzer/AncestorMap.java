package com.wanderingjew.gedcomanalyzer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * CLI that builds a Leaflet/OpenStreetMap HTML page of a person's ancestors,
 * one marker per person (death place, or birth place if no death location),
 * coloured by generation.
 *
 * Reads the geni-cache only (no network, no token) — so it can be run any time,
 * even while a GeniFetch is still filling the cache, to see a partial map.
 */
public class AncestorMap {

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.out.println("Usage: AncestorMap <start-id> <max-generations> <output.html> [cache-dir]");
            System.out.println("  Builds an ancestor map from the cache only (no fetching, no token).");
            System.out.println("  cache-dir: optional cache directory (default ./geni-cache).");
            System.out.println("  Run it any time — even while GeniFetch is still running — for a partial map.");
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
        fetcher.fetch(startId, maxGenerations);

        List<GeniAncestorFetcher.MapPoint> points = fetcher.mapPoints();
        System.out.println("Mapping " + points.size() + " people with coordinates.");

        String name = fetcher.startPersonName();
        String title = "Ancestors of " + (name != null ? name : startId);
        new AncestorMapWriter().write(points, outputFile, title);
        System.out.println("Map written to: " + outputFile);
    }
}
