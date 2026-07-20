package com.wanderingjew.gedcomanalyzer;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayDeque;
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
        Queue<QueueEntry> queue = new ArrayDeque<>();
        Set<String> queued = new HashSet<>();
        Set<String> visited = new HashSet<>();

        String start = normalizeStart(startId);
        queue.add(new QueueEntry(start, 0));
        queued.add(start);

        try {
            while (!queue.isEmpty()) {
                QueueEntry entry = queue.poll();
                JsonNode root = client.immediateFamily(entry.id);
                if (root == null) {
                    // Offline mode and this profile isn't cached yet — skip it (partial result).
                    continue;
                }
                JsonNode focus = root.get("focus");
                if (focus == null) {
                    continue;
                }

                String numericId = stripPrefix(focus.path("id").asText());
                if (numericId.isEmpty() || visited.contains(numericId)) {
                    continue;
                }
                visited.add(numericId);

                ProfileData data = parseFocus(focus);
                data.generation = entry.generation;
                accumulateUnions(root.get("nodes"));
                data.childUnionId = findChildUnion(root.get("nodes"), numericId);
                synchronized (this) {
                    profiles.put(numericId, data);
                }

                if (profiles.size() % 25 == 0) {
                    System.out.println("  Fetched " + profiles.size() + " profiles (generation "
                            + entry.generation + ", " + client.getCacheHits() + " from cache)...");
                }
                if (checkpointEvery > 0 && checkpointPath != null && profiles.size() % checkpointEvery == 0) {
                    writeCheckpoint();
                }

                // Enqueue parents (partners of the union in which this profile is a child).
                if (data.childUnionId != null && (maxGenerations <= 0 || entry.generation < maxGenerations)) {
                    Set<String> parents = unionPartners.getOrDefault(data.childUnionId, new LinkedHashSet<>());
                    for (String parentId : parents) {
                        if (parentId.equals(numericId)) {
                            continue;
                        }
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

        System.out.println("Fetched " + profiles.size() + " profiles total ("
                + client.getRequestCount() + " API calls, " + client.getCacheHits() + " cache hits).");
        printGenerationHistogram();
        return buildGedcomData();
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
        String root = null;
        int maxGen = 0;
        for (java.util.Map.Entry<String, ProfileData> e : profiles.entrySet()) {
            if (e.getValue().generation == 0) {
                root = e.getKey();
            }
            maxGen = Math.max(maxGen, e.getValue().generation);
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
        try {
            GedcomData snapshot = snapshot();
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
            for (String parentNumeric : unionPartners.getOrDefault(unionId, new LinkedHashSet<>())) {
                String parentGuid = numericToGuid.get(parentNumeric);
                if (parentGuid == null) {
                    continue;
                }
                ProfileData parent = profiles.get(parentNumeric);
                if ("female".equalsIgnoreCase(parent.gender)) {
                    family.setWifeId(parentGuid);
                } else {
                    family.setHusbandId(parentGuid);
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
        p.setBirthLatitude(d.birthLat);
        p.setBirthLongitude(d.birthLng);
        p.setDeathDate(d.deathDate);
        p.setDeathPlace(d.deathPlace);
        p.setDeathLatitude(d.deathLat);
        p.setDeathLongitude(d.deathLng);
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
        public final boolean death;

        MapPoint(String name, String lifeDates, String place, int generation,
                 double lat, double lng, boolean death) {
            this.name = name;
            this.lifeDates = lifeDates;
            this.place = place;
            this.generation = generation;
            this.lat = lat;
            this.lng = lng;
            this.death = death;
        }

        /**
         * Build a map point for a person at the given generation, using their death
         * location (or birth location if no death coordinates). Returns null if the
         * person has no usable coordinates.
         */
        public static MapPoint fromPerson(Person p, int generation) {
            if (p.getDeathLatitude() != null && p.getDeathLongitude() != null) {
                return new MapPoint(p.getDisplayName(), p.getLifeDates(), p.getDeathPlace(),
                        generation, p.getDeathLatitude(), p.getDeathLongitude(), true);
            }
            if (p.getBirthLatitude() != null && p.getBirthLongitude() != null) {
                return new MapPoint(p.getDisplayName(), p.getLifeDates(), p.getBirthPlace(),
                        generation, p.getBirthLatitude(), p.getBirthLongitude(), false);
            }
            return null;
        }
    }

    private static class QueueEntry {
        final String id;
        final int generation;

        QueueEntry(String id, int generation) {
            this.id = id;
            this.generation = generation;
        }
    }
}
