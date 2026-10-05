package com.wanderingjew.gedcomanalyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@code guid -> cache-filename} index for one geni-cache directory, so tools like
 * {@link InvalidateCache} can find a profile's cache file without opening and parsing every file
 * in the directory. That scan is slow on a cloud-mounted cache of thousands of files, and it's
 * unavoidable without an index because cache files are named by Geni's internal {@code id}, not
 * the public guid — so the focus guid has to be read out of each file.
 *
 * <p>Stored as a plain TSV sidecar {@code <cache-dir>/.guid-index.tsv}: one
 * {@code <filename>\t<focusGuid>} line per cache file (focus guid empty for a file with no focus,
 * e.g. a cached {@code _denied} stub). It lives inside the (git-ignored) cache dir and is not
 * matched by the {@code *.v*.json} glob.
 *
 * <p>Maintained incrementally: {@link #reconcile} drops entries whose file is gone and reads
 * focus.guid only out of cache files not already indexed. So the first run after a big fetch reads
 * just the newly-added files, and the very first run (no index yet) does a one-time full scan that
 * builds it; every run after that is effectively instant.
 */
public class GuidCacheIndex {

    private static final String INDEX_NAME = ".guid-index.tsv";

    private final Path cacheDir;
    private final Path indexFile;
    private final Map<String, String> fileToGuid = new LinkedHashMap<>(); // filename -> focus guid

    public GuidCacheIndex(Path cacheDir) {
        this.cacheDir = cacheDir;
        this.indexFile = cacheDir.resolve(INDEX_NAME);
    }

    /** Load the saved index (if any). A missing or unreadable index just starts empty. */
    public void load() {
        fileToGuid.clear();
        if (!Files.isRegularFile(indexFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab >= 0) {
                    fileToGuid.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: couldn't read guid index (" + e.getMessage() + "); rebuilding.");
            fileToGuid.clear();
        }
    }

    /**
     * Bring the index in line with the directory, then save it: forget entries whose file is gone,
     * and read {@code focus.guid} out of any cache file not yet indexed (printing progress for that
     * read phase, since on a first build it is the slow full scan).
     */
    public void reconcile(ObjectMapper mapper) throws IOException {
        Set<String> present = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.v*.json")) {
            for (Path p : stream) {
                present.add(p.getFileName().toString());
            }
        }
        fileToGuid.keySet().retainAll(present); // drop vanished files

        List<String> toRead = new ArrayList<>();
        for (String name : present) {
            if (!fileToGuid.containsKey(name)) {
                toRead.add(name);
            }
        }
        if (!toRead.isEmpty()) {
            boolean firstBuild = toRead.size() == present.size();
            System.out.println("Indexing " + toRead.size() + " new cache file(s)"
                    + (firstBuild ? " (first build — one-time full scan of this cache dir)" : "") + "...");
            int step = Math.max(1, toRead.size() / 20);
            for (int i = 0; i < toRead.size(); i++) {
                String name = toRead.get(i);
                String guid = "";
                try {
                    JsonNode focus = mapper.readTree(cacheDir.resolve(name).toFile()).get("focus");
                    if (focus != null) {
                        guid = focus.path("guid").asText("");
                    }
                } catch (IOException e) {
                    guid = ""; // unreadable/partial — index as no-guid (a later reconcile re-reads if it changes)
                }
                fileToGuid.put(name, guid);
                int done = i + 1;
                if (done % step == 0 || done == toRead.size()) {
                    System.out.println("  ...indexed " + done + "/" + toRead.size()
                            + " (" + (done * 100 / toRead.size()) + "%)");
                }
            }
        }
        save();
    }

    /** guid -> every cache filename whose focus is that guid (usually one; &gt;1 only with orphans). */
    public Map<String, List<String>> guidToFiles() {
        Map<String, List<String>> out = new HashMap<>();
        for (Map.Entry<String, String> e : fileToGuid.entrySet()) {
            String guid = e.getValue();
            if (guid != null && !guid.isEmpty()) {
                out.computeIfAbsent(guid, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        return out;
    }

    /** Forget a filename (call after deleting its file); persist with {@link #save}. */
    public void forget(String filename) {
        fileToGuid.remove(filename);
    }

    public void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fileToGuid.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue() == null ? "" : e.getValue()).append('\n');
        }
        Files.write(indexFile, sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
