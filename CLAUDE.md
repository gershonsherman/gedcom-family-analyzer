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
- `GedcomParser` — parses GEDCOM incl. custom tags `_MARNM`, `_GENINAME`, `_CURRENT`
  (current residence — no date, just a place), and `PLAC>MAP>LATI/LONG` (levels 3/4, under
  `BIRT`/`DEAT`/`_CURRENT`). Prefers ASCII names over Hebrew/foreign. When merging multiple
  files for the same person (`parseMultipleFiles`), the **first** file to supply a given
  birth/death/current-residence date+place+coordinates wins, not the last — otherwise the
  winner depended on alphabetical filename order (an accident of naming, not a deliberate
  "prefer this source" choice), and a later file could overwrite just the place text while
  leaving earlier coordinates in place, mismatching the two.
- `FamilyRelationshipAnalyzer` — relationship computations.
- `GedcomFamilyAnalyzer` — CLI/main; writes the HTML report, which embeds an ancestor map,
  a descendant map, and a cousin map (siblings + 1st-5th cousins) when the GEDCOM has
  coordinates — see `AncestorMapWriter`/`CousinMapWriter` below. The cousin map is also
  written as a standalone `<report>-cousins-map.html` file alongside the main report.
  Grandchildren and deeper descendant generations are grouped by parent family with a
  "Children of X & Y (N):" sub-heading (matching the COUSINS section's style) once a
  generation can span several different families; direct children stay a flat list since
  they all share the target's own family, already named in the info header above. The
  ANCESTORS/DESCENDANTS/COUSINS section headings each show a total in parens (e.g.
  "ANCESTORS (62)") — the literal sum of the per-generation/per-degree sub-counts already
  shown underneath, not a distinct-people count, so a pedigree-collapse ancestor appearing
  in more than one generation is counted once per generation, same as the sub-counts always
  did.
- `GedcomWriter` — writes a `GedcomData` back to a single `.ged`.

### Geni API fetcher subsystem
- `GeniClient` — calls `profile-<id>/immediate-family` with fields incl. `birth`, `death`,
  `current_residence` (living people; exact JSON shape unconfirmed against docs — parsed
  defensively, accepting either a nested `location` object or fields directly on
  `current_residence`, matching `birth`/`death`'s shape); token from `GENI_ACCESS_TOKEN`;
  on-disk cache `geni-cache/<id>.v3.json` (git-ignored; bump `CACHE_VERSION` if the
  requested `fields` change — this invalidates the *entire* cache, forcing a full refetch,
  so don't bump it casually); adaptive pacing off `X-API-Rate-*` headers; `setOffline(true)`
  = cache-only (returns null on miss). `cacheDirFromEnv()` default. A 403 (privacy-restricted
  profile — common on a deep descendant fetch reaching many living relatives) throws
  `GeniAccessDeniedException` rather than the generic fatal error, and the denial itself is
  cached (`{"_denied":true}`) so a resumed/offline run doesn't re-attempt the blocked call.
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
    descendants would be silently missed. **Confirmed working on a real live run** (Irit,
    up to 6 generations) — see Findings below.
  - A 403-denied profile is recorded as a synthesized `"private-<id>"` person named
    `"Private"` (matching how Geni's own site shows it) rather than silently omitted, so the
    tree still shows that someone exists there. A denied *parent* (found ascending) needs no
    special handling — `buildGedcomData()` links a union's partners generically. A denied
    *child* (found descending) can't report its own parent union, since we never got its
    focus response — `QueueEntry` carries the union it was already discovered through, as a
    fallback `childUnionId`. This surfaced a real bug in `buildGedcomData()`'s husband/wife
    assignment: unknown gender defaulted to "husband", which could silently overwrite an
    already-assigned husband and drop them from the family — fixed with a two-pass
    assignment (known genders first; unknown-gender partners, e.g. `"Private"`, only fill
    whichever slot is still open).
- `GeniFetch` / `GeniCousinFetch` — CLI (fetch + build), online, need token:
  `<start-guid> <max-gens|up-gens> <out.ged> [cache-dir]`.
- `BuildGedcom` / `BuildCousinGedcom` / `AncestorMap` — OFFLINE cache-only CLIs (no token),
  same args shape; safe to run while a `GeniFetch`/`GeniCousinFetch` is going.
- `AncestorMapWriter` — Leaflet/OSM map, teardrop pins coloured by generation (continuous
  rainbow, capped at a configurable generation — default 40 for the ancestor map, 8 for the
  descendant map, since descendant trees are realistically much shallower), compact legend,
  title. Popup shows current/death/birth location depending on `MapPoint.locationType`.
  Shared by the ancestor map (`MapPoint.fromPerson`: death > birth priority — ancestors are
  overwhelmingly deceased) and the descendant map (`MapPoint.fromPersonPreferCurrent`:
  current > death > birth — descendants, especially recent generations, are usually alive).
  (Won't render as a Claude Artifact — CSP blocks external tiles/CDN; open the HTML locally.)
- `CousinMapWriter` — same Leaflet approach, but a **fixed 6-colour scale** by relationship
  degree (red = sibling, orange → purple = 1st → 5th cousin) instead of a continuous
  generation scale, also using `fromPersonPreferCurrent`.
- `InvalidateCache <guid>[,<guid>…] … [--cache-dir <dir>]` — deletes a person's cache file
  (matches on `focus.guid`, which is correct — grep-by-guid is NOT reliable) so the next
  fetch re-downloads them. Guids may be comma-separated within one arg, space-separated as
  multiple args, or both. Matches any cache-version filename (`*.v*.json`), so it also
  cleans up orphaned files left behind by a past `CACHE_VERSION` bump, not just the current
  version. **Which profile to invalidate:** whoever's own Geni data changed, not
  necessarily the person you edited — e.g. adding a new child means invalidating an
  *existing* parent (their `immediate-family` response is what reveals the new child; the
  new child herself was never cached, so there's nothing to invalidate for her). Cache
  files are keyed by Geni's short internal `id`, not the long public guid — to find a
  specific file, `grep -rl '"guid":"<guid>"'` the cache dir rather than guessing the
  filename.
- `LineageEndpoints <gedcom-dir> <person-id>` — EXPLORATORY, offline (reads the GEDCOM
  directory directly, no Geni API), not yet wired into the main report. Prototype for the
  "label ancestors by documented lineage" feature — see its own javadoc and "Open /
  possible next steps" below for what it computes and the open design question.
- `PlaceOverrides` + `place-overrides.tsv` — manual coordinate corrections for places Geni
  geocoded wrongly (e.g. "Babylon" → Babylon NY). We do NOT geocode; all coords are Geni's.

## Geni API auth & limits

- **Client-side (implicit) OAuth.** App registered with Site+Callback on a domain the
  user controls (localhost was rejected). Get a token by visiting
  `https://www.geni.com/platform/oauth/authorize?client_id=<key>&redirect_uri=<callback>&response_type=token`
  while logged in, then read `#access_token=…` from the address bar. Token lasts ~24h.
- **Rate limit: approved 2026-08-10** at **40 requests / 10s** (App ID 2102 "Ancestor
  Fetcher", approved by Geni support). Previously unapproved = 1 request/10s. `GeniClient.
  pace()` derives its spacing from the `X-API-Rate-Limit`/`X-API-Rate-Window` response
  headers automatically (`ceil(window*1000/limit * 1.2)` headroom), so no code change was
  needed to pick up the new limit. **Confirmed with a real full-cache-wipe refetch of
  Irit's tree the same night: ~100 profiles/min sustained end-to-end**, 1,839 profiles, no
  slowdown at any generation depth or through heavy 403-denial clusters. This genuinely is
  a large, real speedup — see the note below on why we'd earlier (wrongly) predicted
  otherwise. Approval also grants the `profile/ancestors` endpoint (fetch a person's whole
  ancestor line in fewer round trips than our BFS over `immediate-family`) — not yet used,
  lower priority now that plain rate approval already fixed throughput.
- **Historical per-call-latency claim did NOT reproduce and should be treated as stale.**
  An earlier debugging session (weeks prior) measured ~2-3 profiles/min even after fixing
  a pacing bug, and attributed it to Geni's `immediate-family` endpoint itself taking ~20s
  to respond (independent of rate-limit spacing) — a real measurement at the time. Based on
  that, we predicted the new 40/10s approval wouldn't help since per-call latency, not
  spacing, was assumed to be the floor. That prediction was wrong: the Aug 10 live refetch
  ran at ~100/min, ~30x the old figure, with no sign of a ~20s-per-call latency floor.
  Whatever caused the original ~20s figure — Geni-side conditions at the time, or something
  about the unapproved tier beyond pure rate spacing — it isn't happening now. Moral: don't
  trust either old throughput number as a permanent constant; if fetch speed matters again,
  measure fresh rather than reasoning from this history.
- **`GeniClient.pace()` measures spacing from request-*start* to request-*start*, not from
  the previous response's return.** It used to sleep the full target spacing unconditionally
  before every request, so a slow response got a *full extra* spacing tacked on afterward.
  Now it tracks when the previous request was sent and sleeps only the remainder needed to
  reach the target spacing (never negative). **Deliberately not parallelized to go faster**:
  concurrent requests might help throughput, but this app behaves conservatively (no 429s)
  specifically to protect its standing with Geni — concurrent connections risk looking
  abusive regardless of approval status. Rejected in favor of waiting on rate-limit
  approval, which arrived and, per the measurement above, already made fetches fast enough
  that concurrency isn't worth the reputational risk.
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

- **Sharing a cache directory across two close cousins' fetches works and pays off
  substantially.** When Nini (Doreen, Irit's cousin) got her own ancestor + cousin fetch
  (2026-08-26), her `GeniFetch`/`GeniCousinFetch` were pointed at Irit's existing
  `geni-cache/6000000097910553827` instead of a fresh directory, since the cache is keyed
  purely by each profile's own Geni id — it has no concept of "whose tree." Result: her
  ancestor fetch got 27/80 profiles (34%) from cache, and her cousin fetch got 997/1,320
  (75.5%!) from cache. No effect on Irit's own report either — `FamilyRelationshipAnalyzer`
  computes ancestors/descendants/cousins by graph traversal from the specific target
  person's own id, so extra profiles from Nini's unrelated side just sit inert, unreachable
  from Irit's own id. Two separate `.ged` output filenames are still required though —
  each fetch overwrites its output completely rather than merging, so sharing an output
  filename (a mistake caught before running it) would have clobbered one person's file
  with the other's assembled data.
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
  show people present in the data. `fetchWithDescendants` (see above) now recovers these
  by fetching descendants of ancestors — confirmed with a real live run.
- **A deep descendant fetch hits privacy-restricted (403) profiles often** — expected on
  any run that reaches many living relatives, not an edge case. See the `GeniClient` /
  `GeniAncestorFetcher` "Private" handling above.
- **A "phantom extra parent" in a report means a stale guid from an old export, not a data
  entry error.** Geni occasionally merges/renumbers a profile's guid; an older hand-export
  file still carrying the pre-merge guid and a fresh Geni-API-fetched file carrying the
  current guid both describe the same real person, but the analyzer merges files by exact
  `@I…@` id, so it sees two distinct parents. Diagnose by finding the two `FAM` records for
  the affected child across all `.ged` files — a real duplicate has the *same spouse and
  child* in both records with only the other parent's guid differing. Fix by editing the
  older file: repoint the stale `HUSB`/`WIFE` line to the current guid and delete the
  now-orphaned old INDI record (safe if that record carries no unique data — check it isn't
  a `CHIL` anywhere and has no dates/parents of its own before deleting). One instance
  found and fixed 2026-08-11 (Marcus Mordechai Bergwerk's father, Yehuda Bergwerk,
  duplicated between `GEDCOM All 2025_07_22.ged` and the current Irit fetch). Likely to
  recur elsewhere as old exports age against fresh API fetches — no general tooling built
  for it yet (a `PlaceOverrides`-style guid-alias mechanism was considered and explicitly
  deferred in favor of one-off fixes, since these are rare enough not to be worth the
  infrastructure yet).

## Local, machine-specific setup (NOT in the repo — recreate per machine)

These are git-ignored and won't come from a clone. The project's code lives in a plain
local git clone per machine (synced via GitHub push/pull); the actual data — GEDCOM
files, the Geni cache, and generated reports — lives once on Google Drive and each
clone just symlinks to it, so both machines share the same data without putting any of
it in git:
- `.vscode/launch.json` — per-person run configs (has family names/IDs; kept local, one
  copy per machine — not synced via Drive or git).
- `.vscode/settings.json` — excludes `gedcoms`/`geni-cache`/`output` from VS Code's file
  watcher and search indexer. **Recreate this on every machine** — without it, VS Code
  recursively walks those Drive symlinks on startup and can take a very long time to
  open the window (or appear to hang with no error), since Drive's cloud filesystem is slow
  (e.g. a plain `cp -R` of ~1,250 small cache files timed out repeatedly; `rsync` was needed).
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

- **IN PROGRESS: label ancestors by documented lineage** (started 2026-08-26). Mark wants
  notable/documented ancestors (e.g. the Exilarch line, Rashi's family) annotated in the
  report, not just shown as bare names. `LineageEndpoints` (new, exploratory/not yet
  wired into the report — see its own javadoc for full findings) computes, for a given
  person: the count of distinct ahnentafel "lines" (root-to-leaf paths through the
  ancestor binary tree, NOT deduped across pedigree-collapse paths the way
  `getAncestorsByGeneration` is), ranks distinct endpoint ancestors by how many lines
  terminate at each (a "worth checking first" proxy, not notability itself), and
  auto-flags likely-titled names by regex (no separate title field exists — any signal
  is embedded in the free-text name). Real run against Mark's tree: 64,540 lines / 312
  distinct endpoints (the longest reaching 102 generations back, an unbroken Exilarch
  succession to Zerubbabel, ~500s BCE — matches Mark's "back to 600 BCE"). Expanding
  scope to "any ancestor anywhere in the tree" (not just the 312 endpoints, since a
  notable figure like Rashi can be a mid-line waypoint whose own ancestry continues
  further back) found 1,193 total distinct ancestors, 445 of them (37%!) auto-flagged —
  this tree contains a substantial documented rabbinic genealogy (~1300s-1700s
  Poland/Germany: Katzenellenbogen, Horowitz, Spira, Isserles/"Rama", Loew/"Maharal",
  Luria, Weil, Auerbach, among others), not just a handful of isolated notable people. A
  batch of real web searches against the ~20 top-multiplicity endpoints confirmed nearly
  all as genuine historical figures (Kalonymus dynasty of Mainz; Rashi's own
  family/students — Rashbam, RIBaN, Ri HaZaken, Ri Bekhor Shor; the Abin/Abun family) —
  only one name came back inconclusive. **Unresolved open question, pick up here first:**
  given how many of the 445 already carry an obvious title in their own name text, is
  that self-evident enough to label without individual web research (saving actual
  research effort for genuinely ambiguous names, like the original top-20 batch before
  we knew who "Shimon, of Le Mans" was)? Mark signed off before answering. Also still
  undecided: where the resulting labels/notes should live (a new field on `Person`? a
  `place-overrides.tsv`-style annotation file? report-display-only, not fed back into the
  GEDCOM?) and how they'd render in the HTML report.
- **Cousin/descendant fetcher — DONE, confirmed working.** `GeniAncestorFetcher.
  fetchWithDescendants` + `GeniCousinFetch`/`BuildCousinGedcom` (see Architecture above),
  plus the ancestor/descendant/cousin maps, the current-residence field, and "Private"
  handling for access-denied profiles. Live `GeniCousinFetch` runs completed successfully
  for both Irit (6 generations, most recently a full cache-wipe refetch 2026-08-10 after
  extensive Geni edits — see the rate-limit section above) and Mark (`Mark-geni-cousins.ged`,
  825KB, completed 2026-08-02) and the resulting reports looked right.
- **Higher Geni rate limit — DONE, approved 2026-08-10** (40 req/10s; see Geni API auth &
  limits above). No code change needed, and confirmed with a real full refetch of Irit's
  tree the same night: ~100 profiles/min sustained, ~30x the old ~3/min figure — this
  genuinely fixed fetch speed, contrary to an initial (wrong) prediction that per-call
  latency would still bottleneck it. `profile/ancestors` (also newly unlocked) remains an
  option to cut call count further but is now lower priority.
- Optional: Google Geocoding fallback for places Geni left WITHOUT any coordinates
  (distinct from `place-overrides.tsv`, which fixes WRONG coordinates).
