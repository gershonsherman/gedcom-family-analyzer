package com.wanderingjew.gedcomanalyzer;

import java.io.IOException;
import java.nio.file.Path;

/**
 * CLI entry point that pulls a person's ancestry from the Geni API and writes it
 * out as a single GEDCOM file — a workaround for Geni's size-limited GEDCOM export.
 *
 * The access token is read from the GENI_ACCESS_TOKEN environment variable.
 */
public class GeniFetch {

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.out.println("Usage: GeniFetch <start-id> <max-generations> <output.ged> [request-delay-ms]");
            System.out.println("  start-id:         Geni guid (e.g. 6000000031619060876), guid form (g...), or numeric profile id");
            System.out.println("  max-generations:  how many generations up to fetch (0 = unlimited)");
            System.out.println("  output.ged:       path to write the assembled GEDCOM");
            System.out.println("  request-delay-ms: optional pause between API calls (default 300)");
            System.out.println();
            System.out.println("  The Geni access token is read from the GENI_ACCESS_TOKEN environment variable.");
            System.out.println("  Responses are cached under ./geni-cache so reruns and token refreshes are cheap.");
            System.exit(1);
        }

        String token = System.getenv("GENI_ACCESS_TOKEN");
        if (token == null || token.trim().isEmpty()) {
            System.err.println("GENI_ACCESS_TOKEN is not set. Obtain a token from Geni and export it, e.g.:");
            System.err.println("  export GENI_ACCESS_TOKEN='...'");
            System.exit(1);
        }

        String startId = args[0];
        int maxGenerations = Integer.parseInt(args[1]);
        String outputFile = args[2];
        long delayMs = args.length > 3 ? Long.parseLong(args[3]) : 300L;

        Path cacheDir = GeniClient.cacheDirFromEnv();
        System.out.println("Cache directory: " + cacheDir.toAbsolutePath());
        System.out.println("Fetching ancestors of " + startId
                + (maxGenerations > 0 ? " up to " + maxGenerations + " generations" : " (unlimited)") + "...");
        System.out.println("Responses cache under " + cacheDir.toAbsolutePath()
                + " — rerun with a fresh token to resume where this left off.");

        GeniClient client = new GeniClient(token.trim(), cacheDir, delayMs);
        GeniAncestorFetcher fetcher = new GeniAncestorFetcher(client);
        fetcher.enableCheckpoint(outputFile, 200);

        // On Ctrl-C or unexpected exit, still write whatever was gathered so far.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                GedcomData partial = fetcher.snapshot();
                if (partial.getPersonCount() > 0) {
                    new GedcomWriter().write(partial, outputFile);
                    System.out.println("\nWrote partial GEDCOM (" + partial.getPersonCount()
                            + " persons) to " + outputFile + ". Rerun to resume.");
                }
            } catch (Exception ignored) {
                // Best-effort only.
            }
        }));

        try {
            GedcomData data = fetcher.fetch(startId, maxGenerations);
            System.out.println("Assembled " + data.getPersonCount() + " persons and "
                    + data.getFamilyCount() + " families.");
            new GedcomWriter().write(data, outputFile);
            System.out.println("GEDCOM written to: " + outputFile);
        } catch (IOException e) {
            // Most commonly a 401 when the 24-hour token expires mid-run.
            System.err.println();
            System.err.println("Fetch stopped: " + e.getMessage());
            System.err.println("Progress is cached. Get a fresh token, re-export GENI_ACCESS_TOKEN,");
            System.err.println("and run the exact same command again to resume from where it stopped.");
            System.exit(2);
        }
    }
}
