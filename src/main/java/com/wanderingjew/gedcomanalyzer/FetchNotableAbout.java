package com.wanderingjew.gedcomanalyzer;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Research pass for the "Notable Ancestral Lines" report appendix. Walks the target's
 * ancestry (via {@link LineageAnalysis}) and, for every ancestor not yet recorded, pulls
 * that Geni profile's {@code about_me} and stores its first sentence verbatim in the shared,
 * guid-keyed {@link NotableAnnotations} file. Verbatim only — no summarization here, so the
 * report never shows invented text.
 *
 * We fetch ALL ancestors (not just the title-flagged ones) because the presence of an
 * about_me is itself a notability signal that catches figures whose names carry no title.
 * The run is incremental: a guid already in the annotation file (bio or empty "checked, none")
 * is skipped, so re-running after a token refresh resumes cheaply. Needs GENI_ACCESS_TOKEN.
 *
 * Usage: FetchNotableAbout &lt;gedcom-dir-or-files&gt; &lt;person-id&gt; [annotation-file]
 */
public class FetchNotableAbout {

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.out.println("Usage: FetchNotableAbout <gedcom-dir-or-files> <person-id> [annotation-file]");
            System.out.println("  Fetches each ancestor's Geni about_me (first sentence) into a shared,");
            System.out.println("  guid-keyed annotation file. Incremental; needs GENI_ACCESS_TOKEN.");
            System.exit(1);
        }
        String gedcomArg = args[0];
        String personId = args[1].replaceAll("@", "");
        Path annFile = Paths.get(args.length > 2 ? args[2] : NotableAnnotations.DEFAULT_FILE);

        String token = System.getenv("GENI_ACCESS_TOKEN");
        if (token == null || token.trim().isEmpty()) {
            System.err.println("Error: GENI_ACCESS_TOKEN is not set. Get a fresh token and export it, then rerun.");
            System.exit(1);
        }

        List<String> files = resolveGedcomFiles(gedcomArg);
        if (files.isEmpty()) {
            System.err.println("Error: no GEDCOM (.ged) files found for '" + gedcomArg + "'.");
            System.exit(1);
        }
        System.out.println("Parsing " + files.size() + " GEDCOM file(s)...");
        GedcomParser parser = new GedcomParser();
        GedcomData data = files.size() == 1 ? parser.parseFile(files.get(0)) : parser.parseMultipleFiles(files);

        Person target = data.getPerson(personId);
        if (target == null) {
            System.err.println("Error: person '" + personId + "' not found.");
            System.exit(1);
        }
        System.out.println("Target: " + target.getDisplayName());

        LineageAnalysis lineage = new LineageAnalysis(target);
        NotableAnnotations ann = new NotableAnnotations(annFile);
        System.out.println("Ancestors in tree: " + lineage.allAncestors().size()
                + " | already annotated: " + ann.size());

        GeniClient client = new GeniClient(token.trim(), GeniClient.cacheDirFromEnv(), 250L);

        // Optional cap on NEW fetches this run (env NOTABLE_FETCH_LIMIT) — for a bounded test
        // slice. 0 / unset = no cap (fetch everyone not yet recorded).
        int limit = 0;
        try {
            String lim = System.getenv("NOTABLE_FETCH_LIMIT");
            if (lim != null && !lim.trim().isEmpty()) {
                limit = Integer.parseInt(lim.trim());
            }
        } catch (NumberFormatException ignore) {
            // treat an unparseable value as no cap
        }

        int checked = 0, notable = 0, denied = 0, skipped = 0;
        for (Person p : lineage.allAncestors()) {
            if (limit > 0 && checked + denied >= limit) {
                System.out.println("Reached NOTABLE_FETCH_LIMIT=" + limit + " — stopping this slice.");
                break;
            }
            String guid = toGuid(p.getId());
            if (guid == null) {                 // not a Geni guid (private-… stub, odd id)
                skipped++;
                continue;
            }
            if (ann.contains(guid)) {           // already fetched (bio or checked-empty)
                skipped++;
                continue;
            }
            try {
                JsonNode resp = client.fetchProfile("g" + guid, "id,guid,name,about_me");
                String about = aboutMe(resp);
                int encLinks = about == null ? 0 : countEncyclopediaLinks(about);
                String prose = about == null ? "" : clean(about);
                String bio = firstProseSentence(prose);
                ann.append(guid, prose.length(), encLinks, p.getDisplayName(), bio);
                checked++;
                if (encLinks >= 1) {
                    notable++;
                }
                if (checked % 50 == 0) {
                    System.out.println("  ...checked " + checked + " (" + notable + " notable, "
                            + denied + " denied)");
                }
            } catch (GeniAccessDeniedException e) {
                ann.append(guid, 0, 0, p.getDisplayName(), "");  // record the denial so we don't retry it
                denied++;
            } catch (IOException e) {
                // 401 (bad/expired token) or a fatal error — stop; progress so far is saved.
                System.err.println("Stopped: " + e.getMessage());
                System.out.println("Progress saved to " + annFile.toAbsolutePath() + " — rerun to resume.");
                System.exit(2);
            }
        }

        System.out.println();
        System.out.println("Done. Fetched " + checked + " profile(s); " + notable + " notable (encyclopedia-cited); "
                + denied + " denied; " + skipped + " skipped (already done / not a Geni guid).");
        System.out.println("Annotation file: " + annFile.toAbsolutePath() + " (" + ann.size() + " total).");
        System.out.println("API requests this run: " + client.getRequestCount());
    }

    /** Person id -> bare Geni guid, or null if it isn't one (e.g. a "private-…" stub). */
    private static String toGuid(String id) {
        if (id == null) {
            return null;
        }
        String g = id.startsWith("I") ? id.substring(1) : id;
        // 6+ digits to match GedcomFamilyAnalyzer.guidOf: also covers the shorter numeric
        // internal ids some older profiles carry. (fetchProfile below prepends "g", which
        // resolves long guids; a short internal id may not, so such a fetch can no-op — but
        // that is no worse than the old threshold, which skipped these people entirely.)
        return g.matches("\\d{6,}") ? g : null;
    }

    /** about_me from a direct-profile response (top level), falling back to a focus wrapper. */
    private static String aboutMe(JsonNode resp) {
        if (resp == null) {
            return null;
        }
        String v = text(resp, "about_me");
        if (v == null && resp.has("focus")) {
            v = text(resp.get("focus"), "about_me");
        }
        return v;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Strip the markup Geni bios use (HTML, MediaWiki links/bold/headings, bare URLs) and
     * leading bullet/punctuation noise, then collapse whitespace — so what's left starts at
     * the first real word of prose. Geni about_me is typically wiki-flavoured, e.g.
     * {@code *[http://… label]\n'''Name''' (1473-1551) was …}.
     */
    private static String clean(String about) {
        String s = about;
        s = s.replaceAll("<[^>]+>", " ");         // HTML tags
        s = s.replaceAll("\\[/?[^\\]]*\\]", " ");  // [wiki link label] and [b]…[/b] markup
        s = s.replaceAll("https?://\\S+", " ");    // bare URLs
        s = s.replaceAll("www\\.\\S+", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
        s = s.replaceAll("'{2,}", " ");            // '' / ''' wiki italic/bold
        s = s.replaceAll("={2,}", " ");            // == wiki headings ==
        s = s.replaceAll("[-_]{3,}", " ");         // ---- / ____ separator rules
        // Geni wraps a profile's imported GEDCOM note in literal "GEDCOM Note" markers and
        // often a "Bio notes:" label — strip that scaffolding so the bio starts at real prose.
        s = s.replaceAll("(?i)GEDCOM Note", " ");
        s = s.replaceAll("(?i)^\\s*(Bio notes?|Notes?|Biography)\\s*:\\s*", " ");
        s = s.replaceAll("\\s+", " ").trim();
        s = s.replaceAll("(?i)^(Bio notes?|Notes?|Biography)\\s*:\\s*", "");
        s = s.replaceAll("^[^\\p{L}(]+", "");      // drop leading bullets/punctuation noise
        return s;
    }

    // Encyclopedia references that signal a genuinely documented (notable) figure, as opposed
    // to an ordinary relative whose about_me is a personal note or a genealogical data dump.
    private static final Pattern ENCYCLOPEDIA = Pattern.compile(
            "(?i)(wikipedia|jewishencyclopedia|wikidata|britannica|yivoencyclopedia|geni\\.com/surnames)");

    private static int countEncyclopediaLinks(String rawAbout) {
        Matcher m = ENCYCLOPEDIA.matcher(rawAbout);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    // A sentence terminator followed by whitespace or end-of-string.
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](\\s|$)");

    /**
     * First PROSE-like sentence: skips leading data/date/citation fragments (which are mostly
     * digits and punctuation, e.g. "b. Dec 3, 1804 d. Aug 26, 1873 #713") and returns the first
     * sentence that reads as actual biographical text. Empty string if none qualifies.
     */
    private static String firstProseSentence(String prose) {
        if (prose.isEmpty()) {
            return "";
        }
        int start = 0;
        Matcher m = SENTENCE_END.matcher(prose);
        while (m.find()) {
            String sentence = prose.substring(start, m.start() + 1).trim();
            if (looksLikeProse(sentence)) {
                return sentence;
            }
            start = m.end();
        }
        String tail = prose.substring(start).trim();
        if (looksLikeProse(tail)) {
            return tail.length() > 240 ? tail.substring(0, 240).trim() + "…" : tail;
        }
        return "";
    }

    // Markers of a genealogical data dump or citation, not biographical prose.
    private static final Pattern DUMP_MARKER = Pattern.compile(
            "[|{}#~=]|\\b[bmd]:|\\bcensus\\b|\\bBy:|\\bSource:|\\bp\\.\\s*\\d", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_TOKEN = Pattern.compile("\\d+");

    /**
     * True if a sentence reads as biographical prose rather than a genealogical data line.
     * Rejects short fragments, mostly-uppercase/citation lines, and lines carrying data-dump
     * markers ({@code | { } # m: b: d: census By: Source:}) or three-plus numbers (dates/ids).
     */
    private static boolean looksLikeProse(String s) {
        if (s.length() < 25) {
            return false;
        }
        if (DUMP_MARKER.matcher(s).find()) {
            return false;
        }
        Matcher nums = NUMBER_TOKEN.matcher(s);
        int numTokens = 0;
        while (nums.find()) {
            numTokens++;
        }
        if (numTokens >= 3) {                 // e.g. "b 1728 Lvov ... d 1795 ... 377"
            return false;
        }
        int words = 0;
        for (String t : s.split("\\s+")) {
            if (t.chars().filter(Character::isLetter).count() >= 2) {
                words++;
            }
        }
        if (words < 5) {
            return false;
        }
        long letters = s.chars().filter(Character::isLetter).count();
        long lower = s.chars().filter(Character::isLowerCase).count();
        // Require real lowercase content (not an ALLCAPS header) and mostly letters.
        return lower >= letters * 0.4 && letters >= s.length() * 0.6;
    }

    /** Directory (all *.ged, sorted), comma-separated list, or single file. */
    private static List<String> resolveGedcomFiles(String input) {
        String cleaned = input.trim();
        if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        List<String> files = new ArrayList<>();
        File asDir = new File(cleaned);
        if (asDir.isDirectory()) {
            File[] geds = asDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".ged"));
            if (geds != null) {
                Arrays.sort(geds);
                for (File f : geds) {
                    files.add(f.getPath());
                }
            }
            return files;
        }
        for (String part : cleaned.split(",")) {
            String path = part.trim();
            if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
                path = path.substring(1, path.length() - 1);
            }
            if (!path.isEmpty()) {
                files.add(path);
            }
        }
        return files;
    }
}
