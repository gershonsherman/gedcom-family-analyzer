package com.wanderingjew.gedcomanalyzer;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Walks a person's ancestry upward through the Geni API and assembles the result
 * into a {@link GedcomData} model. Traversal follows, for each profile, the union
 * in which the profile is a "child"; the "partner" profiles of that union are its
 * parents, which are then queued for fetching.
 *
 * <p>Optionally ({@link #fetchWithDescendants}) it continues past the ancestors: every
 * "boundary" ancestor — one whose own parents weren't fetched, either because the
 * generation cap was hit or Geni's data simply ends there — is used as the root of a
 * downward walk through all of their descendants. Since nearer ancestors' descendant
 * trees are subsets of a boundary ancestor's, this single downward pass recovers
 * cousins, aunts/uncles, etc. at every remove without walking each line separately.
 */
public class GeniAncestorFetcher {

    private static final String[] MONTHS = {
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
    };

    private final GeniClient client;

    // Full detail for every profile we fetched as a focus, keyed by numeric profile id.
    private final Map<String, ProfileData> profiles = new LinkedHashMap<>();
    // Union membership accumulated from every response, keyed by numeric union id.
    private final Map<String, Set<String>> unionPartners = new HashMap<>();
    private final Map<String, Set<String>> unionChildren = new HashMap<>();
    // Numeric union id -> Geni union guid (which matches the family id in Geni's own
    // GEDCOM export), so our output merges cleanly with those exports.
    private final Map<String, String> unionGuid = new HashMap<>();
    // Reverse index of unionPartners: numeric profile id -> unions where they're a partner.
    // Used by descend() to find a person's own marriages (and, through them, their children).
    private final Map<String, Set<String>> profileUnions = new HashMap<>();

    // Every profile id we've fetched as its own focus, across both ascend and descend.
    private final Set<String> visited = new LinkedHashSet<>();
    // The numeric id of the original start person (generation 0 during ascend), used to
    // anchor the ancestor-positions walk even after descend() adds other generation-0 people.
    private String startNumericId;
    // Count of profiles skipped due to a 403 (privacy-restricted) response, and how many
    // of those were newly discovered this run (a live 403) rather than already known from
    // a prior run's cached denial.
    private int accessDeniedCount = 0;
    private int newAccessDeniedCount = 0;

    private final GedcomWriter writer = new GedcomWriter();
    private String checkpointPath;
    private int checkpointEvery = 0;

    public GeniAncestorFetcher(GeniClient client) {
        this.client = client;
    }

    /** Periodically write the GEDCOM-so-far every {@code every} profiles, so a long
     *  run always leaves a usable file behind if interrupted. */
    public void enableCheckpoint(String path, int every) {
        this.checkpointPath = path;
        this.checkpointEvery = every;
    }

    /** Build a GEDCOM from whatever has been fetched so far (used for partial/resume writes). */
    public synchronized GedcomData snapshot() {
        return buildGedcomData();
    }

    /**
     * Fetch ancestors of the start profile up to {@code maxGenerations} (0 = unlimited).
     * {@code startId} may be a Geni guid ("6000000031619060876"), a guid form
     * ("g6000000031619060876"), or a numeric profile id.
     */
    public GedcomData fetch(String startId, int maxGenerations) throws IOException, InterruptedException {
        ascend(startId, maxGenerations);
        printFetchSummary();
        printGenerationHistogram();
        return buildGedcomData();
    }

    /**
     * Fetch ancestors of the start profile up to {@code upGenerations} (0 = unlimited),
     * then descend from every "boundary" ancestor — one whose own parents weren't
     * fetched — through all of their descendants, recovering cousins, aunts/uncles,
     * etc. In-laws (partners who married into the line) are fetched for their own
     * name/details but never traversed past, so the walk stays confined to blood
     * descendants of the boundary ancestors.
     */
    public GedcomData fetchWithDescendants(String startId, int upGenerations) throws IOException, InterruptedException {
        Set<String> boundary = ascend(startId, upGenerations);
        System.out.println("Ancestor fetch reached " + boundary.size()
                + (boundary.size() == 1 ? " boundary line" : " boundary lines")
                + "; descending to find their descendants...");
        descend(boundary);
        printFetchSummary();
        printGenerationHistogram();
        return buildGedcomData();
    }

    private void printFetchSummary() {
        System.out.println("Fetched " + profiles.size() + " profiles total ("
                + client.getRequestCount() + " API calls, " + client.getCacheHits() + " cache hits"
                + (accessDeniedCount > 0 ? ", " + accessDeniedCount + " access-denied (skipped)"
                        + (newAccessDeniedCount > 0 ? " — " + newAccessDeniedCount + " new this run" : "") : "")
                + ").");
    }

    /**
     * BFS upward from {@code startId}. Returns the "boundary" set: profiles whose own
     * parents weren't fetched, either because {@code maxGenerations} was reached or
     * because Geni's data simply has no further parents recorded.
     */
    private Set<String> ascend(String startId, int maxGenerations) throws IOException, InterruptedException {
        Queue<QueueEntry> queue = new ArrayDeque<>();
        Set<String> queued = new HashSet<>();
        Set<String> boundary = new LinkedHashSet<>();

        String start = normalizeStart(startId);
        queue.add(new QueueEntry(start, 0));
        queued.add(start);

        try {
            while (!queue.isEmpty()) {
                QueueEntry entry = queue.poll();
                String numericId = fetchProfile(entry.id, entry.generation, null);
                if (numericId == null) {
                    continue;
                }
                if (entry.generation == 0 && startNumericId == null) {
                    startNumericId = numericId;
                }
                ProfileData data = profiles.get(numericId);

                Set<String> parentIds = data.childUnionId == null
                        ? Collections.emptySet()
                        : new LinkedHashSet<>(unionPartners.getOrDefault(data.childUnionId, Collections.emptySet()));
                parentIds.remove(numericId);

                boolean reachedCap = maxGenerations > 0 && entry.generation >= maxGenerations;
                if (data.childUnionId == null || parentIds.isEmpty() || reachedCap) {
                    boundary.add(numericId);
                }
                if (!reachedCap) {
                    for (String parentId : parentIds) {
                        if (!queued.contains(parentId) && !visited.contains(parentId)) {
                            queue.add(new QueueEntry(parentId, entry.generation + 1));
                            queued.add(parentId);
                        }
                    }
                }
            }
        } finally {
            // Always leave a usable file behind, even if the token expired mid-run.
            if (checkpointPath != null) {
                writeCheckpoint();
            }
        }
        return boundary;
    }

    /**
     * BFS downward from each boundary ancestor, following every union they're a
     * partner in to reach their children, grandchildren, etc. The spouse in each such
     * union is fetched for their own details but not traversed further — only the
     * boundary ancestors' own bloodline continues down the queue.
     */
    private void descend(Set<String> boundary) throws IOException, InterruptedException {
        Queue<QueueEntry> queue = new ArrayDeque<>();
        Set<String> queued = new HashSet<>();

        for (String rootId : boundary) {
            ProfileData root = profiles.get(rootId);
            if (root == null) {
                continue;
            }
            queue.add(new QueueEntry(rootId, root.generation));
            queued.add(rootId);
        }

        try {
            while (!queue.isEmpty()) {
                QueueEntry entry = queue.poll();
                String numericId = entry.id;
                int generation;
                if (visited.contains(numericId)) {
                    // Already fetched (e.g. a direct ancestor visited during ascend) — no
                    // need to refetch, but still expand their unions below so siblings and
                    // their own descendants aren't missed. Use its authoritative generation
                    // rather than the value computed along this particular descent path.
                    generation = profiles.get(numericId).generation;
                } else {
                    numericId = fetchProfile(entry.id, entry.generation, entry.knownChildUnionId);
                    if (numericId == null) {
                        continue;
                    }
                    generation = entry.generation;
                }

                for (String unionId : profileUnions.getOrDefault(numericId, Collections.emptySet())) {
                    for (String partnerId : unionPartners.getOrDefault(unionId, Collections.emptySet())) {
                        if (!partnerId.equals(numericId) && !visited.contains(partnerId)) {
                            // Fetch the in-law fully (for their name/details) but don't
                            // enqueue them — they're not a blood descendant of the boundary.
                            fetchProfile(partnerId, generation, null);
                        }
                    }
                    for (String childId : unionChildren.getOrDefault(unionId, Collections.emptySet())) {
                        if (!queued.contains(childId)) {
                            queue.add(new QueueEntry(childId, generation - 1, unionId));
                            queued.add(childId);
                        }
                    }
                }
            }
        } finally {
            if (checkpointPath != null) {
                writeCheckpoint();
            }
        }
    }

    /**
     * Fetch a single profile as its own focus (full detail), recording it at the given
     * generation and accumulating any union edges found in the response. Returns the
     * profile's resolved numeric id, or null if it's unavailable (offline cache miss)
     * or was already fetched via another path.
     *
     * @param knownChildUnionId the union this person is already known to be a child of
     *                          (from descend()'s traversal), used only as a fallback if
     *                          the profile turns out to be access-denied; pass null when
     *                          not applicable (ascend, or in-law lookups during descend).
     */
    private String fetchProfile(String id, int generation, String knownChildUnionId) throws IOException, InterruptedException {
        JsonNode root;
        try {
            root = client.immediateFamily(id);
        } catch (GeniAccessDeniedException e) {
            // A privacy-restricted profile — expected to happen repeatedly on a deep
            // descendant fetch reaching many living relatives. Record a "Private"
            // placeholder (matching how Geni's own site shows it) rather than silently
            // omitting them, and keep going rather than aborting the whole run.
            return recordPrivateProfile(id, generation, knownChildUnionId, !e.isFromCache());
        }
        if (root == null) {
            // Offline mode and this profile isn't cached yet — skip it (partial result).
            return null;
        }
        JsonNode focus = root.get("focus");
        if (focus == null) {
            // A valid immediate-family response always has a focus. If instead the
            // body carries an error message (e.g. an expired token), surface it
            // rather than silently skipping — otherwise the run ends quietly
            // incomplete with no explanation.
            JsonNode message = root.get("message");
            if (message != null && !message.isNull()) {
                throw new IOException("Geni API error for profile " + id + ": "
                        + message.asText() + " — is your access token valid?");
            }
            return null;
        }

        String numericId = stripPrefix(focus.path("id").asText());
        if (numericId.isEmpty() || visited.contains(numericId)) {
            return null;
        }
        visited.add(numericId);

        ProfileData data = parseFocus(focus);
        data.generation = generation;
        accumulateUnions(root.get("nodes"));
        data.childUnionId = findChildUnion(root.get("nodes"), numericId);
        synchronized (this) {
            profiles.put(numericId, data);
        }

        if (profiles.size() % 25 == 0) {
            System.out.println("  Fetched " + profiles.size() + " profiles (generation "
                    + generation + ", " + client.getCacheHits() + " from cache)...");
        }
        if (checkpointEvery > 0 && checkpointPath != null && profiles.size() % checkpointEvery == 0) {
            writeCheckpoint();
        }
        return numericId;
    }

    /**
     * Record a placeholder for a profile Geni denied access to, so the tree still shows
     * that someone exists there (matching Geni's own "Private" display) instead of
     * silently having a gap. We don't know their name or other details, and — since we
     * couldn't fetch their own focus — not their parents either; {@code knownChildUnionId}
     * (the union we already know they're a child of, from descend()'s own traversal) is
     * used as a fallback so they still appear correctly placed as that union's child. When
     * discovered during ascend instead (as a denied parent), no such fallback is needed:
     * buildGedcomData() already links a person into a union generically via
     * {@code unionPartners}, independent of whether that person has their own childUnionId.
     *
     * @param newThisRun true if this denial required a live API call (never seen before —
     *                   either newly added on Geni or newly reached by this traversal);
     *                   false if it was already known from a prior run's cached denial.
     */
    private String recordPrivateProfile(String id, int generation, String knownChildUnionId, boolean newThisRun) {
        if (visited.contains(id)) {
            return null;
        }
        visited.add(id);
        accessDeniedCount++;
        if (newThisRun) {
            newAccessDeniedCount++;
        }
        System.out.println("  Profile " + id + " is private (access denied"
                + (newThisRun ? ", NEW" : ", already known from a prior run") + ") — recording as \"Private\".");

        ProfileData data = new ProfileData();
        data.guid = "private-" + id;
        data.name = "Private";
        data.generation = generation;
        data.childUnionId = knownChildUnionId;
        synchronized (this) {
            profiles.put(id, data);
        }
        return id;
    }

    /**
     * Print, per generation, both the distinct ancestor count and the ahnentafel
     * "positions" count (every path, counting a person once per path). With pedigree
     * collapse the two diverge; the positions column matches Geni's Ancestor report.
     */
    public void printGenerationHistogram() {
        java.util.Map<Integer, Integer> distinct = new java.util.TreeMap<>();
        for (ProfileData d : profiles.values()) {
            distinct.merge(d.generation, 1, Integer::sum);
        }
        java.util.Map<Integer, Long> positions = computePositionCounts();

        System.out.println("Ancestors per generation (0 = start person):");
        System.out.printf("  %-5s %10s %10s%n", "gen", "distinct", "positions");
        for (java.util.Map.Entry<Integer, Integer> e : distinct.entrySet()) {
            int g = e.getKey();
            System.out.printf("  %-5d %10d %10d%n", g, e.getValue(), positions.getOrDefault(g, 0L));
        }
    }

    /** Count ahnentafel positions (distinct root-to-ancestor paths) per generation. */
    private java.util.Map<Integer, Long> computePositionCounts() {
        java.util.Map<Integer, Long> counts = new java.util.TreeMap<>();
        // Anchored on the actual start person, not "whoever has generation 0" — once
        // fetchWithDescendants runs, distant cousins can land on generation 0 too.
        String root = startNumericId;
        int maxGen = 0;
        for (ProfileData d : profiles.values()) {
            maxGen = Math.max(maxGen, d.generation);
        }
        if (root == null) {
            return counts;
        }

        java.util.List<PathEntry> level = new java.util.ArrayList<>();
        java.util.Set<String> rootPath = new java.util.HashSet<>();
        rootPath.add(root);
        level.add(new PathEntry(root, rootPath));

        for (int g = 0; g <= maxGen && !level.isEmpty(); g++) {
            counts.put(g, (long) level.size());
            java.util.List<PathEntry> next = new java.util.ArrayList<>();
            for (PathEntry entry : level) {
                ProfileData d = profiles.get(entry.id);
                if (d == null || d.childUnionId == null) {
                    continue;
                }
                // Use the same two parents (one father, one mother) the GEDCOM records,
                // not every union partner, so positions match the actual ancestor tree.
                for (String parent : parentsOf(d.childUnionId)) {
                    if (!entry.path.contains(parent)) {
                        java.util.Set<String> path = new java.util.HashSet<>(entry.path);
                        path.add(parent);
                        next.add(new PathEntry(parent, path));
                    }
                }
            }
            level = next;
        }
        return counts;
    }

    /** The (up to) two parents of a union — one father, one mother — matching how the GEDCOM is built. */
    private java.util.List<String> parentsOf(String unionId) {
        String father = null;
        String mother = null;
        for (String p : unionPartners.getOrDefault(unionId, new LinkedHashSet<>())) {
            ProfileData d = profiles.get(p);
            if (d == null) {
                continue;
            }
            if ("female".equalsIgnoreCase(d.gender)) {
                mother = p;
            } else {
                father = p;
            }
        }
        java.util.List<String> parents = new java.util.ArrayList<>(2);
        if (father != null) {
            parents.add(father);
        }
        if (mother != null) {
            parents.add(mother);
        }
        return parents;
    }

    private static final class PathEntry {
        final String id;
        final java.util.Set<String> path;

        PathEntry(String id, java.util.Set<String> path) {
            this.id = id;
            this.path = path;
        }
    }

    private void writeCheckpoint() {
        GedcomData snapshot = snapshot();
        // Don't clobber the output with an empty file (e.g. when a run fails immediately).
        if (snapshot.getPersonCount() == 0) {
            return;
        }
        try {
            writer.write(snapshot, checkpointPath);
            System.out.println("  Checkpoint: wrote " + snapshot.getPersonCount()
                    + " persons to " + checkpointPath);
        } catch (IOException e) {
            System.err.println("  Warning: checkpoint write failed: " + e.getMessage());
        }
    }

    // --- response parsing -------------------------------------------------

    private ProfileData parseFocus(JsonNode focus) {
        ProfileData d = new ProfileData();
        d.guid = textOrNull(focus, "guid");
        d.name = textOrNull(focus, "name");
        d.firstName = textOrNull(focus, "first_name");
        d.lastName = textOrNull(focus, "last_name");
        d.maidenName = textOrNull(focus, "maiden_name");
        d.gender = textOrNull(focus, "gender");

        JsonNode birth = focus.get("birth");
        if (birth != null) {
            d.birthDate = buildDate(birth.get("date"));
            JsonNode loc = birth.get("location");
            d.birthPlace = buildPlace(loc);
            if (loc != null) {
                d.birthLat = doubleOrNull(loc, "latitude");
                d.birthLng = doubleOrNull(loc, "longitude");
            }
        }
        JsonNode death = focus.get("death");
        if (death != null) {
            d.deathDate = buildDate(death.get("date"));
            JsonNode loc = death.get("location");
            d.deathPlace = buildPlace(loc);
            if (loc != null) {
                d.deathLat = doubleOrNull(loc, "latitude");
                d.deathLng = doubleOrNull(loc, "longitude");
            }
        }
        JsonNode currentResidence = focus.get("current_residence");
        if (currentResidence != null) {
            // Unlike birth/death, current_residence isn't a dated event; its exact shape
            // isn't confirmed against Geni's docs, so accept either a nested "location"
            // object (matching birth/death) or the fields directly on current_residence.
            JsonNode loc = currentResidence.has("location") ? currentResidence.get("location") : currentResidence;
            d.currentPlace = buildPlace(loc);
            if (loc != null) {
                d.currentLat = doubleOrNull(loc, "latitude");
                d.currentLng = doubleOrNull(loc, "longitude");
            }
        }
        return d;
    }

    /** Record which profiles are partners / children in each union we encounter. */
    private void accumulateUnions(JsonNode nodes) {
        if (nodes == null) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = nodes.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> node = it.next();
            if (node.getKey().startsWith("union-")) {
                JsonNode g = node.getValue().get("guid");
                if (g != null && !g.isNull()) {
                    unionGuid.put(stripPrefix(node.getKey()), g.asText());
                }
                continue;
            }
            if (!node.getKey().startsWith("profile-")) {
                continue;
            }
            String profileId = stripPrefix(node.getKey());
            JsonNode edges = node.getValue().get("edges");
            if (edges == null) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> edgeIt = edges.fields();
            while (edgeIt.hasNext()) {
                Map.Entry<String, JsonNode> edge = edgeIt.next();
                String unionId = stripPrefix(edge.getKey());
                String rel = edge.getValue().path("rel").asText();
                if ("partner".equals(rel)) {
                    unionPartners.computeIfAbsent(unionId, k -> new LinkedHashSet<>()).add(profileId);
                    profileUnions.computeIfAbsent(profileId, k -> new LinkedHashSet<>()).add(unionId);
                } else if ("child".equals(rel)) {
                    unionChildren.computeIfAbsent(unionId, k -> new LinkedHashSet<>()).add(profileId);
                }
            }
        }
    }

    /** The union in which the given profile is a child (i.e. links to its parents). */
    private String findChildUnion(JsonNode nodes, String numericId) {
        if (nodes == null) {
            return null;
        }
        JsonNode node = nodes.get("profile-" + numericId);
        if (node == null || node.get("edges") == null) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> edgeIt = node.get("edges").fields();
        while (edgeIt.hasNext()) {
            Map.Entry<String, JsonNode> edge = edgeIt.next();
            if ("child".equals(edge.getValue().path("rel").asText())) {
                return stripPrefix(edge.getKey());
            }
        }
        return null;
    }

    // --- model assembly ---------------------------------------------------

    private GedcomData buildGedcomData() {
        Map<String, Person> persons = new LinkedHashMap<>();
        Map<String, Family> families = new LinkedHashMap<>();

        // One Person per fetched profile, keyed by guid (matching the I<guid> convention).
        Map<String, String> numericToGuid = new HashMap<>();
        for (Map.Entry<String, ProfileData> e : profiles.entrySet()) {
            ProfileData d = e.getValue();
            if (d.guid == null) {
                continue;
            }
            numericToGuid.put(e.getKey(), d.guid);
            persons.put(d.guid, toPerson(d));
        }

        // A Family for each union that is some fetched person's parent-union.
        for (Map.Entry<String, ProfileData> e : profiles.entrySet()) {
            ProfileData child = e.getValue();
            String unionId = child.childUnionId;
            if (unionId == null || child.guid == null) {
                continue;
            }
            // Use the union's Geni guid as the family id so it matches Geni's own GEDCOM
            // export (falling back to the numeric union id if no guid was seen).
            String familyId = unionGuid.getOrDefault(unionId, unionId);
            Family family = families.computeIfAbsent(familyId, Family::new);

            // Link child -> family.
            family.addChild(child.guid);
            Person childPerson = persons.get(child.guid);
            if (childPerson != null && !childPerson.getFamilyIdsAsChild().contains(familyId)) {
                childPerson.addFamilyAsChild(familyId);
            }

            // Assign parents (only those we actually fetched, so they have full detail).
            // Two passes: known-gender partners take their correct slot first, so an
            // unknown-gender partner (e.g. a "Private" placeholder from a denied
            // profile — see recordPrivateProfile) can never overwrite them; it just
            // fills whichever slot is still open afterward instead.
            Set<String> unionParentIds = unionPartners.getOrDefault(unionId, new LinkedHashSet<>());
            for (String parentNumeric : unionParentIds) {
                String parentGuid = numericToGuid.get(parentNumeric);
                if (parentGuid == null) {
                    continue;
                }
                ProfileData parent = profiles.get(parentNumeric);
                if ("female".equalsIgnoreCase(parent.gender)) {
                    family.setWifeId(parentGuid);
                } else if ("male".equalsIgnoreCase(parent.gender)) {
                    family.setHusbandId(parentGuid);
                }
            }
            for (String parentNumeric : unionParentIds) {
                String parentGuid = numericToGuid.get(parentNumeric);
                if (parentGuid == null) {
                    continue;
                }
                ProfileData parent = profiles.get(parentNumeric);
                boolean knownGender = "female".equalsIgnoreCase(parent.gender) || "male".equalsIgnoreCase(parent.gender);
                if (!knownGender) {
                    if (family.getHusbandId() == null) {
                        family.setHusbandId(parentGuid);
                    } else if (family.getWifeId() == null) {
                        family.setWifeId(parentGuid);
                    }
                    // else: both slots already filled (e.g. 3+ partners with unknown
                    // gender) — still recorded as a Person and linked below, just not
                    // as this family's HUSB/WIFE (GEDCOM has one slot each).
                }
                Person parentPerson = persons.get(parentGuid);
                if (parentPerson != null && !parentPerson.getFamilyIdsAsSpouse().contains(familyId)) {
                    parentPerson.addFamilyAsSpouse(familyId);
                }
            }
        }

        return new GedcomData(persons, families);
    }

    private Person toPerson(ProfileData d) {
        Person p = new Person(d.guid);
        p.setGeniName(d.name);
        p.setGivenName(d.firstName);

        // Geni's last_name is the current/married surname; maiden_name is the birth surname.
        // Only treat last_name as a married name for women — for men, maiden_name is just a
        // birth-name variant and shouldn't become a (semantically wrong) married name.
        boolean female = "female".equalsIgnoreCase(d.gender);
        if (female && d.maidenName != null && !d.maidenName.equalsIgnoreCase(d.lastName)) {
            p.setSurname(d.maidenName);
            p.setMarriedName(d.lastName);
        } else if (d.lastName != null) {
            p.setSurname(d.lastName);
        } else {
            p.setSurname(d.maidenName);
        }

        if ("male".equalsIgnoreCase(d.gender)) {
            p.setSex("M");
        } else if ("female".equalsIgnoreCase(d.gender)) {
            p.setSex("F");
        }

        p.setBirthDate(d.birthDate);
        p.setBirthPlace(d.birthPlace);
        // Apply manual coordinate corrections for places Geni geocoded wrongly.
        double[] birthOverride = PlaceOverrides.get().lookup(d.birthPlace);
        if (birthOverride != null) {
            p.setBirthLatitude(birthOverride[0]);
            p.setBirthLongitude(birthOverride[1]);
        } else {
            p.setBirthLatitude(d.birthLat);
            p.setBirthLongitude(d.birthLng);
        }

        p.setDeathDate(d.deathDate);
        p.setDeathPlace(d.deathPlace);
        double[] deathOverride = PlaceOverrides.get().lookup(d.deathPlace);
        if (deathOverride != null) {
            p.setDeathLatitude(deathOverride[0]);
            p.setDeathLongitude(deathOverride[1]);
        } else {
            p.setDeathLatitude(d.deathLat);
            p.setDeathLongitude(d.deathLng);
        }

        p.setCurrentPlace(d.currentPlace);
        double[] currentOverride = PlaceOverrides.get().lookup(d.currentPlace);
        if (currentOverride != null) {
            p.setCurrentLatitude(currentOverride[0]);
            p.setCurrentLongitude(currentOverride[1]);
        } else {
            p.setCurrentLatitude(d.currentLat);
            p.setCurrentLongitude(d.currentLng);
        }
        return p;
    }

    // --- helpers ----------------------------------------------------------

    private String normalizeStart(String startId) {
        String s = startId.trim();
        if (s.startsWith("@") && s.endsWith("@")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.startsWith("I") || s.startsWith("i")) {
            s = s.substring(1);
        }
        // A bare guid (all digits) is queried via the "g" form; numeric profile ids are used as-is.
        if (s.matches("\\d{15,}")) {
            return "g" + s;
        }
        return s;
    }

    private String buildDate(JsonNode date) {
        if (date == null) {
            return null;
        }
        Integer year = intOrNull(date, "year");
        Integer month = intOrNull(date, "month");
        Integer day = intOrNull(date, "day");
        boolean circa = date.path("circa").asBoolean(false);

        if (year == null) {
            return textOrNull(date, "formatted_date");
        }
        StringBuilder sb = new StringBuilder();
        if (circa) {
            sb.append("ABT ");
        }
        if (month != null && month >= 1 && month <= 12) {
            if (day != null && day >= 1 && day <= 31) {
                sb.append(day).append(" ");
            }
            sb.append(MONTHS[month - 1]).append(" ");
        }
        sb.append(year);
        return sb.toString();
    }

    private String buildPlace(JsonNode location) {
        if (location == null) {
            return null;
        }
        String formatted = textOrNull(location, "formatted_location");
        if (formatted != null) {
            return formatted;
        }
        StringBuilder sb = new StringBuilder();
        for (String field : new String[]{"city", "state", "country"}) {
            String v = textOrNull(location, field);
            if (v != null) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(v);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String stripPrefix(String id) {
        if (id == null) {
            return "";
        }
        int dash = id.indexOf('-');
        return dash >= 0 ? id.substring(dash + 1) : id;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asInt();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        double d = v.asDouble();
        // Geni uses 0/0 or absent coordinates for ungeocoded places; treat 0,0 as missing.
        return d == 0.0 ? null : d;
    }

    /**
     * One map marker per fetched person, placed at their death location, or their
     * birth location if no death coordinates are available. People with neither are
     * omitted. Ordered deepest-generation first so nearer ancestors draw on top.
     */
    /** Display name of the start person (generation 0), or null if not fetched. */
    public String startPersonName() {
        for (ProfileData d : profiles.values()) {
            if (d.generation == 0) {
                return toPerson(d).getDisplayName();
            }
        }
        return null;
    }

    public java.util.List<MapPoint> mapPoints() {
        java.util.List<MapPoint> points = new java.util.ArrayList<>();
        for (ProfileData d : profiles.values()) {
            MapPoint point = MapPoint.fromPerson(toPerson(d), d.generation);
            if (point != null) {
                points.add(point);
            }
        }
        points.sort((a, b) -> Integer.compare(b.generation, a.generation));
        return points;
    }

    private static class ProfileData {
        String guid;
        String name;
        String firstName;
        String lastName;
        String maidenName;
        String gender;
        String birthDate;
        String birthPlace;
        Double birthLat;
        Double birthLng;
        String deathDate;
        String deathPlace;
        Double deathLat;
        Double deathLng;
        String currentPlace;
        Double currentLat;
        Double currentLng;
        String childUnionId;
        int generation;
    }

    /** A single map marker: one per person, at their death place (or birth place if no death location). */
    public static class MapPoint {
        public final String name;
        public final String lifeDates;
        public final String place;
        public final int generation;
        public final double lat;
        public final double lng;
        /** "current", "death", or "birth" — which of the person's locations this point uses. */
        public final String locationType;

        MapPoint(String name, String lifeDates, String place, int generation,
                 double lat, double lng, String locationType) {
            this.name = name;
            this.lifeDates = lifeDates;
            this.place = place;
            this.generation = generation;
            this.lat = lat;
            this.lng = lng;
            this.locationType = locationType;
        }

        /**
         * Build a map point for a person at the given generation, using their death
         * location (or birth location if no death coordinates). Returns null if the
         * person has no usable coordinates. Used for ancestors, who are overwhelmingly
         * deceased — current residence is not considered here.
         */
        public static MapPoint fromPerson(Person p, int generation) {
            if (p.getDeathLatitude() != null && p.getDeathLongitude() != null) {
                return new MapPoint(p.getDisplayName(), p.getLifeDates(), p.getDeathPlace(),
                        generation, p.getDeathLatitude(), p.getDeathLongitude(), "death");
            }
            if (p.getBirthLatitude() != null && p.getBirthLongitude() != null) {
                return new MapPoint(p.getDisplayName(), p.getLifeDates(), p.getBirthPlace(),
                        generation, p.getBirthLatitude(), p.getBirthLongitude(), "birth");
            }
            return null;
        }

        /**
         * Build a map point preferring current residence, then death, then birth location.
         * Used for siblings/cousins, most of whom (unlike ancestors) are still alive.
         * {@code degree} is the relationship degree (0 = sibling, 1..5 = cousin degree),
         * stored in the {@code generation} field. Returns null if the person has no
         * usable coordinates.
         */
        public static MapPoint fromPersonPreferCurrent(Person p, int degree) {
            if (p.getCurrentLatitude() != null && p.getCurrentLongitude() != null) {
                return new MapPoint(p.getDisplayName(), p.getLifeDates(), p.getCurrentPlace(),
                        degree, p.getCurrentLatitude(), p.getCurrentLongitude(), "current");
            }
            if (p.getDeathLatitude() != null && p.getDeathLongitude() != null) {
                return new MapPoint(p.getDisplayName(), p.getLifeDates(), p.getDeathPlace(),
                        degree, p.getDeathLatitude(), p.getDeathLongitude(), "death");
            }
            if (p.getBirthLatitude() != null && p.getBirthLongitude() != null) {
                return new MapPoint(p.getDisplayName(), p.getLifeDates(), p.getBirthPlace(),
                        degree, p.getBirthLatitude(), p.getBirthLongitude(), "birth");
            }
            return null;
        }
    }

    private static class QueueEntry {
        final String id;
        final int generation;
        // The union this person is already known to be a child of, if discovered via
        // descend()'s unionChildren traversal — used as a fallback childUnionId if their
        // own profile turns out to be access-denied, since then we can't find it out any
        // other way. Null for ascend entries (not needed there — see fetchProfile).
        final String knownChildUnionId;

        QueueEntry(String id, int generation) {
            this(id, generation, null);
        }

        QueueEntry(String id, int generation, String knownChildUnionId) {
            this.id = id;
            this.generation = generation;
            this.knownChildUnionId = knownChildUnionId;
        }
    }
}
