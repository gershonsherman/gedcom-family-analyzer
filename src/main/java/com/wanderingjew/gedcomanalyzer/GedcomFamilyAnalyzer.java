package com.wanderingjew.gedcomanalyzer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * Main class for GEDCOM Family Relationship Analyzer.
 * Analyzes family relationships in GEDCOM files.
 */
public class GedcomFamilyAnalyzer {
    
    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.out.println("Usage: java -jar gedcom-family-analyzer.jar <gedcom-files> <person-id> [html-output-file]");
            System.out.println("  gedcom-files: Path to the GEDCOM file(s) - single file or comma-separated list");
            System.out.println("  person-id: ID of the person to analyze (with or without @ symbols)");
            System.out.println("  html-output-file: Optional path to HTML output file");
            System.out.println();
            System.out.println("Examples:");
            System.out.println("  Single file: java -jar gedcom-family-analyzer.jar family1.ged I1");
            System.out.println("  Multiple files: java -jar gedcom-family-analyzer.jar \"family1.ged,family2.ged\" I1");
            System.out.println("  With HTML output: java -jar gedcom-family-analyzer.jar \"family1.ged,family2.ged\" I1 output.html");
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
            GedcomData gedcomData;
            
            if (gedcomFiles.contains(",")) {
                // Multiple files - first strip outer quotes from entire string
                String cleanedFiles = gedcomFiles.trim();
                if (cleanedFiles.startsWith("\"") && cleanedFiles.endsWith("\"")) {
                    cleanedFiles = cleanedFiles.substring(1, cleanedFiles.length() - 1);
                }
                
                String[] filePaths = cleanedFiles.split(",");
                List<String> fileList = new ArrayList<>();
                for (String filePath : filePaths) {
                    // Remove surrounding quotes and trim whitespace
                    String cleanedPath = filePath.trim();
                    if (cleanedPath.startsWith("\"") && cleanedPath.endsWith("\"")) {
                        cleanedPath = cleanedPath.substring(1, cleanedPath.length() - 1);
                    }
                    fileList.add(cleanedPath);
                }
                gedcomData = parser.parseMultipleFiles(fileList);
            } else {
                // Single file - also need to strip quotes
                String cleanedFile = gedcomFiles.trim();
                if (cleanedFile.startsWith("\"") && cleanedFile.endsWith("\"")) {
                    cleanedFile = cleanedFile.substring(1, cleanedFile.length() - 1);
                }
                gedcomData = parser.parseFile(cleanedFile);
            }
            
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
            writer.println("        .person-name { font-weight: bold; color: #2c3e50; }");
            writer.println("        .person-id { color: #7f8c8d; font-family: monospace; }");
            writer.println("        .life-dates { color: #27ae60; font-style: italic; margin-left: 20px; }");
            writer.println("        .section { margin-bottom: 30px; }");
            writer.println("        .info { background-color: #ecf0f1; padding: 15px; border-radius: 5px; margin-bottom: 20px; }");
            writer.println("        .generation { margin-bottom: 20px; }");
            writer.println("    </style>");
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
            
            // Ancestors
            writer.println("    <div class=\"section\">");
            writer.println("        <h2>ANCESTORS</h2>");
            writeAncestorsHtml(analyzer, targetPerson, writer);
            writer.println("    </div>");
            
            // Descendants
            writer.println("    <div class=\"section\">");
            writer.println("        <h2>DESCENDANTS</h2>");
            writeDescendantsHtml(analyzer, targetPerson, writer);
            writer.println("    </div>");
            
            // Siblings
            writer.println("    <div class=\"section\">");
            writer.println("        <h2>SIBLINGS</h2>");
            writeSiblingsHtml(analyzer, targetPerson, writer);
            writer.println("    </div>");
            
            // Cousins
            writer.println("    <div class=\"section\">");
            writer.println("        <h2>COUSINS</h2>");
            writeCousinsHtml(analyzer, targetPerson, writer, gedcomData);
            writer.println("    </div>");
            
            writer.println("</body>");
            writer.println("</html>");
        }
    }
    
    private void writeAncestorsHtml(FamilyRelationshipAnalyzer analyzer, Person targetPerson, PrintWriter writer) {
        Map<Integer, List<Person>> ancestorsByGen = analyzer.getAncestorsByGeneration(targetPerson);
        
        if (ancestorsByGen.isEmpty()) {
            writer.println("        <p>No ancestors found.</p>");
        } else {
            int maxGen = ancestorsByGen.keySet().stream().max(Integer::compareTo).orElse(1);
            for (int gen = 1; gen <= maxGen; gen++) {
                List<Person> genList = ancestorsByGen.getOrDefault(gen, new ArrayList<>());
                if (genList.isEmpty()) continue;
                
                String heading;
                if (gen == 1) heading = "Parents";
                else if (gen == 2) heading = "Grandparents";
                else heading = "Great " + (gen - 2) + " Grandparents";
                
                writer.println("        <div class=\"generation\">");
                writer.println("            <h3>" + heading + "</h3>");
                for (Person ancestor : genList) {
                    writer.println("            <div class=\"person\">");
                    writer.println("                <span class=\"person-name\">" + ancestor.getDisplayName() + "</span>");
                    writer.println("                <span class=\"person-id\"> (" + ancestor.getId() + ")</span>");
                    if (!ancestor.getLifeDates().isEmpty()) {
                        writer.println("                <div class=\"life-dates\">" + ancestor.getLifeDates() + "</div>");
                    }
                    writer.println("            </div>");
                }
                writer.println("        </div>");
            }
        }
    }
    
    private void writeDescendantsHtml(FamilyRelationshipAnalyzer analyzer, Person targetPerson, PrintWriter writer) {
        Map<Integer, List<Person>> descendantsByGen = analyzer.getDescendantsByGeneration(targetPerson);
        
        if (descendantsByGen.isEmpty()) {
            writer.println("        <p>No descendants found.</p>");
        } else {
            int maxGen = descendantsByGen.keySet().stream().max(Integer::compareTo).orElse(1);
            for (int gen = 1; gen <= maxGen; gen++) {
                List<Person> genList = descendantsByGen.getOrDefault(gen, new ArrayList<>());
                if (genList.isEmpty()) continue;
                
                String heading;
                if (gen == 1) heading = "Children";
                else if (gen == 2) heading = "Grandchildren";
                else heading = "Great " + (gen - 2) + " Grandchildren";
                
                writer.println("        <div class=\"generation\">");
                writer.println("            <h3>" + heading + "</h3>");
                for (Person descendant : genList) {
                    writer.println("            <div class=\"person\">");
                    writer.println("                <span class=\"person-name\">" + descendant.getDisplayName() + "</span>");
                    writer.println("                <span class=\"person-id\"> (" + descendant.getId() + ")</span>");
                    if (!descendant.getLifeDates().isEmpty()) {
                        writer.println("                <div class=\"life-dates\">" + descendant.getLifeDates() + "</div>");
                    }
                    writer.println("            </div>");
                }
                writer.println("        </div>");
            }
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
    
    private void writeCousinsHtml(FamilyRelationshipAnalyzer analyzer, Person targetPerson, PrintWriter writer, GedcomData gedcomData) {
        boolean foundAnyCousins = false;
        for (int degree = 1; degree <= 6; degree++) {
            Map<String, List<Person>> groupedCousins = analyzer.getCousinsGroupedByFamily(targetPerson, degree);
            if (!groupedCousins.isEmpty()) {
                foundAnyCousins = true;
                String degreeText = degree == 1 ? "1st" : degree == 2 ? "2nd" : degree == 3 ? "3rd" : degree + "th";
                
                // Calculate total count for this degree
                int totalCount = 0;
                for (List<Person> cousins : groupedCousins.values()) {
                    totalCount += cousins.size();
                }
                
                writer.println("        <h3>" + degreeText + " Cousins (" + totalCount + ")</h3>");
                
                for (Map.Entry<String, List<Person>> entry : groupedCousins.entrySet()) {
                    String familyId = entry.getKey();
                    List<Person> cousins = entry.getValue();
                    
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
                    
                    for (Person cousin : cousins) {
                        writer.println("            <div class=\"person\">");
                        writer.println("                <span class=\"person-name\">" + cousin.getDisplayName() + "</span>");
                        writer.println("                <span class=\"person-id\"> (" + cousin.getId() + ")</span>");
                        if (!cousin.getLifeDates().isEmpty()) {
                            writer.println("                <div class=\"life-dates\">" + cousin.getLifeDates() + "</div>");
                        }
                        writer.println("            </div>");
                    }
                    writer.println("        </div>");
                }
            }
        }
        
        if (!foundAnyCousins) {
            writer.println("        <p>No cousins found.</p>");
        }
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