package com.wanderingjew.gedcomanalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a person in the GEDCOM file.
 */
public class Person {
    private String id;
    private String givenName;
    private String surname;
    private String marriedName;
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

    public String getMarriedName() { return marriedName; }
    public void setMarriedName(String marriedName) { this.marriedName = marriedName; }

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
     * When a married name is recorded, women are shown as
     * "Given Married (Maiden)", e.g. "Annie Sherman (Dreyer)".
     */
    public String getDisplayName() {
        String given = trimToNull(givenName);
        String maiden = maidenSurname();
        String married = trimToNull(marriedName);

        if (married != null) {
            StringBuilder name = new StringBuilder();
            if (given != null) {
                name.append(given).append(" ");
            }
            name.append(married);
            // Only show the maiden name when it differs from the married name.
            if (maiden != null && !maiden.equalsIgnoreCase(married)) {
                name.append(" (").append(maiden).append(")");
            }
            return name.toString();
        }

        StringBuilder name = new StringBuilder();
        if (given != null) {
            name.append(given);
        }
        if (maiden != null) {
            if (name.length() > 0) {
                name.append(" ");
            }
            name.append(maiden);
        }
        if (name.length() > 0) {
            return name.toString();
        }

        if (fullName != null && !fullName.trim().isEmpty()) {
            return fullName.trim();
        }
        return "Unknown (" + id + ")";
    }

    /**
     * Get birth and death information as a string: year plus place, e.g.
     * "b. 1878 (Kraków) - d. 1972 (New York)".
     */
    public String getLifeDates() {
        String birth = formatEvent("b.", birthDate, birthPlace);
        String death = formatEvent("d.", deathDate, deathPlace);

        if (birth.isEmpty()) {
            return death;
        }
        if (death.isEmpty()) {
            return birth;
        }
        return birth + " - " + death;
    }

    private String formatEvent(String prefix, String date, String place) {
        String year = extractYear(date);
        boolean hasPlace = place != null && !place.trim().isEmpty();
        if (year == null && !hasPlace) {
            return "";
        }
        StringBuilder sb = new StringBuilder(prefix);
        if (year != null) {
            sb.append(" ").append(year);
        }
        if (hasPlace) {
            sb.append(" (").append(place.trim()).append(")");
        }
        return sb.toString();
    }

    private static final Pattern YEAR_PATTERN = Pattern.compile("(\\d{4})");

    /** Extract the first 4-digit year from a GEDCOM date value (handles "BET 1732 AND 1735", "ABT 1900", etc.). */
    private String extractYear(String date) {
        if (date == null) {
            return null;
        }
        Matcher m = YEAR_PATTERN.matcher(date);
        return m.find() ? m.group(1) : null;
    }

    /** Surname for display, treating the "NN" placeholder (no/unknown name) as absent. */
    private String maidenSurname() {
        String s = trimToNull(surname);
        if (s == null || s.equalsIgnoreCase("NN")) {
            return null;
        }
        return s;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
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