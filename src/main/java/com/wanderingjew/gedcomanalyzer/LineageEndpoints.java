package com.wanderingjew.gedcomanalyzer;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

/**
 * EXPLORATORY / PROTOTYPE — not yet wired into the main report. Offline analysis (reads
 * the GEDCOM directory directly, no Geni API) for the "label ancestors by documented
 * lineage" feature under discussion: counts distinct ahnentafel "lines" (root-to-leaf
 * paths through the ancestor binary tree) for a given person, ranks distinct endpoint
 * ancestors by how many lines terminate at each (a proxy for "worth checking first," not
 * notability itself), and flags names that look like they carry a title/office (regex
 * over the free-text name, since there's no separate title field in the data).
 *
 * Usage: LineageEndpoints [gedcom-dir] [person-id]
 *   gedcom-dir defaults to "gedcoms/Gedcom files", person-id defaults to Mark's own id.
 *   person-id accepts either bare ("6000000...") or GEDCOM ("@I6000000...@") form.
 *
 * A line "ends" at the deepest ancestor along that specific path for whom no further
 * parents are recorded. Deliberately does NOT dedup across paths the way
 * getAncestorsByGeneration does — a shared ancestor reached via two different routes
 * (pedigree collapse) contributes to two different lines, since each is a biologically
 * distinct lineal path even though it resolves to the same real person.
 *
 * Three memoized quantities:
 *   - endpointCount(p): how many lines terminate somewhere in p's own upward ancestry.
 *     Safely keyed by person id alone (not path-dependent).
 *   - height(p): the longest known chain of ancestors above p. Also safely per-person.
 *     ("Depth of a given endpoint from the target" is NOT safely memoizable this way —
 *     a collapsed ancestor can be the terminus of lines at different depths at once —
 *     so individual endpoints aren't labeled by depth here.)
 *   - multiplicity(p): how many distinct paths from the TARGET reach p at all. Computed
 *     top-down: multiplicity(target) = 1; multiplicity(p) = sum of multiplicity(child)
 *     over p's recorded children within this tree (discovered via a separate reverse-edge
 *     pass, since Person only exposes parent links).
 *
 * Findings from a real run against Mark's tree (2026-08-26, ~10 GEDCOM files merged):
 *   - 64,540 total lines (ahnentafel endpoints), but only 312 distinct endpoint PEOPLE —
 *     heavy pedigree collapse means each endpoint terminates ~200 lines on average.
 *   - Longest single line: 102 generations, an unbroken Exilarch succession ending at
 *     Zerubbabel (3rd Exilarch, traditionally Davidic, d. Memphis Egypt, ~500s BCE) —
 *     matches Mark's own "back to 600 BCE" recollection.
 *   - Expanding scope from "just the 312 endpoints" to "every distinct ancestor anywhere
 *     in the tree" (per Mark: notable waypoints like Rashi occur mid-line, not just at a
 *     line's terminus) found 1,193 total distinct ancestors, of which 445 (37%!)
 *     auto-flag as likely titled by the name-pattern regex — this tree contains a large,
 *     well-documented rabbinic genealogy (~1300s-1700s Poland/Germany: Katzenellenbogen,
 *     Horowitz, Spira, Isserles/"Rama", Loew/"Maharal", Luria, Weil, Auerbach families,
 *     among others), not just a handful of isolated notable figures.
 *   - A batch of real web searches against the ~20 top-multiplicity endpoints confirmed
 *     nearly all of them as genuine documented historical figures (see project chat log
 *     for details — not reproduced here): the Kalonymus rabbinic dynasty of Mainz, and
 *     Rashi's own family/students (Rashbam = Rabbi Samuel ben Meir, RIBaN = Judah ben
 *     Nathan, Ri HaZaken = Isaac ben Samuel of Dampierre, Ri Bekhor Shor), plus the
 *     Exilarch/Davidic line and the Abin/Abun family. Only one name (Abba Abbahu bar
 *     Acha, resh metivta al-Kafri) came back inconclusive.
 *
 * OPEN DESIGN QUESTION (unresolved as of last session — see CLAUDE.md "Open / possible
 * next steps"): given how many ancestors (445) already carry an obvious title in their
 * own name text, is that self-evident enough to label them without individual web
 * research (reserving actual research effort for genuinely ambiguous names, like the
 * original top-20 batch before we knew what "Shimon, of Le Mans" referred to)? Mark
 * signed off before answering; pick this up first next session.
 */
public class LineageEndpoints {

    private static final Map<String, Long> endpointCountMemo = new HashMap<>();
    private static final Map<String, Integer> heightMemo = new HashMap<>();
    private static final Map<String, Long> multiplicityMemo = new HashMap<>();
    private static final Set<String> visitingForCount = new HashSet<>();
    private static final Set<String> visitingForHeight = new HashSet<>();
    private static final Set<String> visitingForMultiplicity = new HashSet<>();
    private static final Set<String> distinctEndpointIds = new HashSet<>();
    private static final Map<String, List<Person>> childrenInTree = new HashMap<>();

    // Rough regex net for title/office-looking text in a free-text name — ordinal +
    // word ("38th Exilarch", "3rd Gaon"), or a fixed set of known title/dynasty words.
    // Expanded after a first research pass turned up real hits this list missed
    // (HaGadol, Kalonymus, Treves, Tosafot, HaSandlar). Still not exhaustive — a name
    // missing this can still be notable, just not auto-flagged.
    private static final Pattern ORDINAL_TITLE = Pattern.compile(
            "\\b\\d+(st|nd|rd|th)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern KNOWN_TITLE_WORDS = Pattern.compile(
            "\\b(Exilarch|Gaon|Nasi|Rashi|Rambam|Rabbi|Rav|King|Prince|Kohen|Cohen Gadol|"
            + "ha-Levi|HaLevi|Patriarch|Sage|Rebbe|Tzaddik|Priest|HaGadol|Hagadol|"
            + "Kalonymus|Kalonymos|Klonymus|Treves|Tosafot|Tosafos|Tossafot|HaSandlar|"
            + "Hasandlar|Metivta|Metibta|Resh)\\b", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) throws Exception {
        String gedcomDir = args.length > 0 ? args[0] : "gedcoms/Gedcom files";
        String personId = args.length > 1 ? args[1] : "6000000097908618998";

        File dir = new File(gedcomDir);
        File[] geds = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".ged"));
        Arrays.sort(geds);
        List<String> files = new ArrayList<>();
        for (File f : geds) files.add(f.getPath());

        GedcomParser parser = new GedcomParser();
        GedcomData data = parser.parseMultipleFiles(files);

        String cleanId = personId.replaceAll("@", "");
        Person target = data.getPerson(cleanId);
        if (target == null) {
            System.out.println("Person not found: " + cleanId);
            return;
        }
        System.out.println("Target: " + target.getDisplayName() + " (" + target.getId() + ")");

        long totalLines = countEndpoints(target);
        int maxHeight = height(target);

        System.out.println();
        System.out.println("Total distinct lines (ahnentafel endpoints): " + totalLines);
        System.out.println("Distinct endpoint people: " + distinctEndpointIds.size());
        System.out.println("Longest line reaches back " + maxHeight + " generations.");

        // Discover every distinct ancestor reachable from target (not just the 312
        // endpoints — per Mark's call, notable waypoints like Rashi can occur mid-line,
        // where the line continues further back past them), and the reverse (parent ->
        // child) edges needed for multiplicity. Then pull multiplicity for every one of
        // them (memoization is on-demand per node, so it must be invoked for each).
        Set<String> allAncestorIds = discoverChildEdges(target);
        List<Person> allAncestors = new ArrayList<>();
        for (String id : allAncestorIds) {
            Person p = data.getPerson(id);
            if (p != null) {
                allAncestors.add(p);
                computeMultiplicity(p, target.getId());
            }
        }
        allAncestors.sort((a, b) -> Long.compare(
                multiplicityMemo.getOrDefault(b.getId(), 0L),
                multiplicityMemo.getOrDefault(a.getId(), 0L)));

        System.out.println();
        System.out.println("Total distinct ancestors (any depth, not just endpoints): " + allAncestors.size());

        long sumOfEndpointMultiplicities = 0;
        for (String id : distinctEndpointIds) {
            sumOfEndpointMultiplicities += multiplicityMemo.getOrDefault(id, 0L);
        }
        System.out.println("Sum of endpoint multiplicities: " + sumOfEndpointMultiplicities
                + " (should equal " + totalLines + " above)");

        int flagged = 0;
        List<Person> flaggedList = new ArrayList<>();
        for (Person p : allAncestors) {
            String name = p.getDisplayName();
            boolean looksTitled = ORDINAL_TITLE.matcher(name).find() || KNOWN_TITLE_WORDS.matcher(name).find();
            if (looksTitled) {
                flagged++;
                flaggedList.add(p);
            }
        }
        System.out.println(flagged + " of " + allAncestors.size()
                + " total ancestors auto-flagged as likely titled/notable by name pattern (endpoint or not):");
        System.out.printf("%-8s %-4s %s%n", "LINES", "END?", "NAME (dates/places)");
        for (Person p : flaggedList) {
            long mult = multiplicityMemo.getOrDefault(p.getId(), 0L);
            boolean isEndpoint = distinctEndpointIds.contains(p.getId());
            String dates = p.getLifeDates();
            System.out.printf("%-8d %-4s %s%s%n",
                    mult, isEndpoint ? "Y" : "", p.getDisplayName(),
                    dates.isEmpty() ? "" : " (" + dates + ")");
        }
    }

    private static long countEndpoints(Person person) {
        String id = person.getId();
        Long cached = endpointCountMemo.get(id);
        if (cached != null) return cached;
        if (!visitingForCount.add(id)) {
            return 1;
        }
        List<Person> parents = person.getParents();
        long result;
        if (parents.isEmpty()) {
            result = 1;
            distinctEndpointIds.add(id);
        } else {
            result = 0;
            for (Person parent : parents) result += countEndpoints(parent);
        }
        visitingForCount.remove(id);
        endpointCountMemo.put(id, result);
        return result;
    }

    private static int height(Person person) {
        String id = person.getId();
        Integer cached = heightMemo.get(id);
        if (cached != null) return cached;
        if (!visitingForHeight.add(id)) return 0;
        List<Person> parents = person.getParents();
        int result = 0;
        for (Person parent : parents) result = Math.max(result, 1 + height(parent));
        visitingForHeight.remove(id);
        heightMemo.put(id, result);
        return result;
    }

    /**
     * Plain BFS from target, deduped by id, recording reverse (parent -> child) edges.
     * Returns every distinct ancestor id reached (including target itself).
     */
    private static Set<String> discoverChildEdges(Person target) {
        Set<String> visited = new HashSet<>();
        Deque<Person> queue = new ArrayDeque<>();
        queue.add(target);
        visited.add(target.getId());
        while (!queue.isEmpty()) {
            Person p = queue.poll();
            for (Person parent : p.getParents()) {
                childrenInTree.computeIfAbsent(parent.getId(), k -> new ArrayList<>()).add(p);
                if (visited.add(parent.getId())) {
                    queue.add(parent);
                }
            }
        }
        return visited;
    }

    /**
     * multiplicity(target) = 1 (seed). multiplicity(p) for anyone else = sum of
     * multiplicity(child) over p's recorded children within this tree — each of a
     * child's own paths-from-target extends by exactly one step to reach p.
     */
    private static long computeMultiplicity(Person person, String targetId) {
        String id = person.getId();
        Long cached = multiplicityMemo.get(id);
        if (cached != null) return cached;
        if (id.equals(targetId)) {
            multiplicityMemo.put(id, 1L);
            return 1L;
        }
        if (!visitingForMultiplicity.add(id)) {
            return 0; // cycle guard
        }
        long total = 0;
        for (Person child : childrenInTree.getOrDefault(id, Collections.emptyList())) {
            total += computeMultiplicity(child, targetId);
        }
        visitingForMultiplicity.remove(id);
        multiplicityMemo.put(id, total);
        return total;
    }
}
