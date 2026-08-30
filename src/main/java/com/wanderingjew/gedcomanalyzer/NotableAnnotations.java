package com.wanderingjew.gedcomanalyzer;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared, guid-keyed store of notable-ancestor annotations pulled from each Geni profile's
 * about_me. Keyed by Geni guid, so it is shared across ALL trees — annotate a figure like the
 * Abarbanel once and every report benefits, exactly like the geni-cache. Kept OUT of git (the
 * guids reveal which profiles are in a family tree); lives on Drive with the other data.
 *
 * <p>File format: tab-separated {@code guid<TAB>proseLen<TAB>encLinks<TAB>famous<TAB>name<TAB>bio},
 * "#" comments. The {@code famous} flag and a human-readable {@code name} come before the long
 * free-text {@code bio} so the file reads at a glance and the hand-edited flag stays easy to set;
 * a row short of a column parses with that column empty / not-famous.
 * <ul>
 *   <li>{@code encLinks} — count of encyclopedia references (Wikipedia / Jewish Encyclopedia /
 *       Wikidata / Britannica) in the about_me. This is the high-precision notability signal: a
 *       genuinely documented figure like the Abarbanel cites encyclopedias, whereas an ordinary
 *       relative's about_me is a personal note or a genealogical data dump with none. The report
 *       flags on {@code encLinks >= 1}, not mere about_me presence.</li>
 *   <li>{@code proseLen} — length of the about_me after markup is stripped (kept for tuning /
 *       a possible secondary signal; not the primary flag, since long ≠ notable — many long
 *       about_me fields are just dates/census data).</li>
 *   <li>{@code famous} — a hand-curated marquee flag ({@code 1}/{@code true}/{@code yes} = famous,
 *       {@code 0} / anything else / absent = not). {@code encLinks} already gives a broad "notable"
 *       set (hundreds of people); this narrower flag marks the handful of genuinely famous ancestors
 *       (Rashi, the Abarbanel…) that the report surfaces as an always-visible "Including:" preview
 *       under each generation-bucket heading. Set entirely by hand — the research pass never sets
 *       it.</li>
 *   <li>{@code name} — the ancestor's display name at the time of writing, purely a human aid for
 *       eyeballing / editing the file; NOT read back by the report (which names people from the
 *       GEDCOM), so a stale name here is harmless.</li>
 *   <li>{@code bio} — the first prose-like sentence, verbatim (may be empty). Last, being the one
 *       long free-text field.</li>
 * </ul>
 * A guid present with {@code encLinks 0}, {@code proseLen 0} and empty bio means "checked, no
 * about_me" — recorded so an incremental re-run skips it.
 */
public class NotableAnnotations {

    public static final String DEFAULT_FILE = "notable-ancestors.tsv";

    private static final class Entry {
        final int proseLen;
        final int encLinks;
        final String bio;
        final boolean famous;
        Entry(int proseLen, int encLinks, String bio, boolean famous) {
            this.proseLen = proseLen;
            this.encLinks = encLinks;
            this.bio = bio;
            this.famous = famous;
        }
    }

    private final Path file;
    private final Map<String, Entry> byGuid = new LinkedHashMap<>();

    public NotableAnnotations(Path file) {
        this.file = file;
        load();
    }

    /** Instance reading the default file in the working directory (used by the report). */
    public static NotableAnnotations getDefault() {
        return new NotableAnnotations(Paths.get(DEFAULT_FILE));
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                String guid = parts[0].trim();
                if (guid.isEmpty()) {
                    continue;
                }
                int proseLen = parts.length >= 2 ? parseIntSafe(parts[1]) : 0;
                int encLinks = parts.length >= 3 ? parseIntSafe(parts[2]) : 0;
                boolean famous = parts.length >= 4 && parseBoolSafe(parts[3]);
                // parts[4] is the human-readable name column (eyeballing aid only — the report
                // gets names from the GEDCOM, so it isn't retained here); bio follows it.
                String bio = parts.length >= 6 ? parts[5].trim() : "";
                byGuid.put(guid, new Entry(proseLen, encLinks, bio, famous));
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read " + file + ": " + e.getMessage());
        }
    }

    /** True if this guid has already been fetched (with or without an about_me). */
    public boolean contains(String guid) {
        return byGuid.containsKey(guid);
    }

    /** True if this guid has a non-empty bio sentence (regardless of notability). */
    public boolean hasBio(String guid) {
        Entry e = byGuid.get(guid);
        return e != null && !e.bio.isEmpty();
    }

    /** True if this guid's about_me cites at least one encyclopedia — the notability signal. */
    public boolean isNotable(String guid) {
        Entry e = byGuid.get(guid);
        return e != null && e.encLinks >= 1;
    }

    /** True if this guid is hand-flagged famous (the marquee subset shown as a bucket preview). */
    public boolean isFamous(String guid) {
        Entry e = byGuid.get(guid);
        return e != null && e.famous;
    }

    /** The first-sentence bio for this guid, or null if none recorded. */
    public String bio(String guid) {
        Entry e = byGuid.get(guid);
        return (e == null || e.bio.isEmpty()) ? null : e.bio;
    }

    /** Count of encyclopedia references in this guid's about_me (0 if none / not recorded). */
    public int encLinks(String guid) {
        Entry e = byGuid.get(guid);
        return e == null ? 0 : e.encLinks;
    }

    /** Stripped-prose length of this guid's about_me (0 if none / not recorded). */
    public int proseLen(String guid) {
        Entry e = byGuid.get(guid);
        return e == null ? 0 : e.proseLen;
    }

    public int size() {
        return byGuid.size();
    }

    /**
     * Record (and persist) a guid's about_me prose length, encyclopedia-link count, display
     * name (an eyeballing aid), and bio. The marquee {@code famous} flag is always written as
     * {@code 0} here — it is hand-curated, never set by the research pass.
     */
    public void append(String guid, int proseLen, int encLinks, String name, String bio) throws IOException {
        boolean fresh = !Files.isRegularFile(file);
        String cleanName = oneLine(name);
        String cleanBio = oneLine(bio);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (fresh) {
                w.write("# Notable-ancestor annotations, guid-keyed, shared across all trees.\n");
                w.write("# Format: guid<TAB>proseLen<TAB>encLinks<TAB>famous<TAB>name<TAB>bio"
                        + "  (encLinks>=1 => notable; famous=1 => marquee preview; empty bio = none)\n");
            }
            w.write(guid + "\t" + proseLen + "\t" + encLinks + "\t0\t" + cleanName + "\t" + cleanBio + "\n");
        }
        byGuid.put(guid, new Entry(proseLen, encLinks, cleanBio, false));
    }

    /** Collapse a field to a single tab-free line so it can't break the TSV columns. */
    private static String oneLine(String s) {
        return s == null ? ""
                : s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean parseBoolSafe(String s) {
        String t = s.trim().toLowerCase();
        return t.equals("1") || t.equals("true") || t.equals("yes") || t.equals("y");
    }
}
