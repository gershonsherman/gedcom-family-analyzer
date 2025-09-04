package com.wanderingjew.gedcomanalyzer;

import java.util.*;

/**
 * Analyzes family relationships in GEDCOM data.
 */
public class FamilyRelationshipAnalyzer {
    private GedcomData gedcomData;
    
    public FamilyRelationshipAnalyzer(GedcomData gedcomData) {
        this.gedcomData = gedcomData;
    }
    
    /**
     * Get all ancestors of a person (parents, grandparents, etc.).
     */
    public List<Person> getAncestors(Person person) {
        Set<Person> ancestors = new HashSet<>();
        getAncestorsRecursive(person, ancestors, 0);
        return new ArrayList<>(ancestors);
    }
    
    /**
     * Get ancestors up to a specific generation.
     */
    public List<Person> getAncestors(Person person, int maxGenerations) {
        Set<Person> ancestors = new HashSet<>();
        getAncestorsRecursive(person, ancestors, maxGenerations);
        return new ArrayList<>(ancestors);
    }
    
    private void getAncestorsRecursive(Person person, Set<Person> ancestors, int maxGenerations) {
        getAncestorsRecursive(person, ancestors, maxGenerations, 0);
    }
    
    private void getAncestorsRecursive(Person person, Set<Person> ancestors, int maxGenerations, int currentGeneration) {
        if (person == null || (maxGenerations > 0 && currentGeneration >= maxGenerations)) {
            return;
        }
        
        for (Person parent : person.getParents()) {
            if (ancestors.add(parent)) {
                getAncestorsRecursive(parent, ancestors, maxGenerations, currentGeneration + 1);
            }
        }
    }
    
    /**
     * Get all descendants of a person (children, grandchildren, etc.).
     */
    public List<Person> getDescendants(Person person) {
        Set<Person> descendants = new HashSet<>();
        getDescendantsRecursive(person, descendants, 0);
        return new ArrayList<>(descendants);
    }
    
    /**
     * Get descendants up to a specific generation.
     */
    public List<Person> getDescendants(Person person, int maxGenerations) {
        Set<Person> descendants = new HashSet<>();
        getDescendantsRecursive(person, descendants, maxGenerations);
        return new ArrayList<>(descendants);
    }
    
    private void getDescendantsRecursive(Person person, Set<Person> descendants, int maxGenerations) {
        getDescendantsRecursive(person, descendants, maxGenerations, 0);
    }
    
    private void getDescendantsRecursive(Person person, Set<Person> descendants, int maxGenerations, int currentGeneration) {
        if (person == null || (maxGenerations > 0 && currentGeneration >= maxGenerations)) {
            return;
        }
        
        for (Person child : person.getChildren()) {
            if (descendants.add(child)) {
                getDescendantsRecursive(child, descendants, maxGenerations, currentGeneration + 1);
            }
        }
    }
    
    /**
     * Get siblings of a person.
     */
    public List<Person> getSiblings(Person person) {
        return new ArrayList<>(person.getSiblings());
    }
    
    /**
     * Get first cousins of a person.
     */
    public List<Person> getFirstCousins(Person person) {
        Set<Person> cousins = new HashSet<>();
        
        for (Person parent : person.getParents()) {
            for (Person uncleAunt : parent.getSiblings()) {
                cousins.addAll(uncleAunt.getChildren());
            }
        }
        
        // Remove the person themselves and their siblings
        cousins.remove(person);
        cousins.removeAll(person.getSiblings());
        
        return new ArrayList<>(cousins);
    }
    
    /**
     * Get second cousins of a person.
     */
    public List<Person> getSecondCousins(Person person) {
        Set<Person> cousins = new HashSet<>();
        
        for (Person parent : person.getParents()) {
            for (Person grandparent : parent.getParents()) {
                for (Person greatUncleAunt : grandparent.getSiblings()) {
                    for (Person firstCousinParent : greatUncleAunt.getChildren()) {
                        cousins.addAll(firstCousinParent.getChildren());
                    }
                }
            }
        }
        
        // Remove the person themselves, their siblings, and first cousins
        cousins.remove(person);
        cousins.removeAll(person.getSiblings());
        cousins.removeAll(getFirstCousins(person));
        
        return new ArrayList<>(cousins);
    }
    
    /**
     * Get third cousins of a person.
     */
    public List<Person> getThirdCousins(Person person) {
        Set<Person> cousins = new HashSet<>();
        
        for (Person parent : person.getParents()) {
            for (Person grandparent : parent.getParents()) {
                for (Person greatGrandparent : grandparent.getParents()) {
                    for (Person greatGreatUncleAunt : greatGrandparent.getSiblings()) {
                        for (Person secondCousinParent : greatGreatUncleAunt.getChildren()) {
                            for (Person firstCousinParent : secondCousinParent.getChildren()) {
                                cousins.addAll(firstCousinParent.getChildren());
                            }
                        }
                    }
                }
            }
        }
        
        // Remove closer relatives
        cousins.remove(person);
        cousins.removeAll(person.getSiblings());
        cousins.removeAll(getFirstCousins(person));
        cousins.removeAll(getSecondCousins(person));
        
        return new ArrayList<>(cousins);
    }
    
    /**
     * Get fourth cousins of a person.
     */
    public List<Person> getFourthCousins(Person person) {
        Set<Person> cousins = new HashSet<>();
        
        for (Person parent : person.getParents()) {
            for (Person grandparent : parent.getParents()) {
                for (Person greatGrandparent : grandparent.getParents()) {
                    for (Person greatGreatGrandparent : greatGrandparent.getParents()) {
                        for (Person greatGreatGreatUncleAunt : greatGreatGrandparent.getSiblings()) {
                            for (Person thirdCousinParent : greatGreatGreatUncleAunt.getChildren()) {
                                for (Person secondCousinParent : thirdCousinParent.getChildren()) {
                                    for (Person firstCousinParent : secondCousinParent.getChildren()) {
                                        cousins.addAll(firstCousinParent.getChildren());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Remove closer relatives
        cousins.remove(person);
        cousins.removeAll(person.getSiblings());
        cousins.removeAll(getFirstCousins(person));
        cousins.removeAll(getSecondCousins(person));
        cousins.removeAll(getThirdCousins(person));
        
        return new ArrayList<>(cousins);
    }
    
    /**
     * Get fifth cousins of a person.
     */
    public List<Person> getFifthCousins(Person person) {
        Set<Person> cousins = new HashSet<>();
        
        for (Person parent : person.getParents()) {
            for (Person grandparent : parent.getParents()) {
                for (Person greatGrandparent : grandparent.getParents()) {
                    for (Person greatGreatGrandparent : greatGrandparent.getParents()) {
                        for (Person greatGreatGreatGrandparent : greatGreatGrandparent.getParents()) {
                            for (Person greatGreatGreatGreatUncleAunt : greatGreatGreatGrandparent.getSiblings()) {
                                for (Person fourthCousinParent : greatGreatGreatGreatUncleAunt.getChildren()) {
                                    for (Person thirdCousinParent : fourthCousinParent.getChildren()) {
                                        for (Person secondCousinParent : thirdCousinParent.getChildren()) {
                                            for (Person firstCousinParent : secondCousinParent.getChildren()) {
                                                cousins.addAll(firstCousinParent.getChildren());
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Remove closer relatives
        cousins.remove(person);
        cousins.removeAll(person.getSiblings());
        cousins.removeAll(getFirstCousins(person));
        cousins.removeAll(getSecondCousins(person));
        cousins.removeAll(getThirdCousins(person));
        cousins.removeAll(getFourthCousins(person));
        
        return new ArrayList<>(cousins);
    }
    
    /**
     * Get sixth cousins of a person.
     */
    public List<Person> getSixthCousins(Person person) {
        Set<Person> cousins = new HashSet<>();
        
        for (Person parent : person.getParents()) {
            for (Person grandparent : parent.getParents()) {
                for (Person greatGrandparent : grandparent.getParents()) {
                    for (Person greatGreatGrandparent : greatGrandparent.getParents()) {
                        for (Person greatGreatGreatGrandparent : greatGreatGrandparent.getParents()) {
                            for (Person greatGreatGreatGreatGrandparent : greatGreatGreatGrandparent.getParents()) {
                                for (Person greatGreatGreatGreatGreatUncleAunt : greatGreatGreatGreatGrandparent.getSiblings()) {
                                    for (Person fifthCousinParent : greatGreatGreatGreatGreatUncleAunt.getChildren()) {
                                        for (Person fourthCousinParent : fifthCousinParent.getChildren()) {
                                            for (Person thirdCousinParent : fourthCousinParent.getChildren()) {
                                                for (Person secondCousinParent : thirdCousinParent.getChildren()) {
                                                    for (Person firstCousinParent : secondCousinParent.getChildren()) {
                                                        cousins.addAll(firstCousinParent.getChildren());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Remove closer relatives
        cousins.remove(person);
        cousins.removeAll(person.getSiblings());
        cousins.removeAll(getFirstCousins(person));
        cousins.removeAll(getSecondCousins(person));
        cousins.removeAll(getThirdCousins(person));
        cousins.removeAll(getFourthCousins(person));
        cousins.removeAll(getFifthCousins(person));
        
        return new ArrayList<>(cousins);
    }
    
    /**
     * Get cousins of a specific degree (1-6).
     */
    public List<Person> getCousins(Person person, int degree) {
        switch (degree) {
            case 1: return getFirstCousins(person);
            case 2: return getSecondCousins(person);
            case 3: return getThirdCousins(person);
            case 4: return getFourthCousins(person);
            case 5: return getFifthCousins(person);
            case 6: return getSixthCousins(person);
            default: return new ArrayList<>();
        }
    }
    
    /**
     * Get all cousins up to a specific degree.
     */
    public List<Person> getAllCousins(Person person, int maxDegree) {
        Set<Person> allCousins = new HashSet<>();
        
        for (int degree = 1; degree <= maxDegree; degree++) {
            allCousins.addAll(getCousins(person, degree));
        }
        
        return new ArrayList<>(allCousins);
    }
    
    /**
     * Get cousins grouped by their parent families.
     * Returns a map where the key is the parent family ID and the value is a list of cousins from that family.
     */
    public Map<String, List<Person>> getCousinsGroupedByFamily(Person person, int degree) {
        Map<String, List<Person>> groupedCousins = new HashMap<>();
        
        switch (degree) {
            case 1:
                return getFirstCousinsGroupedByFamily(person);
            case 2:
                return getSecondCousinsGroupedByFamily(person);
            case 3:
                return getThirdCousinsGroupedByFamily(person);
            case 4:
                return getFourthCousinsGroupedByFamily(person);
            case 5:
                return getFifthCousinsGroupedByFamily(person);
            case 6:
                return getSixthCousinsGroupedByFamily(person);
            default:
                return new HashMap<>();
        }
    }
    
    private Map<String, List<Person>> getFirstCousinsGroupedByFamily(Person person) {
        Map<String, List<Person>> groupedCousins = new HashMap<>();
        
        for (Person parent : person.getParents()) {
            for (Person uncleAunt : parent.getSiblings()) {
                for (Person cousin : uncleAunt.getChildren()) {
                    // Group by the family where the cousin is a child
                    for (String familyId : cousin.getFamilyIdsAsChild()) {
                        groupedCousins.computeIfAbsent(familyId, k -> new ArrayList<>()).add(cousin);
                    }
                }
            }
        }
        
        // Remove the person themselves and their siblings
        for (List<Person> cousins : groupedCousins.values()) {
            cousins.remove(person);
            cousins.removeAll(person.getSiblings());
        }
        
        // Remove empty groups
        groupedCousins.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        
        return groupedCousins;
    }
    
    private Map<String, List<Person>> getSecondCousinsGroupedByFamily(Person person) {
        Map<String, List<Person>> groupedCousins = new HashMap<>();
        
        for (Person parent : person.getParents()) {
            for (Person grandparent : parent.getParents()) {
                for (Person greatUncleAunt : grandparent.getSiblings()) {
                    for (Person firstCousinParent : greatUncleAunt.getChildren()) {
                        for (Person cousin : firstCousinParent.getChildren()) {
                            // Group by the family where the cousin is a child
                            for (String familyId : cousin.getFamilyIdsAsChild()) {
                                groupedCousins.computeIfAbsent(familyId, k -> new ArrayList<>()).add(cousin);
                            }
                        }
                    }
                }
            }
        }
        
        // Remove closer relatives
        for (List<Person> cousins : groupedCousins.values()) {
            cousins.remove(person);
            cousins.removeAll(person.getSiblings());
            cousins.removeAll(getFirstCousins(person));
        }
        
        // Remove empty groups
        groupedCousins.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        
        return groupedCousins;
    }
    
    private Map<String, List<Person>> getThirdCousinsGroupedByFamily(Person person) {
        Map<String, List<Person>> groupedCousins = new HashMap<>();
        
        for (Person parent : person.getParents()) {
            for (Person grandparent : parent.getParents()) {
                for (Person greatGrandparent : grandparent.getParents()) {
                    for (Person greatGreatUncleAunt : greatGrandparent.getSiblings()) {
                        for (Person secondCousinParent : greatGreatUncleAunt.getChildren()) {
                            for (Person firstCousinParent : secondCousinParent.getChildren()) {
                                for (Person cousin : firstCousinParent.getChildren()) {
                                    // Group by the family where the cousin is a child
                                    for (String familyId : cousin.getFamilyIdsAsChild()) {
                                        groupedCousins.computeIfAbsent(familyId, k -> new ArrayList<>()).add(cousin);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Remove closer relatives
        for (List<Person> cousins : groupedCousins.values()) {
            cousins.remove(person);
            cousins.removeAll(person.getSiblings());
            cousins.removeAll(getFirstCousins(person));
            cousins.removeAll(getSecondCousins(person));
        }
        
        // Remove empty groups
        groupedCousins.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        
        return groupedCousins;
    }
    
    private Map<String, List<Person>> getFourthCousinsGroupedByFamily(Person person) {
        Map<String, List<Person>> groupedCousins = new HashMap<>();
        
        for (Person parent : person.getParents()) {
            for (Person grandparent : parent.getParents()) {
                for (Person greatGrandparent : grandparent.getParents()) {
                    for (Person greatGreatGrandparent : greatGrandparent.getParents()) {
                        for (Person greatGreatGreatUncleAunt : greatGreatGrandparent.getSiblings()) {
                            for (Person thirdCousinParent : greatGreatGreatUncleAunt.getChildren()) {
                                for (Person secondCousinParent : thirdCousinParent.getChildren()) {
                                    for (Person firstCousinParent : secondCousinParent.getChildren()) {
                                        for (Person cousin : firstCousinParent.getChildren()) {
                                            // Group by the family where the cousin is a child
                                            for (String familyId : cousin.getFamilyIdsAsChild()) {
                                                groupedCousins.computeIfAbsent(familyId, k -> new ArrayList<>()).add(cousin);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Remove closer relatives
        for (List<Person> cousins : groupedCousins.values()) {
            cousins.remove(person);
            cousins.removeAll(person.getSiblings());
            cousins.removeAll(getFirstCousins(person));
            cousins.removeAll(getSecondCousins(person));
            cousins.removeAll(getThirdCousins(person));
        }
        
        // Remove empty groups
        groupedCousins.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        
        return groupedCousins;
    }
    
    private Map<String, List<Person>> getFifthCousinsGroupedByFamily(Person person) {
        Map<String, List<Person>> groupedCousins = new HashMap<>();
        
        for (Person parent : person.getParents()) {
            for (Person grandparent : parent.getParents()) {
                for (Person greatGrandparent : grandparent.getParents()) {
                    for (Person greatGreatGrandparent : greatGrandparent.getParents()) {
                        for (Person greatGreatGreatGrandparent : greatGreatGrandparent.getParents()) {
                            for (Person greatGreatGreatGreatUncleAunt : greatGreatGreatGrandparent.getSiblings()) {
                                for (Person fourthCousinParent : greatGreatGreatGreatUncleAunt.getChildren()) {
                                    for (Person thirdCousinParent : fourthCousinParent.getChildren()) {
                                        for (Person secondCousinParent : thirdCousinParent.getChildren()) {
                                            for (Person firstCousinParent : secondCousinParent.getChildren()) {
                                                for (Person cousin : firstCousinParent.getChildren()) {
                                                    // Group by the family where the cousin is a child
                                                    for (String familyId : cousin.getFamilyIdsAsChild()) {
                                                        groupedCousins.computeIfAbsent(familyId, k -> new ArrayList<>()).add(cousin);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Remove closer relatives
        for (List<Person> cousins : groupedCousins.values()) {
            cousins.remove(person);
            cousins.removeAll(person.getSiblings());
            cousins.removeAll(getFirstCousins(person));
            cousins.removeAll(getSecondCousins(person));
            cousins.removeAll(getThirdCousins(person));
            cousins.removeAll(getFourthCousins(person));
        }
        
        // Remove empty groups
        groupedCousins.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        
        return groupedCousins;
    }

    private Map<String, List<Person>> getSixthCousinsGroupedByFamily(Person person) {
        Map<String, List<Person>> groupedCousins = new HashMap<>();
        
        for (Person parent : person.getParents()) {
            for (Person grandparent : parent.getParents()) {
                for (Person greatGrandparent : grandparent.getParents()) {
                    for (Person greatGreatGrandparent : greatGrandparent.getParents()) {
                        for (Person greatGreatGreatGrandparent : greatGreatGrandparent.getParents()) {
                            for (Person greatGreatGreatGreatGrandparent : greatGreatGreatGrandparent.getParents()) {
                                for (Person greatGreatGreatGreatGreatUncleAunt : greatGreatGreatGreatGrandparent.getSiblings()) {
                                    for (Person fifthCousinParent : greatGreatGreatGreatGreatUncleAunt.getChildren()) {
                                        for (Person fourthCousinParent : fifthCousinParent.getChildren()) {
                                            for (Person thirdCousinParent : fourthCousinParent.getChildren()) {
                                                for (Person secondCousinParent : thirdCousinParent.getChildren()) {
                                                    for (Person firstCousinParent : secondCousinParent.getChildren()) {
                                                        for (Person cousin : firstCousinParent.getChildren()) {
                                                            // Group by the family where the cousin is a child
                                                            for (String familyId : cousin.getFamilyIdsAsChild()) {
                                                                groupedCousins.computeIfAbsent(familyId, k -> new ArrayList<>()).add(cousin);
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Remove closer relatives
        for (List<Person> cousins : groupedCousins.values()) {
            cousins.remove(person);
            cousins.removeAll(person.getSiblings());
            cousins.removeAll(getFirstCousins(person));
            cousins.removeAll(getSecondCousins(person));
            cousins.removeAll(getThirdCousins(person));
            cousins.removeAll(getFourthCousins(person));
            cousins.removeAll(getFifthCousins(person));
        }
        
        // Remove empty groups
        groupedCousins.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        
        return groupedCousins;
    }

    /**
     * Get the relationship degree between two people.
     * Returns -1 if no relationship is found.
     */
    public int getRelationshipDegree(Person person1, Person person2) {
        if (person1.equals(person2)) {
            return 0; // Same person
        }
        
        // Check if they are siblings
        if (person1.getSiblings().contains(person2)) {
            return 1; // Siblings
        }
        
        // Check cousins
        for (int degree = 1; degree <= 6; degree++) {
            if (getCousins(person1, degree).contains(person2)) {
                return degree + 1; // Cousins are degree + 1
            }
        }
        
        // Check if one is ancestor of the other
        if (getAncestors(person1).contains(person2) || getAncestors(person2).contains(person1)) {
            return -2; // Ancestor/descendant relationship
        }
        
        return -1; // No relationship found
    }
    
    /**
     * Get ancestors grouped by generation.
     * Returns a map where the key is the generation number (1 = parents, 2 = grandparents, etc.)
     * and the value is a list of persons in that generation.
     */
    public Map<Integer, List<Person>> getAncestorsByGeneration(Person person) {
        Map<Integer, List<Person>> result = new HashMap<>();
        getAncestorsByGenerationRecursive(person, 1, result, new HashSet<>());
        return result;
    }

    private void getAncestorsByGenerationRecursive(Person person, int generation, Map<Integer, List<Person>> result, Set<Person> visited) {
        if (person == null || visited.contains(person)) return;
        visited.add(person);
        
        for (Person parent : person.getParents()) {
            result.computeIfAbsent(generation, k -> new ArrayList<>()).add(parent);
            getAncestorsByGenerationRecursive(parent, generation + 1, result, visited);
        }
    }

    /**
     * Get descendants grouped by generation.
     * Returns a map where the key is the generation number (1 = children, 2 = grandchildren, etc.)
     * and the value is a list of persons in that generation.
     */
    public Map<Integer, List<Person>> getDescendantsByGeneration(Person person) {
        Map<Integer, List<Person>> result = new HashMap<>();
        getDescendantsByGenerationRecursive(person, 1, result, new HashSet<>());
        return result;
    }

    private void getDescendantsByGenerationRecursive(Person person, int generation, Map<Integer, List<Person>> result, Set<Person> visited) {
        if (person == null || visited.contains(person)) return;
        visited.add(person);
        
        for (Person child : person.getChildren()) {
            result.computeIfAbsent(generation, k -> new ArrayList<>()).add(child);
            getDescendantsByGenerationRecursive(child, generation + 1, result, visited);
        }
    }
} 