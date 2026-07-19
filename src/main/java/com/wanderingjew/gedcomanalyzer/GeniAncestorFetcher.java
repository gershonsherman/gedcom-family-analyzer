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
        return buildGedcomData();
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
        d.firstName = textOrNull(focus, "first_name");
        d.lastName = textOrNull(focus, "last_name");
        d.maidenName = textOrNull(focus, "maiden_name");
        d.gender = textOrNull(focus, "gender");

        JsonNode birth = focus.get("birth");
        if (birth != null) {
            d.birthDate = buildDate(birth.get("date"));
            d.birthPlace = buildPlace(birth.get("location"));
        }
        JsonNode death = focus.get("death");
        if (death != null) {
            d.deathDate = buildDate(death.get("date"));
            d.deathPlace = buildPlace(death.get("location"));
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
            Family family = families.computeIfAbsent(unionId, Family::new);

            // Link child -> family.
            family.addChild(child.guid);
            Person childPerson = persons.get(child.guid);
            if (childPerson != null && !childPerson.getFamilyIdsAsChild().contains(unionId)) {
                childPerson.addFamilyAsChild(unionId);
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
                if (parentPerson != null && !parentPerson.getFamilyIdsAsSpouse().contains(unionId)) {
                    parentPerson.addFamilyAsSpouse(unionId);
                }
            }
        }

        return new GedcomData(persons, families);
    }

    private Person toPerson(ProfileData d) {
        Person p = new Person(d.guid);
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
        p.setDeathDate(d.deathDate);
        p.setDeathPlace(d.deathPlace);
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

    private static class ProfileData {
        String guid;
        String firstName;
        String lastName;
        String maidenName;
        String gender;
        String birthDate;
        String birthPlace;
        String deathDate;
        String deathPlace;
        String childUnionId;
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
