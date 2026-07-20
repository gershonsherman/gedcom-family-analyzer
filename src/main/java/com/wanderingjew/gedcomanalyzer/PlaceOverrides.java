package com.wanderingjew.gedcomanalyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual coordinate corrections for places Geni geocoded to the wrong spot
 * (e.g. "Babylon" -> Babylon, NY). Loaded once from a tab-separated file
 * "place-overrides.tsv" in the working directory:
 *
 *   # place substring (case-insensitive)   latitude   longitude
 *   Babylon            32.5355   44.4275
 *   Memphis, Egypt     29.8447   31.2508
 *
 * When a person's birth/death place contains one of these substrings, the
 * corrected coordinates are used instead of Geni's.
 */
public class PlaceOverrides {

    private static final String FILE = "place-overrides.tsv";
    private static PlaceOverrides instance;

    private final List<String> patterns = new ArrayList<>();
    private final List<double[]> coords = new ArrayList<>();

    private PlaceOverrides() {
        load(Paths.get(FILE));
    }

    /** Shared instance, loaded from ./place-overrides.tsv on first use (empty if absent). */
    public static synchronized PlaceOverrides get() {
        if (instance == null) {
            instance = new PlaceOverrides();
        }
        return instance;
    }

    private void load(Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            int count = 0;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 3) {
                    continue;
                }
                try {
                    double lat = Double.parseDouble(parts[1].trim());
                    double lng = Double.parseDouble(parts[2].trim());
                    patterns.add(parts[0].trim().toLowerCase());
                    coords.add(new double[]{lat, lng});
                    count++;
                } catch (NumberFormatException ignored) {
                    // skip malformed row
                }
            }
            if (count > 0) {
                System.out.println("Loaded " + count + " place coordinate override(s) from " + FILE + ".");
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read " + FILE + ": " + e.getMessage());
        }
    }

    /** Corrected [latitude, longitude] if the place matches an override, else null. */
    public double[] lookup(String place) {
        if (place == null) {
            return null;
        }
        String lower = place.toLowerCase();
        for (int i = 0; i < patterns.size(); i++) {
            if (lower.contains(patterns.get(i))) {
                return coords.get(i);
            }
        }
        return null;
    }
}
