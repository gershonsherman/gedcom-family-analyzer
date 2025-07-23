package com.wanderingjew.gedcomanalyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a family unit in the GEDCOM file.
 */
public class Family {
    private String id;
    private String husbandId;
    private String wifeId;
    private List<String> childrenIds = new ArrayList<>();
    private String marriageDate;
    private String marriagePlace;
    private String divorceDate;
    
    // Computed relationships
    private Person husband;
    private Person wife;
    private List<Person> children = new ArrayList<>();

    public Family(String id) {
        this.id = id;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getHusbandId() { return husbandId; }
    public void setHusbandId(String husbandId) { this.husbandId = husbandId; }

    public String getWifeId() { return wifeId; }
    public void setWifeId(String wifeId) { this.wifeId = wifeId; }

    public List<String> getChildrenIds() { return childrenIds; }
    public void setChildrenIds(List<String> childrenIds) { this.childrenIds = childrenIds; }

    public String getMarriageDate() { return marriageDate; }
    public void setMarriageDate(String marriageDate) { this.marriageDate = marriageDate; }

    public String getMarriagePlace() { return marriagePlace; }
    public void setMarriagePlace(String marriagePlace) { this.marriagePlace = marriagePlace; }

    public String getDivorceDate() { return divorceDate; }
    public void setDivorceDate(String divorceDate) { this.divorceDate = divorceDate; }

    public Person getHusband() { return husband; }
    public void setHusband(Person husband) { this.husband = husband; }

    public Person getWife() { return wife; }
    public void setWife(Person wife) { this.wife = wife; }

    public List<Person> getChildren() { return children; }
    public void setChildren(List<Person> children) { this.children = children; }

    /**
     * Add a child ID to this family.
     */
    public void addChild(String childId) {
        if (!childrenIds.contains(childId)) {
            childrenIds.add(childId);
        }
    }

    /**
     * Get marriage information as a string.
     */
    public String getMarriageInfo() {
        StringBuilder info = new StringBuilder();
        
        if (marriageDate != null && !marriageDate.trim().isEmpty()) {
            info.append("m. ").append(marriageDate.trim());
        }
        
        if (marriagePlace != null && !marriagePlace.trim().isEmpty()) {
            if (info.length() > 0) {
                info.append(" in ");
            }
            info.append(marriagePlace.trim());
        }
        
        if (divorceDate != null && !divorceDate.trim().isEmpty()) {
            if (info.length() > 0) {
                info.append(" - ");
            }
            info.append("div. ").append(divorceDate.trim());
        }
        
        return info.toString();
    }

    /**
     * Get the spouse of a given person in this family.
     */
    public Person getSpouseOf(Person person) {
        if (person.getId().equals(husbandId)) {
            return wife;
        } else if (person.getId().equals(wifeId)) {
            return husband;
        }
        return null;
    }

    /**
     * Get all parents in this family.
     */
    public List<Person> getParents() {
        List<Person> parents = new ArrayList<>();
        if (husband != null) {
            parents.add(husband);
        }
        if (wife != null) {
            parents.add(wife);
        }
        return parents;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Family ").append(id).append(": ");
        
        if (husband != null) {
            sb.append(husband.getDisplayName());
        } else if (husbandId != null) {
            sb.append("Husband ").append(husbandId);
        }
        
        sb.append(" & ");
        
        if (wife != null) {
            sb.append(wife.getDisplayName());
        } else if (wifeId != null) {
            sb.append("Wife ").append(wifeId);
        }
        
        if (!children.isEmpty()) {
            sb.append(" (").append(children.size()).append(" children)");
        }
        
        return sb.toString();
    }
} 