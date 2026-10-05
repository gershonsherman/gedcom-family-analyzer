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
  - **Collapsible navigation.** The report is built from nested `<details>`: every main
    section is a `<details class="section">` whose `<summary>` is the heading (ANCESTOR MAP,
    ANCESTORS, NOTABLE ANCESTRAL LINES — now its own top-level section, DESCENDANT MAP,
    DESCENDANTS, SIBLINGS, COUSIN MAP, COUSINS), and each generation/degree under
    Ancestors/Descendants/Cousins is a nested `<details class="gen">`. Helpers
    `openSection`/`closeSection`/`openGen`/`closeGen` emit them; a summary is styled to look like
    the old `<h2>`/`<h3>` (the plain `.generation` div/`<h3>` path is gone) with a rotating ▸
    marker. **Everything starts collapsed** (the `open` arg to those helpers is `false`
    everywhere) — flip an `openSection(...)`/`openGen(...)` call's last arg to `true` to have a
    section start expanded. `writeAncestorsHtml` returns the per-ancestor generation map so the
    notable section (written right after it in `main`, not nested inside ANCESTORS anymore) can
    reuse it; `writeNotableLineages` writes nothing when there's no notable content, so it never
    leaves an empty section. **Leaflet-in-collapsed-`<details>` gotcha:** a map initialized in a
    hidden 0×0 container renders broken, so `AncestorMapWriter`/`CousinMapWriter` each append a
    one-shot `toggle` handler (`getElementById(divId).closest('details')`) that calls
    `invalidateSize()` + re-`fitBounds` the first time its section opens, then unhooks so later
    toggles keep the user's pan/zoom. Harmless when a section is open by default or when the map
    isn't inside a `<details>` (standalone map files → `closest` is null).
  - **Inline lineage context.** Each person entry renders as `name (id) (↑ … , ↓ …)` — the id
    inline right after the name, then a grey `.lineage` note. Arrows follow **page/list
    direction**, not tree direction: ↑ = toward the target (up the list, the closer generation),
    ↓ = further away (down the list). So **ancestors** (Grandparents+, gen ≥ 2) show
    `↑ children, ↓ parents`; **descendants** (Grandchildren+, gen ≥ 2) show `↓ children` only
    (their parents are already in the "Children of X & Y" group header). `writePersonEntry(…,
    crossRef, upNames, downNames)` takes pre-formatted name lists from `joinNames` (which
    HTML-escapes and wraps each name in a bidi-isolated `.nm` span so a Hebrew name can't reorder
    the arrows/commas); `lineageContext` assembles the note. Parents/children come straight off
    `Person.getParents()`/`getChildren()` (already resolved), so no GedcomData handle is needed.
    Gen-1 rows (Parents / Children), Siblings and Cousins get no lineage note.
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
  filename. **Speed:** because of that id-vs-guid mismatch, finding a guid's file otherwise
  means opening and parsing *every* `*.v*.json` — brutal on the cloud-mounted cache (~4,300
  files ≈ tens of minutes at ~0.8 files/sec cold). So it uses `GuidCacheIndex`
  (`<cache-dir>/.guid-index.tsv`, one `<filename>\t<focusGuid>` line per file): `reconcile()`
  reads focus.guid only out of files **not already indexed** and drops vanished ones, so the
  first run per cache dir does a one-time full scan (with `...indexed N/total (X%)` progress),
  and every run after is effectively instant (it reads just whatever a later fetch added).
  **Fast path:** if the already-saved index covers every target guid, it deletes straight away
  and skips `reconcile()` entirely — no directory listing, no file reads at all ("All target
  guid(s) already in the index — no scan needed."). It only reconciles (which must scan to read
  files a later fetch added) when a target isn't in the index yet. The index lives inside the
  git-ignored cache dir; delete it to force a rebuild. Nothing else maintains it yet —
  `GeniClient` could append to it on each cache write to keep even the first-after-a-big-fetch
  run instant, deferred as not worth touching the fetch hot path.
- `LineageAnalysis` — the report-wired lineage computation (instance-based, built from a
  target's `getParents()` graph, no GedcomData handle). Powers the **"Notable Ancestral
  Lines"** appendix at the end of the ANCESTORS section: distinct title-flagged ancestors
  (each once) in collapsible `<details>` accordions bucketed by 10-great-grandparent range
  (collapsed by default; all shown, not capped — so deep notables like Rashi still appear),
  plus the deepest documented lines (endpoints by longest path, placeholder/spouse/"(No
  Name)" endpoints filtered out). Display-only — computed at report time, nothing stored.
  `looksNotable()` (the title/dynasty regex) lives here now. The notable set is
  `looksNotable(name) OR NotableAnnotations.isNotable(guid) OR NotableAnnotations.isFamous(guid)`
  — the middle catches title-less notables (e.g. the Abarbanel) via the research pass below;
  the last is the hand-curated **marquee** flag. Flagged entries render a one-line bio + a
  `[Geni]` link (`geniProfileUrl(guid)` = `/people/x/<guid>`; Geni is a JS SPA so this can't be
  server-verified, but it's the standard resolve-by-id deep link and a one-line fix since only
  the guid is stored). `allAncestors()` exposes the full ancestor set.
  - **Marquee "Including:" preview.** Under each generation-bucket accordion heading, an
    always-visible "Including:" block lists that bucket's **famous** ancestors (name + relationship
    only), so the standout names show without expanding the accordion (full dates/[Geni]/bio stay
    inside). Famous = the hand-set `famous` column of `notable-ancestors.tsv` (see below); the four
    seed names (Heschel, Maharshal, Abarbanel, Rashi) were just examples — **mark anyone famous
    by editing the tsv**. `guidOf()` (report) and `toGuid()` (`FetchNotableAbout`) match `\d{6,}`
    now, not `\d{10,}` — so shorter numeric Geni ids (e.g. Abraham Joshua Heschel = `3381491`) get
    a guid, a `[Geni]` link, and annotation lookups instead of being silently dropped.
- `NotableAnnotations` + `FetchNotableAbout` — the **research pass** enriching the above
  (shipped + validated live 2026-08-30 on Mark's ~1,192 ancestors). `FetchNotableAbout
  <gedcom-dir> <person-id> [ann-file]` (ONLINE, needs token) walks every ancestor, fetches its
  Geni `about_me`, and records — in the shared, guid-keyed, git-ignored `notable-ancestors.tsv`
  (`guid<TAB>proseLen<TAB>encLinks<TAB>famous<TAB>name<TAB>bio`; the `famous` flag and a
  human-readable `name` sit before the long free-text `bio` so the file reads at a glance —
  `famous` is **hand-curated only** (research pass writes `0`, every row an explicit `0`/`1`), and
  `name` is a display-name eyeballing aid NOT read back by the report, so a stale one is harmless)
  — the count of **encyclopedia citations**
  (Wikipedia/Jewish Encyclopedia/Wikidata/Britannica) and the first **prose-like** sentence
  (verbatim, no summarization). `isNotable(guid)` = `encLinks>=1`. **Key finding:** about_me
  *presence* is a poor notability signal (most ancestors have one, and long ones are usually
  genealogical data dumps or personal notes — e.g. "swam across the Vistula"); **encyclopedia
  citations** are the high-precision signal that separates the Abarbanel from ordinary
  relatives (~11% flagged vs. ~60% by length). Incremental (skips guids already recorded);
  `NOTABLE_FETCH_LIMIT=N` caps a test slice. Uses `GeniClient.fetchProfile(id,fields)` — a
  single-profile GET that does NOT touch the v3 immediate-family cache (so responses aren't
  cached: a schema change means a full ~6-min refetch). One shared file across all trees
  (guid is universal), and it's hand-editable to polish marquee bios **and to set the `famous`
  flag**. To mark someone famous: find them in the report's Notable accordions, copy the guid out
  of their `[Geni]` link (`/people/x/<guid>`), then in `notable-ancestors.tsv` flip the `famous`
  column (4th, before `bio`) from `0` to `1` on that guid's row (or add a row
  `<guid>\t0\t0\t1\t<name>\t<optional bio>` if none exists — an empty-bio famous row still renders
  name+relationship in the preview), and regenerate the report. `isFamous(guid)` = `famous`
  column is `1`/`true`/`yes`. The `name` column (backfilled from the GEDCOMs for existing rows,
  written from the Geni/display name for new ones) makes it easy to find the right guid by eye.
- `LineageEndpoints <gedcom-dir> <person-id>` — EXPLORATORY, offline, standalone `main`,
  NOT wired into the report (that's `LineageAnalysis` now). Kept for its richer path-count
  analysis (ahnentafel line counts, multiplicity ranking) used while designing the feature.
- `PlaceOverrides` + `place-overrides.tsv` — manual coordinate corrections for places Geni
  geocoded wrongly (e.g. "Babylon" → Babylon NY). We do NOT geocode; all coords are Geni's.
- `AncestorStops` + `ancestor-stops.tsv` — guids the fetcher will NOT ascend past (committed,
  like `place-overrides.tsv`; format `guid<TAB>note`). `GeniAncestorFetcher.ascend()` treats a
  stop-listed profile as a boundary — fetches the person, but doesn't follow their parents.
  **Why:** Geni's collaborative tree has spurious cross-tree bridges where one wrong parent link
  splices a Jewish line into the exhaustively-documented **medieval-European-royalty** tree,
  flooding a deep fetch (`maxGenerations` large) with tens of thousands of non-ancestors. The
  first real case (2026-08-31): **Juana Abravanel** (`6000000024862267114`, ~gen 23 from Mark)
  was given "Gonzalo de Monroy" (a Spanish Christian noble) as a parent — the classic
  **Abarbanel/Abravanel → Iberian-nobility** splice. A `maxGen=200` fetch ballooned to 3,193
  profiles / 535 royals; stop-listing that one node dropped it to 1,020 with **zero** royals.
  Applies to both online `GeniFetch` and offline `BuildGedcom` (both use `ascend`). **Diagnosis
  tip:** the royal *names* (King/Duke/Count of …, de Bourgogne/Limoges/Narbonne) get obvious deep
  (~gen 30+), but the entry *edge* is shallower — find it by tracing the parent graph from the
  target up to the shallowest royal and watching for the Jewish→Christian name flip, or by
  cutting a candidate node and re-counting reachable royals (should drop to ~0). **Verification
  tip:** compare per-generation ancestor counts against a close relative's pre-contamination
  report (e.g. a first cousin's) — the deep shared generations should match exactly; a royal
  leak shows up as an explosion at depth.

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
- **Bidi/RTL rendering gotcha:** a display name ending in Hebrew (RTL, e.g. Rashi = "RASHI -
  רש״י") followed on the same line by neutral punctuation + a number ("— 24th …") gets those
  pulled into the RTL run and visually reordered. Fixed once, globally, with `unicode-bidi:
  isolate` on the `.person-name` CSS class (every name in the report renders through that span),
  not per-string hacks. The Leaflet map popups render names via a different path
  (`AncestorMapWriter`/`CousinMapWriter`) and would need the same isolation if the bleed shows
  there.

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

- **SHIPPED (2026-08-30): "Notable Ancestral Lines" report appendix** (see
  `LineageAnalysis` in Architecture). Labels documented/notable ancestors at the end of the
  ANCESTORS section. v1 shipped the **title-in-name auto-flag** and v2 (same day) added the
  **encyclopedia-citation research pass** below (notable = title OR encyclopedia-cited):
  on Mark's tree the title flag alone catches 445 of ~1,193 distinct ancestors — a substantial documented
  rabbinic genealogy (Katzenellenbogen, Horowitz, Spira, Isserles/"Rama", Loew/"Maharal",
  Luria, Weil, Auerbach…) — and the deepest line runs 102 generations to Zerubbabel (3rd
  Exilarch, ~500s BCE, matching Mark's "back to 600 BCE"). Rendered as layout **C**: notable
  ancestors in collapsible generation-bucket accordions (`<details>`, by 10-great-grandparent
  range, click to expand — all shown, not capped, so Rashi at ~24th ggp appears) + a
  deepest-lines list (top 15, placeholder/spouse endpoints filtered). Per-person-once (not
  "famous people under each line") because pedigree collapse puts the same person on dozens
  of lines.
  - **SHIPPED v2 (2026-08-30): the research pass.** No longer auto-flag-only — see
    `NotableAnnotations` + `FetchNotableAbout` in Architecture. Fetches every ancestor's Geni
    `about_me`, flags notability by **encyclopedia citations** (not about_me length — length
    over-flags on data dumps), and shows a verbatim prose bio + `[Geni]` link. Validated live
    on Mark's tree: 1,181 fetched, 132 encyclopedia-flagged, lifting the notable set 445 → 530
    (the added ~85 are title-less: Katzenellenbogen, Auerbach, Abarbanel…). Stored in the
    shared guid-keyed `notable-ancestors.tsv` (git-ignored), merged at report time — NOT the
    GEDCOM, since both fetched and re-exported `.ged` files get overwritten. Hand-editable to
    polish individual bios (a few auto-extracted ones grab a citation/book title).
  - A design-time batch of web searches on ~20 top endpoints confirmed nearly all as genuine
    (Kalonymus dynasty of Mainz; Rashi's family/students — Rashbam, RIBaN, Ri HaZaken, Ri
    Bekhor Shor), so even the title flag is a trustworthy first pass.
  - **Still possible (deferred):** individual human/Claude verification of borderline names,
    and a curated "most notable" highlight — but the encyclopedia-citation signal already
    gives a high-precision automatic pass, so this is low priority.
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
