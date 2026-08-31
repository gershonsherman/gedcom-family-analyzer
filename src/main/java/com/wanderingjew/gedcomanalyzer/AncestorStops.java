package com.wanderingjew.gedcomanalyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * Guids the Geni ancestor fetcher must NOT ascend past. The listed person is still fetched
 * (they're a genuine ancestor), but their parents are not followed — the fetch treats them as a
 * boundary, exactly like hitting the generation cap.
 *
 * <p>This exists to cut <b>spurious cross-tree bridges</b> in Geni's collaborative data: a single
 * wrong parent link where a Jewish ancestor is attached to a European-noble parent splices your
 * tree into the enormous, exhaustively-documented medieval-royalty tree, flooding a deep fetch
 * with tens of thousands of people who aren't your ancestors. The canonical case is the
 * <b>Abarbanel/Abravanel &rarr; Iberian-nobility</b> splice (Juana Abravanel given "Gonzalo de
 * Monroy" as a parent). Cutting one edge there removed 535 royals and ~2,170 profiles from a real
 * run.
 *
 * <p>Loaded once from a tab-separated {@code ancestor-stops.tsv} in the working directory:
 * <pre>
 *   # guid                 note
 *   6000000024862267114    Juana Abravanel — bridges the Abarbanel line into European royalty
 * </pre>
 * Committed (like {@code place-overrides.tsv}): these are Geni data-quality fixes, useful to
 * anyone fetching the same shared profiles, not personal data.
 */
public class AncestorStops {

    private static final String FILE = "ancestor-stops.tsv";
    private static AncestorStops instance;

    private final Set<String> guids = new HashSet<>();

    private AncestorStops() {
        load(Paths.get(FILE));
    }

    /** Shared instance, loaded from ./ancestor-stops.tsv on first use (empty if absent). */
    public static synchronized AncestorStops get() {
        if (instance == null) {
            instance = new AncestorStops();
        }
        return instance;
    }

    private void load(Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                // Accept a bare guid, an optional leading "g", and an optional <TAB>note.
                String guid = trimmed.split("\t", 2)[0].trim();
                if (guid.startsWith("g") || guid.startsWith("G")) {
                    guid = guid.substring(1);
                }
                if (!guid.isEmpty()) {
                    guids.add(guid);
                }
            }
            if (!guids.isEmpty()) {
                System.out.println("Loaded " + guids.size() + " ancestor stop(s) from " + FILE
                        + " (won't ascend past these).");
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read " + FILE + ": " + e.getMessage());
        }
    }

    /** True if this Geni guid is a stop point — fetch the person, but not their parents. */
    public boolean isStop(String guid) {
        return guid != null && guids.contains(guid);
    }
}
