package com.wanderingjew.gedcomanalyzer;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Writes an in-memory {@link GedcomData} model back out as a single GEDCOM 5.5.1 file.
 * Only the fields the model retains are emitted (name incl. married name, sex,
 * birth/death date + place, marriage/divorce, and the family links).
 */
public class GedcomWriter {

    /**
     * Write the given data to a GEDCOM file at the supplied path.
     */
    public void write(GedcomData data, String outputPath) throws IOException {
        try (Writer w = Files.newBufferedWriter(Paths.get(outputPath), StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(w)) {

            writeHeader(out);

            for (Person person : data.getPersons().values()) {
                writeIndividual(out, person);
            }
            for (Family family : data.getFamilies().values()) {
                writeFamily(out, family);
            }

            out.println("0 TRLR");
        }
    }

    private void writeHeader(PrintWriter out) {
        out.println("0 HEAD");
        out.println("1 SOUR GedcomFamilyAnalyzer");
        out.println("1 GEDC");
        out.println("2 VERS 5.5.1");
        out.println("2 FORM LINEAGE-LINKED");
        out.println("1 CHAR UTF-8");
    }

    private void writeIndividual(PrintWriter out, Person person) {
        out.println("0 @I" + person.getId() + "@ INDI");

        String given = trim(person.getGivenName());
        String surname = trim(person.getSurname());
        // GEDCOM name line uses the maiden/birth surname; the married name (if any)
        // is recorded separately via the _MARNM sub-tag, matching Geni's own export.
        out.println("1 NAME " + (given == null ? "" : given) + " /" + (surname == null ? "" : surname) + "/");
        if (given != null) {
            out.println("2 GIVN " + given);
        }
        if (surname != null) {
            out.println("2 SURN " + surname);
        }
        if (trim(person.getMarriedName()) != null) {
            out.println("2 _MARNM " + person.getMarriedName().trim());
        }
        if (trim(person.getGeniName()) != null) {
            out.println("2 _GENINAME " + person.getGeniName().trim());
        }

        if (trim(person.getSex()) != null) {
            out.println("1 SEX " + person.getSex().trim());
        }

        writeEvent(out, "BIRT", person.getBirthDate(), person.getBirthPlace(),
                person.getBirthLatitude(), person.getBirthLongitude());
        writeEvent(out, "DEAT", person.getDeathDate(), person.getDeathPlace(),
                person.getDeathLatitude(), person.getDeathLongitude());

        for (String famId : person.getFamilyIdsAsChild()) {
            out.println("1 FAMC @F" + famId + "@");
        }
        for (String famId : person.getFamilyIdsAsSpouse()) {
            out.println("1 FAMS @F" + famId + "@");
        }

        out.println("1 RFN geni:" + person.getId());
    }

    private void writeEvent(PrintWriter out, String tag, String date, String place,
                            Double latitude, Double longitude) {
        String d = trim(date);
        String p = trim(place);
        boolean hasCoords = latitude != null && longitude != null;
        if (d == null && p == null && !hasCoords) {
            return;
        }
        out.println("1 " + tag);
        if (d != null) {
            out.println("2 DATE " + d);
        }
        if (p != null || hasCoords) {
            out.println("2 PLAC " + (p == null ? "" : p));
            if (hasCoords) {
                out.println("3 MAP");
                out.println("4 LATI " + formatLatitude(latitude));
                out.println("4 LONG " + formatLongitude(longitude));
            }
        }
    }

    /** GEDCOM latitude: hemisphere letter (N/S) followed by the absolute value. */
    private String formatLatitude(double lat) {
        return (lat >= 0 ? "N" : "S") + String.format("%.6f", Math.abs(lat));
    }

    /** GEDCOM longitude: hemisphere letter (E/W) followed by the absolute value. */
    private String formatLongitude(double lng) {
        return (lng >= 0 ? "E" : "W") + String.format("%.6f", Math.abs(lng));
    }

    private void writeFamily(PrintWriter out, Family family) {
        out.println("0 @F" + family.getId() + "@ FAM");
        if (trim(family.getHusbandId()) != null) {
            out.println("1 HUSB @I" + family.getHusbandId() + "@");
        }
        if (trim(family.getWifeId()) != null) {
            out.println("1 WIFE @I" + family.getWifeId() + "@");
        }
        for (String childId : family.getChildrenIds()) {
            out.println("1 CHIL @I" + childId + "@");
        }
        String marrDate = trim(family.getMarriageDate());
        String marrPlace = trim(family.getMarriagePlace());
        if (marrDate != null || marrPlace != null) {
            out.println("1 MARR");
            if (marrDate != null) {
                out.println("2 DATE " + marrDate);
            }
            if (marrPlace != null) {
                out.println("2 PLAC " + marrPlace);
            }
        }
        if (trim(family.getDivorceDate()) != null) {
            out.println("1 DIV");
            out.println("2 DATE " + family.getDivorceDate().trim());
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
