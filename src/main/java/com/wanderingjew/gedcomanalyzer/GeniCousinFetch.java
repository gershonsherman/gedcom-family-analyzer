package com.wanderingjew.gedcomanalyzer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CLI entry point that pulls a person's ancestry from the Geni API up to a given
 * generation, then descends from every ancestor whose own parents weren't fetched
 * through all of their descendants — recovering cousins, aunts/uncles, etc. that a
 * plain ancestor fetch never reaches — and writes the combined result as a GEDCOM.
 *
 * The access token is read from the GENI_ACCESS_TOKEN environment variable.
 */
public class GeniCousinFetch {

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.out.println("Usage: GeniCousinFetch <start-id> <up-generations> <output.ged> [cache-dir]");
            System.out.println("  start-id:        Geni guid (e.g. 6000000031619060876), guid form (g...), or numeric profile id");
            System.out.println("  up-generations:  how many generations up to fetch before descending (0 = unlimited)");
            System.out.println("  output.ged:      path to write the assembled GEDCOM");
            System.out.println("  cache-dir:       optional cache directory (default ./geni-cache); use a separate");
            System.out.println("                   one per unrelated tree so their caches don't intermingle");
            System.out.println();
            System.out.println("  Descends from every ancestor whose own parents weren't fetched (generation cap");
            System.out.println("  reached, or Geni's data simply ends there) through all of their descendants.");
            System.out.println("  This is typically MANY more profiles than a plain ancestor fetch — a higher");
            System.out.println("  Geni rate limit matters more here.");
            System.out.println();
            System.out.println("  The Geni access token is read from the GENI_ACCESS_TOKEN environment variable.");
            System.exit(1);
        }

        String token = System.getenv("GENI_ACCESS_TOKEN");
        if (token == null || token.trim().isEmpty()) {
            System.err.println("GENI_ACCESS_TOKEN is not set. Obtain a token from Geni and export it, e.g.:");
            System.err.println("  export GENI_ACCESS_TOKEN='...'");
            System.exit(1);
        }

        String startId = args[0];
        int upGenerations = Integer.parseInt(args[1]);
        String outputFile = args[2];
        Path cacheDir = args.length > 3 ? Paths.get(args[3]) : GeniClient.cacheDirFromEnv();

        System.out.println("Cache directory: " + cacheDir.toAbsolutePath());
        System.out.println("Fetching ancestors of " + startId
                + (upGenerations > 0 ? " up to " + upGenerations + " generations" : " (unlimited)")
                + ", then descending to find their descendants...");
        System.out.println("Rerun the same command with a fresh token to resume where this left off.");

        GeniClient client = new GeniClient(token.trim(), cacheDir, 300L);
        GeniAncestorFetcher fetcher = new GeniAncestorFetcher(client);
        fetcher.enableCheckpoint(outputFile, 200);

        // On Ctrl-C or unexpected exit, still write whatever was gathered so far. Shutdown
        // hooks run on EVERY JVM exit though, including a normal successful return from
        // main() below — so without this flag, a fully completed run would still print a
        // misleading "partial... rerun to resume" message after already writing the final
        // GEDCOM.
        AtomicBoolean completed = new AtomicBoolean(false);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (completed.get()) {
                return;
            }
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
            GedcomData data = fetcher.fetchWithDescendants(startId, upGenerations);
            if (data.getPersonCount() == 0) {
                System.out.println();
                System.out.println("WARNING: 0 people were fetched — nothing was written.");
                System.out.println("Most likely your access token is invalid or expired, or the start id is wrong.");
                System.out.println("Get a fresh token (export GENI_ACCESS_TOKEN) and check the guid, then rerun.");
                System.exit(3);
            }
            System.out.println("Assembled " + data.getPersonCount() + " persons and "
                    + data.getFamilyCount() + " families.");
            new GedcomWriter().write(data, outputFile);
            System.out.println("GEDCOM written to: " + outputFile);
            completed.set(true);
        } catch (IOException e) {
            // Most commonly an invalid/expired token (401) — surfaced clearly here.
            System.err.println();
            System.err.println("=======================================================================");
            System.err.println(" FETCH STOPPED: " + e.getMessage());
            System.err.println("=======================================================================");
            System.err.println("If this is a token problem, get a fresh token, re-export GENI_ACCESS_TOKEN,");
            System.err.println("and run the exact same command again — cached progress is kept.");
            System.exit(2);
        }
    }
}
