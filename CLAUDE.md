# GedcomFamilyAnalyzer — project context

Java 11 / Maven CLI that reads GEDCOM 5.5.1 files and produces an HTML report of a
person's ancestors, descendants, siblings, and cousins (1st–6th), plus a companion
subsystem that fetches ancestry directly from the Geni.com API. Originally started in
Cursor; developed further with Claude.

> This file is the portable project memory (auto-loaded by Claude). Personal data
> (family names, Geni person IDs) is deliberately kept OUT of this public repo — those
> live only in the local, git-ignored `.vscode/launch.json`.

## Build & run

```bash
mvn clean package -DskipTests
# jar: target/gedcom-family-analyzer-1.0.0-jar-with-dependencies.jar
```

Main analyzer:
`java -jar target/…jar <gedcom-files> <person-id> [html-output]`
- `<gedcom-files>` accepts a **directory** (uses every `*.ged` inside, sorted), a single
  file, or a comma-separated list.

## Architecture

- `Person` / `Family` / `GedcomData` — model. `Person` carries givenName, surname,
  marriedName, geniName, birth/death date+place+lat/long, and relationship lists.
- `GedcomParser` — parses GEDCOM incl. custom tags `_MARNM`, `_GENINAME`, and
  `PLAC>MAP>LATI/LONG` (levels 3/4). Prefers ASCII names over Hebrew/foreign.
- `FamilyRelationshipAnalyzer` — relationship computations.
- `GedcomFamilyAnalyzer` — CLI/main; writes the HTML report (which embeds an
  ancestors-only Leaflet map when the GEDCOM has coordinates).
- `GedcomWriter` — writes a `GedcomData` back to a single `.ged`.

### Geni API fetcher subsystem
- `GeniClient` — calls `profile-<id>/immediate-family`; token from `GENI_ACCESS_TOKEN`;
  on-disk cache `geni-cache/<id>.v2.json` (git-ignored; bump `CACHE_VERSION` if the
  requested `fields` change); adaptive pacing off `X-API-Rate-*` headers;
  `setOffline(true)` = cache-only (returns null on miss). `cacheDirFromEnv()` default.
- `GeniAncestorFetcher` — BFS **upward** (`fetch`/`ascend`): the union where the focus has
  `rel:"child"` is the parent-union; its `rel:"partner"` profiles are the parents. Dedups
  by numeric profile id; each ancestor fetched once as its own focus (only `focus` has
  full detail). Builds `GedcomData`; captures lat/long, generation, and union guids.
  Histogram prints both **distinct** and **ahnentafel positions** per generation
  (positions is anchored on `startNumericId`, not "whoever has generation 0" — needed once
  descendants can also land on generation 0).
  - `fetchWithDescendants(startId, upGenerations)` — ascends as above, then **descends**
    from every "boundary" profile (one whose own parents weren't fetched: `childUnionId`
    null, no partners recorded, or the generation cap was hit) through ALL of their
    descendants via `profileUnions` (a profile-id → their-own-marriage-unions reverse
    index) and `unionChildren`. Since a nearer ancestor's descendants are always a subset
    of a boundary ancestor's, descending from just the boundary set recovers cousins,
    aunts/uncles, nieces/nephews, etc. at every remove in one pass. In-laws are fetched
    for their name/details but never traversed past. Already-`visited` nodes (direct
    ancestors found during ascend, e.g. the start person's own parents) are still expanded
    during descend — using their authoritative ascend-assigned generation, not the value
    computed along the descent path — otherwise full siblings and the start person's own
    descendants would be silently missed.
- `GeniFetch` / `GeniCousinFetch` — CLI (fetch + build), online, need token:
  `<start-guid> <max-gens|up-gens> <out.ged> [cache-dir]`.
- `BuildGedcom` / `BuildCousinGedcom` / `AncestorMap` — OFFLINE cache-only CLIs (no token),
  same args shape; safe to run while a `GeniFetch`/`GeniCousinFetch` is going.
- `AncestorMapWriter` — Leaflet/OSM map, teardrop pins coloured by generation (rainbow
  capped at gen 40, deeper = violet), compact legend, title. (Won't render as a Claude
  Artifact — CSP blocks external tiles/CDN; open the HTML locally.)
- `InvalidateCache <guid> …` — deletes a person's cache file (matches on `focus.guid`,
  which is correct — grep-by-guid is NOT reliable) so the next fetch re-downloads them.
- `PlaceOverrides` + `place-overrides.tsv` — manual coordinate corrections for places Geni
  geocoded wrongly (e.g. "Babylon" → Babylon NY). We do NOT geocode; all coords are Geni's.

## Geni API auth & limits

- **Client-side (implicit) OAuth.** App registered with Site+Callback on a domain the
  user controls (localhost was rejected). Get a token by visiting
  `https://www.geni.com/platform/oauth/authorize?client_id=<key>&redirect_uri=<callback>&response_type=token`
  while logged in, then read `#access_token=…` from the address bar. Token lasts ~24h.
- **Rate limit:** unapproved app = **1 request / 10s** (adaptive pacing self-throttles to
  ~12s/call, no 429s). A deep run is slow but resumable — rerun the same command with a
  fresh token; the cache skips finished profiles. Higher limits require app approval via
  email to `api@geni.com` (answer their read-only/personal-use questionnaire).
- **VS Code + `.vscode/launch.json` gotcha:** the `GeniFetch` configs read the token via
  `"env": {"GENI_ACCESS_TOKEN": "${env:GENI_ACCESS_TOKEN}"}`, which only sees a var that
  was exported **before VS Code itself started**. `export`ing in an integrated terminal
  after VS Code is already open won't work — quit VS Code fully and relaunch it (e.g.
  `code .`) from a shell where the token is already exported.

## Key design decisions & gotchas

- **Family ids = the Geni union GUID** (not the internal union id), so our `.ged`'s
  `@F…@` match Geni's own export and the two merge cleanly. Without this, combining our
  fetch with a Geni hand-export doubled parents ("(2x)" on everyone).
  `buildRelationships` also guards parents against duplicate adds.
- **Generations are computed by level-order BFS, not DFS.** A DFS with a global visited
  set placed a pedigree-collapse ancestor at whatever depth recursion first reached them,
  not their closest relationship (e.g. Rashi, a 24th-great-grandfather, showed as ~36th).
  `groupByGeneration` fixed this. Collapsed ancestors appear in each generation they
  occur in, annotated "also Nth great-grandparent".
- **Display names:** married-(maiden) format applies to **women only** (some GEDCOMs put
  `_MARNM` on men). For API data, `GeniAncestorFetcher` captures Geni's `name` field and
  writes it as `_GENINAME`; `getDisplayName` prefers it (women get the maiden appended),
  so reports match Geni's on-site names. Hand-exports (no `_GENINAME`) use constructed names.
- **Coordinate NPE gotcha:** never mix a primitive and a nullable `Double` in a ternary
  (autounboxing NPEs on null). Use if/else.

## Findings that shaped the work

- **Geni counts ahnentafel positions; our histogram counts distinct people.** With heavy
  cousin-marriage pedigree collapse these diverge widely at depth (e.g. gen 20: ~445
  positions vs ~177 distinct). Recomputing our positions matches Geni within ±1–2% →
  the ancestor fetch is complete. Off-by-one: our gen N (0 = self) = Geni's "gen N+1".
- **The Geni GEDCOM export is NOT badly capped for ANCESTORS** (it went to ~gen 30 with
  essentially everything). The API fetch's real value is coordinates, Geni display names,
  refreshing edited fields, and the deep speculative tail. BUT…
- **Geni's GEDCOM export IS scope-limited for COLLATERAL relatives** (~1,800 people per
  export). It reaches ~2nd cousins but drops 3rd/4th. If distant cousins are "missing"
  from a report, they're simply **not in the exported files** — the analyzer can only
  show people present in the data. The API fetcher only walks **ancestors**, so it does
  NOT currently recover cousins (that would require fetching descendants of ancestors).

## Local, machine-specific setup (NOT in the repo — recreate per machine)

These are git-ignored and won't come from a clone. The project's code lives in a plain
local git clone per machine (synced via GitHub push/pull); the actual data — GEDCOM
files, the Geni cache, and generated reports — lives once on Google Drive and each
clone just symlinks to it, so both machines share the same data without putting any of
it in git:
- `.vscode/launch.json` — per-person run configs (has family names/IDs; kept local, one
  copy per machine — not synced via Drive or git).
- `gedcoms` — a symlink to the **parent** folder containing the GEDCOM files (not the
  `Gedcom files/` subfolder itself — `launch.json`'s args are `gedcoms/Gedcom files/…`,
  `gedcoms/Ancestor Maps/…`, `gedcoms/Family Analyzer Reports/…`):
  `ln -sfn "<path to the folder containing Gedcom files/>" gedcoms`.
- `geni-cache` — a symlink to the shared Geni API response cache on Drive:
  `ln -sfn "<path to geni-cache on Drive>" geni-cache`. (A real *local* `geni-cache*/`
  dir, e.g. for a scratch/offline test, is also gitignored if you ever want one instead.)
- `output` — a symlink to the shared generated-reports folder on Drive:
  `ln -sfn "<path to output on Drive>" output`.
- `*.ged` / `*.html` — data and output wherever they land directly in the repo root
  (except `test-family.ged`).

## Open / possible next steps

- **Cousin/descendant fetcher — built, not yet run for real.** `GeniAncestorFetcher.
  fetchWithDescendants` + the `GeniCousinFetch` / `BuildCousinGedcom` CLIs (see
  Architecture above) exist and are validated (a hand-built synthetic cache fixture
  round-tripped correctly end-to-end through `GedcomFamilyAnalyzer`'s cousin report; a
  real-cache offline smoke test against the existing 1,248-profile cache also ran clean,
  though that cache predates this feature so it had no aunt/uncle/cousin data to surface
  offline). **What's actually left is a live run**: `GeniCousinFetch <start-id>
  <up-generations> <out.ged>` with a fresh `GENI_ACCESS_TOKEN`, to pull the previously-
  unfetched descendant profiles into the cache. Once that GEDCOM exists,
  `GedcomFamilyAnalyzer` already prints the resulting cousin lists — no further code
  changes needed there. NOTE: descendant runs can be many thousands of profiles — much
  bigger than an ancestor run — so a higher rate limit really matters here.
- Optional: request a higher Geni rate limit (email `api@geni.com`).
- Optional: Google Geocoding fallback for places Geni left WITHOUT any coordinates
  (distinct from `place-overrides.tsv`, which fixes WRONG coordinates).
- The project currently lives on Google Drive, which causes slow cache scans; moving it
  to a local path (with GitHub as backup) would help.
