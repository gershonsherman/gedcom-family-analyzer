package com.wanderingjew.gedcomanalyzer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for GEDCOM 5.5.1 files.
 */
public class GedcomParser {
    private static final Pattern GEDCOM_LINE_PATTERN = Pattern.compile("^(\\d+)\\s+(@([^@]+)@\\s+)?([A-Z_]+)\\s*(.*)$");
    
    private Map<String, Person> persons = new HashMap<>();
    private Map<String, Family> families = new HashMap<>();
    
    /**
     * Parse a GEDCOM file and return the parsed data.
     */
    public GedcomData parseFile(String filePath) throws IOException {
        return parseFile(filePath, null);
    }
    
    /**
     * Parse a GEDCOM file and merge with existing data.
     * If existingData is null, starts fresh. If not null, merges with existing data.
     */
    public GedcomData parseFile(String filePath, GedcomData existingData) throws IOException {
        return parseFile(filePath, existingData, true);
    }
    
    /**
     * Parse a GEDCOM file and merge with existing data.
     * If existingData is null, starts fresh. If not null, merges with existing data.
     * @param buildRelationshipsNow if true, build relationships after parsing this file
     */
    public GedcomData parseFile(String filePath, GedcomData existingData, boolean buildRelationshipsNow) throws IOException {
        if (existingData == null) {
            persons.clear();
            families.clear();
        } else {
            persons = new HashMap<>(existingData.getPersons());
            families = new HashMap<>(existingData.getFamilies());
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            String currentId = null;
            String currentTag = null;
            int currentLevel = -1;
            boolean skipCurrentRecord = false;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                
                Matcher matcher = GEDCOM_LINE_PATTERN.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                
                int level = Integer.parseInt(matcher.group(1));
                String id = matcher.group(3);
                String tag = matcher.group(4);
                String value = matcher.group(5);
                
                // Handle level 0 records (individuals and families)
                if (level == 0) {
                    if (id != null) {
                        currentId = id;
                        // Check if this ID already exists - if so, skip this record
                        if (persons.containsKey(currentId) || families.containsKey(currentId)) {
                            skipCurrentRecord = true;
                        } else {
                            skipCurrentRecord = false;
                            if (tag.equals("INDI")) {
                                persons.put(currentId, new Person(currentId));
                            } else if (tag.equals("FAM")) {
                                families.put(currentId, new Family(currentId));
                            }
                        }
                    }
                } else if (level == 1) {
                    currentTag = tag;
                    if (currentId != null && !skipCurrentRecord) {
                        processLevel1Tag(currentId, tag, value);
                    }
                } else if (level == 2) {
                    if (currentId != null && currentTag != null && !skipCurrentRecord) {
                        processLevel2Tag(currentId, currentTag, tag, value);
                    }
                }
            }
        }
        
        // Build relationships only if requested
        if (buildRelationshipsNow) {
            buildRelationships();
        }
        
        return new GedcomData(persons, families);
    }
    
    /**
     * Parse multiple GEDCOM files and return combined data.
     * Duplicate IDs (same person/family in multiple files) are automatically skipped.
     */
    public GedcomData parseMultipleFiles(List<String> filePaths) throws IOException {
        GedcomData combinedData = null;
        
        for (String filePath : filePaths) {
            System.out.println("Parsing file: " + filePath);
            // Don't build relationships until all files are parsed
            combinedData = parseFile(filePath, combinedData, false);
        }
        
        // Build relationships once after all files are parsed
        if (combinedData != null) {
            persons = new HashMap<>(combinedData.getPersons());
            families = new HashMap<>(combinedData.getFamilies());
            buildRelationships();
            combinedData = new GedcomData(persons, families);
        }
        
        return combinedData;
    }
    
    /**
     * Process level 1 tags for individuals and families.
     */
    private void processLevel1Tag(String id, String tag, String value) {
        if (persons.containsKey(id)) {
            Person person = persons.get(id);
            switch (tag) {
                case "NAME":
                    parseName(person, value);
                    break;
                case "SEX":
                    person.setSex(value);
                    break;
                case "BIRT":
                    // Birth event - handled by level 2 tags
                    break;
                case "DEAT":
                    // Death event - handled by level 2 tags
                    break;
                case "FAMS":
                    person.addFamilyAsSpouse(cleanId(value));
                    break;
                case "FAMC":
                    person.addFamilyAsChild(cleanId(value));
                    break;
            }
        } else if (families.containsKey(id)) {
            Family family = families.get(id);
            switch (tag) {
                case "HUSB":
                    family.setHusbandId(cleanId(value));
                    break;
                case "WIFE":
                    family.setWifeId(cleanId(value));
                    break;
                case "CHIL":
                    family.addChild(cleanId(value));
                    break;
                case "MARR":
                    // Marriage event - handled by level 2 tags
                    break;
                case "DIV":
                    family.setDivorceDate(value);
                    break;
            }
        }
    }
    
    /**
     * Process level 2 tags (subordinate to level 1 tags).
     */
    private void processLevel2Tag(String id, String parentTag, String tag, String value) {
        if (persons.containsKey(id)) {
            Person person = persons.get(id);
            switch (parentTag) {
                case "NAME":
                    switch (tag) {
                        case "GIVN":
                            person.setGivenName(value);
                            break;
                        case "SURN":
                            person.setSurname(value);
                            break;
                    }
                    break;
                case "BIRT":
                    switch (tag) {
                        case "DATE":
                            person.setBirthDate(value);
                            break;
                        case "PLAC":
                            person.setBirthPlace(value);
                            break;
                    }
                    break;
                case "DEAT":
                    switch (tag) {
                        case "DATE":
                            person.setDeathDate(value);
                            break;
                        case "PLAC":
                            person.setDeathPlace(value);
                            break;
                    }
                    break;
            }
        } else if (families.containsKey(id)) {
            Family family = families.get(id);
            if (parentTag.equals("MARR")) {
                switch (tag) {
                    case "DATE":
                        family.setMarriageDate(value);
                        break;
                    case "PLAC":
                        family.setMarriagePlace(value);
                        break;
                }
            }
        }
    }
    
    /**
     * Parse a GEDCOM name value.
     * Prefers English names (ASCII characters) over Hebrew/foreign language names.
     */
    private void parseName(Person person, String nameValue) {
        // Check if this name contains non-ASCII characters (likely Hebrew/foreign)
        boolean isForeignName = !nameValue.matches("^[\\x00-\\x7F\\s/]+$");
        
        // If we already have an English name and this is a foreign name, skip it
        if (isForeignName && person.getGivenName() != null && 
            person.getGivenName().matches("^[\\x00-\\x7F\\s]+$")) {
            return;
        }
        
        // If we already have a foreign name and this is an English name, use the English one
        if (!isForeignName && person.getGivenName() != null && 
            !person.getGivenName().matches("^[\\x00-\\x7F\\s]+$")) {
            // Clear existing foreign name data
            person.setGivenName(null);
            person.setSurname(null);
            person.setFullName(null);
        }
        
        // GEDCOM name format: Given /Surname/
        String[] parts = nameValue.split("/");
        if (parts.length >= 2) {
            person.setGivenName(parts[0].trim());
            person.setSurname(parts[1].trim());
            
            // Build full name
            StringBuilder fullName = new StringBuilder();
            if (!parts[0].trim().isEmpty()) {
                fullName.append(parts[0].trim());
            }
            if (!parts[1].trim().isEmpty()) {
                if (fullName.length() > 0) {
                    fullName.append(" ");
                }
                fullName.append(parts[1].trim());
            }
            person.setFullName(fullName.toString());
        } else {
            person.setFullName(nameValue.trim());
        }
    }
    
    /**
     * Build relationships between persons and families.
     */
    private void buildRelationships() {
        // Link persons to families
        for (Family family : families.values()) {
            if (family.getHusbandId() != null && persons.containsKey(family.getHusbandId())) {
                family.setHusband(persons.get(family.getHusbandId()));
            }
            if (family.getWifeId() != null && persons.containsKey(family.getWifeId())) {
                family.setWife(persons.get(family.getWifeId()));
            }
            
            for (String childId : family.getChildrenIds()) {
                if (persons.containsKey(childId)) {
                    family.getChildren().add(persons.get(childId));
                }
            }
        }
        
        // Build person relationships
        for (Person person : persons.values()) {
            // Build parent relationships
            for (String familyId : person.getFamilyIdsAsChild()) {
                if (families.containsKey(familyId)) {
                    Family family = families.get(familyId);
                    if (family.getHusband() != null) {
                        person.getParents().add(family.getHusband());
                    }
                    if (family.getWife() != null) {
                        person.getParents().add(family.getWife());
                    }
                }
            }
            
            // Build spouse relationships
            for (String familyId : person.getFamilyIdsAsSpouse()) {
                if (families.containsKey(familyId)) {
                    Family family = families.get(familyId);
                    Person spouse = family.getSpouseOf(person);
                    if (spouse != null && !person.getSpouses().contains(spouse)) {
                        person.getSpouses().add(spouse);
                    }
                }
            }
            
            // Build sibling relationships
            for (String familyId : person.getFamilyIdsAsChild()) {
                if (families.containsKey(familyId)) {
                    Family family = families.get(familyId);
                    for (Person sibling : family.getChildren()) {
                        if (!sibling.equals(person) && !person.getSiblings().contains(sibling)) {
                            person.getSiblings().add(sibling);
                        }
                    }
                }
            }
            
            // Build children relationships
            for (String familyId : person.getFamilyIdsAsSpouse()) {
                if (families.containsKey(familyId)) {
                    Family family = families.get(familyId);
                    for (Person child : family.getChildren()) {
                        if (!person.getChildren().contains(child)) {
                            person.getChildren().add(child);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Clean an ID by removing @ symbols.
     */
    private String cleanId(String id) {
        if (id == null) {
            return null;
        }
        return id.replaceAll("@", "");
    }
} 