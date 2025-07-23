package com.wanderingjew.gedcomanalyzer;

import java.util.HashMap;
import java.util.Map;

/**
 * Container for parsed GEDCOM data.
 */
public class GedcomData {
    private Map<String, Person> persons;
    private Map<String, Family> families;
    
    public GedcomData(Map<String, Person> persons, Map<String, Family> families) {
        this.persons = persons;
        this.families = families;
    }
    
    public Map<String, Person> getPersons() {
        return persons;
    }
    
    public Map<String, Family> getFamilies() {
        return families;
    }
    
    /**
     * Get a person by ID.
     */
    public Person getPerson(String id) {
        return persons.get(id);
    }
    
    /**
     * Get a family by ID.
     */
    public Family getFamily(String id) {
        return families.get(id);
    }
    
    /**
     * Get the number of persons in the data.
     */
    public int getPersonCount() {
        return persons.size();
    }
    
    /**
     * Get the number of families in the data.
     */
    public int getFamilyCount() {
        return families.size();
    }
} 