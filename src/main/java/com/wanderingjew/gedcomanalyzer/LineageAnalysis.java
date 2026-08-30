package com.wanderingjew.gedcomanalyzer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Report-time computation for the "notable ancestral lines" appendix (display-only —
 * nothing is stored on Person or written to the GEDCOM). For a target person it finds:
 *   - notableAncestors(): distinct ancestors whose free-text name looks titled/notable
 *     (regex heuristic — there is no separate title field in the data);
 *   - deepestLines(): the deepest "endpoints" (ancestors with no further recorded
 *     parents), each with how many generations back the longest path to them runs.
 *
 * Anchored on the target's own upward ancestry only (built from Person.getParents()),
 * so it needs no GedcomData handle. Endpoint depth uses the LONGEST path to the endpoint
 * (the "how far back" story number); a collapsed ancestor reached by many paths is still
 * a single distinct person here. The distinct-people counts intentionally differ from the
 * ahnentafel path counts in the exploratory LineageEndpoints tool.
 */
public class LineageAnalysis {

    // Ordinal + word ("38th Exilarch"), or a fixed set of known title/dynasty words.
    // Not exhaustive — a name missing this can still be notable, just not auto-flagged.
    private static final Pattern ORDINAL_TITLE = Pattern.compile(
            "\\b\\d+(st|nd|rd|th)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern KNOWN_TITLE_WORDS = Pattern.compile(
            "\\b(Exilarch|Gaon|Nasi|Rashi|Rambam|Rabbi|Rav|King|Prince|Kohen|Cohen Gadol|"
            + "ha-Levi|HaLevi|Patriarch|Sage|Rebbe|Tzaddik|Priest|HaGadol|Hagadol|"
            + "Kalonymus|Kalonymos|Klonymus|Treves|Tosafot|Tosafos|Tossafot|HaSandlar|"
            + "Hasandlar|Metivta|Metibta|Resh|Abarbanel|Abravanel|Abrabanel|Maharal|"
            + "Maharam|Maharshal|Katzenellenbogen)\\b", Pattern.CASE_INSENSITIVE);

    /** True if a free-text name looks like it carries a title/office/known dynasty. */
    public static boolean looksNotable(String name) {
        if (name == null) {
            return false;
        }
        return ORDINAL_TITLE.matcher(name).find() || KNOWN_TITLE_WORDS.matcher(name).find();
    }

    private final Person target;
    // parent-id -> the ancestors (closer to target) that named it as a parent
    private final Map<String, List<Person>> childrenInTree = new HashMap<>();
    private final Map<String, Person> ancestorsById = new LinkedHashMap<>();
    private final Set<String> endpointIds = new HashSet<>();

    private final Map<String, Integer> maxDepthMemo = new HashMap<>();
    private final Set<String> visitingDepth = new HashSet<>();

    public LineageAnalysis(Person target) {
        this.target = target;
        discover();
    }

    /** BFS up from the target, deduped by id; record reverse edges and endpoints. */
    private void discover() {
        Set<String> visited = new HashSet<>();
        Deque<Person> queue = new ArrayDeque<>();
        queue.add(target);
        visited.add(target.getId());
        while (!queue.isEmpty()) {
            Person p = queue.poll();
            List<Person> parents = p.getParents();
            if (parents.isEmpty() && !p.getId().equals(target.getId())) {
                endpointIds.add(p.getId());
            }
            for (Person parent : parents) {
                childrenInTree.computeIfAbsent(parent.getId(), k -> new ArrayList<>()).add(p);
                if (visited.add(parent.getId())) {
                    ancestorsById.put(parent.getId(), parent);
                    queue.add(parent);
                }
            }
        }
    }

    /** All distinct ancestors of the target (excluding the target). */
    public java.util.Collection<Person> allAncestors() {
        return ancestorsById.values();
    }

    /** Distinct ancestors (excluding the target) whose name looks notable. */
    public List<Person> notableAncestors() {
        List<Person> out = new ArrayList<>();
        for (Person p : ancestorsById.values()) {
            if (looksNotable(p.getDisplayName())) {
                out.add(p);
            }
        }
        return out;
    }

    /** Longest path length from the target up to the ancestor with this id. */
    private int maxDepth(String id) {
        if (id.equals(target.getId())) {
            return 0;
        }
        Integer cached = maxDepthMemo.get(id);
        if (cached != null) {
            return cached;
        }
        if (!visitingDepth.add(id)) {
            return 0; // cycle guard (shouldn't happen in a valid ancestor graph)
        }
        int best = 0;
        for (Person child : childrenInTree.getOrDefault(id, Collections.emptyList())) {
            best = Math.max(best, 1 + maxDepth(child.getId()));
        }
        visitingDepth.remove(id);
        maxDepthMemo.put(id, best);
        return best;
    }

    /** An endpoint ancestor and how many generations back the longest line to them runs. */
    public static class Line {
        public final Person endpoint;
        public final int generations;

        Line(Person endpoint, int generations) {
            this.endpoint = endpoint;
            this.generations = generations;
        }
    }

    /**
     * The deepest endpoints, sorted by longest-path depth descending, capped at {@code limit}.
     * Placeholder/unnamed endpoints (bare "?"/".", "Wife of …"/"Husband of …" spouse stubs,
     * "Unknown …", or a lone single-token first name) are filtered out so the list reads as
     * actual documented lines, not noise.
     */
    public List<Line> deepestLines(int limit) {
        List<Line> lines = new ArrayList<>();
        for (String id : endpointIds) {
            Person p = ancestorsById.get(id);
            if (p != null && isSubstantiveEndpoint(p)) {
                lines.add(new Line(p, maxDepth(id)));
            }
        }
        lines.sort((a, b) -> Integer.compare(b.generations, a.generations));
        return lines.size() > limit ? new ArrayList<>(lines.subList(0, limit)) : lines;
    }

    private static boolean isSubstantiveEndpoint(Person p) {
        String name = p.getDisplayName();
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        String lower = trimmed.toLowerCase();
        if (lower.isEmpty() || lower.startsWith("unknown") || lower.contains("no name")) {
            return false;
        }
        if (lower.matches(".*\\b(wife|husband)\\b.*")) {
            return false; // spouse placeholder ("Wife #1 of …", "1st Wife of …")
        }
        if (looksNotable(trimmed)) {
            return true; // titled — always a real, meaningful endpoint
        }
        // Otherwise require at least two substantive word tokens (a lone "Esthra ." doesn't count).
        int realTokens = 0;
        for (String token : trimmed.split("\\s+")) {
            if (token.chars().filter(Character::isLetter).count() >= 2) {
                realTokens++;
            }
        }
        return realTokens >= 2;
    }
}
