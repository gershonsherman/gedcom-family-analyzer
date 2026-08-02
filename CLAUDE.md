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
  they all share the target's own family, already named in the info header above.
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
  email to `api@geni.com` (answer their read-only/personal-use questionnaire) — **request
  is still pending** as of this writing.
- **`GeniClient.pace()` measures spacing from request-*start* to request-*start*, not from
  the previous response's return.** It used to sleep the full target spacing unconditionally
  before every request, so a slow response (Geni's `immediate-family` endpoint can take
  ~20s) got a *full extra* spacing tacked on afterward — observed throughput was ~2-3
  profiles/min against a ~5/min target. Now it tracks when the previous request was sent
  and sleeps only the remainder needed to reach the target spacing (never negative) — this
  can't go below Geni's own per-call response time, which is now the real floor, not our
  code. **Deliberately not parallelized to go faster**: concurrent requests might dodge the
  latency floor (if Geni's limit is purely rate-based, not connection-based), but this app
  is unapproved and under the stricter tier specifically because it behaves conservatively
  (no 429s) — concurrent connections risk looking abusive and could jeopardize the pending
  rate-limit approval above. Rejected in favor of just waiting on Geni's approval.
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
  show people present in the data. `fetchWithDescendants` (see above) now recovers these
  by fetching descendants of ancestors — confirmed with a real live run.
- **A deep descendant fetch hits privacy-restricted (403) profiles often** — expected on
  any run that reaches many living relatives, not an edge case. See the `GeniClient` /
  `GeniAncestorFetcher` "Private" handling above.

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

- **Cousin/descendant fetcher — DONE, confirmed working.** `GeniAncestorFetcher.
  fetchWithDescendants` + `GeniCousinFetch`/`BuildCousinGedcom` (see Architecture above),
  plus the ancestor/descendant/cousin maps, the current-residence field, and "Private"
  handling for access-denied profiles. A live `GeniCousinFetch` run for Irit (6 generations)
  completed successfully and the resulting report looked right. **Mark's own cousin fetch
  hasn't been run yet** — same command, just his id/cache dir (see `launch.json`).
- Higher Geni rate limit request — already submitted, **pending approval** (see Geni API
  auth & limits above). Still worth following up on; a descendant run is much bigger than
  an ancestor-only one, and per-call latency (not just rate spacing) is now the real
  bottleneck either way.
- Optional: Google Geocoding fallback for places Geni left WITHOUT any coordinates
  (distinct from `place-overrides.tsv`, which fixes WRONG coordinates).
