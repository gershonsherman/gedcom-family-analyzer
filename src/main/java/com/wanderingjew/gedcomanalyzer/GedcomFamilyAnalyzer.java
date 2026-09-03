package com.wanderingjew.gedcomanalyzer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Main class for GEDCOM Family Relationship Analyzer.
 * Analyzes family relationships in GEDCOM files.
 */
public class GedcomFamilyAnalyzer {
    
    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.out.println("Usage: java -jar gedcom-family-analyzer.jar <gedcom-files> <person-id> [html-output-file]");
            System.out.println("  gedcom-files: a directory (uses every *.ged inside it), a single file,");
            System.out.println("                or a comma-separated list of files");
            System.out.println("  person-id: ID of the person to analyze (with or without @ symbols)");
            System.out.println("  html-output-file: Optional path to HTML output file");
            System.out.println();
            System.out.println("Examples:");
            System.out.println("  Directory:      java -jar gedcom-family-analyzer.jar \"path/to/gedcoms\" I1 output.html");
            System.out.println("  Single file:    java -jar gedcom-family-analyzer.jar family1.ged I1");
            System.out.println("  Multiple files: java -jar gedcom-family-analyzer.jar \"family1.ged,family2.ged\" I1");
            System.exit(1);
        }
        
        String gedcomFiles = args[0];
        String personId = args[1];
        String htmlOutputFile = args.length > 2 ? args[2] : null;
        
        GedcomFamilyAnalyzer analyzer = new GedcomFamilyAnalyzer();
        analyzer.analyzeFamily(gedcomFiles, personId, htmlOutputFile);
    }
    
    public void analyzeFamily(String gedcomFiles, String personId, String htmlOutputFile) {
        try {
            System.out.println("==========================================");
            System.out.println("GEDCOM Family Relationship Analyzer");
            System.out.println("==========================================");
            System.out.println("GEDCOM Files: " + gedcomFiles);
            System.out.println("Person ID: " + personId);
            System.out.println();
            
            // Parse GEDCOM file(s)
            System.out.println("Parsing GEDCOM file(s)...");
            GedcomParser parser = new GedcomParser();

            List<String> fileList = resolveGedcomFiles(gedcomFiles);
            if (fileList.isEmpty()) {
                System.out.println("Error: no GEDCOM (.ged) files found for '" + gedcomFiles + "'.");
                System.exit(1);
            }
            System.out.println("GEDCOM files (" + fileList.size() + "):");
            for (String f : fileList) {
                System.out.println("  " + f);
            }

            GedcomData gedcomData = fileList.size() == 1
                    ? parser.parseFile(fileList.get(0))
                    : parser.parseMultipleFiles(fileList);

            System.out.println("Found " + gedcomData.getPersonCount() + " persons and " + gedcomData.getFamilyCount() + " families.");
            System.out.println();
            
            // Find target person
            String cleanPersonId = personId.replaceAll("@", "");
            Person targetPerson = gedcomData.getPerson(cleanPersonId);
            if (targetPerson == null) {
                System.out.println("Error: Person with ID '" + personId + "' not found.");
                System.exit(1);
            }
            
            System.out.println("Target Person: " + targetPerson.getDisplayName());
            if (!targetPerson.getLifeDates().isEmpty()) {
                System.out.println("Life Dates: " + targetPerson.getLifeDates());
            }
            System.out.println();
            
            // Analyze relationships
            FamilyRelationshipAnalyzer analyzer = new FamilyRelationshipAnalyzer(gedcomData);
            
            // Generate output
            if (htmlOutputFile != null) {
                // Ensure output directory exists
                ensureOutputDirectoryExists(htmlOutputFile);
                generateHtmlOutput(analyzer, targetPerson, gedcomFiles, personId, htmlOutputFile, gedcomData);
                System.out.println("HTML output written to: " + htmlOutputFile);
            } else {
                displayConsoleOutput(analyzer, targetPerson, gedcomData);
            }
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Resolve the first argument into a list of GEDCOM file paths. Accepts a directory
     * (all *.ged files inside it, sorted), a comma-separated list, or a single file.
     * Surrounding quotes are stripped so shell-quoted arguments work too.
     */
    private List<String> resolveGedcomFiles(String input) {
        String cleaned = stripQuotes(input.trim());
        List<String> files = new ArrayList<>();

        File asDir = new File(cleaned);
        if (asDir.isDirectory()) {
            File[] geds = asDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".ged"));
            if (geds != null) {
                java.util.Arrays.sort(geds);
                for (File f : geds) {
                    files.add(f.getPath());
                }
            }
            return files;
        }

        for (String part : cleaned.split(",")) {
            String path = stripQuotes(part.trim());
            if (!path.isEmpty()) {
                files.add(path);
            }
        }
        return files;
    }

    private String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private void displayConsoleOutput(FamilyRelationshipAnalyzer analyzer, Person targetPerson, GedcomData gedcomData) {
        displayAncestors(analyzer, targetPerson);
        displayDescendants(analyzer, targetPerson);
        displaySiblings(analyzer, targetPerson);
        displayCousins(analyzer, targetPerson, gedcomData);
    }
    
    private void generateHtmlOutput(FamilyRelationshipAnalyzer analyzer, Person targetPerson, String gedcomFile, String personId, String htmlOutputFile, GedcomData gedcomData) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(htmlOutputFile))) {
            writer.println("<!DOCTYPE html>");
            writer.println("<html lang=\"en\">");
            writer.println("<head>");
            writer.println("    <meta charset=\"UTF-8\">");
            writer.println("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
            writer.println("    <title>" + targetPerson.getDisplayName() + " Family Relationship Analysis</title>");
            writer.println("    <style>");
            writer.println("        body { font-family: Arial, sans-serif; margin: 20px; line-height: 1.6; }");
            writer.println("        h1 { color: #2c3e50; font-size: 28px; border-bottom: 3px solid #3498db; padding-bottom: 10px; }");
            writer.println("        h2 { color: #34495e; font-size: 24px; margin-top: 30px; margin-bottom: 15px; border-left: 4px solid #3498db; padding-left: 15px; }");
            writer.println("        h3 { color: #2980b9; font-size: 20px; margin-top: 20px; margin-bottom: 10px; }");
            writer.println("        .person { margin: 8px 0; padding: 5px 0; }");
            // isolate: keep a name's own text direction from bleeding into the surrounding
            // line. Without it, a name ending in Hebrew (RTL, e.g. "RASHI - רש״י") pulls the
            // neutral punctuation and the number that follow it ("— 24th …") into the RTL run,
            // reordering them; isolating the span makes the outer LTR line treat it as one unit.
            writer.println("        .person-name { font-weight: bold; color: #2c3e50;"
                    + " unicode-bidi: isolate; }");
            writer.println("        .person-id { color: #7f8c8d; font-family: monospace; }");
            // Inline lineage context in the same secondary grey as the Geni id. Arrows follow page
            // direction: ↑ = toward the target (up the list), ↓ = further away (down the list) — so
            // ancestors show ↑ children ↓ parents, descendants show ↓ children. Each name is
            // bidi-isolated so a Hebrew name can't reorder the arrows/commas.
            writer.println("        .lineage { color: #7f8c8d; font-size: 13px; }");
            writer.println("        .lineage .nm { unicode-bidi: isolate; }");
            writer.println("        .dup-count { color: #c0392b; font-weight: bold; }");
            writer.println("        .cross-ref { color: #8e44ad; font-style: italic; font-size: 13px; }");
            writer.println("        .life-dates { color: #27ae60; font-style: italic; margin-left: 20px; }");
            writer.println("        .section { margin-bottom: 30px; }");
            writer.println("        .info { background-color: #ecf0f1; padding: 15px; border-radius: 5px; margin-bottom: 20px; }");
            writer.println("        .generation { margin-bottom: 20px; }");
            // Collapsible sections: each main heading is a <summary> styled like the old <h2>,
            // each generation/degree a nested <summary> styled like the old <h3>. A rotating
            // triangle replaces the default disclosure marker so it reads as intentional.
            writer.println("        details.section { margin-bottom: 30px; }");
            writer.println("        details.section > summary { list-style: none; cursor: pointer;"
                    + " color: #34495e; font-size: 24px; font-weight: bold; margin-top: 30px;"
                    + " margin-bottom: 15px; border-left: 4px solid #3498db; padding: 6px 0 6px 15px; }");
            writer.println("        details.section > summary::-webkit-details-marker { display: none; }");
            writer.println("        details.section > summary::before { content: '\\25B8'; color: #3498db;"
                    + " display: inline-block; width: 0.9em; margin-left: -0.2em; transition: transform 0.15s; }");
            writer.println("        details.section[open] > summary::before { transform: rotate(90deg); }");
            writer.println("        details.gen { margin-bottom: 20px; }");
            writer.println("        details.gen > summary { list-style: none; cursor: pointer;"
                    + " color: #2980b9; font-size: 20px; font-weight: bold; margin-top: 20px; margin-bottom: 10px; }");
            writer.println("        details.gen > summary::-webkit-details-marker { display: none; }");
            writer.println("        details.gen > summary::before { content: '\\25B8'; color: #2980b9;"
                    + " display: inline-block; width: 0.9em; margin-left: -0.2em; transition: transform 0.15s; }");
            writer.println("        details.gen[open] > summary::before { transform: rotate(90deg); }");
            writer.println("    </style>");
            writer.println(AncestorMapWriter.leafletHead());
            writer.println("</head>");
            writer.println("<body>");
            
            writer.println("    <h1>" + targetPerson.getDisplayName() + " Family Relationship Analysis</h1>");
            writer.println("    <div class=\"info\">");
            writer.println("        <strong>Target Person:</strong> " + targetPerson.getDisplayName() + "<br>");
            if (!targetPerson.getLifeDates().isEmpty()) {
                writer.println("        <strong>Life Dates:</strong> " + targetPerson.getLifeDates() + "<br>");
            }
            writer.println("        <strong>Person ID:</strong> " + personId + "<br>");
            writer.println("        <strong>GEDCOM File:</strong> " + gedcomFile);
            writer.println("    </div>");

            // Ancestor map (only rendered when the data carries coordinates)
            String mapHtml = buildAncestorMapHtml(analyzer, targetPerson);
            if (!mapHtml.isEmpty()) {
                openSection(writer, "ANCESTOR MAP", false);
                writer.print(mapHtml);
                closeSection(writer);
            }

            // Ancestors — returns each ancestor's generation set, reused by the notable section.
            Map<String, java.util.TreeSet<Integer>> ancestorGens =
                    writeAncestorsHtml(analyzer, targetPerson, writer);

            // Notable Ancestral Lines — its own top-level section now (written only when non-empty).
            writeNotableLineages(targetPerson, ancestorGens, writer);

            // Descendant map (only rendered when the data carries coordinates). Unlike the
            // ancestor map, this prefers current residence over death/birth location —
            // most descendants, especially recent generations, are still alive.
            String descendantMapHtml = buildDescendantMapHtml(analyzer, targetPerson);
            if (!descendantMapHtml.isEmpty()) {
                openSection(writer, "DESCENDANT MAP", false);
                writer.print(descendantMapHtml);
                closeSection(writer);
            }

            // Descendants (writes its own collapsible section)
            writeDescendantsHtml(analyzer, targetPerson, writer, gedcomData);

            // Siblings
            openSection(writer, "SIBLINGS", false);
            writeSiblingsHtml(analyzer, targetPerson, writer);
            closeSection(writer);

            // Cousin map (siblings + 1st-5th cousins, coloured by degree; only rendered
            // when the data carries coordinates). Also written as a standalone file.
            List<GeniAncestorFetcher.MapPoint> cousinPoints = buildCousinMapPoints(analyzer, targetPerson);
            if (!cousinPoints.isEmpty()) {
                openSection(writer, "COUSIN MAP", false);
                writer.print(new CousinMapWriter().mapSection(cousinPoints, "cousin-map", "500px"));
                closeSection(writer);

                String cousinMapPath = cousinMapOutputPath(htmlOutputFile);
                ensureOutputDirectoryExists(cousinMapPath);
                new CousinMapWriter().write(cousinPoints, cousinMapPath,
                        targetPerson.getDisplayName() + " Cousin Map");
                System.out.println("Cousin map written to: " + cousinMapPath);
            }

            // Cousins (writes its own collapsible section)
            writeCousinsHtml(analyzer, targetPerson, writer, gedcomData);
            
            writer.println("</body>");
            writer.println("</html>");
        }
    }
    
    /**
     * Build the embeddable ancestor-map HTML (target at generation 0, then ancestors by
     * generation, deduped). Returns an empty string when no one has coordinates, so the
     * map section is simply omitted for coordinate-less GEDCOMs.
     */
    private String buildAncestorMapHtml(FamilyRelationshipAnalyzer analyzer, Person targetPerson) throws IOException {
        List<GeniAncestorFetcher.MapPoint> points = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        seen.add(targetPerson.getId());
        GeniAncestorFetcher.MapPoint self = GeniAncestorFetcher.MapPoint.fromPerson(targetPerson, 0);
        if (self != null) {
            points.add(self);
        }

        Map<Integer, List<Person>> byGen = analyzer.getAncestorsByGeneration(targetPerson);
        int maxGen = byGen.keySet().stream().max(Integer::compareTo).orElse(0);
        for (int gen = 1; gen <= maxGen; gen++) {
            for (Person ancestor : byGen.getOrDefault(gen, new ArrayList<>())) {
                if (!seen.add(ancestor.getId())) {
                    continue;
                }
                GeniAncestorFetcher.MapPoint point = GeniAncestorFetcher.MapPoint.fromPerson(ancestor, gen);
                if (point != null) {
                    points.add(point);
                }
            }
        }
        return new AncestorMapWriter().mapSection(points, "ancestor-map", "500px");
    }

    /**
     * Build the embeddable descendant-map HTML (target at generation 0, then descendants
     * by generation, deduped). Unlike the ancestor map, points prefer current residence
     * over death/birth location — most descendants are still alive — and the generation
     * color scale uses a much smaller cap, since descendant trees are realistically only
     * a handful of generations deep. Returns an empty string when no one has coordinates.
     */
    private String buildDescendantMapHtml(FamilyRelationshipAnalyzer analyzer, Person targetPerson) throws IOException {
        List<GeniAncestorFetcher.MapPoint> points = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        seen.add(targetPerson.getId());
        GeniAncestorFetcher.MapPoint self = GeniAncestorFetcher.MapPoint.fromPersonPreferCurrent(targetPerson, 0);
        if (self != null) {
            points.add(self);
        }

        Map<Integer, List<Person>> byGen = analyzer.getDescendantsByGeneration(targetPerson);
        int maxGen = byGen.keySet().stream().max(Integer::compareTo).orElse(0);
        for (int gen = 1; gen <= maxGen; gen++) {
            for (Person descendant : byGen.getOrDefault(gen, new ArrayList<>())) {
                if (!seen.add(descendant.getId())) {
                    continue;
                }
                GeniAncestorFetcher.MapPoint point = GeniAncestorFetcher.MapPoint.fromPersonPreferCurrent(descendant, gen);
                if (point != null) {
                    points.add(point);
                }
            }
        }
        return new AncestorMapWriter().mapSection(points, "descendant-map", "500px", 8);
    }

    /**
     * Build map points for siblings and 1st-5th cousins, coloured by relationship
     * degree (0 = sibling ... 5 = 5th cousin — see {@link CousinMapWriter}). Each
     * person appears at most once, at their nearest degree; {@code getCousinsGroupedByFamily}
     * already excludes closer relatives from each degree's results, so no further
     * cross-degree dedup is needed beyond guarding duplicate entries within one degree.
     */
    private List<GeniAncestorFetcher.MapPoint> buildCousinMapPoints(FamilyRelationshipAnalyzer analyzer, Person targetPerson) {
        List<GeniAncestorFetcher.MapPoint> points = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (Person sibling : analyzer.getSiblings(targetPerson)) {
            if (!seen.add(sibling.getId())) {
                continue;
            }
            GeniAncestorFetcher.MapPoint point = GeniAncestorFetcher.MapPoint.fromPersonPreferCurrent(sibling, 0);
            if (point != null) {
                points.add(point);
            }
        }

        for (int degree = 1; degree <= 5; degree++) {
            Map<String, List<Person>> groupedCousins = analyzer.getCousinsGroupedByFamily(targetPerson, degree);
            for (List<Person> cousins : groupedCousins.values()) {
                for (Person cousin : cousins) {
                    if (!seen.add(cousin.getId())) {
                        continue;
                    }
                    GeniAncestorFetcher.MapPoint point = GeniAncestorFetcher.MapPoint.fromPersonPreferCurrent(cousin, degree);
                    if (point != null) {
                        points.add(point);
                    }
                }
            }
        }
        return points;
    }

    /** Derive the standalone cousin-map path from the main HTML output path (e.g. "x.html" -> "x-cousins-map.html"). */
    private String cousinMapOutputPath(String htmlOutputFile) {
        if (htmlOutputFile.toLowerCase().endsWith(".html")) {
            return htmlOutputFile.substring(0, htmlOutputFile.length() - 5) + "-cousins-map.html";
        }
        return htmlOutputFile + "-cousins-map.html";
    }

    /** Open a collapsible top-level section whose clickable summary is the heading. */
    private void openSection(PrintWriter writer, String heading, boolean open) {
        writer.println("    <details class=\"section\"" + (open ? " open" : "") + ">");
        writer.println("        <summary>" + heading + "</summary>");
    }

    private void closeSection(PrintWriter writer) {
        writer.println("    </details>");
    }

    /** Open a collapsible generation/degree sub-accordion (nested inside a section). */
    private void openGen(PrintWriter writer, String heading, boolean open) {
        writer.println("        <details class=\"gen\"" + (open ? " open" : "") + ">");
        writer.println("            <summary>" + heading + "</summary>");
    }

    private void closeGen(PrintWriter writer) {
        writer.println("        </details>");
    }

    /**
     * Write the (collapsible) ANCESTORS section. Returns the per-ancestor generation sets
     * (person id -> the generations they appear in), which the Notable Ancestral Lines section
     * reuses to place each notable ancestor; an empty map when there are no ancestors.
     */
    private Map<String, java.util.TreeSet<Integer>> writeAncestorsHtml(
            FamilyRelationshipAnalyzer analyzer, Person targetPerson, PrintWriter writer) {
        Map<Integer, List<Person>> ancestorsByGen = analyzer.getAncestorsByGeneration(targetPerson);

        if (ancestorsByGen.isEmpty()) {
            openSection(writer, "ANCESTORS", false);
            writer.println("        <p>No ancestors found.</p>");
            closeSection(writer);
            return new java.util.HashMap<>();
        }
        int maxGen = ancestorsByGen.keySet().stream().max(Integer::compareTo).orElse(1);

        // Which generations each ancestor appears in — a person reached via lines of
        // different lengths (pedigree collapse) shows up in more than one.
        Map<String, java.util.TreeSet<Integer>> personGens = new java.util.HashMap<>();
        for (int gen = 1; gen <= maxGen; gen++) {
            for (Person p : ancestorsByGen.getOrDefault(gen, new ArrayList<>())) {
                personGens.computeIfAbsent(p.getId(), k -> new java.util.TreeSet<>()).add(gen);
            }
        }

        // Collapse each generation up front so the section total (sum of the
        // per-generation counts below) can be shown in the heading before the
        // per-generation breakdown. A pedigree-collapse ancestor who appears in more
        // than one generation is counted once per generation here, same as the
        // per-generation subtotals it's summing — not deduplicated across generations.
        LinkedHashMap<Integer, LinkedHashMap<Person, Integer>> collapsedByGen = new LinkedHashMap<>();
        int total = 0;
        for (int gen = 1; gen <= maxGen; gen++) {
            List<Person> genList = ancestorsByGen.getOrDefault(gen, new ArrayList<>());
            if (genList.isEmpty()) continue;
            LinkedHashMap<Person, Integer> collapsed = collapseByPerson(genList);
            collapsedByGen.put(gen, collapsed);
            total += collapsed.size();
        }

        openSection(writer, "ANCESTORS (" + total + ")", false);
        for (Map.Entry<Integer, LinkedHashMap<Person, Integer>> genEntry : collapsedByGen.entrySet()) {
            int gen = genEntry.getKey();
            LinkedHashMap<Person, Integer> collapsed = genEntry.getValue();
            openGen(writer, ancestorGenLabelPlural(gen) + " (" + collapsed.size() + ")", false);
            for (Map.Entry<Person, Integer> entry : collapsed.entrySet()) {
                Person a = entry.getKey();
                String crossRef = ancestorCrossReference(gen, personGens.get(a.getId()));
                // Grandparents and beyond (gen >= 2): ↑ children (toward you), ↓ parents (further back).
                String up = gen >= 2 ? joinNames(a.getChildren()) : "";
                String down = gen >= 2 ? joinNames(a.getParents()) : "";
                writePersonEntry(writer, a, entry.getValue(), crossRef, up, down);
            }
            closeGen(writer);
        }
        closeSection(writer);
        return personGens;
    }

    /**
     * The NOTABLE ANCESTRAL LINES section (its own top-level collapsible section, written right
     * after ANCESTORS): distinct notable ancestors (each once, closest relationship first) plus
     * the deepest documented lines. Auto-detected from titles in the names — display-only,
     * computed here, not stored. Writes nothing at all when there is no notable content, so the
     * caller doesn't need to guard against an empty section. {@code personGens} comes from
     * {@link #writeAncestorsHtml}.
     */
    private void writeNotableLineages(Person targetPerson,
                                      Map<String, java.util.TreeSet<Integer>> personGens,
                                      PrintWriter writer) {
        LineageAnalysis lineage = new LineageAnalysis(targetPerson);
        NotableAnnotations annotations = NotableAnnotations.getDefault();
        // Notable = flagged by the name heuristic OR carrying a SUBSTANTIAL Geni about_me
        // (long encyclopedic prose, not a short imported GEDCOM note), which catches title-less
        // notables the name regex misses (e.g. the Abarbanel) without flooding the list with
        // ordinary relatives whose about_me is just a one-line note.
        List<Person> notable = new ArrayList<>();
        for (Person p : lineage.allAncestors()) {
            String g = guidOf(p);
            if (LineageAnalysis.looksNotable(p.getDisplayName()) || annotations.isNotable(g)
                    || annotations.isFamous(g)) {
                notable.add(p);
            }
        }
        List<LineageAnalysis.Line> deepest = lineage.deepestLines(15);
        if (notable.isEmpty() && deepest.isEmpty()) {
            return;
        }

        java.util.function.Function<Person, Integer> closestGen = p -> {
            java.util.TreeSet<Integer> gens = personGens.get(p.getId());
            return (gens == null || gens.isEmpty()) ? Integer.MAX_VALUE : gens.first();
        };
        notable.sort((a, b) -> {
            int cmp = Integer.compare(closestGen.apply(a), closestGen.apply(b));
            return cmp != 0 ? cmp : a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
        });

        openSection(writer, "NOTABLE ANCESTRAL LINES", false);
        writer.println("            <p style=\"color:#7f8c8d; font-style:italic;\">Auto-detected from titles / known families in the names, plus anyone with a Geni biography — a starting point, not individually verified.</p>");

        if (!notable.isEmpty()) {
            writer.println("            <strong style=\"color:#8e44ad; font-size:16px;\">Notable ancestors (" + notable.size() + "):</strong>");
            writer.println("            <p style=\"color:#7f8c8d; font-style:italic; margin:2px 0 8px;\">Grouped by how far back — click a heading to expand.</p>");

            // Bucket by 10-great-grandparent ranges (notable is already closest-first).
            LinkedHashMap<Integer, List<Person>> buckets = new LinkedHashMap<>();
            for (Person p : notable) {
                int gen = closestGen.apply(p);
                int ggp = (gen == Integer.MAX_VALUE) ? 1 : gen - 2; // 1st great-grandparent = gen 3
                int bucket = ggp <= 0 ? 1 : ((ggp - 1) / 10) + 1;
                buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(p);
            }
            for (Map.Entry<Integer, List<Person>> b : buckets.entrySet()) {
                List<Person> people = b.getValue();
                writer.println("            <details>");
                writer.println("                <summary style=\"cursor:pointer; font-weight:bold; color:#2980b9; padding:4px 0;\">"
                        + greatGrandBucketLabel(b.getKey()) + " (" + people.size() + ")</summary>");
                for (Person p : people) {
                    int gen = closestGen.apply(p);
                    String rel = (gen == Integer.MAX_VALUE) ? "" : " — " + ancestorGenLabelSingular(gen);
                    String dates = p.getLifeDates();
                    String guid = guidOf(p);
                    writer.println("                <div class=\"person\">");
                    writer.println("                    <span class=\"person-name\">" + p.getDisplayName() + "</span>"
                            + "<span class=\"cross-ref\">" + rel + "</span>"
                            + (dates.isEmpty() ? "" : " <span class=\"life-dates\">(" + dates + ")</span>")
                            + (guid == null ? "" : " <a href=\"" + geniProfileUrl(guid)
                                    + "\" target=\"_blank\" style=\"font-size:12px;\">[Geni]</a>"));
                    String bio = annotations.bio(guid);
                    if (bio != null) {
                        writer.println("                    <div style=\"color:#555; font-size:13px; margin:2px 0 0 16px;\">"
                                + escapeHtml(bio) + "</div>");
                    }
                    writer.println("                </div>");
                }
                writer.println("            </details>");

                // Always-visible "Including:" preview of the marquee (hand-flagged famous)
                // ancestors in this bucket, so the standout names show without expanding the
                // accordion. The full detail (dates, [Geni], bio) stays inside the accordion above.
                List<Person> famousHere = new ArrayList<>();
                for (Person p : people) {
                    if (annotations.isFamous(guidOf(p))) {
                        famousHere.add(p);
                    }
                }
                if (!famousHere.isEmpty()) {
                    writer.println("            <div style=\"margin:2px 0 12px 16px;\">");
                    writer.println("                <span style=\"color:#8e44ad; font-weight:bold; font-size:13px;\">Including:</span>");
                    for (Person p : famousHere) {
                        int gen = closestGen.apply(p);
                        String rel = (gen == Integer.MAX_VALUE) ? "" : " — " + ancestorGenLabelSingular(gen);
                        writer.println("                <div style=\"font-size:14px;\">"
                                + "<span class=\"person-name\">" + p.getDisplayName() + "</span>"
                                + "<span class=\"cross-ref\">" + rel + "</span></div>");
                    }
                    writer.println("            </div>");
                }
            }
        }

        if (!deepest.isEmpty()) {
            writer.println("            <strong style=\"color:#8e44ad; font-size:16px;\">Deepest documented lines:</strong>");
            for (LineageAnalysis.Line line : deepest) {
                writer.println("            <div class=\"person\">");
                writer.println("                &rarr; <span class=\"person-name\">" + line.endpoint.getDisplayName() + "</span>"
                        + " <span class=\"cross-ref\">— " + line.generations + " generations back</span>");
                writer.println("            </div>");
            }
        }
        closeSection(writer);
    }

    /** Person id -> bare Geni guid, or null if it isn't one (e.g. a "private-…" stub). */
    private String guidOf(Person p) {
        String id = p.getId();
        if (id == null) {
            return null;
        }
        String g = id.startsWith("I") ? id.substring(1) : id;
        // 6+ digits: covers both long Geni guids and the shorter numeric internal ids some
        // older profiles carry (e.g. Abraham Joshua Heschel = 3381491). Non-Geni ids (test
        // "I1", "private-…" stubs) fall below this or aren't all-digits.
        return g.matches("\\d{6,}") ? g : null;
    }

    /**
     * Public Geni profile URL for a guid. Kept in one place so the link format is a
     * one-line fix if it ever changes — the annotation file stores only the guid.
     */
    private String geniProfileUrl(String guid) {
        return "https://www.geni.com/people/x/" + guid;
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Section heading for an ancestor generation (e.g. 1 -> "Parents", 12 -> "Great 10 Grandparents"). */
    private String ancestorGenLabelPlural(int gen) {
        if (gen == 1) return "Parents";
        if (gen == 2) return "Grandparents";
        return "Great " + (gen - 2) + " Grandparents";
    }

    /** Singular label for a cross-reference (e.g. 1 -> "parent", 21 -> "19th great-grandparent"). */
    private String ancestorGenLabelSingular(int gen) {
        if (gen == 1) return "parent";
        if (gen == 2) return "grandparent";
        return ordinal(gen - 2) + " great-grandparent";
    }

    /** Accordion heading for a 10-great-grandparent bucket (1 -> "Up to 10th …", 3 -> "21st–30th …"). */
    private String greatGrandBucketLabel(int bucket) {
        int endGgp = bucket * 10;
        if (bucket == 1) {
            return "Up to " + ordinal(endGgp) + " Great-Grandparents";
        }
        int startGgp = (bucket - 1) * 10 + 1;
        return ordinal(startGgp) + "–" + ordinal(endGgp) + " Great-Grandparents";
    }

    /**
     * When an ancestor also appears at other generations (via other bloodlines), return a
     * note naming those other positions; empty string otherwise.
     */
    private String ancestorCrossReference(int currentGen, java.util.TreeSet<Integer> allGens) {
        if (allGens == null || allGens.size() < 2) {
            return "";
        }
        StringBuilder sb = new StringBuilder("also ");
        boolean first = true;
        for (int g : allGens) {
            if (g == currentGen) continue;
            if (!first) sb.append(", ");
            sb.append(ancestorGenLabelSingular(g));
            first = false;
        }
        return sb.toString();
    }

    private String ordinal(int n) {
        int mod100 = n % 100;
        String suffix;
        if (mod100 >= 11 && mod100 <= 13) {
            suffix = "th";
        } else {
            switch (n % 10) {
                case 1: suffix = "st"; break;
                case 2: suffix = "nd"; break;
                case 3: suffix = "rd"; break;
                default: suffix = "th";
            }
        }
        return n + suffix;
    }
    
    private void writeDescendantsHtml(FamilyRelationshipAnalyzer analyzer, Person targetPerson, PrintWriter writer, GedcomData gedcomData) {
        Map<Integer, List<Person>> descendantsByGen = analyzer.getDescendantsByGeneration(targetPerson);

        if (descendantsByGen.isEmpty()) {
            openSection(writer, "DESCENDANTS", false);
            writer.println("        <p>No descendants found.</p>");
            closeSection(writer);
            return;
        }
        int maxGen = descendantsByGen.keySet().stream().max(Integer::compareTo).orElse(1);

        // Collapse each generation up front so the section total (sum of the
        // per-generation counts below) can be shown in the heading first.
        LinkedHashMap<Integer, LinkedHashMap<Person, Integer>> collapsedByGen = new LinkedHashMap<>();
        int total = 0;
        for (int gen = 1; gen <= maxGen; gen++) {
            List<Person> genList = descendantsByGen.getOrDefault(gen, new ArrayList<>());
            if (genList.isEmpty()) continue;
            LinkedHashMap<Person, Integer> collapsed = collapseByPerson(genList);
            collapsedByGen.put(gen, collapsed);
            total += collapsed.size();
        }

        openSection(writer, "DESCENDANTS (" + total + ")", false);
        for (Map.Entry<Integer, LinkedHashMap<Person, Integer>> genEntry : collapsedByGen.entrySet()) {
            int gen = genEntry.getKey();
            LinkedHashMap<Person, Integer> collapsed = genEntry.getValue();

            String heading;
            if (gen == 1) heading = "Children";
            else if (gen == 2) heading = "Grandchildren";
            else heading = "Great " + (gen - 2) + " Grandchildren";

            openGen(writer, heading + " (" + collapsed.size() + ")", false);
            if (gen == 1) {
                // Children all share the target's own family (already named in the
                // info header above) — a per-family sub-heading would be redundant.
                for (Map.Entry<Person, Integer> entry : collapsed.entrySet()) {
                    writePersonEntry(writer, entry.getKey(), entry.getValue());
                }
            } else {
                writeDescendantsGroupedByParentFamily(collapsed, writer, gedcomData);
            }
            closeGen(writer);
        }
        closeSection(writer);
    }

    /**
     * Write descendants (grandchildren or deeper) grouped by their parent family, with a
     * "Children of X & Y (N):" sub-heading per family — matching how the COUSINS section
     * groups cousins by family. Useful once there are many descendants across several
     * different children's families, where a flat list no longer makes clear who's whose.
     */
    private void writeDescendantsGroupedByParentFamily(LinkedHashMap<Person, Integer> people, PrintWriter writer, GedcomData gedcomData) {
        LinkedHashMap<String, LinkedHashMap<Person, Integer>> byFamily = new LinkedHashMap<>();
        for (Map.Entry<Person, Integer> entry : people.entrySet()) {
            Person person = entry.getKey();
            List<String> familyIds = person.getFamilyIdsAsChild();
            if (familyIds.isEmpty()) {
                byFamily.computeIfAbsent("", k -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
            } else {
                for (String familyId : familyIds) {
                    byFamily.computeIfAbsent(familyId, k -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
                }
            }
        }

        for (Map.Entry<String, LinkedHashMap<Person, Integer>> familyEntry : byFamily.entrySet()) {
            String familyId = familyEntry.getKey();
            LinkedHashMap<Person, Integer> members = familyEntry.getValue();

            String familyDisplayName = familyId.isEmpty() ? "Unknown Family" : "Family " + familyId;
            if (!familyId.isEmpty() && gedcomData.getFamily(familyId) != null) {
                familyDisplayName = gedcomData.getFamily(familyId).getDisplayName();
            }

            writer.println("        <div style=\"margin-left: 20px; margin-bottom: 10px;\">");
            writer.println("            <strong style=\"color: #8e44ad; font-size: 16px;\">Children of "
                    + familyDisplayName + " (" + members.size() + "):</strong>");
            for (Map.Entry<Person, Integer> memberEntry : members.entrySet()) {
                // Grandchildren and beyond are grouped here (gen >= 2); their parents are already
                // named in the "Children of X & Y" header, so show ↓ their own children (next gen).
                Person d = memberEntry.getKey();
                writePersonEntry(writer, d, memberEntry.getValue(), "", "", joinNames(d.getChildren()));
            }
            writer.println("        </div>");
        }
    }
    
    private void writeSiblingsHtml(FamilyRelationshipAnalyzer analyzer, Person targetPerson, PrintWriter writer) {
        List<Person> siblings = analyzer.getSiblings(targetPerson);
        
        if (siblings.isEmpty()) {
            writer.println("        <p>No siblings found.</p>");
        } else {
            for (Person sibling : siblings) {
                writer.println("        <div class=\"person\">");
                writer.println("            <span class=\"person-name\">" + sibling.getDisplayName() + "</span>");
                writer.println("            <span class=\"person-id\"> (" + sibling.getId() + ")</span>");
                if (!sibling.getLifeDates().isEmpty()) {
                    writer.println("            <div class=\"life-dates\">" + sibling.getLifeDates() + "</div>");
                }
                writer.println("        </div>");
            }
        }
    }
    
    /**
     * Collapse a list of persons by identity, preserving first-seen order and
     * counting occurrences. Used so a person who appears more than once in the
     * same sub-list (e.g. via pedigree collapse when cousins marry) is listed once.
     */
    private LinkedHashMap<Person, Integer> collapseByPerson(List<Person> people) {
        LinkedHashMap<Person, Integer> collapsed = new LinkedHashMap<>();
        for (Person person : people) {
            collapsed.merge(person, 1, Integer::sum);
        }
        return collapsed;
    }

    /** Write a single person entry, appending a "(Nx)" marker when count > 1. */
    private void writePersonEntry(PrintWriter writer, Person person, int count) {
        writePersonEntry(writer, person, count, "", "", "");
    }

    private void writePersonEntry(PrintWriter writer, Person person, int count, String crossRef) {
        writePersonEntry(writer, person, count, crossRef, "", "");
    }

    /**
     * Write a single person entry: name, then id, then an optional inline lineage note
     * "(↑ upNames, ↓ downNames)" — plus an optional "(Nx)" marker and cross-reference on the name.
     * The arrows follow list/page direction (↑ = toward the target / up the page, ↓ = further
     * away / down the page), so the caller passes whichever relationship belongs on each side —
     * ancestors: ↑ children, ↓ parents; descendants: ↓ children. {@code upNames}/{@code downNames}
     * are pre-formatted name lists (see {@link #joinNames}); either may be empty.
     */
    private void writePersonEntry(PrintWriter writer, Person person, int count, String crossRef,
                                  String upNames, String downNames) {
        String dupMarker = count > 1 ? " <span class=\"dup-count\">(" + count + "x)</span>" : "";
        String crossRefSpan = (crossRef != null && !crossRef.isEmpty())
                ? " <span class=\"cross-ref\">— " + crossRef + "</span>" : "";
        String lineage = lineageContext(upNames, downNames);
        writer.println("            <div class=\"person\">");
        writer.println("                <span class=\"person-name\">" + person.getDisplayName() + "</span>"
                + dupMarker + crossRefSpan
                + " <span class=\"person-id\">(" + person.getId() + ")</span>"
                + lineage);
        if (!person.getLifeDates().isEmpty()) {
            writer.println("                <div class=\"life-dates\">" + person.getLifeDates() + "</div>");
        }
        writer.println("            </div>");
    }

    /**
     * Inline "(↑ upNames, ↓ downNames)" context in the secondary grey, or "" when both are empty.
     * The name lists are already formatted by {@link #joinNames}; the arrows follow page
     * direction, not tree direction — the caller decides which relationship goes on each side.
     */
    private String lineageContext(String upNames, String downNames) {
        StringBuilder parts = new StringBuilder();
        if (upNames != null && !upNames.isEmpty()) {
            parts.append("&uarr; ").append(upNames);
        }
        if (downNames != null && !downNames.isEmpty()) {
            if (parts.length() > 0) parts.append(", ");
            parts.append("&darr; ").append(downNames);
        }
        return parts.length() == 0 ? "" : " <span class=\"lineage\">(" + parts + ")</span>";
    }

    /** Comma-joined, HTML-escaped, bidi-isolated display names; skips blank/placeholder names. */
    private String joinNames(List<Person> people) {
        if (people == null || people.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Person p : people) {
            String name = p.getDisplayName();
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append("<span class=\"nm\">").append(escapeHtml(name.trim())).append("</span>");
        }
        return sb.toString();
    }

    private void writeCousinsHtml(FamilyRelationshipAnalyzer analyzer, Person targetPerson, PrintWriter writer, GedcomData gedcomData) {
        // Collapse every degree up front so the section total (sum of each degree's
        // subtotal below) can be shown in the heading before the per-degree breakdown.
        LinkedHashMap<Integer, Map<String, LinkedHashMap<Person, Integer>>> collapsedByDegree = new LinkedHashMap<>();
        LinkedHashMap<Integer, Integer> countByDegree = new LinkedHashMap<>();
        int grandTotal = 0;
        for (int degree = 1; degree <= 6; degree++) {
            Map<String, List<Person>> groupedCousins = analyzer.getCousinsGroupedByFamily(targetPerson, degree);
            if (groupedCousins.isEmpty()) continue;

            Map<String, LinkedHashMap<Person, Integer>> collapsedGroups = new LinkedHashMap<>();
            int totalCount = 0;
            for (Map.Entry<String, List<Person>> entry : groupedCousins.entrySet()) {
                LinkedHashMap<Person, Integer> collapsed = collapseByPerson(entry.getValue());
                collapsedGroups.put(entry.getKey(), collapsed);
                totalCount += collapsed.size();
            }
            collapsedByDegree.put(degree, collapsedGroups);
            countByDegree.put(degree, totalCount);
            grandTotal += totalCount;
        }

        openSection(writer, "COUSINS (" + grandTotal + ")", false);

        if (collapsedByDegree.isEmpty()) {
            writer.println("        <p>No cousins found.</p>");
            closeSection(writer);
            return;
        }

        for (Map.Entry<Integer, Map<String, LinkedHashMap<Person, Integer>>> degreeEntry : collapsedByDegree.entrySet()) {
            int degree = degreeEntry.getKey();
            String degreeText = degree == 1 ? "1st" : degree == 2 ? "2nd" : degree == 3 ? "3rd" : degree + "th";
            openGen(writer, degreeText + " Cousins (" + countByDegree.get(degree) + ")", false);

            for (Map.Entry<String, LinkedHashMap<Person, Integer>> entry : degreeEntry.getValue().entrySet()) {
                String familyId = entry.getKey();
                LinkedHashMap<Person, Integer> cousins = entry.getValue();

                // Get family display name
                String familyDisplayName = "Family " + familyId;
                if (gedcomData.getFamily(familyId) != null) {
                    familyDisplayName = gedcomData.getFamily(familyId).getDisplayName();
                }

                if (cousins.size() > 1) {
                    writer.println("        <div style=\"margin-left: 20px; margin-bottom: 10px;\">");
                    writer.println("            <strong style=\"color: #8e44ad; font-size: 16px;\">Children of " + familyDisplayName + " (" + cousins.size() + " cousins):</strong>");
                } else {
                    writer.println("        <div style=\"margin-left: 20px; margin-bottom: 10px;\">");
                    writer.println("            <strong style=\"color: #8e44ad; font-size: 16px;\">Children of " + familyDisplayName + ":</strong>");
                }

                for (Map.Entry<Person, Integer> cousinEntry : cousins.entrySet()) {
                    writePersonEntry(writer, cousinEntry.getKey(), cousinEntry.getValue());
                }
                writer.println("        </div>");
            }
            closeGen(writer);
        }
        closeSection(writer);
    }

    private void displayAncestors(FamilyRelationshipAnalyzer analyzer, Person targetPerson) {
        System.out.println("ANCESTORS:");
        System.out.println("----------");
        Map<Integer, List<Person>> ancestorsByGen = analyzer.getAncestorsByGeneration(targetPerson);
        
        if (ancestorsByGen.isEmpty()) {
            System.out.println("No ancestors found.");
        } else {
            int maxGen = ancestorsByGen.keySet().stream().max(Integer::compareTo).orElse(1);
            for (int gen = 1; gen <= maxGen; gen++) {
                List<Person> genList = ancestorsByGen.getOrDefault(gen, new ArrayList<>());
                if (genList.isEmpty()) continue;
                String heading;
                if (gen == 1) heading = "Parents:";
                else if (gen == 2) heading = "Grandparents:";
                else heading = "Great_" + (gen - 2) + "_Grandparents:";
                System.out.println(heading);
                for (Person ancestor : genList) {
                    System.out.println("  " + ancestor.getDisplayName() + " (" + ancestor.getId() + ")");
                    if (!ancestor.getLifeDates().isEmpty()) {
                        System.out.println("    " + ancestor.getLifeDates());
                    }
                }
            }
        }
        System.out.println();
    }
    
    /**
     * Display descendants of the target person.
     */
    private void displayDescendants(FamilyRelationshipAnalyzer analyzer, Person targetPerson) {
        System.out.println("DESCENDANTS:");
        System.out.println("------------");
        Map<Integer, List<Person>> descendantsByGen = analyzer.getDescendantsByGeneration(targetPerson);
        
        if (descendantsByGen.isEmpty()) {
            System.out.println("No descendants found.");
        } else {
            int maxGen = descendantsByGen.keySet().stream().max(Integer::compareTo).orElse(1);
            for (int gen = 1; gen <= maxGen; gen++) {
                List<Person> genList = descendantsByGen.getOrDefault(gen, new ArrayList<>());
                if (genList.isEmpty()) continue;
                String heading;
                if (gen == 1) heading = "Children:";
                else if (gen == 2) heading = "Grandchildren:";
                else heading = "Great_" + (gen - 2) + "_Grandchildren:";
                System.out.println(heading);
                for (Person descendant : genList) {
                    System.out.println("  " + descendant.getDisplayName() + " (" + descendant.getId() + ")");
                    if (!descendant.getLifeDates().isEmpty()) {
                        System.out.println("    " + descendant.getLifeDates());
                    }
                }
            }
        }
        System.out.println();
    }
    
    /**
     * Display siblings of the target person.
     */
    private void displaySiblings(FamilyRelationshipAnalyzer analyzer, Person targetPerson) {
        System.out.println("SIBLINGS:");
        System.out.println("---------");
        
        List<Person> siblings = analyzer.getSiblings(targetPerson);
        if (siblings.isEmpty()) {
            System.out.println("No siblings found.");
        } else {
            for (Person sibling : siblings) {
                System.out.println("  " + sibling.getDisplayName() + " (" + sibling.getId() + ")");
                if (!sibling.getLifeDates().isEmpty()) {
                    System.out.println("    " + sibling.getLifeDates());
                }
            }
        }
        System.out.println();
    }
    
    /**
     * Display cousins of the target person (1st through 6th cousins).
     */
    private void displayCousins(FamilyRelationshipAnalyzer analyzer, Person targetPerson, GedcomData gedcomData) {
        System.out.println("COUSINS:");
        System.out.println("--------");
        
        boolean foundAnyCousins = false;
        for (int degree = 1; degree <= 6; degree++) {
            Map<String, List<Person>> groupedCousins = analyzer.getCousinsGroupedByFamily(targetPerson, degree);
            if (!groupedCousins.isEmpty()) {
                foundAnyCousins = true;
                String degreeText = getDegreeText(degree);
                
                // Calculate total count for this degree
                int totalCount = 0;
                for (List<Person> cousins : groupedCousins.values()) {
                    totalCount += cousins.size();
                }
                
                System.out.println(degreeText + " Cousins (" + totalCount + "):");
                
                for (Map.Entry<String, List<Person>> entry : groupedCousins.entrySet()) {
                    String familyId = entry.getKey();
                    List<Person> cousins = entry.getValue();
                    
                    // Get family display name
                    String familyDisplayName = "Family " + familyId;
                    if (gedcomData.getFamily(familyId) != null) {
                        familyDisplayName = gedcomData.getFamily(familyId).getDisplayName();
                    }
                    
                    if (cousins.size() > 1) {
                        System.out.println("  Children of " + familyDisplayName + " (" + cousins.size() + " cousins):");
                    } else {
                        System.out.println("  Children of " + familyDisplayName + ":");
                    }
                    
                    for (Person cousin : cousins) {
                        System.out.println("    " + cousin.getDisplayName() + " (" + cousin.getId() + ")");
                        if (!cousin.getLifeDates().isEmpty()) {
                            System.out.println("      " + cousin.getLifeDates());
                        }
                    }
                }
                System.out.println();
            }
        }
        
        if (!foundAnyCousins) {
            System.out.println("No cousins found.");
            System.out.println();
        }
    }
    
    /**
     * Get the text representation of a cousin degree.
     */
    private String getDegreeText(int degree) {
        switch (degree) {
            case 1: return "1ST";
            case 2: return "2ND";
            case 3: return "3RD";
            case 4: return "4TH";
            case 5: return "5TH";
            case 6: return "6TH";
            default: return degree + "TH";
        }
    }
    
    /**
     * Ensures the output directory exists for the given file path.
     * Creates any necessary parent directories.
     */
    private void ensureOutputDirectoryExists(String filePath) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
    }
} 