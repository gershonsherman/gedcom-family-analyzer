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
- `GeniAncestorFetcher` — BFS **upward**: the union where the focus has `rel:"child"` is
  the parent-union; its `rel:"partner"` profiles are the parents. Dedups by numeric
  profile id; each ancestor fetched once as its own focus (only `focus` has full detail).
  Builds `GedcomData`; captures lat/long, generation, and union guids. Histogram prints
  both **distinct** and **ahnentafel positions** per generation.
- `GeniFetch` — CLI (fetch + build): `<start-guid> <max-gens> <out.ged> [cache-dir]`; needs token.
- `BuildGedcom` / `AncestorMap` — OFFLINE cache-only CLIs (no token), args
  `<start-guid> <max-gens> <out> [cache-dir]`; safe to run while a GeniFetch is going.
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

These are git-ignored and won't come from a clone:
- `.vscode/launch.json` — per-person run configs (has family names/IDs; kept local).
- `gedcoms` — a symlink to the folder of GEDCOM files, so configs can use short paths:
  `ln -sfn "<path to GEDCOM folder>" gedcoms`. That folder is organised into subdirs
  `Gedcom files/`, `Ancestor Maps/`, `Family Analyzer Reports/`.
- `geni-cache*/` — API response caches (large).
- `*.ged` / `*.html` — data and output (except `test-family.ged`).

## Open / possible next steps

- **Cousin/descendant fetcher (planned next major feature).** Geni's GEDCOM export is
  capped (~1,800 profiles), which truncates distant cousins — a "missing" 3rd/4th cousin
  is simply not in the exported files. Plan: a fetcher that goes UP to the Nth-great-
  grandparents (default 4th = gen 6, → up to 5th cousins) and then DOWN through ALL their
  descendants. Reuses `immediate-family` (a person's partner-unions give their children)
  plus the existing cache/offline/GEDCOM code. Make the "up" depth a parameter (cousin
  degree vs. fetch time). NOTE: descendant runs can be many thousands of profiles — much
  bigger than an ancestor run — so a higher rate limit really matters here.
- Optional: request a higher Geni rate limit (email `api@geni.com`).
- Optional: Google Geocoding fallback for places Geni left WITHOUT any coordinates
  (distinct from `place-overrides.tsv`, which fixes WRONG coordinates).
- The project currently lives on Google Drive, which causes slow cache scans; moving it
  to a local path (with GitHub as backup) would help.
