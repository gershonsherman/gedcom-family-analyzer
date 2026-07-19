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

    // Track whether the current record's birth/death place is being assembled
    // from a multi-line ADDR block (CITY/STAE/CTRY) rather than a single PLAC.
    private boolean birthPlaceFromAddr = false;
    private boolean deathPlaceFromAddr = false;
    
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
            String currentLevel2Tag = null;
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
                    // Starting a new record: reset per-record place assembly state.
                    birthPlaceFromAddr = false;
                    deathPlaceFromAddr = false;
                    currentTag = null;
                    currentLevel2Tag = null;
                    if (id != null) {
                        currentId = id;
                        // Check if this ID already exists
                        if (persons.containsKey(currentId) || families.containsKey(currentId)) {
                            // Record exists, but we'll merge additional data if it's incomplete
                            skipCurrentRecord = false;
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
                    currentLevel2Tag = null;
                    if (currentId != null && !skipCurrentRecord) {
                        processLevel1Tag(currentId, tag, value);
                    }
                } else if (level == 2) {
                    currentLevel2Tag = tag;
                    if (currentId != null && currentTag != null && !skipCurrentRecord) {
                        processLevel2Tag(currentId, currentTag, tag, value);
                    }
                } else if (level == 3) {
                    if (currentId != null && currentTag != null && currentLevel2Tag != null && !skipCurrentRecord) {
                        processLevel3Tag(currentId, currentTag, currentLevel2Tag, tag, value);
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
                            if (!shouldSkipForeign(person.getGivenName(), value)) {
                                person.setGivenName(value);
                            }
                            break;
                        case "SURN":
                            // "NN" is a placeholder for an unknown surname; never let it
                            // overwrite a real one, and prefer a Latin surname over a foreign one.
                            if (!isUnknownSurname(value)
                                    && !shouldSkipForeign(person.getSurname(), value)) {
                                person.setSurname(value);
                            }
                            break;
                        case "_MARNM":
                            if (value != null && !value.trim().isEmpty()
                                    && !shouldSkipForeign(person.getMarriedName(), value)) {
                                person.setMarriedName(value.trim());
                            }
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
     * Process level 3 tags. Currently used to assemble a birth/death place from an
     * ADDR block (CITY/STAE/CTRY), for files that record places that way instead of PLAC.
     */
    private void processLevel3Tag(String id, String parentTag, String level2Tag, String tag, String value) {
        if (!persons.containsKey(id)) {
            return;
        }
        if (!"ADDR".equals(level2Tag)) {
            return;
        }
        if (!"BIRT".equals(parentTag) && !"DEAT".equals(parentTag)) {
            return;
        }
        if (!"CITY".equals(tag) && !"STAE".equals(tag) && !"CTRY".equals(tag)) {
            return;
        }
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        value = value.trim();
        Person person = persons.get(id);

        if ("BIRT".equals(parentTag)) {
            String existing = person.getBirthPlace();
            if (existing == null || existing.trim().isEmpty()) {
                person.setBirthPlace(value);
                birthPlaceFromAddr = true;
            } else if (birthPlaceFromAddr) {
                person.setBirthPlace(existing + ", " + value);
            }
            // else: a PLAC value already won; leave it alone.
        } else {
            String existing = person.getDeathPlace();
            if (existing == null || existing.trim().isEmpty()) {
                person.setDeathPlace(value);
                deathPlaceFromAddr = true;
            } else if (deathPlaceFromAddr) {
                person.setDeathPlace(existing + ", " + value);
            }
        }
    }

    /** True if this surname is the "NN" placeholder for an unknown/no name. */
    private boolean isUnknownSurname(String value) {
        return value != null && value.trim().equalsIgnoreCase("NN");
    }

    /** True if the value contains non-ASCII (e.g. Hebrew) characters. */
    private boolean isForeign(String value) {
        return value != null && !value.matches("^[\\x00-\\x7F\\s]*$");
    }

    /**
     * True when an incoming foreign value should be skipped because we already
     * hold a non-empty Latin (ASCII) value — we prefer the Latin spelling.
     */
    private boolean shouldSkipForeign(String existing, String incoming) {
        return isForeign(incoming)
                && existing != null && !existing.trim().isEmpty() && !isForeign(existing);
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
            String given = parts[0].trim();
            String sur = parts[1].trim();
            person.setGivenName(given);
            // Skip the "NN" placeholder so it doesn't become a literal surname.
            if (!isUnknownSurname(sur)) {
                person.setSurname(sur);
            }

            // Build full name
            StringBuilder fullName = new StringBuilder();
            if (!given.isEmpty()) {
                fullName.append(given);
            }
            if (!sur.isEmpty() && !isUnknownSurname(sur)) {
                if (fullName.length() > 0) {
                    fullName.append(" ");
                }
                fullName.append(sur);
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