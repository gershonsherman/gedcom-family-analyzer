package com.wanderingjew.gedcomanalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a person in the GEDCOM file.
 */
public class Person {
    private String id;
    private String givenName;
    private String surname;
    private String fullName;
    private String birthDate;
    private String deathDate;
    private String birthPlace;
    private String deathPlace;
    private String sex;
    private List<String> familyIdsAsChild = new ArrayList<>();
    private List<String> familyIdsAsSpouse = new ArrayList<>();
    
    // Computed relationships
    private List<Person> parents = new ArrayList<>();
    private List<Person> children = new ArrayList<>();
    private List<Person> spouses = new ArrayList<>();
    private List<Person> siblings = new ArrayList<>();

    public Person(String id) {
        this.id = id;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getGivenName() { return givenName; }
    public void setGivenName(String givenName) { this.givenName = givenName; }

    public String getSurname() { return surname; }
    public void setSurname(String surname) { this.surname = surname; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getBirthDate() { return birthDate; }
    public void setBirthDate(String birthDate) { this.birthDate = birthDate; }

    public String getDeathDate() { return deathDate; }
    public void setDeathDate(String deathDate) { this.deathDate = deathDate; }

    public String getBirthPlace() { return birthPlace; }
    public void setBirthPlace(String birthPlace) { this.birthPlace = birthPlace; }

    public String getDeathPlace() { return deathPlace; }
    public void setDeathPlace(String deathPlace) { this.deathPlace = deathPlace; }

    public String getSex() { return sex; }
    public void setSex(String sex) { this.sex = sex; }

    public List<String> getFamilyIdsAsChild() { return familyIdsAsChild; }
    public void setFamilyIdsAsChild(List<String> familyIdsAsChild) { this.familyIdsAsChild = familyIdsAsChild; }

    public List<String> getFamilyIdsAsSpouse() { return familyIdsAsSpouse; }
    public void setFamilyIdsAsSpouse(List<String> familyIdsAsSpouse) { this.familyIdsAsSpouse = familyIdsAsSpouse; }

    public List<Person> getParents() { return parents; }
    public void setParents(List<Person> parents) { this.parents = parents; }

    public List<Person> getChildren() { return children; }
    public void setChildren(List<Person> children) { this.children = children; }

    public List<Person> getSpouses() { return spouses; }
    public void setSpouses(List<Person> spouses) { this.spouses = spouses; }

    public List<Person> getSiblings() { return siblings; }
    public void setSiblings(List<Person> siblings) { this.siblings = siblings; }

    /**
     * Add a family ID where this person is a child.
     */
    public void addFamilyAsChild(String familyId) {
        if (!familyIdsAsChild.contains(familyId)) {
            familyIdsAsChild.add(familyId);
        }
    }

    /**
     * Add a family ID where this person is a spouse.
     */
    public void addFamilyAsSpouse(String familyId) {
        if (!familyIdsAsSpouse.contains(familyId)) {
            familyIdsAsSpouse.add(familyId);
        }
    }

    /**
     * Get a display name for the person.
     */
    public String getDisplayName() {
        if (fullName != null && !fullName.trim().isEmpty()) {
            return fullName;
        }
        
        StringBuilder name = new StringBuilder();
        if (givenName != null && !givenName.trim().isEmpty()) {
            name.append(givenName.trim());
        }
        if (surname != null && !surname.trim().isEmpty()) {
            if (name.length() > 0) {
                name.append(" ");
            }
            name.append(surname.trim());
        }
        
        if (name.length() == 0) {
            return "Unknown (" + id + ")";
        }
        
        return name.toString();
    }

    /**
     * Get birth and death information as a string.
     */
    public String getLifeDates() {
        StringBuilder dates = new StringBuilder();
        
        if (birthDate != null && !birthDate.trim().isEmpty()) {
            dates.append("b. ").append(birthDate.trim());
        }
        
        if (deathDate != null && !deathDate.trim().isEmpty()) {
            if (dates.length() > 0) {
                dates.append(" - ");
            }
            dates.append("d. ").append(deathDate.trim());
        }
        
        return dates.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Person person = (Person) obj;
        return Objects.equals(id, person.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return getDisplayName() + " (" + id + ")";
    }
} 