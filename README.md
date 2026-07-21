# GEDCOM Family Analyzer

A Java utility for reading GEDCOM 5.5.1 files and displaying family relationships including ancestors, descendants, siblings, and cousins up to 5th cousins.

## Features

- **GEDCOM 5.5.1 Support**: Full parsing of GEDCOM 5.5.1 format files
- **Multi-File Support**: Analyze multiple GEDCOM files simultaneously (perfect for Geni.com exports)
  - Automatically handles duplicate IDs across files
  - Combines data from related family trees
- **Family Relationships**: Analyze and display:
  - Ancestors (parents, grandparents, etc.) grouped by generation
  - Descendants (children, grandchildren, etc.) grouped by generation
  - Siblings
  - First cousins through 6th cousins
- **Multiple Output Formats**: 
  - Console output for quick viewing
  - HTML output with enhanced formatting and larger fonts
- **Geni API Fetching**: Pull a person's full ancestry directly from Geni.com (beyond the
  size-limited export), with coordinates and a generation-coloured ancestor map — see
  [Fetching Ancestry from the Geni API](#fetching-ancestry-from-the-geni-api)
- **Name Preference**: Automatically prefers English names over Hebrew/foreign language names when multiple NAME records exist
- **Comprehensive Output**: Displays names, IDs, and life dates for all relatives
- **Command Line Interface**: Easy to use from the command line

## Requirements

- Java 11 or higher
- Maven 3.6 or higher (for building)

## Building the Project

1. Navigate to the project directory:
   ```bash
   cd GedcomFamilyAnalyzer
   ```

2. Build the project using Maven:
   ```bash
   mvn clean package
   ```

3. The executable JAR file will be created in the `target` directory as `gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar`

## Usage

### Basic Usage (Console Output)

```bash
java -jar target/gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar <gedcom-files> <person-id>
```

### HTML Output

```bash
java -jar target/gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar <gedcom-files> <person-id> <html-output-file>
```

### Examples

```bash
# Single file - Console output
java -jar target/gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar family.ged @I1@

# Single file - HTML output
java -jar target/gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar family.ged @I1@ family_analysis.html

# Multiple files - Console output
java -jar target/gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar "family1.ged,family2.ged" @I1@

# Multiple files - HTML output
java -jar target/gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar "family1.ged,family2.ged" @I1@ combined_analysis.html
```

### Parameters

- `gedcom-files`: Path to the GEDCOM file(s) to analyze - single file or comma-separated list
- `person-id`: The GEDCOM ID of the person to analyze (e.g., `@I1@`, `@F1@`)
- `html-output-file`: Optional path to HTML output file for enhanced formatting

## Output Format

The utility provides a comprehensive analysis including:

### Target Person Information
- Name and ID
- Life dates (birth/death)

### Ancestors
- All ancestors grouped by generation:
  - Parents
  - Grandparents  
  - Great 1 Grandparents
  - Great 2 Grandparents
  - etc.
- Names, IDs, and life dates

### Descendants
- All descendants grouped by generation:
  - Children
  - Grandchildren
  - Great 1 Grandchildren
  - Great 2 Grandchildren
  - etc.
- Names, IDs, and life dates

### Siblings
- Full and half siblings
- Names, IDs, and life dates

### Cousins
- 1st through 6th cousins
- **Grouped by family** - cousins who are siblings are listed together
- Family identification with cousin counts
- Names, IDs, and life dates

## GEDCOM Format Support

The utility supports the following GEDCOM 5.5.1 elements:

### Individual Records (@I...@ INDI)
- NAME (with GIVN and SURN sub-tags)
- SEX
- BIRT (with DATE and PLAC sub-tags)
- DEAT (with DATE and PLAC sub-tags)
- FAMS (family as spouse)
- FAMC (family as child)

### Family Records (@F...@ FAM)
- HUSB (husband)
- WIFE (wife)
- CHIL (children)
- MARR (with DATE and PLAC sub-tags)
- DIV (divorce date)

## Example Output

```
==========================================
GEDCOM Family Relationship Analyzer
==========================================
GEDCOM Files: family1.ged,family2.ged
Person ID: @I1@

Parsing GEDCOM file(s)...
Parsing file: family1.ged
Parsing file: family2.ged
Found 67 persons and 18 families.

Target Person: John Smith
Life Dates: b. 1980 - d. 2020

ANCESTORS:
----------
  Mary Johnson (@I2@)
    b. 1955
  Robert Smith (@I3@)
    b. 1950 - d. 2010

DESCENDANTS:
-------------
  Sarah Smith (@I4@)
    b. 2005
  Michael Smith (@I5@)
    b. 2008

SIBLINGS:
---------
  Jennifer Smith (@I6@)
    b. 1982

COUSINS:
--------
1ST COUSINS (3):
  David Johnson (@I7@)
    b. 1981
  Lisa Johnson (@I8@)
    b. 1983
  Thomas Johnson (@I9@)
    b. 1985

2ND COUSINS (5):
  Emma Wilson (@I10@)
    b. 1990
  ...

==========================================
Analysis complete.
```

## Project Structure

```
GedcomFamilyAnalyzer/
├── src/main/java/com/wanderingjew/gedcomanalyzer/
│   ├── Person.java                    # Person data model
│   ├── Family.java                    # Family data model
│   ├── GedcomData.java                # Container for parsed data
│   ├── GedcomParser.java              # GEDCOM file parser
│   ├── FamilyRelationshipAnalyzer.java # Relationship calculations
│   └── GedcomFamilyAnalyzer.java      # Main application class
├── pom.xml                           # Maven configuration
└── README.md                         # This file
```

## Error Handling

The utility provides clear error messages for common issues:

- Invalid command line arguments
- File not found or unreadable
- Person ID not found in GEDCOM file
- Malformed GEDCOM data

## Multi-File Support

The utility supports analyzing multiple GEDCOM files simultaneously, which is particularly useful for Geni.com exports where related families are split across multiple files.

### How It Works

- **Automatic Duplicate Detection**: If the same person ID appears in multiple files, the utility automatically skips the duplicate entry
- **Data Combination**: All unique individuals and families from all files are combined into a single analysis
- **Relationship Preservation**: All family relationships are preserved across files

### Use Cases

- **Geni.com Exports**: Combine multiple family tree exports from Geni.com
- **Related Families**: Analyze families that are connected but exported separately
- **Large Family Trees**: Split very large trees into multiple files for easier management

### Example

If you have two GEDCOM files from Geni.com:
- `maternal-family.ged` (contains person @I1@ and their maternal relatives)
- `paternal-family.ged` (contains person @I1@ and their paternal relatives)

You can analyze both together:
```bash
java -jar target/gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar "maternal-family.ged,paternal-family.ged" @I1@
```

This will show all relationships from both sides of the family in a single analysis.

## Fetching Ancestry from the Geni API

Geni.com's GEDCOM export is limited in how deep it goes. To pull a person's full
ancestry — with coordinates for mapping and Geni's own display names — the project
can fetch directly from the Geni REST API and assemble its own GEDCOM.

### One-time: register a Geni application

1. Log in to Geni and open <https://www.geni.com/platform/developer/apps>.
2. Click **Register New Application**, and set both the **Site URL** and **Callback
   URL** to a domain you control (e.g. `https://wanderingjew.info`). `localhost` is
   often rejected.
3. Copy the **Application key** — this is your `client_id`.

### Each session: get an access token

Geni access tokens last about 24 hours. In a browser where you are logged in to
Geni, paste the following, replacing `YOUR_APP_KEY` and the redirect with your
registered callback URL:

```
https://www.geni.com/platform/oauth/authorize?client_id=YOUR_APP_KEY&redirect_uri=https://wanderingjew.info&response_type=token
```

Approve the prompt. Your browser redirects to your callback and the **address bar**
ends with:

```
https://wanderingjew.info/#access_token=XXXXXXXX&expires_in=NNNN
```

Copy the value between `access_token=` and `&`, then export it:

```bash
export GENI_ACCESS_TOKEN='XXXXXXXX'
```

(Optional) confirm the token works — replace `<GUID>` with any Geni guid:

```bash
curl -s "https://www.geni.com/api/profile-g<GUID>/immediate-family?access_token=$GENI_ACCESS_TOKEN" | head
```

### Fetch and build

The start id is a Geni **guid** — the number in a `1 RFN geni:<guid>` line, or the
trailing number in a Geni profile URL.

```bash
# Fetch ancestry from the API and write a GEDCOM (requires GENI_ACCESS_TOKEN).
# Args: <start-guid> <max-generations> <output.ged>
java -cp target/gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar \
  com.wanderingjew.gedcomanalyzer.GeniFetch <start-guid> 100 output/ancestors.ged
```

Responses are cached under `geni-cache/` (git-ignored). The fetch self-paces to the
API rate limit (as low as 1 request / 10 s for a new, unapproved app) and can be
**resumed** by re-running the same command with a fresh token — cached profiles are
skipped, so no work is lost when a token expires.

For an **unrelated tree** (a person who shares no ancestors with a previous fetch),
use a separate cache directory so the caches don't intermingle. Set `GENI_CACHE_DIR`
(default `geni-cache`) — all four tools honour it:

```bash
export GENI_CACHE_DIR=geni-cache-<name>
```

(The built GEDCOM is always scoped to the start person's ancestors regardless, but a
separate cache keeps unrelated trees cleanly apart and easy to manage.)

The cache is the real asset; these offline tools rebuild from it **without a token**,
and are safe to run even while a fetch is still going:

```bash
# Assemble a GEDCOM from the cache only (offline).
java -cp target/gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar \
  com.wanderingjew.gedcomanalyzer.BuildGedcom <start-guid> 100 output/ancestors.ged

# Build a standalone Leaflet/OpenStreetMap map of the ancestors, coloured by generation.
java -cp target/gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar \
  com.wanderingjew.gedcomanalyzer.AncestorMap <start-guid> 100 output/ancestors-map.html
```

Feed the assembled GEDCOM into the main analyzer as usual to get the HTML report
(which embeds an ancestor map when the GEDCOM has coordinates).

### Refreshing a person after editing on Geni

After you correct a profile on Geni, drop its cache file so the next fetch
re-downloads the updated data (leaving the rest of the cache intact):

```bash
java -cp target/gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar \
  com.wanderingjew.gedcomanalyzer.InvalidateCache <guid> [<guid> ...]
```

### Correcting bad coordinates

Geni sometimes geocodes a place to the wrong spot (e.g. "Babylon" → Babylon, NY).
Add a **tab-separated** line to `place-overrides.tsv` and rebuild — matching places
then use your coordinates instead of Geni's:

```
# place substring   latitude   longitude
Babylon              32.5355    44.4275
```

## Limitations

- Currently supports GEDCOM 5.5.1 format only
- Cousin analysis limited to 6th cousins
- No support for complex family structures (adoption, step-relationships)
- No support for GEDCOM extensions or custom tags

## Contributing

To contribute to this project:

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## License

This project is open source and available under the MIT License.

## Support

For issues or questions, please create an issue in the project repository. 
## Output Directory

The project includes an `output/` directory for organizing generated files:

- **HTML files** are automatically excluded from Git via `.gitignore`
- **Recommended usage**: Save HTML output to `output/` directory
- **Example**: `output/family_analysis.html`
- **Benefits**: Keeps project root clean and organized
