# API Specification

Base URL (local): `http://localhost:8080/api`

All requests and responses are JSON with camelCase field names. Timestamps are
ISO-8601 UTC (`2026-10-01T10:00:00Z`). Ids are UUIDs.

Items marked **(extension)** were added in Phase 2 on top of the Phase 0 draft, and items
marked **(extension, Phase 3)** were added with the AI explanation layer; they are all
additive, so clients written against the draft keep working. The **Profile** endpoints
(Phase 4) are new. **(Phase 5)**, **(Phase 6)** and **(Phase 7)** items are new in those
phases.

> **Personal data, no auth.** `/api/profile/**` returns the owner's CV and contact details
> (PII) to anyone who can reach the port, and so does `/api/applications/**` **(Phase 7)**:
> tailored CVs, cover letters, their PDF/DOCX files and the tracker notes. The server listens on `127.0.0.1` by default
> (`SERVER_ADDRESS`); don't expose it until authentication exists (`PRODUCTION_READINESS.md`,
> section 6).

## Conventions

### Paging (all list endpoints)

| Param | Default | Allowed | Notes |
|---|---|---|---|
| `page` | `0` | `>= 0` | zero-based |
| `size` | `20` | `1..100` | out of range is a `400 INVALID_PARAMETER` (never clamped) |

```json
{ "content": [ ... ], "page": 0, "size": 20, "totalPages": 10, "totalElements": 200 }
```
`size` and `totalElements` are **(extension)**.

### Input normalization

Strings are normalized like the ETL before validation and storage: surrounding
whitespace is trimmed and blank becomes `null`; emails are also lowercased; skill names
also collapse internal whitespace runs to one space. Skills are referenced **by name,
case-insensitively**, must already exist (`POST /skills`), and are never auto-created.

### Unknown JSON fields

Request bodies with unknown fields are rejected (`400 MALFORMED_REQUEST`,
e.g. `"Unknown field 'fullname'."`), so typos never silently drop data.

### Request ids

Every response carries `X-Request-Id`. A client-supplied `X-Request-Id` matching
`[A-Za-z0-9-]{1,64}` is reused; otherwise the server generates one. The same id appears in
error bodies and server logs.

---

## Candidates

### `GET /candidates`
**Query params:** `page`, `size`, `skill` (optional, case-insensitive skill name; an unknown
skill returns an empty page).

Sorted by `fullName`, then `id`.

**Response `200`**
```json
{
  "content": [
    { "id": "uuid", "fullName": "Ada Lovelace", "email": "ada@example.com", "summary": "..." }
  ],
  "page": 0, "size": 20, "totalPages": 3, "totalElements": 45
}
```
**Errors:** `400 INVALID_PARAMETER`

### `GET /candidates/{id}`
**Response `200`**
```json
{
  "id": "uuid",
  "fullName": "Ada Lovelace",
  "email": "ada@example.com",
  "summary": "...",
  "skills": [ { "skillId": "uuid", "name": "Java", "category": "Language", "yearsExperience": 5 } ],
  "createdAt": "2026-10-01T10:00:00Z",
  "updatedAt": "2026-10-01T10:00:00Z"
}
```
Skills are sorted by name. `skillId`, `category`, `createdAt`, `updatedAt` are **(extension)**.

**Errors:** `400 INVALID_ID`, `404 CANDIDATE_NOT_FOUND`

### `POST /candidates` **(extension)**
```json
{ "fullName": "Ada Lovelace", "email": "Ada@Example.com", "summary": "...",
  "skills": [ { "name": "Java", "yearsExperience": 5 }, { "name": "SQL" } ] }
```
**Response `201`** with `Location: /api/candidates/{id}` and the candidate detail
(email stored as `ada@example.com`).

| Field | Rule |
|---|---|
| `fullName` | required, max 200 |
| `email` | required, valid (`local@domain.tld`), max 320, unique case-insensitively |
| `summary` | optional, max 20000 |
| `skills` | optional (may be empty), max 100 entries |
| `skills[i].name` | required, must exist, no duplicates |
| `skills[i].yearsExperience` | optional, `0..60` |

**Errors:** `400 VALIDATION_FAILED` (all invalid fields reported at once), `400 MALFORMED_REQUEST`,
`409 EMAIL_ALREADY_EXISTS` (`"A candidate with email ada@example.com already exists (id …)."`)

### `PUT /candidates/{id}` **(extension)**
Full replace, including skills (same body and rules as `POST`). Skill links are diffed:
dropped skills are removed, new ones added, years updated only where changed, so an
identical `PUT` writes nothing (and does not make matches stale). Last write wins.

**Response `200`** candidate detail. **Errors:** `400`, `404 CANDIDATE_NOT_FOUND`, `409 EMAIL_ALREADY_EXISTS`,
`409 DATA_CONFLICT` for the owner's own candidate (`"This candidate is your profile; edit it with
PUT /api/profile."`, Phase 4), `503 SERVICE_BUSY` (+ `Retry-After: 2`) **(Phase 6)**.

**Phase 6:** the update first takes the candidate's match lock (`candidate_match:{id}`), so it
serializes with candidate-side scoring (`GET /candidates/{id}/matches`). If the lock can't be taken
within 10 s, nothing is saved and the answer is `503 SERVICE_BUSY` (`"The server is busy with a
conflicting update. Please try again in a few seconds."`); retry.

### `DELETE /candidates/{id}` **(extension)**
**Response `204`**. Also deletes the candidate's skills and match rows.
**Errors:** `400 INVALID_ID`, `404 CANDIDATE_NOT_FOUND`, `409 DATA_CONFLICT` for the owner's own
candidate (`"This candidate is your profile; it can't be deleted here."`, Phase 4).

---

## Jobs

### `GET /jobs`
**Query params:** `page`, `size`, `skill` (optional; matches jobs listing the skill as
**required or nice-to-have**).

Sorted by `title`, `company`, `id`.

**Response `200`**
```json
{
  "content": [
    { "id": "uuid", "title": "Backend Engineer", "company": "Acme", "description": "...",
      "skillCount": 4, "matchable": true, "origin": "MANUAL" }
  ],
  "page": 0, "size": 20, "totalPages": 3, "totalElements": 50
}
```
`skillCount` and `matchable` are **(extension)**. `matchable` is `false` for jobs with no
skills (possible for ETL-loaded jobs).
`origin` **(extension, Phase 5)** is `MANUAL` (created here or by the ETL) or `FEED` (found by
the job feed). FEED jobs are listed and can be matched like any other job, but they are
read-only here: `PUT` and `DELETE` answer `409 DATA_CONFLICT`.

### `GET /jobs/{id}`
**Response `200`**
```json
{
  "id": "uuid", "title": "Backend Engineer", "company": "Acme", "description": "...",
  "matchable": true,
  "skills": [ { "skillId": "uuid", "name": "Java", "category": "Language", "required": true } ],
  "createdAt": "2026-10-01T10:00:00Z", "updatedAt": "2026-10-01T10:00:00Z",
  "origin": "MANUAL"
}
```
**Errors:** `400 INVALID_ID`, `404 JOB_NOT_FOUND`

### `POST /jobs` **(extension)**
```json
{ "title": "Backend Engineer", "company": "Acme", "description": "...",
  "skills": [ { "name": "Java", "required": true }, { "name": "Docker", "required": false } ] }
```
**Response `201`** with `Location: /api/jobs/{id}` and the job detail.

| Field | Rule |
|---|---|
| `title` | required, max 300 |
| `company` | required, max 200; `(title, company)` unique after trimming among MANUAL jobs (a FEED job with the same pair doesn't conflict) |
| `description` | optional, max 20000 |
| `skills` | **at least 1**, max 100 (`"Add at least one skill so candidates can be ranked for this job."`) |
| `skills[i].name` | required, must exist, no duplicates |
| `skills[i].required` | optional, default `true` |

**Errors:** `400 VALIDATION_FAILED` / `MALFORMED_REQUEST`, `409 JOB_ALREADY_EXISTS`

### `PUT /jobs/{id}` **(extension)**
Full replace, links diffed like candidates. **Response `200`**.
**Errors:** `400`, `404 JOB_NOT_FOUND`, `409 JOB_ALREADY_EXISTS`, `409 DATA_CONFLICT` for a FEED
job (checked before the body is validated): `"This job comes from the job feed (source
greenhouse:acme) and is kept up to date automatically. It can't be edited or deleted here."`
(the source part is omitted when the job has no posting), `503 SERVICE_BUSY` (+ `Retry-After: 2`)
**(Phase 6)**.

**Phase 6:** the update first takes the job's match lock (`job_match:{id}`, the lock every match
writer uses), so a score is never computed from skills that are being replaced. If the lock can't
be taken within 10 s (for example while `GET /jobs/{id}/matches` or a candidate's matches request
is scoring this job), nothing is saved and the answer is `503 SERVICE_BUSY` (`"The server is busy
with a conflicting update. Please try again in a few seconds."`); retry. `POST` and `DELETE` don't
take the lock.

### `DELETE /jobs/{id}` **(extension)**
**Response `204`** (also deletes the job's skills and match rows). **Errors:** `400`, `404`,
`409 DATA_CONFLICT` for a FEED job (same message as `PUT`).

---

## Skills **(extension)**

### `GET /skills`
**Query params:** `q` (optional, case-insensitive substring), `page`, `size`.
Sorted by `lower(name)`, then `id`. Items: `{ "id", "name", "category" }`.

### `GET /skills/{id}`
**Response `200`** `{ "id", "name", "category" }`. **Errors:** `400 INVALID_ID`, `404 SKILL_NOT_FOUND`

### `POST /skills`
```json
{ "name": "Kubernetes", "category": "DevOps" }
```
`name` required, max 100 (whitespace normalized); `category` optional, max 100.
**Response `201`** with `Location: /api/skills/{id}`.
**Errors:** `400 VALIDATION_FAILED`, `409 SKILL_ALREADY_EXISTS`
(`"A skill named 'kubernetes' already exists as 'Kubernetes' (id …)."`). Since Phase 5, also
`409 SKILL_ALREADY_EXISTS` when the name is already an alias of a skill
(`"'Postgres' is already another name (alias) for 'PostgreSQL' (skill id …). Use that skill, or delete the alias first."`).

No `PUT`/`DELETE` for skills in Phase 2.

**Aliases in requests (Phase 5).** Wherever a request names skills (candidates, jobs, the
profile), a name that is not a skill name but is an alias resolves to the alias's skill
("Postgres" → PostgreSQL). An exact skill name always wins. Two names for the same skill in one
list are a duplicate (`400`, at the second entry).

### `GET /skills/{id}/aliases` (Phase 5)
**Response `200`** `[ { "id", "alias", "createdAt" } ]`, sorted by alias.
**Errors:** `400 INVALID_ID`, `404 SKILL_NOT_FOUND`

### `POST /skills/{id}/aliases` (Phase 5)
```json
{ "alias": "Postgres" }
```
`alias` required, max 100 (whitespace normalized). **Response `201`** with
`Location: /api/skills/{id}/aliases/{aliasId}`.
**Errors:** `400 VALIDATION_FAILED`, `404 SKILL_NOT_FOUND`, `409 SKILL_ALIAS_ALREADY_EXISTS` (the
alias exists already, on any skill, or it is the name of a skill).

### `DELETE /skills/{id}/aliases/{aliasId}` (Phase 5)
**Response `204`**. **Errors:** `400 INVALID_ID`, `404 SKILL_NOT_FOUND`, `404 SKILL_ALIAS_NOT_FOUND`

---

## Preferences (Phase 5)

Hand-entered filters for the job feed. Nothing derives them from the CV or from AI.

### `GET /preferences`
**Response `200`** `{ "version": 3, "preferences": { … }, "updatedAt": "…" }`.
**Errors:** `404 PREFERENCES_NOT_FOUND` (`"No job preferences saved yet; the feed is not filtered. …"`)

### `PUT /preferences`
Full replace; the version goes up by one on every save. **Response `200`** (same shape as `GET`).
```json
{ "preferences": {
    "targetTitles": ["Backend Engineer"],
    "excludedTitleKeywords": ["Intern", "Sales"],
    "regions": { "countries": ["ZA"], "includeRemote": true, "remoteScope": "ELIGIBLE_FROM_COUNTRIES",
                 "remoteLocationKeywords": ["worldwide", "anywhere", "global", "emea", "africa", "south africa"] },
    "seniority": ["MID", "SENIOR"],
    "salaryFloor": { "amount": 600000, "currency": "ZAR", "period": "YEAR" },
    "workAuthorization": ["ZA"],
    "noticePeriodDays": 30 } }
```
| Field | Rule (every field optional) |
|---|---|
| `targetTitles` | 0–20 items, each 2–100 characters; empty = any title |
| `excludedTitleKeywords` | 0–30 items, each 2–50 characters |
| `regions` | omitted → the defaults shown above |
| `regions.countries` | ISO 3166 alpha-2 (case-insensitive), at most 50; omitted → `["ZA"]`; empty = any country |
| `regions.includeRemote` | default `true` |
| `regions.remoteScope` | `ANYWHERE` or `ELIGIBLE_FROM_COUNTRIES` (default) |
| `regions.remoteLocationKeywords` | 0–30 items, each 2–50 characters; omitted → the defaults above |
| `seniority` | `INTERN`, `JUNIOR`, `MID`, `SENIOR`, `LEAD`, `PRINCIPAL`, `MANAGER`; empty = any |
| `salaryFloor` | optional; `amount` > 0, `currency` ISO 4217, `period` `YEAR` or `MONTH` |
| `workAuthorization` | ISO 3166 alpha-2, at most 50; empty = not checked |
| `noticePeriodDays` | 0–365 |

Text is trimmed and whitespace runs are collapsed; duplicates (ignoring case) are dropped.
Field errors use full paths, e.g. `preferences.regions.countries[1]`: `"'XX' is not an ISO country code."`
After the save has committed, every open feed job is queued for re-evaluation. If that queueing fails
(e.g. it conflicts with a poll, or a job stays locked by another transaction for more than 2 s on each
of 3 attempts), the save still succeeds and the next processor run catches up.
**Errors:** `400 VALIDATION_FAILED`; `400 MALFORMED_REQUEST` for unknown fields or enum values.

---

## Job feed sources (Phase 5)

The watchlist the feed polls: company job boards (Greenhouse, Lever, Ashby) and Adzuna searches
(`ADZUNA`, South Africa). Each ACTIVE source is polled every
`effectivePollIntervalSeconds` (±10% jitter) by a background scheduler, or on request with
`POST /feed/sources/{id}/poll`. The outcome of the last poll is in `lastStatus`:

| `lastStatus` | Meaning | Next poll |
|---|---|---|
| `OK` | the listing was read and stored | after the interval |
| `NOT_MODIFIED` | `304`, or the same body as last time; nothing to store | after the interval |
| `SUSPICIOUS_EMPTY` | the listing suddenly lacks most open postings (or all of at least 3); nothing was closed. A second such poll in a row closes them | after the interval |
| `RATE_LIMITED` | the provider said `429` | after its `Retry-After` (at least the interval, at most 1 h), else as `ERROR` |
| `ERROR` | 5xx, network error or timeout (or saving failed) | interval × 2^failures, at most 1 h |
| `INVALID_RESPONSE` / `TOO_LARGE` | unreadable listing, more than half of the postings unreadable, or a body over 20 MB; nothing was saved | as `ERROR` |
| `NOT_FOUND` / `UNAUTHORIZED` | the board is gone or refused access; the source stays `ACTIVE` in case it comes back. For Adzuna, also `"Adzuna isn't configured: set ADZUNA_APP_ID and ADZUNA_APP_KEY."` (not counted as a failure) | after 6 h |
| `BUDGET_EXHAUSTED` | Adzuna only: today's request budget (`talentmatch.feed.adzuna.daily-request-budget`, default 200 per UTC day, shared by every Adzuna search, probes included) is used up. No request is made and no failure is counted | after midnight UTC (+0–5 min) |

A poll whose save loses a database lock conflict with a concurrent update of the same jobs is not a
failure: nothing is saved, `lastStatus`, `lastError` and `consecutiveFailures` stay as they were, and
the source is polled again 5–10 s later.
`consecutiveFailures` resets on `OK`/`NOT_MODIFIED`; `lastError` is a short sanitized note (no
response body, no URL query, no key). The first successful poll sets `baselineAt`: postings seen
then count as already known (not new) unless they were published within the last 24 h. A posting
that leaves a board's listing is closed; one that comes back is reopened. An Adzuna search is never
a complete listing: its postings close only after 14 days unseen (`adzuna.unseen-close-after`).
When page 1 of an Adzuna search is full and everything on it is newer than the last successful
poll, page 2 is fetched too (within the budget).

**`FeedSourceResponse`**
```json
{ "id": "…", "kind": "LEVER", "managedBy": "OWNER", "state": "ACTIVE", "companyName": "Acme",
  "boardToken": "acme", "options": { "leverInstance": "eu" }, "pollIntervalSeconds": null,
  "effectivePollIntervalSeconds": 300, "nextPollAt": "…", "lastPolledAt": null, "lastSuccessAt": null,
  "lastStatus": null, "lastError": null, "consecutiveFailures": 0, "openPostings": 0,
  "baselineAt": null, "createdAt": "…", "warnings": [] }
```
`effectivePollIntervalSeconds` is the interval actually used: `pollIntervalSeconds`, or the default
for the kind (company boards: 300; Adzuna: 900), and never below the minimum (company boards: 120;
Adzuna: 600). Adzuna searches share the daily budget, so each one waits at least
`24 h × active Adzuna searches / budget` (4 searches with a budget of 200: 1728 s).

An Adzuna source has `boardToken: null` and `options: {"country": "za", "what": "java developer",
"where": "Cape Town"}` (`where` only when set). Sources with `managedBy: "PREFERENCES"` are the
**derived searches**: one per target title in your preferences (at most 3; with no target titles,
your confirmed profile's most recent job title), plus `"remote"` when remote jobs are wanted. They
are kept in step automatically when preferences are saved or the profile is confirmed (and at
startup), only while the Adzuna keys are set; they can't be edited or deleted here. Adzuna jobs
show `"via": "Adzuna"` in `sources[]`, link to Adzuna's own page (attribution), and email
notifications add "Jobs by Adzuna".
`warnings` is filled only by `POST`. No ETag, body hash or API key is ever returned.

### `GET /feed/sources`
**Query params:** `page`, `size`, `kind` (`GREENHOUSE` | `LEVER` | `ASHBY` | `ADZUNA`), `state`
(`ACTIVE` | `PAUSED`). Newest first. **Response `200`** `PageResponse<FeedSourceResponse>`.
**Errors:** `400 INVALID_PARAMETER`

### `GET /feed/sources/{id}`
**Response `200`**. **Errors:** `400 INVALID_ID`, `404 FEED_SOURCE_NOT_FOUND`

### `POST /feed/sources`
```json
{ "kind": "LEVER", "boardToken": "acme", "companyName": "Acme",
  "options": { "leverInstance": "eu" }, "pollIntervalSeconds": 600, "verify": true }
```
| Field | Rule |
|---|---|
| `kind` | required: `GREENHOUSE`, `LEVER`, `ASHBY` or `ADZUNA`. `ADZUNA` needs `ADZUNA_APP_ID` and `ADZUNA_APP_KEY` (`400` on `kind` without them) |
| `boardToken` | company boards: required; `[A-Za-z0-9._-]{1,100}`, the board name from the URL (`boards.greenhouse.io/<token>`, `jobs.lever.co/<site>`, `jobs.ashbyhq.com/<name>`). `ADZUNA`: must be absent |
| `options.what` / `options.where` | `ADZUNA` only: search terms (required) and location (optional), each at most 100 characters; whitespace is collapsed. The country is `talentmatch.feed.adzuna.country` (`za`) |
| `companyName` | optional, max 200; Greenhouse fills it from the board when omitted |
| `options.leverInstance` | Lever only: `"eu"` (jobs.eu.lever.co) or `"global"` (default) |
| `pollIntervalSeconds` | optional, 120..86400 for company boards, 600..86400 for `ADZUNA` |
| `verify` | default `true`: check the board with the provider first (waits at most 10 s). For `ADZUNA`, runs the search once for one result (counts against the daily budget) |

Greenhouse and Ashby tokens are case-insensitive (`Acme` and `acme` are the same source). **Lever
site names are case-sensitive** and kept as given.

**Response `201`** with `Location: /api/feed/sources/{id}`. `warnings` explains anything to watch:
- `"The board exists but has no open postings right now."`
- `"Couldn't reach Lever to check the board; it will be checked on the first poll."` (network
  error, timeout or 5xx; the source is saved anyway). Rate limits and odd answers get a similar note.
- `verify: false` saves without a check and says so.
- Adzuna: `"The Adzuna search finds no jobs right now; it keeps checking for new ones."`, or
  `"Today's Adzuna request budget is used up, so the search wasn't checked; …"`.

**Errors:**
- `400 VALIDATION_FAILED`: invalid fields, or the provider has no such board (field `boardToken`:
  `"Greenhouse has no job board 'acme'. Check the token in the board URL (boards.greenhouse.io/<token>)."`),
  or Adzuna rejected the keys (field `kind`: `"Adzuna rejected ADZUNA_APP_ID / ADZUNA_APP_KEY. …"`).
- `400 MALFORMED_REQUEST`: unknown fields or an unknown `kind`.
- `409 FEED_SOURCE_ALREADY_EXISTS`: `"Lever site 'acme' is already on your watchlist (source id …)."`

### `PUT /feed/sources/{id}`
```json
{ "companyName": "Acme", "state": "PAUSED", "pollIntervalSeconds": null }
```
Full replace of the editable fields: `state` is required; a missing `companyName` or
`pollIntervalSeconds` clears it (the interval goes back to the default). `kind`, `boardToken` and
`options` can't be changed (`400 MALFORMED_REQUEST`, unknown field). A paused source set back to
`ACTIVE` is due at once. **Response `200`**.
**Errors:** `400 VALIDATION_FAILED`, `400 INVALID_ID`, `404 FEED_SOURCE_NOT_FOUND`,
`409 DATA_CONFLICT` (the source is managed by your job preferences; change those instead).

### `DELETE /feed/sources/{id}`
**Response `204`**. The source's postings are deleted, and so are feed jobs left with no posting
(with their skills, matches and notifications). A job that still has a posting from another source
is kept.
**Errors:** `400 INVALID_ID`, `404 FEED_SOURCE_NOT_FOUND`, `409 DATA_CONFLICT` (managed by your job
preferences).

### `POST /feed/sources/{id}/poll`
Polls the source now, whatever its schedule (a `PAUSED` source too). The poll runs in the
background; check `lastStatus` with `GET /feed/sources/{id}` a moment later.

**Response `202`**
```json
{ "sourceId": "…", "queued": true }
```
If every poll thread is busy, the source is made due and the scheduler polls it on its next tick
(still `202`).

**Errors:**
- `400 INVALID_ID`, `404 FEED_SOURCE_NOT_FOUND`
- `409 FEED_DISABLED`: the feed is switched off (`FEED_ENABLED=false`).
- `409 FEED_POLL_IN_PROGRESS`: the source is being polled right now.
- `429 FEED_POLL_RATE_LIMITED` with `Retry-After: n`: the source was polled less than 60 s ago
  (`"This source was polled less than a minute ago. You can poll it again in 42 seconds; …"`).

### `POST /feed/sources/lookup`
Finds a company's job boards by name, for when you don't know the board token. **Read-only**: it
never adds a source; add a board it finds with `POST /feed/sources` (its `kind` and `boardToken`).
```json
{ "companyName": "Acme Payments (Pty) Ltd" }
```
| Field | Rule |
|---|---|
| `companyName` | required, max 200; the name as on the company's careers page. Whitespace is collapsed |

The name is turned into up to 3 board-name **guesses**, most specific first: run together,
hyphenated, then the first word alone (only with two words or more, and at least 3 characters).
Accents are dropped, dots and apostrophes removed, other punctuation separates words, and trailing
legal suffixes (`Pty`, `Ltd`, `Inc`, `GmbH`, …) are ignored: `"Acme Payments (Pty) Ltd"` →
`acmepayments`, `acme-payments`, `acme`. Each guess is checked on Greenhouse, Lever (global instance
only; add `jobs.eu.lever.co` sites by hand with `options.leverInstance: "eu"`) and Ashby, within a
10 s budget for the whole lookup. Adzuna is never queried (no budget is used). A board already on
your watchlist is reported from the database without a check. Works while the feed is disabled.

**Response `200`**
```json
{ "companyName": "Acme Payments (Pty) Ltd",
  "candidates": ["acmepayments", "acme-payments", "acme"],
  "found": [
    { "kind": "GREENHOUSE", "boardToken": "acmepayments", "companyName": "Acme Payments",
      "openPostings": null, "warning": null, "sourceId": null },
    { "kind": "LEVER", "boardToken": "acme", "companyName": "Acme",
      "openPostings": 12, "warning": "Already on your watchlist.", "sourceId": "…" },
    { "kind": "ASHBY", "boardToken": "acme", "companyName": null,
      "openPostings": 0, "warning": "The board exists but has no open postings right now.", "sourceId": null }
  ],
  "unchecked": [ { "kind": "LEVER", "boardToken": "acme-payments", "reason": "TIMEOUT" } ],
  "complete": true }
```
- `found`: boards that exist, in the order checked (per guess, most specific first: Greenhouse,
  Lever, Ashby). `companyName` is the board's name (Greenhouse
  only) or, for a board already watched, the source's name. `openPostings` is known for Ashby boards,
  empty boards (`0`, with the warning) and watched sources; otherwise `null`. `sourceId` is set only
  for a board already on your watchlist.
- `unchecked`: boards that couldn't be checked. `reason`: `TIMEOUT`, `NETWORK`, `SERVER_ERROR`,
  `RATE_LIMITED`, `UNAUTHORIZED`, `INVALID_RESPONSE`, `TOO_LARGE`, `BUSY` (every check worker busy,
  or an unexpected error) or `BUDGET_EXCEEDED` (the 10 s budget ran out first).
- `complete`: `false` when the budget ran out before every guess was checked (some `unchecked` have
  `BUDGET_EXCEEDED`).
- Boards the providers don't know are left out. An empty `found` with `complete: true` and no
  `unchecked` means none of the guesses exists; the board may still use another name.
- A guess can match **another company** with the same name: check the board before adding it.

**Errors:**
- `400 VALIDATION_FAILED` on `companyName`: missing or blank, longer than 200 characters, with no
  letters or digits to build a board name from (`"companyName has no letters or digits (a–z, 0–9) to
  build a board name from. …"`), or giving no usable board name, e.g. a single word over 100
  characters (`"companyName gives no usable board name (board names are at most 100 characters of
  a–z, 0–9 and -). …"`). Both say to add the source with the `boardToken` from its board URL instead.
- `400 MALFORMED_REQUEST`: malformed JSON or unknown fields.
- `429 FEED_LOOKUP_RATE_LIMITED` with `Retry-After: n`: another lookup is running (`"Another company
  lookup is running; try again in 10 seconds."`), or the last one started less than 10 s ago (`"A
  company lookup ran less than 10 seconds ago; try again in 4 seconds."`). Invalid requests don't
  count.

The 10 s budget, the 10 s spacing between lookups and the 3 guesses are defaults, set by
`talentmatch.feed.lookup.budget`, `talentmatch.feed.lookup.min-interval` and
`talentmatch.feed.lookup.max-candidates`; the messages above show the configured values.

---

## Job feed (Phase 5)

Each feed job found or changed by a poll is processed in the background within seconds: its
skills are matched against the skill vocabulary (names and aliases; nothing is invented), it is
checked against your preferences, and it is scored against your confirmed profile with the same
scoring as `/jobs/{id}/matches`. A job with no recognised skills is not matchable and is never
scored or notified. A job that is open, new (first seen within the last 24 h and not part of a
source's first poll), passes your preferences and scores at least the notification threshold is
queued for one notification, at most once ever. Confirming your profile, saving preferences or
adding a skill or alias re-processes every open job (no repeat notifications).

### `GET /feed/jobs`
**Query params:** `page`, `size`, `since` (ISO-8601 instant, compared with `firstSeenAt`),
`minScore` (0..1, your score), `includeFiltered` (default `false`), `includeClosed` (default
`false`), `includeBaseline` (default `false`). Newest `firstSeenAt` first.
**Response `200`** `PageResponse<FeedJobResponse>`. Without a profile nothing is scored, so
`minScore` matches nothing. **Errors:** `400 INVALID_PARAMETER`

**`FeedJobResponse`**
```json
{ "jobId": "…", "title": "Backend Engineer", "company": "Acme", "primaryUrl": "https://…",
  "firstSeenAt": "…", "postedAt": "…", "closedAt": null, "baseline": false,
  "workplace": "HYBRID", "locationText": "Cape Town", "countryCodes": ["ZA"], "seniority": "UNKNOWN",
  "salary": { "min": 600000, "max": 800000, "currency": "ZAR", "period": "YEAR", "estimated": false },
  "score": 0.8, "scorePercent": 80, "matchable": true,
  "summary": "Matches 2 of 2 required skills; 0 of 1 nice-to-have.",
  "matchedRequired": ["Java", "SQL"], "missingRequired": [],
  "preferenceVerdict": "PASS", "filterReasons": [], "flags": ["REMOTE_ELIGIBILITY_UNKNOWN"],
  "skills": [ { "name": "Java", "required": true, "source": "DICTIONARY" },
              { "name": "Kubernetes", "required": false, "source": "DICTIONARY" } ],
  "aiSuggestions": [], "enrichmentStatus": "PENDING",
  "notification": { "status": "PENDING", "channel": "EMAIL", "sentAt": null },
  "sources": [ { "kind": "LEVER", "via": "Lever", "url": "https://…", "externalId": "…",
                 "firstSeenAt": "…", "closedAt": null } ] }
```
- `salary` is `null` when no salary is stated; `estimated` salaries are never used for filtering.
- `score` is your cached score (`null` without a profile, before processing, or when the job has no
  skills). `summary`, `matchedRequired` and `missingRequired` are computed from the job's current
  skills and yours.
- `preferenceVerdict` is `null` until the job was processed. `filterReasons`: `TITLE`,
  `EXCLUDED_KEYWORD`, `REGION`, `REMOTE_NOT_WANTED`, `REMOTE_REGION`, `SENIORITY`, `SALARY`,
  `WORK_PERMIT`. `flags`: `LOCATION_UNKNOWN`, `REMOTE_ELIGIBILITY_UNKNOWN`, `SALARY_OTHER_CURRENCY`,
  `IMMEDIATE_START` (never filter).
- `skills[].source`: `DICTIONARY`, `AI` or `BOTH`. `aiSuggestions` (`{name, requirement, evidence}`)
  are skills the AI found that aren't in the vocabulary; they are never created automatically (add
  one with `POST /skills` or as an alias, and open jobs are re-matched).
- `enrichmentStatus`: the background AI stage. `PENDING` (waiting, or retrying after a failure),
  `RUNNING`, `SUCCEEDED`, `FAILED` (after 3 tries; the job keeps its dictionary skills) or `SKIPPED`
  (AI is off; skipped open jobs are queued again when the app starts with AI on). The AI only adds
  skills it can quote from the posting, decides required vs nice-to-have, and a job it changes is
  re-scored (it can then be notified, once, if it is still new). It runs at most 30 calls an hour and
  waits while a CV extraction uses the local model.
- `notification` is `null` when none was queued.
- `sources` lists every posting of the job (several providers can post the same job).

### `GET /feed/jobs/{jobId}`
**Response `200`** `FeedJobResponse` plus `description` (omitted while the posting has none yet).
**Errors:** `400 INVALID_ID`; `404 JOB_NOT_FOUND` (also for a job that isn't from the feed:
`"Job … is not from the job feed. Read it with GET /api/jobs/…."`)

### `GET /feed/status`
**Response `200`**
```json
{ "enabled": true, "schedulerEnabled": true,
  "profile": { "present": true, "version": 3, "appliedVersion": 3 },
  "preferences": { "present": true, "version": 2 },
  "notifications": { "enabled": true, "channel": "EMAIL", "configured": true, "minScore": 0.6,
                     "pending": 1, "failedLast24h": 0, "paused": false, "pausedUntil": null, "lastError": null },
  "sources": { "total": 4, "active": 3, "failing": 1, "lastSuccessAt": "…" },
  "aggregator": { "configured": true, "requestsToday": 37, "dailyBudget": 200 },
  "processing": { "pending": 12 },
  "enrichment": { "pending": 3, "running": 1, "failed": 0, "modelBusy": false, "aiEnabled": true,
                  "pausedUntil": null, "capReachedUntil": null },
  "detection": { "p50LatencySeconds": 312, "samples": 9, "windowDays": 7 } }
```
- `schedulerEnabled`: sources are polled and their jobs processed automatically (the feed and its
  scheduler are both on).
- `profile.appliedVersion`: the profile version the open feed jobs were last queued for re-scoring
  against; it differs from `version` only until the processor catches up.
- `preferences.present`: `false` means the feed is not filtered.
- `sources.failing`: ACTIVE sources whose last poll failed.
- `processing.pending`: feed jobs waiting to be processed (skills, filter, score).
- `enrichment`: open jobs waiting for, in, or failed by the AI stage; `modelBusy` while a CV
  extraction holds the local model; `aiEnabled` false when AI is off (or
  `talentmatch.feed.skills.enrichment.enabled=false`); `pausedUntil` while the stage waits after the
  model timed out or failed; `capReachedUntil` while the hourly cap (30 calls) holds it. Both are
  `null` when not in effect.
- `detection`: how fast new jobs reach you. `p50LatencySeconds` is the median of
  `sent_at − postedAt` (or `firstSeenAt` when the provider gives no date) over the notification emails
  sent in the last `windowDays` (7) days, negative spans counted as 0; `samples` is how many emails
  that was. `p50LatencySeconds` is `null` with no samples. `samples` and `windowDays` go beyond the
  Phase 5 spec §5.2.
- `aggregator`: whether the Adzuna keys are set, and the requests counted against today's (UTC)
  budget, probes included.
- `notifications`: the saved settings (`enabled`, `channel`, `minScore`), whether the channel is
  `configured`, `pending` (queued or being sent), `failedLast24h` (failed in the last 24 hours,
  expired ones included), and the sender's state: `paused` / `pausedUntil` after a configuration
  error (bad login, TLS, unknown host) and `lastError`, the sanitized text of the last failed send
  (e.g. `"SMTP login failed (535). Check SMTP_USERNAME and SMTP_PASSWORD."`; cleared by a successful
  send). `paused`, `pausedUntil` and `lastError` go beyond the Phase 5 spec §5.2.

The actuator health component `feed` is `UP`, or `DEGRADED` when an ACTIVE source failed 3 times
in a row or has had no successful poll for more than 3 × its interval (details: counts and source
ids), with reason `EMAIL_CONFIG` while email sending is paused after a configuration error, or
`NOTIFICATIONS_FAILED` when a notification failed in the last hour (expired ones aren't counted).
Details include `notifications: {configured, paused, pausedUntil, failedLastHour}`. It never opens an
SMTP connection and never turns overall health `DOWN`.

Metrics (Micrometer, not exposed over HTTP yet): `talentmatch.feed.polls` (timer; tags `kind`,
`outcome` = the lower-cased `lastStatus`, or `deferred` / `lease_lost`), `talentmatch.feed.postings.new`
(counter; tag `kind`; a source's first poll is not counted), `talentmatch.feed.enrichments` (timer; tag
`outcome`), `talentmatch.feed.notifications` (counter; tags `channel`, `outcome` = `sent`, `transient`,
`permanent`, `config`, `expired`, `no_content`, `internal_error`; the test email is not counted) and
`talentmatch.feed.detection.latency` (timer, one sample per email sent).

---

## Notifications (Phase 5)

Email over SMTP is the only channel. Email is **configured** when `SMTP_HOST`, `NOTIFY_EMAIL_FROM` and
`NOTIFY_EMAIL_TO` are set (plus `SMTP_USERNAME` and `SMTP_PASSWORD` if the server needs a login);
notifications are **disabled** until the owner turns them on. An email holds only the job's facts
(title, company, workplace, location, score, matched/missing required skills, apply link), never CV
data. At most one notification per job, ever; at most `maxPerHour` emails an hour. Delivery is at
least once: a crash mid-send can send the same email again, with the same `Message-ID`.

### `GET /notifications/settings`
**Response `200`** (defaults when nothing was saved: disabled, EMAIL, 0.6, 20)
```json
{ "enabled": true, "channel": "EMAIL", "minScore": 0.6, "maxPerHour": 20,
  "email": { "configured": true, "host": "smtp.gmail.com", "port": 587, "security": "STARTTLS",
             "from": "o•••@gmail.com", "to": "o•••@example.com", "authenticated": true } }
```
Not configured: `"email": { "configured": false, "missing": ["SMTP_HOST", "NOTIFY_EMAIL_TO"] }` (the
blank variables). The username and password are never returned; `from` and `to` are masked
(`from` is masked too, which goes beyond the Phase 5 spec, because it is usually the owner's own
address).

### `PUT /notifications/settings`
**Request** (full replacement)
```json
{ "enabled": true, "minScore": 0.6, "maxPerHour": 20, "channel": "EMAIL" }
```
**Response `200`** as `GET`. `channel` is optional (only `"EMAIL"` exists). A new `minScore` applies to
new evaluations only; turning notifications off stops sending, and queued ones expire after 24 hours.
**Errors:** `400 VALIDATION_FAILED` with field errors: `enabled` `"enabled is required."`;
`minScore` `"minScore must be a number between 0 and 1."`; `maxPerHour`
`"maxPerHour must be a whole number between 1 and 200."`; `enabled: true` while email isn't configured:
`enabled` `"Email isn't configured: set SMTP_HOST, NOTIFY_EMAIL_FROM and NOTIFY_EMAIL_TO (plus
SMTP_USERNAME and SMTP_PASSWORD if your server needs a login), then restart."`.
`400 MALFORMED_REQUEST` for an unknown field or another `channel` value (`"SMS"`).

### `POST /notifications/test`
No body. Sends the fixed email `[TalentMatch] Test notification` (the settings summary, no job or CV
data) synchronously, bounded by the SMTP timeouts (10 s each). Works while notifications are disabled;
creates no notification and doesn't count toward the hourly cap. A success also ends a pause after a
configuration error.
**Response `200`**
```json
{ "channel": "EMAIL", "delivered": true, "to": "o•••@example.com",
  "messageId": "<test-3f2c0e9a-1b2c-4d5e-8f90-a1b2c3d4e5f6@example.com>" }
```
**Errors:** `409 NOTIFICATION_NOT_CONFIGURED` (same text as the `PUT` message above);
`429 NOTIFICATION_TEST_RATE_LIMITED` with `Retry-After: n` (one attempt per 30 s, failed ones
included); `502 NOTIFICATION_DELIVERY_FAILED`, e.g. `"The mail server rejected the login (535). Check
SMTP_USERNAME and SMTP_PASSWORD."`, `"Couldn't connect to smtp.example.com:587 within 10 seconds."`,
`"The mail server refused the recipient address (550). Check NOTIFY_EMAIL_TO."`, `"The mail server
doesn't offer STARTTLS on port 587. Use port 465 with SMTP_SECURITY=SSL."`.

### `GET /notifications`
**Query:** `page`, `size` (see Paging). **Response `200`** `PageResponse` of
```json
{ "id": "…", "jobId": "…", "title": "Backend Engineer", "company": "Acme", "channel": "EMAIL",
  "status": "SENT", "score": 0.8, "attempts": 1, "lastError": null,
  "createdAt": "…", "sentAt": "…" }
```
newest `createdAt` first. `status`: `PENDING`, `SENDING`, `SENT` or `FAILED`. `lastError` is sanitized
text only: `"SMTP 421 (transient)"`, `"connect timeout"`, `"SMTP 550 (rejected)"`, `"expired"`.
**Errors:** `400 INVALID_PARAMETER`.

---

## Matches

### `GET /jobs/{id}/matches`
Ranked candidates for a job. Scores are cached in `job_match`; missing or stale rows
(older than the latest change of the candidate or the job, including skill changes) are
recomputed first.

| Param | Default | Allowed | Notes |
|---|---|---|---|
| `limit` | `10` | `1..100` | page size |
| `page` **(extension)** | `0` | `>= 0` | |
| `minScore` **(extension)** | `0` | `0..1` | only matches with `score >= minScore` |
| `regenerate` | `false` | `true` / `false` only | `true` rescores every candidate and, with AI enabled, regenerates the AI explanations of the page's top-N rows. Rate-limited **(extension, Phase 3)** |

**Response `200` (matchable job)**
```json
{
  "jobId": "uuid", "jobTitle": "Backend Engineer", "company": "Acme",
  "matchable": true, "reason": null, "message": null,
  "page": 0, "limit": 10, "totalPages": 20, "totalElements": 200,
  "recomputedCandidates": 0,
  "explanationsGenerated": 1,
  "matches": [
    {
      "rank": 1,
      "candidateId": "uuid",
      "candidateName": "Ada Lovelace",
      "score": 0.8333,
      "scorePercent": 83,
      "summary": "Matches 2 of 2 required skills; 1 of 2 nice-to-have.",
      "breakdown": {
        "earnedPoints": 25, "maxPoints": 30,
        "matchedRequired":   [ { "skillId": "uuid", "name": "Java", "yearsExperience": 5 } ],
        "matchedNiceToHave": [ { "skillId": "uuid", "name": "Docker", "yearsExperience": null } ],
        "missingRequired":   [],
        "missingNiceToHave": [ { "skillId": "uuid", "name": "Kubernetes", "yearsExperience": null } ]
      },
      "aiExplanation": "Ada Lovelace covers both required skills, Java with 5 years of experience and SQL. She also brings Docker, but Kubernetes is not listed.",
      "explanationStatus": "READY",
      "explanation": {
        "source": "AI",
        "headline": "Strong fit with every required skill",
        "text": "Ada Lovelace covers both required skills, Java with 5 years of experience and SQL. She also brings Docker, but Kubernetes is not listed.",
        "strengths": ["Java with 5 years", "SQL"],
        "gaps": ["Kubernetes (nice-to-have)"],
        "model": "ollama/qwen2.5:7b-instruct",
        "generatedAt": "2026-10-02T09:00:00Z",
        "reason": null,
        "note": null
      },
      "computedAt": "2026-10-01T10:00:00Z"
    }
  ]
}
```

Field notes (all **(extension)** except `jobId`, `matches[].candidateId`, `candidateName`,
`score`, `aiExplanation`, `computedAt`):

- `rank` = `page * limit + index + 1`; order is `score` desc, then `candidateName`, then
  `candidateId` (stable across pages and ties).
- `score` is `earned points / max points` (required skill = 10, nice-to-have = 5, configurable),
  rounded to 4 decimals; `scorePercent` is the rounded percentage. Candidates scoring 0 are
  listed too (use `minScore` to hide them). Years of experience never change the score.
- `summary` and `breakdown` are deterministic (sorted by skill name); missing skills always have
  `yearsExperience: null`.
- `recomputedCandidates` = scores refreshed by this request (0 when everything was fresh).
- `computedAt` = when the score was last verified fresh.
- `explanationsGenerated` **(extension, Phase 3)** = AI explanations generated by this request
  (0 when all were cached, not eligible, or unfinished).

#### Explanations **(extension, Phase 3)**

- `explanationStatus` is one of `READY | PENDING | STALE | UNAVAILABLE` (precedence in that order):
  - `READY`: `aiExplanation` holds a current AI explanation.
  - `PENDING`: an AI explanation is being generated and did not finish within the request
    budget; it keeps running and is stored when done. **Reload in a few seconds.**
  - `STALE`: an older AI explanation exists but the candidate or job changed since; it was
    not refreshed this time (e.g. rank above top N, or the AI was unavailable).
  - `UNAVAILABLE`: no AI explanation (AI disabled, rank above top N, or generation failed).
- Invariant: `aiExplanation != null` ⇔ `explanationStatus == "READY"` ⇔ `explanation.source == "AI"`.
  `aiExplanation` only ever contains current AI text, never template text and never outdated text.
- `explanation` is **always present** on every match item:

| Field | Meaning |
|---|---|
| `source` | `AI` or `TEMPLATE` (a deterministic summary built from the breakdown) |
| `headline` | short summary of the fit |
| `text` | explanation text (equals `aiExplanation` when `source` is `AI`) |
| `strengths` / `gaps` | short phrases naming matched / missing skills (AI: max 3 each, grounded in the breakdown; template: max 5 each) |
| `model` / `generatedAt` | provider/model label and generation time for AI text, else `null` |
| `reason` / `note` | why a template is shown and a user-facing note, `null` for AI text |

| `reason` | `note` |
|---|---|
| `AI_DISABLED` | "AI explanations are turned off, so this summary was built from the skill breakdown." |
| `NOT_IN_TOP_N` | "AI explanations are generated for the top {N} matches only, so this summary was built from the skill breakdown." |
| `GENERATING` | "An AI explanation is being written. Reload in a few seconds; until then this summary was built from the skill breakdown." |
| `AI_BUSY` | "The AI service is busy right now, so this summary was built from the skill breakdown. Reload later for an AI explanation." |
| `PROVIDER_UNAVAILABLE` | "The AI explanation service is unavailable right now, so this summary was built from the skill breakdown." |
| `GENERATION_FAILED` | "An AI explanation couldn't be produced for this match, so this summary was built from the skill breakdown." |

`AI_BUSY` is also returned while the local model (Ollama) is reading a CV (Phase 4): the
extraction holds the model for many minutes, so explanations don't queue behind it. It does
not count as a provider failure.

  For `STALE` items the note is prefixed with "The previous AI explanation is out of date because
  the candidate or job changed. ".

Template example (Ollama not running):
```json
"aiExplanation": null, "explanationStatus": "UNAVAILABLE",
"explanation": { "source": "TEMPLATE", "headline": "Strong match: 2 of 2 required skills",
  "text": "Ada Lovelace has all 2 required skills: Java (5 years), SQL. Nice-to-have skills: has Docker; missing Kubernetes.",
  "strengths": ["Java (5 years)", "SQL", "Docker"], "gaps": ["Kubernetes (nice-to-have)"],
  "model": null, "generatedAt": null, "reason": "PROVIDER_UNAVAILABLE",
  "note": "The AI explanation service is unavailable right now, so this summary was built from the skill breakdown." }
```

- AI explanations are generated only for ranks `1..top-n` (default 5) of the whole ranking, so
  `page=1` rows never trigger model calls. A GET waits at most `request-budget` (default 8s)
  for them. Stored explanations are reused until anything the model sees changes (candidate
  name/summary/skills, job title/company/description/skills, score).
- `regenerate=true` rescores every candidate and forces new AI explanations for the page's
  top-N rows, even when READY; a failed regeneration keeps the previous valid explanation.
  With AI enabled it is **rate-limited to once per job per `regenerate-window` (default 60s)**:
  a repeat gets `429 REGENERATE_RATE_LIMITED` with `Retry-After: n` and the message
  "Matches for this job were regenerated recently. You can regenerate again in n seconds;
  reload without regenerate=true to see the current results." The check runs after parameter
  validation and before the job lookup; a request that then fails (e.g. 404) does not use up
  the slot. With AI disabled, `regenerate` behaves exactly as in Phase 2.
- LLM failures, timeouts, refusals, invalid output, a busy AI executor, and DB errors while
  *storing* an explanation never change the HTTP status and never leak details.

**Response `200` (job with no skills)**: `matchable: false`,
`reason: "JOB_HAS_NO_SKILLS"`, `message: "This job has no skills listed yet, so candidates can't
be ranked. Add skills to see matches."`, `matches: []`, `totalElements: 0`, `totalPages: 0`,
`explanationsGenerated: 0`. Nothing is scored or written.

**Response `200` (no candidates at all)**: `matchable: true`, `reason: "NO_CANDIDATES"`,
`message: "There are no candidates yet. Add candidates to see matches."`, `explanationsGenerated: 0`.

**Errors:** `400 INVALID_ID`, `400 INVALID_PARAMETER` (e.g. `"limit must be between 1 and 100."`,
`"Parameter 'regenerate' must be true or false."`), `404 JOB_NOT_FOUND`,
`429 REGENERATE_RATE_LIMITED` (+ `Retry-After`) **(extension, Phase 3)**,
`503 MATCHES_BUSY` (+ `Retry-After: 2`), `503 DATABASE_UNAVAILABLE` (+ `Retry-After: 5`).

### `POST /matches/recompute`
Batch recompute for all jobs (admin/cron use case, not the dashboard).

**Query params (extension):** `onlyStale` (default `false`: rescore everything; `true`: only
missing or stale rows).

**Response `202`** with `Location: /api/matches/recompute/{runId}` and the run resource (below).

**Errors:** `409 RECOMPUTE_ALREADY_RUNNING` with `Location` of the active run
(`"A recompute is already running (started …). Track it at /api/matches/recompute/{id}."`).
Only one run at a time.

### `GET /matches/recompute/{runId}` **(extension)**
**Response `200`**
```json
{
  "runId": "uuid", "state": "RUNNING", "onlyStale": false,
  "totalJobs": 50, "processed": 20, "skipped": 2, "failed": 0, "percentComplete": 44,
  "matchesWritten": 4000, "startedAt": "2026-10-01T10:00:00Z", "finishedAt": null,
  "message": "Recomputing matches: 22 of 50 jobs done.",
  "skippedJobIds": ["uuid"], "failures": []
}
```
- `state`: `QUEUED | RUNNING | SUCCEEDED | COMPLETED_WITH_ERRORS | FAILED`.
- `processed + skipped + failed` = jobs done. Jobs without skills (or deleted mid-run) are
  `skipped`; `skippedJobIds` is capped at 50, `failures` (`{ "jobId", "message" }`) at 50.

**Errors:** `400 INVALID_ID`, `404 RECOMPUTE_RUN_NOT_FOUND` (`"No recompute run with id …. Run
status is kept in memory (last 20 runs) and is lost when the server restarts."`)

### Jobs for a candidate (Phase 6)

#### `GET /candidates/{id}/matches` and `GET /profile/matches`

Ranked, explained jobs for one candidate: the reverse of `GET /jobs/{id}/matches`, using the same
`job_match` score cache and staleness rule. `GET /profile/matches` is the owner's shortcut: the
same list as `/candidates/{ownerCandidateId}/matches`, with the same parameters
(`404 PROFILE_NOT_FOUND` before a profile is saved).

Missing or stale scores for the in-scope jobs (older than the latest change of the candidate or
the job) are computed first, in chunks of `talentmatch.matches.rescore-chunk` (200) jobs, each
under the candidate's match lock and the jobs' match locks (see DATA_MODEL, "Advisory locks").
Feed jobs the feed processor already scored for the owner are reused, not rescored.

| Param | Default | Allowed | Notes |
|---|---|---|---|
| `limit` | `10` | `1..100` (`talentmatch.matches.default-limit` / `max-limit`) | page size |
| `page` | `0` | `>= 0`, and `page * limit` must fit in an int | zero-based |
| `minScore` | `0` | `0..1` | only jobs with `score >= minScore`; 0-score jobs are listed at the default |
| `sort` | `score` | `score`, `newest` (case-insensitive) | `score`: score desc, then `listedAt` desc (undated last), then job id. `newest`: `listedAt` desc (undated last), then score desc, then job id. Stable across pages and ties |
| `postedSince` | none | an ISO-8601 instant (`2026-10-16T00:00:00Z`, an offset is accepted) **or** a positive ISO-8601 duration of at most `P366D` (`P2D`, `PT6H`), meaning now minus the duration | compared with `listedAt`; undated jobs (`listedAtSource: UNKNOWN`) are excluded |
| `country` | none | ISO 3166 alpha-2 codes, comma-separated and/or repeated (`country=za,GB&country=NA`), at most 20; upper-cased, duplicates dropped | a non-remote feed job must list one of the countries. A feed job with **no** country data passes (flag `LOCATION_UNKNOWN`); MANUAL jobs pass; with `remote=include` (or `only`) a REMOTE job passes whatever its countries |
| `remote` | `include` | `include`, `only`, `exclude` (case-insensitive) | `only`: REMOTE feed jobs only (MANUAL jobs drop out). `exclude`: no REMOTE jobs |
| `location` | none | 2..100 characters after NFKC, trim and whitespace collapse | case-insensitive substring of the feed job's location text (`%` and `_` are literal); MANUAL jobs and jobs without location text drop out |
| `origin` | `all` | `all`, `feed`, `manual` (case-insensitive) | `feed` hides MANUAL (incl. ETL) jobs |
| `includeClosed` | `false` | `true` / `false` only | closed feed jobs are hidden by default, for every candidate |
| `includeFiltered` | `false` | `true` / `false` only | **owner only**: jobs the owner's preferences FILTERED are hidden (and not scored or counted) unless `true`. Ignored for other candidates, who see them anyway |
| `regenerate` | `false` | `true` / `false` only | rescores **every** in-scope job and, with AI enabled, forces new seeker explanations for the page's top-N rows. Rate-limited per candidate (below) |

A blank text parameter means "use the default". Only jobs with at least one skill are ranked
(`unmatchableJobs` counts the in-scope jobs without skills).

**`listedAt`** (when the job was listed) and `listedAtSource`:

| `listedAtSource` | Job | `listedAt` |
|---|---|---|
| `POSTED` | feed job with a provider date | the earlier of `postedAt` and `firstSeenAt` (guards against provider clock skew) |
| `FIRST_SEEN` | feed job without a provider date, found after its source was added | `firstSeenAt` |
| `CREATED` | MANUAL job | the job's `createdAt` |
| `UNKNOWN` | baseline feed job (already open when its source was added) without a provider date | `null`: excluded by `postedSince`, last in `sort=newest`, otherwise listed |

**Response `200`**
```json
{
  "candidateId": "6c1e…", "owner": true, "profileVersion": 4,
  "sort": "NEWEST", "page": 0, "limit": 10, "totalPages": 2, "totalElements": 14,
  "recomputedJobs": 3, "explanationsGenerated": 1, "unmatchableJobs": 2,
  "reason": null, "message": null,
  "matches": [
    {
      "rank": 1,
      "jobId": "a71f…", "title": "Backend Engineer", "company": "Acme", "origin": "FEED",
      "listedAt": "2026-10-18T07:12:00Z", "listedAtSource": "POSTED",
      "postedAt": "2026-10-18T07:12:00Z", "firstSeenAt": "2026-10-18T07:16:41Z", "closedAt": null,
      "baseline": false, "primaryUrl": "https://jobs.lever.co/acme/…",
      "workplace": "HYBRID", "locationText": "Cape Town", "countryCodes": ["ZA"], "seniority": "SENIOR",
      "salary": { "min": 600000, "max": 800000, "currency": "ZAR", "period": "YEAR", "estimated": false },
      "enrichmentStatus": "SUCCEEDED",
      "preferenceVerdict": "PASS", "filterReasons": [], "flags": [],
      "score": 0.8, "scorePercent": 80,
      "summary": "Matches 2 of 2 required skills; 0 of 1 nice-to-have.",
      "breakdown": {
        "earnedPoints": 20, "maxPoints": 25,
        "matchedRequired":   [ { "skillId": "…", "name": "Java", "yearsExperience": 5 },
                               { "skillId": "…", "name": "SQL", "yearsExperience": null } ],
        "matchedNiceToHave": [],
        "missingRequired":   [],
        "missingNiceToHave": [ { "skillId": "…", "name": "Kubernetes", "yearsExperience": null } ]
      },
      "aiExplanation": "You have both required skills, Java with 5 years of experience and SQL. The one thing missing is Kubernetes, which the role lists as nice-to-have.",
      "explanationStatus": "READY",
      "explanation": {
        "source": "AI", "headline": "You cover every required skill",
        "text": "You have both required skills, Java with 5 years of experience and SQL. The one thing missing is Kubernetes, which the role lists as nice-to-have.",
        "strengths": ["Java with 5 years", "SQL"], "gaps": ["Kubernetes (nice-to-have)"],
        "model": "ollama/qwen2.5:7b-instruct", "generatedAt": "2026-10-18T07:20:03Z",
        "reason": null, "note": null
      },
      "computedAt": "2026-10-18T07:16:45Z"
    }
  ]
}
```

Field notes:
- `owner`: the candidate is the owner's (the profile's) candidate. `profileVersion` is the owner's
  current profile version, `null` for other candidates. `sort` is `SCORE` or `NEWEST`.
- `rank` = `page * limit + index + 1`.
- **Feed-only fields** (`primaryUrl`, `firstSeenAt`, `postedAt`, `closedAt`, `baseline`,
  `workplace`, `locationText`, `seniority`, `salary`, `enrichmentStatus`) are `null` for MANUAL
  jobs, and `countryCodes` is `[]`. `salary` is `null` when the posting states none.
- **Preference fields:** `preferenceVerdict` (`PASS`, `FILTERED` or `null`), `filterReasons` and
  the stored preference `flags` (as on `/feed/jobs`) are **owner only**: `null` / `[]` for any
  other candidate.
- **`LOCATION_UNKNOWN` (computed):** for **any** caller who passes `country`, `flags` also gets
  `LOCATION_UNKNOWN` on a feed job that has no country data and isn't REMOTE, so you can see it
  passed the country filter unchecked. REMOTE and MANUAL jobs never get it. Without `country`, a
  non-owner's `flags` is always `[]`. No duplicates (the owner's stored flags may already have it).
- `score`, `scorePercent`, `summary`, `breakdown`, `computedAt`: as on `/jobs/{id}/matches`
  (score rounded to 4 decimals; years never change the score; `yearsExperience: null` means
  unknown).
- `recomputedJobs` = scores written by this request (0 when everything was fresh);
  `explanationsGenerated` = AI explanations generated by this request; `unmatchableJobs` =
  in-scope jobs without skills (not ranked).
- `aiExplanation`, `explanationStatus`, `explanation`: the Phase 3 contract and invariant
  (`aiExplanation != null` ⇔ `READY` ⇔ `source == "AI"`), written to the job seeker (below).

**Reasons** (`200`, with `message`):
- `NO_MATCHABLE_JOBS` (no ranked job and no in-scope job with skills): "No job with listed skills
  matches these filters. Jobs without skills can't be ranked; widen the filters or wait for the
  feed to find skills."
- `CANDIDATE_HAS_NO_SKILLS` (otherwise, when the candidate has no skills): "This candidate has no
  skills yet, so every job scores 0%. Add skills (or confirm your profile with PUT /api/profile)
  to see real matches." Jobs are still listed, all at 0, when `minScore` is 0.

**Validation messages** (`400 INVALID_PARAMETER`, `field` = the parameter; the first invalid
parameter is reported):

| Param | Message |
|---|---|
| `limit` | "limit must be between 1 and 100." |
| `page` | "page must be 0 or greater." / "page is too large for this page size." |
| `minScore` | "minScore must be between 0 and 1." |
| `sort` | "sort must be score or newest." |
| `postedSince` | "postedSince must be an ISO-8601 instant like 2026-10-16T00:00:00Z or a duration like P2D (at most P366D)." (also for `P0D` and negative durations) |
| `country` | "'XX' is not an ISO country code." / "Use at most 20 country codes." |
| `remote` | "remote must be include, only or exclude." |
| `location` | "location must be 2 to 100 characters." |
| `origin` | "origin must be all, feed or manual." |
| `includeClosed`, `includeFiltered`, `regenerate` | "Parameter 'includeClosed' must be true or false." (strict binding) |

**Rate limit:** with AI enabled, `regenerate=true` is allowed **once per candidate per
`regenerate-window`** (default 60 s). `/profile/matches` and `/candidates/{ownerId}/matches`
share the owner's slot; the candidate slot never collides with a job's slot on
`/jobs/{id}/matches`, even for the same UUID. A repeat gets `429 REGENERATE_RATE_LIMITED` with
`Retry-After: n` and the message "Matches for this candidate were regenerated recently. You can
regenerate again in n seconds; reload without regenerate=true to see the current results." The
check runs after parameter validation (and, on `/profile/matches`, after the profile lookup) and
before any other database work; a request that then fails (404, 503, …) releases the slot. With
AI disabled, `regenerate` only rescores and is never rate-limited.

**Errors:** `400 INVALID_ID`, `400 INVALID_PARAMETER`, `404 CANDIDATE_NOT_FOUND` ("No candidate
with id ….") on `/candidates/{id}/matches`, `404 PROFILE_NOT_FOUND` ("You have not saved a profile
yet. …") on `/profile/matches`, `429 REGENERATE_RATE_LIMITED` (+ `Retry-After: n`),
`503 MATCHES_BUSY` (+ `Retry-After: 2`) when a match lock of the candidate or of one of its jobs is
held for more than 10 s (or on a deadlock): "Matches for this candidate are being recalculated by
another request. Please try again in a few seconds." (score chunks already committed stay; a retry
has less to do), `503 DATABASE_UNAVAILABLE` (+ `Retry-After: 5`).

#### Seeker explanations (Phase 6)

- **Voice:** written to the job seeker in the second person ("you have Java…"), the same shape as
  the recruiter explanation (`headline`, `text`, `strengths`, `gaps`), so a client can reuse one
  component. The template (`source: TEMPLATE`) is in the second person too, e.g. headline
  "Strong match: you have 2 of 2 required skills", text "You have all 2 required skills: Java
  (5 years), SQL. Nice-to-have skills: you're missing Kubernetes."
- **What the model sees:** the job's title, company and description, the score and skill
  breakdown, and the candidate's summary (for the owner: profile headline + summary). The
  candidate's **name and email are never sent**, nor preferences, salary, the CV or experience
  entries.
- **Storage and freshness:** stored separately from the recruiter explanation (both can be
  `READY` for the same pair). A stored seeker explanation is reused until anything the model sees
  changes (the summary, skills or years, the job's title, company, description or skills, the
  score); a profile save that changes none of these keeps it `READY`.
- **Top N, budget, PENDING, notes:** as in Phase 3, over this list's ranks. For `STALE` items the
  note is prefixed with "The previous AI explanation is out of date because your profile or the
  job changed. ".
- **Grounding (stricter than Phase 3):** besides the Phase 3 checks on `strengths` and `gaps`, the
  whole output is rejected (template with `reason: GENERATION_FAILED`, nothing stored) if any
  field names a known skill that isn't one of this job's skills, if the headline or text contains
  a number that isn't in the match facts (score percent, points, a matched skill's years, the
  skill counts, a number in the job title or company), or if a sentence says "you have/bring/know"
  a missing skill without a negation. Skills outside the skill vocabulary and spelled-out numbers
  can't be checked.

---

## Profile (Phase 4)

The owner's **master profile**: upload a CV (PDF), let the AI turn it into a draft in the
background, review and edit the draft, then save it. Saving creates or updates the owner's
candidate row and skills, so `GET /jobs/{id}/matches` scores the owner like any candidate
(`candidateId` in the profile response). All of these responses contain personal data.

### Profile document

The same shape is used for the AI draft, the `PUT` body and the saved profile. Every field may
be `null` and every list may be empty; dates are `YYYY-MM` or `YYYY`.

```json
{
  "fullName": "Ada Lovelace", "email": "ada@example.com", "phone": "+44 20 7946 0000",
  "location": "London, UK", "headline": "Backend engineer", "summary": "…",
  "links": [ { "label": "GitHub", "url": "https://github.com/ada" } ],
  "experience": [ { "title": "Senior Engineer", "company": "Acme", "location": "London",
                    "startDate": "2021-03", "endDate": null, "current": true,
                    "technologies": ["Java", "PostgreSQL"], "highlights": ["Cut p95 latency by 40%"] } ],
  "projects": [ { "name": "TalentMatch", "description": "…", "technologies": ["Spring Boot"],
                  "url": null, "highlights": [] } ],
  "skills": [ { "name": "Java", "years": 8 }, { "name": "Docker", "years": null } ],
  "certifications": [ { "name": "AWS SAA", "issuer": "Amazon", "issued": "2023-05", "expires": null,
                        "credentialId": null, "url": null } ],
  "education": [ { "institution": "University of London", "qualification": "BSc",
                   "field": "Mathematics", "startDate": "2010", "endDate": "2013" } ],
  "languages": [ { "name": "English", "level": "Native" } ]
}
```

Limits (validated on `PUT`; the AI draft is shortened instead, with a warning): name 200,
single-line fields 300, URLs 500, summary 5000, descriptions 2000, bullets 1000 characters;
at most 50 roles, 50 projects, 100 skills, 50 certifications, 20 education entries, 20
languages, 20 links, 30 bullets/technologies per entry; years 0..60. `current: true` means no
`endDate`. Accepted date inputs: `2021`, `2021-03`, `03/2021`, `Mar 2021`, `March 2021`;
`endDate: "Present"` sets `current`.

### `POST /profile/resume`
Upload a CV as `multipart/form-data`, file in form field `file`. PDF only (detected from the
file's content, not its name), at most 5 MB and 20 pages, with a text layer (scanned images
are rejected; no OCR).

- **`202 Accepted`** + `Location: /api/profile/resume/{id}`: a new CV, queued for reading.
- **`200 OK`**: the same file (same SHA-256) was already uploaded and is not `FAILED`; the
  existing CV is returned and not read again.

Body: a resume (see `GET /profile/resume/{id}`), usually `status: "PENDING"`.

**Errors:** `400 VALIDATION_FAILED` (no `file` part / empty file), `400 MALFORMED_REQUEST`
(unreadable multipart body), `400 RESUME_UNREADABLE` (damaged, password- or copy-protected,
scanned, more than 20 pages, or took more than 30 s to read; the message says which),
`413 PAYLOAD_TOO_LARGE` (`"This file is 5.5 MB; a CV can be at most 5.0 MB. …"`),
`415 UNSUPPORTED_MEDIA_TYPE` (the file is not a PDF: `"Only PDF CVs are supported, and this
file is not a PDF. …"`; or the request is not multipart: `"Content type 'application/json' is
not supported. Upload the file as multipart/form-data, in a form field named 'file'."`),
`503 UPLOAD_BUSY` (other files are being read; retry in a minute).

### `GET /profile/resumes`
Every uploaded CV, newest first; metadata and status only (no draft, no text).

```json
[ { "id": "…", "fileName": "cv.pdf", "sizeBytes": 183422, "pageCount": 2, "status": "SUCCEEDED",
    "failureReason": null, "model": "ollama/qwen2.5:7b-instruct", "warningCount": 3,
    "uploadedAt": "…", "extractionStartedAt": "…", "extractionFinishedAt": "…" } ]
```

### `GET /profile/resume/{id}`
One CV and its extraction. Poll it after uploading: with a local CPU-only model, reading a CV
takes 20–40 minutes.

```json
{
  "id": "…", "fileName": "cv.pdf", "sizeBytes": 183422, "pageCount": 2,
  "status": "SUCCEEDED", "failureReason": null,
  "message": "Your draft profile is ready, with 2 item(s) to check (see warnings). Review it, then save it with PUT /api/profile.",
  "model": "ollama/qwen2.5:7b-instruct",
  "draft": { …profile document… },
  "warnings": [
    { "path": "skills[4].name", "value": "Kubernetes", "message": "This skill was not found in your CV text. Check it is correct (the AI may have reworded or invented it) before saving." },
    { "path": "skills[0].years", "value": "8", "message": "Your CV doesn't state 8 years of Java next to the skill, so the number was removed …" }
  ],
  "uploadedAt": "…", "extractionStartedAt": "…", "extractionFinishedAt": "…"
}
```

`status`: `PENDING` (queued), `RUNNING` (the model is reading it), `SUCCEEDED` (`draft` and
`warnings` set), `FAILED` (`failureReason` set, `message` says what to do). `draft` is `null`
and `warnings` is `null` unless `SUCCEEDED`. Warning `path`s point into `draft` (indices are
the draft's own); a `null` path is about the whole CV (e.g. it was too long and was cut).
Warnings never block saving.

`failureReason`: `AI_DISABLED` (AI is off: enter the profile by hand), `TIMEOUT`,
`PROVIDER_ERROR` (model server unreachable or failed), `REFUSED`, `INVALID_OUTPUT` (malformed
or cut-off answer), `QUEUE_FULL`, `CONTEXT_OVERFLOW` (CV too long for the model's context
window), `TOO_MANY_ATTEMPTS` (interrupted 3 times, e.g. by restarts),
`REMOTE_EXTRACTION_DISABLED` (the provider is hosted — `claude`/`openai` profile — and
`talentmatch.profile.allow-remote-extraction` is `false`, so the CV was not sent; the default
Ollama provider keeps it local).

Never invented: the AI may only copy what the CV says. Skill `years` are kept only when the
number is written within ~60 characters of the skill name in the CV (otherwise `null` plus a
warning; type them back in before saving if they are right); placeholders such as "N/A"
become `null`. Everything else is kept but flagged with a warning when it isn't in the CV
text: names (skills, employers, titles, projects, technologies, certifications, issuers,
institutions, qualifications), numbers in the summary, descriptions and highlights, the year
of each date, the email, the phone (compared by digits), links (compared without scheme and
`www.`), and highlights that share less than 70% of their words (4+ letters) with the CV
("reworded").

**Errors:** `400 INVALID_ID`, `404 RESUME_NOT_FOUND`

### `GET /profile/resume/{id}/file`
The original PDF (`application/pdf`, `Content-Disposition: attachment; filename*=UTF-8''…`,
`X-Content-Type-Options: nosniff`). **Errors:** `400 INVALID_ID`, `404 RESUME_NOT_FOUND`

### `POST /profile/resume/{id}/extract`
Read the CV again (after a failure, or to replace the draft; the old draft is discarded).
**Response `202`** the resume, `status: "PENDING"`.
**Errors:** `400 INVALID_ID`, `404 RESUME_NOT_FOUND`, `409 RESUME_EXTRACTION_IN_PROGRESS`
(it is `PENDING` or `RUNNING`)

### `DELETE /profile/resume/{id}`
Delete an uploaded CV (file, text and draft). A saved profile is kept; its `resumeId` becomes
`null`. **Response `204`**. **Errors:** `400 INVALID_ID`, `404 RESUME_NOT_FOUND`,
`409 RESUME_EXTRACTION_IN_PROGRESS` (the model is reading it; wait, then delete).

### `PUT /profile`
Save the reviewed profile as the master profile (creates it on the first call).

```json
{ "resumeId": "…", "createMissingSkills": true, "profile": { …profile document… } }
```

- `profile` (required): usually the `draft` from `GET /profile/resume/{id}` after your edits.
  `fullName` and `email` are required. Validation is strict: nothing is shortened or dropped
  for you; every problem is a field error on `profile.<path>` (e.g.
  `profile.experience[1].endDate`, indices as sent). An empty entry is an error ("fill it in or
  remove it").
- `createMissingSkills` (default `false`): skills not in the skill table are errors
  (`profile.skills[i].name`, "Unknown skill …") unless this is `true`; then they are created.
- `resumeId` (optional): the CV this came from.

The owner's candidate is created or updated in the same transaction: `fullName`, `email`,
`summary` = headline + summary, skills with years. Skill changes mark the owner's cached
matches stale, as for any candidate. **(Phase 6)** Only a visible change (name, email,
headline/summary, skills or years) bumps the candidate's `updatedAt`; an identical save rescores
nothing and keeps the owner's seeker explanations (`GET /profile/matches`) current. On later saves
the candidate update first takes the candidate's match lock (`candidate_match:{id}`, as
`PUT /candidates/{id}`); if it can't be taken within 10 s, nothing is saved and the answer is
`503 SERVICE_BUSY` (+ `Retry-After: 2`).

**Response `200`**:
```json
{ "candidateId": "…", "resumeId": "…", "version": 3, "profile": { … }, "skills": [ { "skillId": "…", "name": "Java", "category": null, "yearsExperience": 8 } ],
  "confirmedAt": "…", "updatedAt": "…",
  "warnings": [ { "path": "profile.skills[3].name", "value": "Postgres", "message": "New skill 'Postgres' was created, but 'PostgreSQL' already exists. …" } ] }
```
`version` is the confirmed-profile version: every successful `PUT` stores a new one (1, 2, 3, …,
kept as history) and the response returns it. `warnings` (save only; always `[]` on `GET`)
flags new skills that look like existing ones.

**Errors:** `400 VALIDATION_FAILED` (`profile` missing, field errors, unknown skills, unknown
`resumeId`), `400 MALFORMED_REQUEST`, `409 EMAIL_ALREADY_EXISTS` on the first save when another
candidate already uses this email (field `profile.email`: `"Candidate <id> already uses this
email. Delete it or change its email, then save your profile again."`; the existing candidate is
never adopted or merged), `503 SERVICE_BUSY` (+ `Retry-After: 2`, `"The server is busy with a
conflicting update. Please try again in a few seconds."`) **(Phase 6)**.

### `GET /profile`
The saved master profile (same body as the `PUT` response, `warnings: []`).
**Errors:** `404 PROFILE_NOT_FOUND` ("You have not saved a profile yet. …")

---

## Applications (Phase 7)

The **application tracker** plus **tailored documents**: for one job, the AI drafts a tailored CV
and a cover letter from the confirmed master profile in the background; every version is
grounding-checked, the owner edits and approves versions, downloads them as PDF or DOCX, and
tracks the application to an offer or a rejection. All of these responses contain personal data.

The typical sequence:
1. `POST /applications {"jobId": "…", "generate": true}` → `201`, `latestGeneration.status: "PENDING"`;
2. poll `GET /applications/{id}/generations/{genId}` until `SUCCEEDED` (20–30 minutes for both
   documents with Ollama on a CPU; minutes with a GPU or a hosted provider);
3. read `GET /applications/{id}/documents/{docId}`, edit if needed (`POST /applications/{id}/documents`),
   then `POST …/documents/{docId}/approve`;
4. download with `GET …/documents/{docId}/file?format=pdf` (or `docx`);
5. `PUT /applications/{id}` with `status: "APPLIED"`: the tracker records the date and the
   approved versions sent.

Applications are created only by the owner, at most one per job; nothing is tracked
automatically from matches or notifications, and `/profile/matches` is unchanged.

### Application

```json
{
  "id": "1b7e…", "jobId": "a71f…", "jobTitle": "Backend Engineer", "jobCompany": "Acme",
  "jobUrl": "https://jobs.lever.co/acme/…", "jobExists": true, "jobClosed": false, "jobOrigin": "FEED",
  "status": "APPLIED", "statusChangedAt": "2026-10-19T09:40:00Z", "appliedOn": "2026-10-19",
  "notes": "Referred by …", "allowedTransitions": ["INTERVIEW", "OFFER", "REJECTED", "WITHDRAWN"],
  "sentCv": { "id": "…", "version": 2 }, "sentCoverLetter": { "id": "…", "version": 1 },
  "documents": {
    "cv":          { "latest": { …DocumentSummary… }, "approved": { …DocumentSummary… }, "versions": 2 },
    "coverLetter": { "latest": { … }, "approved": { … }, "versions": 1 }
  },
  "latestGeneration": { …Generation… },
  "history": [ { "from": null, "to": "NEW", "forced": false, "at": "…" },
               { "from": "NEW", "to": "APPLIED", "forced": false, "at": "…" } ],
  "createdAt": "…", "updatedAt": "…"
}
```

Field notes:
- `jobTitle`, `jobCompany`, `jobUrl` are a **snapshot** taken at creation (`jobUrl` is the feed
  job's primary URL, `null` for manual jobs). The application outlives the job: when the job is
  deleted (feed jobs are deleted when orphaned), `jobId` becomes `null`, `jobExists: false`,
  `jobOrigin: null`, and versions, files and history stay. A **closed** feed job (`jobClosed:
  true`) can still be tailored.
- `allowedTransitions`: the statuses reachable without `force`, in table order (below).
- `sentCv` / `sentCoverLetter`: the versions recorded as sent (applied statuses only), or `null`.
- `documents.*.latest` is the newest version of that kind, `approved` the newest approved one
  (the "current" version), `versions` the count; `null`/`0` when there is none.
- `latestGeneration`: the newest generation in any status, or `null`. `history`: status changes,
  oldest first (`from: null` is the creation; `forced: true` was a `force` move).

List item (`GET /applications`): `id`, `jobId`, `jobTitle`, `jobCompany`, `jobUrl`, `jobExists`,
`status`, `appliedOn`, `latestCvVersion`, `latestCoverLetterVersion` (numbers or `null`),
`approvedCv`, `approvedCoverLetter` (booleans: at least one approved version), `generationStatus`
(the newest generation's status or `null`), `updatedAt`.

### Tracker statuses

`NEW`, `SHORTLISTED`, `APPLIED`, `INTERVIEW`, `OFFER`, `REJECTED`, `WITHDRAWN` (you pulled out
after applying) and `DISMISSED` (not interested). Moves are enforced:

| From | Allowed to |
|---|---|
| `NEW` | `SHORTLISTED`, `APPLIED`, `DISMISSED` |
| `SHORTLISTED` | `NEW`, `APPLIED`, `DISMISSED` |
| `APPLIED` | `INTERVIEW`, `OFFER`, `REJECTED`, `WITHDRAWN` |
| `INTERVIEW` | `OFFER`, `REJECTED`, `WITHDRAWN` |
| `OFFER` | `REJECTED`, `WITHDRAWN` |
| `DISMISSED` | `NEW`, `SHORTLISTED` |
| `REJECTED`, `WITHDRAWN` | none (terminal) |

`force: true` allows any move, to correct a mistake, and is recorded in `history`. The applied
statuses are `APPLIED`, `INTERVIEW`, `OFFER`, `REJECTED` and `WITHDRAWN`: they always have an
`appliedOn`; the others never have an `appliedOn` or sent versions.

### `POST /applications`
Track a job, optionally generating its documents at once.

```json
{ "jobId": "a71f…", "status": "NEW", "notes": "…", "generate": true,
  "kinds": ["CV", "COVER_LETTER"], "mentionNoticePeriod": false }
```
- `jobId` (required); `status`: `NEW` (default) or `SHORTLISTED`; `notes`: optional, at most
  10000 characters (trimmed; blank = none).
- `generate` (default `false`): also queue a generation (below) of `kinds` (default both) with
  `mentionNoticePeriod`. `kinds` is validated even without `generate`.

**Response `201`** the application + `Location: /api/applications/{id}`; with `generate`,
`latestGeneration` is `PENDING`.

**Errors:** `400 VALIDATION_FAILED`, `400 MALFORMED_REQUEST`, `404 PROFILE_NOT_FOUND` (only with
`generate`, checked before the job; nothing is created), `404 JOB_NOT_FOUND`,
`409 APPLICATION_ALREADY_EXISTS` ("This job is already tracked as application {id}. Open it with
GET /api/applications/{id}.").

### `GET /applications`
The tracked applications, paged (`page`, `size` as in the conventions).

| Param | Default | Allowed | Notes |
|---|---|---|---|
| `status` | all | status names, comma-separated and/or repeated (`status=NEW,APPLIED&status=OFFER`), case-insensitive; blank entries ignored | |
| `sort` | `updated` | `updated`, `created` (case-insensitive) | newest first (`updatedAt` or `createdAt` desc) |

**Errors:** `400 INVALID_PARAMETER` ("status must be a comma-separated list of NEW, SHORTLISTED,
APPLIED, INTERVIEW, OFFER, REJECTED, WITHDRAWN, DISMISSED.", "sort must be updated or created.",
the paging messages).

### `GET /applications/{id}`
One application. **Errors:** `400 INVALID_ID`, `404 APPLICATION_NOT_FOUND`

### `PUT /applications/{id}`
Change the status and the tracker fields, under the application row lock.

```json
{ "status": "APPLIED", "appliedOn": "2026-10-19", "notes": "…",
  "sentCvId": "…", "sentCoverLetterId": "…", "force": false }
```
- `status` (required). Staying in the same status is always allowed; any other move follows the
  table unless `force: true`.
- `notes` is replaced: `null` (or blank) clears it.
- **Entering an applied status** (from `NEW`, `SHORTLISTED` or `DISMISSED`): `appliedOn` defaults
  to today in `talentmatch.applications.zone` (default `Africa/Johannesburg`); `sentCvId` and
  `sentCoverLetterId` default to the newest **approved** version of each kind, or `null` if
  there is none (applying without TalentMatch documents is fine).
- **Staying in the applied statuses:** `appliedOn`, `sentCvId` or `sentCoverLetterId` left out
  (or `null`) keeps the stored value; a value replaces it.
- **Leaving the applied statuses** (only with `force`) clears `appliedOn` and both sent versions.
- An explicit sent id must be a version of **this** application, of the right kind, and
  approved.

**Response `200`** the application.

**Errors:** `400 VALIDATION_FAILED` (messages below), `400 INVALID_ID`, `404 APPLICATION_NOT_FOUND`,
`409 INVALID_STATUS_TRANSITION` ("An application can't move from NEW to INTERVIEW. Allowed next:
SHORTLISTED, APPLIED, DISMISSED. To correct a mistake, send force=true."), `503 SERVICE_BUSY`
(+ `Retry-After: 2`, after a 10 s wait for the row lock; nothing saved).

### `DELETE /applications/{id}`
Delete the application in any status, with its document versions, files, generations and
history. A generation running at that moment discards its result. **Response `204`**.
**Errors:** `400 INVALID_ID`, `404 APPLICATION_NOT_FOUND`, `503 SERVICE_BUSY`

### Tracker validation messages

`400 VALIDATION_FAILED`, `field` = the request field:

| Field | Message |
|---|---|
| `jobId` | "jobId is required." |
| `status` (create) | "A new application starts as NEW or SHORTLISTED." |
| `status` (update) | "status must be one of NEW, SHORTLISTED, APPLIED, INTERVIEW, OFFER, REJECTED, WITHDRAWN, DISMISSED." |
| `appliedOn` | "appliedOn must be a date like 2026-10-19, not in the future and not before 2000-01-01." / "appliedOn can only be set once the application is APPLIED or later." |
| `notes` | "Notes can be at most 10000 characters." |
| `sentCvId`, `sentCoverLetterId` | "No CV version {id} in this application." (also for a version of the other kind) / "CV version {n} is not approved yet. Approve it first (POST /api/applications/{id}/documents/{docId}/approve) or choose an approved version." / "sentCvId can only be set once the application is APPLIED or later." (cover letter wording for `sentCoverLetterId`) |
| `kinds` | "kinds must contain CV and/or COVER_LETTER, each at most once." (empty, duplicate or unknown values; case-insensitive) |

### Generations

#### `POST /applications/{id}/generations`
Queue an AI generation of the application's documents.

```json
{ "kinds": ["CV", "COVER_LETTER"], "mentionNoticePeriod": false }
```
- `kinds`: default both; `CV` and/or `COVER_LETTER`, case-insensitive, each at most once.
- `mentionNoticePeriod` (default `false`): append the fixed sentence "I am available to start
  after a notice period of {n} days." to the letter, from `noticePeriodDays` in your preferences
  (never written by the model). No preferences or no notice period → finding
  `NOTICE_PERIOD_UNKNOWN` and no sentence.
- The body may be omitted (both kinds).

**Response `202`** the generation (`PENDING`) + `Location: /api/applications/{id}/generations/{genId}`.

**Errors:** `400 VALIDATION_FAILED` (`kinds`), `404 APPLICATION_NOT_FOUND`,
`409 DATA_CONFLICT` ("The job for this application no longer exists, so documents can't be
tailored to it. Existing versions are kept."), `404 PROFILE_NOT_FOUND`,
`409 GENERATION_IN_PROGRESS` ("A generation for this application is already RUNNING (generation
{genId}). Wait for it with GET /api/applications/{id}/generations/{genId}."), `503 SERVICE_BUSY`.
With AI disabled the request still returns `202`, and the generation becomes `FAILED/AI_DISABLED`.

#### `GET /applications/{id}/generations/{genId}`

```json
{ "id": "…", "applicationId": "…", "status": "SUCCEEDED", "wantCv": true, "wantCoverLetter": true,
  "failureReason": null, "hint": null, "model": "ollama/qwen2.5:7b-instruct", "profileVersion": 4,
  "attempts": 1, "queuedAt": "…", "startedAt": "…", "finishedAt": "…",
  "results": [ { "kind": "CV", "documentId": "…", "failureReason": null, "hint": null },
               { "kind": "COVER_LETTER", "documentId": null, "failureReason": "INVALID_OUTPUT",
                 "hint": "The model's answer couldn't be used (…). Generate again; …" } ] }
```
**Errors:** `400 INVALID_ID`, `404 GENERATION_NOT_FOUND`

- `status`: `PENDING` (queued) → `RUNNING` (the model is writing) → `SUCCEEDED` (at least one
  document stored) | `FAILED` (`failureReason` and `hint` set). One generation at a time per
  application.
- The CV is written first and stored as soon as it is done, so it can be read while the letter
  is written. `results` has one entry per wanted kind once it is finished: the stored
  `documentId`, or a per-kind `failureReason` and `hint`. One kind failing doesn't discard the
  other: a stored CV with a failed letter is `SUCCEEDED`. When nothing is stored the generation is
  `FAILED` with the CV's failure (the letter's if only the letter was wanted).
- `profileVersion` is the owner profile version the run read when it started; the documents are
  built from it.
- A restart re-runs an unfinished generation and skips a kind it already stored; after 3
  interrupted attempts it is `FAILED/TOO_MANY_ATTEMPTS`.

| `failureReason` | Meaning (the `hint` says what to do) |
|---|---|
| `AI_DISABLED` | AI is off (`talentmatch.ai.enabled=false`) |
| `TIMEOUT` | a model call took longer than `talentmatch.applications.generation.call-timeout` (45 min); on a CPU both documents take 20–30 minutes, keep the computer awake |
| `PROVIDER_ERROR` | the model server couldn't be reached or failed (also: a database error while storing) |
| `REFUSED` | the model stopped without finishing |
| `INVALID_OUTPUT` | malformed or cut-off answer, or too much of it failed the grounding checks (below) |
| `QUEUE_FULL` | too many generations waiting (queue of 10) |
| `CONTEXT_OVERFLOW` | the profile and posting filled the model's context window; raise `talentmatch.ai.ollama.context-tokens` or lower `talentmatch.applications.generation.max-profile-chars` |
| `TOO_MANY_ATTEMPTS` | interrupted 3 times (restarts or crashes); start a new generation |
| `REMOTE_GENERATION_DISABLED` | the provider is hosted (`claude`/`openai` profile) and `talentmatch.applications.allow-remote-generation` is `false`, so nothing was sent; the default Ollama provider keeps the profile local |
| `JOB_DELETED` | the job was deleted before the run |

Only `TIMEOUT`, `PROVIDER_ERROR`, `REFUSED`, `INVALID_OUTPUT` and `CONTEXT_OVERFLOW` occur per
kind.

**What the model sees:** the job's title, company, required and nice-to-have skills and
description (at most `max-job-chars`, 4000), and your profile as labelled facts (at most
`max-profile-chars`, 12000): headline, summary, roles (title, employer, dates, technologies,
highlights), projects, skills with years, certificate names and issuers, education and
languages. **Never sent:** your name, email, phone, location, links, credential ids, certificate
URLs, preferences or salary; the header, greeting and sign-off are added by the server. When the
profile is longer than the budget, older highlights are left out (finding `PROFILE_TRUNCATED`).

### Document versions

Each kind (`CV`, `COVER_LETTER`) has immutable versions numbered 1, 2, 3, … per application:
`source: "AI"` from a generation, `source: "EDIT"` from an owner edit (`basedOnId` = the version
edited). A version is self-contained: it holds the facts copied from the profile version it was
built from (`profileVersion`) plus the rewritten text.

**DocumentSummary** (lists, and inside the application):
```json
{ "id": "…", "kind": "CV", "version": 2, "source": "EDIT", "basedOnId": "…", "profileVersion": 4,
  "profileCurrent": true, "model": null, "createdAt": "…", "approvedAt": null,
  "findingCounts": { "blocking": 0, "warning": 1, "info": 3 } }
```

**Document** (`GET …/documents/{docId}`): the summary plus `content`, `findings`,
`approvalAcknowledged` (`null` until approved), `jobChanged`, `files` and `downloads`:
```json
{ "id": "…", "kind": "CV", "version": 1, "source": "AI", "basedOnId": null, "profileVersion": 4,
  "profileCurrent": true, "model": "ollama/qwen2.5:7b-instruct", "createdAt": "…", "approvedAt": null,
  "findingCounts": { "blocking": 0, "warning": 0, "info": 1 },
  "content": {
    "header": { "fullName": "Ada Lovelace", "email": "ada@example.com", "phone": "…", "location": "London, UK",
                "links": [ { "label": "GitHub", "url": "https://github.com/ada" } ] },
    "headline": "Backend engineer for payment platforms", "summary": "…",
    "skills": ["Java", "PostgreSQL"],
    "experience": [ { "ref": "E1", "title": "Senior Engineer", "company": "Acme", "location": "London",
                      "startDate": "2021-03", "endDate": null, "current": true, "technologies": ["Java"],
                      "highlights": [ { "ref": "E1.H2", "text": "Cut p95 latency by 40%" } ] } ],
    "projects": [ { "ref": "P1", "name": "…", "description": "…", "technologies": [], "url": null, "highlights": [] } ],
    "certifications": [ { "ref": "C1", "name": "AWS SAA", "issuer": "Amazon", "issued": "2023-05", "expires": null } ],
    "education": [ { "ref": "D1", "institution": "…", "qualification": "BSc", "field": "Mathematics",
                     "startDate": "2010", "endDate": "2013" } ],
    "languages": [ { "name": "English", "level": "Native" } ] },
  "findings": [ { "severity": "INFO", "code": "REVERTED", "path": "experience[0].highlights[1]", "value": "35%",
                  "message": "…a number not in this point of your profile…" } ],
  "approvalAcknowledged": null, "jobChanged": false, "files": [],
  "downloads": { "pdf": "/api/applications/{id}/documents/{docId}/file?format=pdf",
                 "docx": "/api/applications/{id}/documents/{docId}/file?format=docx" } }
```
A cover letter's `content` is `{ header, company, jobTitle, date, salutation, paragraphs[], closing,
signature }`: `salutation` "Dear Hiring Manager,", `closing` "Kind regards,", `signature` your
full name, `date` today in the configured zone, all set by the server.

Field notes:
- **Refs** (`E1`, `E1.H2`, `P1`, `P1.H1`, `C1`, `D1`) point into the profile version: roles,
  role highlights, projects, project highlights, certifications, education, in profile order.
- `findings[]`: `{severity, code, path, value, message}`; `path` points into `content`, `value` is
  the offending token only (`"40%"`, `"Kubernetes"`), never a sentence. Codes below.
- `profileCurrent: false` when the profile was saved again after this version was built; the
  version then also carries the computed `BLOCKING` finding `PROFILE_CHANGED` (first in
  `findings`, counted in `findingCounts`).
- `jobChanged: true` when the job still exists and was updated after the version was built
  (informational only).
- `files`: `["PDF", "DOCX"]` once approved, else `[]`.

#### `GET /applications/{id}/documents`
The versions, newest first. `kind=CV|COVER_LETTER` (optional, case-insensitive) narrows it.
**Response `200`** `[DocumentSummary]`. **Errors:** `400 INVALID_PARAMETER` ("kind must be CV or
COVER_LETTER."), `404 APPLICATION_NOT_FOUND`

#### `GET /applications/{id}/documents/{docId}`
**Response `200`** Document. **Errors:** `400 INVALID_ID`, `404 APPLICATION_NOT_FOUND`,
`404 DOCUMENT_NOT_FOUND`

#### `POST /applications/{id}/documents`
Save your edit of a version as a new version of the same kind.

```json
{ "basedOn": "…docId…", "content": { …the base's content, edited… } }
```
Edit rules:
- `content` has the same shape as the base's `content` (unknown or wrong-typed fields inside it
  are `400 MALFORMED_REQUEST`, path `content.…`).
- **Facts are read-only:** the header, titles, companies, locations, dates, `current`,
  technologies, project names, descriptions and URLs, certification and education fields and
  languages must equal the profile version's values (compared after trimming and collapsing
  whitespace). To change a fact, change your profile (`PUT /profile`) and generate again.
- Every role and every education entry stays, roles in profile order; projects and
  certifications may be dropped or reordered. Every highlight keeps a ref of its own role or
  project; skills must be profile skills (stored in the profile's spelling, duplicates removed).
- Limits: highlights 1..400 characters, headline at most 120, summary at most 900. Letters: 1..8
  paragraphs of at most 1500 characters and at most 600 words; `company`, `jobTitle`, `date`,
  `salutation`, `closing` and `signature` stay as in the base.
- Changed free text gets the same fact checks as AI text, but findings on your own text are
  `WARNING`, never `BLOCKING`. The new version keeps the base's `profileVersion`.

**Response `201`** the new Document + `Location: /api/applications/{id}/documents/{docId}`.

**Errors:** `400 VALIDATION_FAILED`, `400 MALFORMED_REQUEST`, `404 APPLICATION_NOT_FOUND`,
`503 SERVICE_BUSY` (+ `Retry-After: 2`; the application row lock is taken before validating).
Validation messages (`field` as shown):

| Field | Message |
|---|---|
| `basedOn` | "basedOn is required." / "No version {id} in this application." |
| `content` | "content is required." |
| `content.experience[0].title` (any read-only fact) | "content.experience[0].title can't be changed here: it comes from your profile (version 4). Change it with PUT /api/profile, then generate again." |
| `content.company`, `content.jobTitle`, `content.date`, `content.salutation`, `content.closing` | "content.salutation can't be changed here: it is set by TalentMatch for this application." (`content.signature` uses the profile message) |
| `content.experience` | "content.experience: every role from your profile stays in the CV, in profile order (E1, E2, E3)." |
| `content.education` | "content.education: every education entry from your profile stays in the CV." |
| `content.projects[1].ref` | "content.projects[1].ref 'P9' is not in profile version 4." / "… is listed twice." / "… is required." |
| `content.experience[0].highlights[2].ref` | "content.experience[0].highlights[2].ref 'E2.H1' belongs to another role." |
| `content.skills[3]` | "content.skills[3]: 'Kubernetes' is not in your profile skills. Add it to your profile first." |
| `content.experience[0].highlights[1].text` | "content.experience[0].highlights[1].text must be 1 to 400 characters." |
| `content.headline`, `content.summary` | "content.summary must be at most 900 characters." (120 for the headline) |
| `content.paragraphs` | "content.paragraphs must have 1 to 8 paragraphs of at most 1500 characters, 600 words in total." |

#### `POST /applications/{id}/documents/{docId}/approve`
Approve a version for sending.

```json
{ "acknowledgeFindings": true }
```
- A version with a `BLOCKING` finding (including the computed `PROFILE_CHANGED`) needs
  `acknowledgeFindings: true`; otherwise `409 FINDINGS_NOT_ACKNOWLEDGED` ("This version has 2
  finding(s) that need your review (PROFILE_CHANGED, NUMBER_NOT_IN_PROFILE). Read them, then approve
  again with acknowledgeFindings=true."). The body may be omitted (`false`).
- The PDF and DOCX are rendered (no draft footer) and stored together with the approval, so an
  approved version always has both files and downloads return those exact bytes from then on.
- The approval is recorded once (`approvedAt`, `approvalAcknowledged`). Approving an approved
  version returns `200` and changes nothing (no acknowledgement needed). There is no un-approve:
  approve a newer version instead. Several versions may be approved; the newest is "current".

**Response `200`** Document. **Errors:** `400 INVALID_ID`, `404 APPLICATION_NOT_FOUND`,
`404 DOCUMENT_NOT_FOUND`, `409 FINDINGS_NOT_ACKNOWLEDGED`, `503 SERVICE_BUSY`

#### `GET /applications/{id}/documents/{docId}/file`
`format=pdf|docx` (default `pdf`, case-insensitive). An approved version returns the bytes stored
at approval. An unapproved version is rendered now as a **draft**: every page has the footer
"DRAFT – not approved in TalentMatch" and the file name ends in `-draft`.

`Content-Type` `application/pdf` or
`application/vnd.openxmlformats-officedocument.wordprocessingml.document`;
`Content-Disposition: attachment` with a UTF-8 `filename*` (`"CV - {fullName} - {company}[-draft].pdf"`
or `"Cover letter - …"`, unsafe characters removed, at most 150 characters);
`X-Content-Type-Options: nosniff`.

Layout: one fixed, single-column, ATS-friendly A4 layout per kind (Noto Sans, headings and
bullets; no tables, columns or images). PDFs embed the font; DOCX files name it, and Word
substitutes a similar font when it is missing.

**Errors:** `400 INVALID_PARAMETER` ("format must be pdf or docx."), `400 INVALID_ID`,
`404 APPLICATION_NOT_FOUND`, `404 DOCUMENT_NOT_FOUND`

### Grounding in plain words

Tailored documents may select, reorder and reword what is in the profile version, never add to
it.
- **The CV is built by reference.** The model returns only refs, skill names and reworded texts
  (headline, summary, highlights). Every fact (titles, employers, dates, project, certificate and
  education names, the contact header) is copied by the server from the profile version, so roles,
  projects, certificates and dates can't be invented. Every role and education entry is always
  included, in profile order; the model picks and orders 2–5 highlights per role (at most 6 are
  kept), up to 4 projects, certifications and up to 20 skills.
- **Fabricated structure is dropped** (`INFO`): an unknown or repeated ref, a highlight under the
  wrong role, a skill not in the profile. A role left without highlights gets its first 2
  original highlights (`ROLE_DEFAULTED`); no skill left → the profile's skills, the job's first
  (`SKILLS_DEFAULTED`).
- **A failed CV claim is reverted** (`REVERTED`, `INFO`) to the profile's wording: a highlight
  with a number not in **that** highlight or a skill not in that highlight or its role, a
  headline or summary with a number or skill not in the profile, or contact details. So an AI CV
  never contains an unverifiable fact; the repairs are listed.
- **A failed cover-letter sentence is kept and flagged** `BLOCKING` (it can't be reverted): a
  number, vocabulary skill or year (19xx/20xx) not in the profile (numbers in the job title and
  company are allowed), or an email, URL or phone number. Approving needs an acknowledgement.
- **Numbers** are compared exactly after canonicalising: `10,000` = `10000`; `40%` = `40 percent`
  = `40 per cent`; `5+` = `5`; "two" … "twenty" = digits. No rounding and no unit conversion
  (`$1.2M` is not `1,200,000`). A profile number counts only as a whole token: "12,000" allows
  12000, not 12 or 000. Years must be years of the profile's dates; the summary may also use the
  career span in whole years, and the letter also each role's duration in whole years. Digits
  inside an allowed skill name ("HTML5") don't count.
- **The whole answer is rejected** (`INVALID_OUTPUT`, nothing stored for that kind) when it is
  unparseable; for the CV, when more than half of the model's items (at least 4) had to be dropped
  or reverted, or it returned no role at all; for the letter, when it has no paragraphs, fewer
  than half the minimum or more than 1.5 × the maximum words, or more than half of its paragraphs
  have a `BLOCKING` finding.
- If the skill vocabulary can't be loaded, the skill checks fail closed: CV texts are reverted,
  letter paragraphs flagged.

| Code | Severity | When |
|---|---|---|
| `UNKNOWN_REF`, `DUPLICATE_REF` | INFO | an unknown, hidden or repeated ref was dropped |
| `SKILL_NOT_IN_PROFILE` | INFO (CV skills list) | a skill not in the profile was dropped |
| `ROLE_DEFAULTED`, `SKILLS_DEFAULTED` | INFO | the profile's originals were used |
| `REVERTED` | INFO | a CV text failed a check and was reverted (the message names the reason) |
| `PROFILE_TRUNCATED` | INFO | older highlights weren't shown to the model (long profile) |
| `NOTICE_PERIOD_UNKNOWN` | INFO | `mentionNoticePeriod` without a notice period in your preferences |
| `HEAVILY_REWORDED` | WARNING | under 50% of a rewritten highlight's content words come from the original, its role or the job |
| `UNKNOWN_NAME` | WARNING | a capitalised word in the letter that the profile and the job don't contain |
| `LENGTH` | WARNING | headline/summary cut, or the letter outside `min-words`..`max-words` (180..350) |
| `NUMBER_NOT_IN_PROFILE`, `SKILL_NOT_IN_PROFILE`, `YEAR_NOT_IN_PROFILE`, `CONTACT_DETAILS` | BLOCKING in AI letters, WARNING in edits | free text states something the profile doesn't |
| `PROFILE_CHANGED` | BLOCKING (computed when read) | the profile was saved again after this version |

Limits of the heuristics (PRODUCTION_READINESS section 10): skills outside the vocabulary,
spelled-out numbers above twenty, and a rewording that changes the meaning with the same words
can't be detected; `UNKNOWN_NAME` is a capitalisation heuristic.

---

## Health

`GET /actuator/health` (plus `/actuator/health/liveness` and `/actuator/health/readiness`),
outside `/api`. Includes database status.

**(extension, Phase 3)** Component `ai` (passive, never calls the model): `UP`, or `DEGRADED`
while the AI circuit breaker is open (e.g. Ollama not running). `DEGRADED` never fails the
overall status (status order `down, out-of-service, up, degraded, unknown`) and readiness does
not include it. Details: `enabled`, `provider`, `model`, `circuit`, `consecutiveFailures`,
`lastSuccessAt`, `lastFailureAt`, `lastFailure` (no keys or URLs). With AI disabled:
`UP {enabled: false, mode: "template-only"}`.

---

## Error Format

All errors share one shape (`code`, `timestamp`, `requestId`, `fieldErrors` are **(extension)**):

```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_FAILED",
  "message": "2 fields are invalid. Fix them and try again.",
  "path": "/api/jobs",
  "timestamp": "2026-10-01T10:00:00Z",
  "requestId": "9f1c...",
  "fieldErrors": [
    { "field": "skills", "message": "Add at least one skill so candidates can be ranked for this job." },
    { "field": "title", "message": "Title is required." }
  ]
}
```
`fieldErrors` is omitted when empty and sorted by `field`. Bodies never contain stack traces,
exception class names or SQL.

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | request body field(s) invalid (unknown/duplicate skill, length, email, years, ...) |
| 400 | `INVALID_PARAMETER` | query parameter out of range or wrong type |
| 400 | `INVALID_ID` | path id is not a UUID (`"'abc' is not a valid id. Ids look like 3f2c0e9a-…"`) |
| 400 | `MALFORMED_REQUEST` | body is not valid JSON, has an unknown field, or a field has the wrong type (**(Phase 7)** also inside a document edit's `content`, with the field path prefixed `content.`) |
| 400 | `RESUME_UNREADABLE` | uploaded PDF can't be read (damaged, protected, scanned, too many pages, too slow) **(Phase 4)** |
| 404 | `CANDIDATE_NOT_FOUND` / `JOB_NOT_FOUND` / `SKILL_NOT_FOUND` / `RECOMPUTE_RUN_NOT_FOUND` / `RESUME_NOT_FOUND` / `PROFILE_NOT_FOUND` / `PREFERENCES_NOT_FOUND` / `SKILL_ALIAS_NOT_FOUND` / `FEED_SOURCE_NOT_FOUND` | resource missing |
| 404 | `APPLICATION_NOT_FOUND` / `DOCUMENT_NOT_FOUND` / `GENERATION_NOT_FOUND` | application, document version or generation missing ("No application with id ….", "Application … has no document version ….", "Application … has no generation ….") **(Phase 7)** |
| 404 | `ENDPOINT_NOT_FOUND` | no such endpoint (`"No endpoint GET /api/foo."`) |
| 405 | `METHOD_NOT_ALLOWED` | wrong HTTP method (`Allow` header lists the supported ones) |
| 413 | `PAYLOAD_TOO_LARGE` | uploaded CV over the size limit **(Phase 4)** |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | body is not `application/json` (upload endpoints: not `multipart/form-data`, or the file is not a PDF); the message says what to send |
| 409 | `EMAIL_ALREADY_EXISTS` / `JOB_ALREADY_EXISTS` / `SKILL_ALREADY_EXISTS` / `SKILL_ALIAS_ALREADY_EXISTS` / `FEED_SOURCE_ALREADY_EXISTS` | natural-key conflict |
| 409 | `DATA_CONFLICT` | other conflicting concurrent change |
| 409 | `RECOMPUTE_ALREADY_RUNNING` | a batch recompute is active |
| 409 | `RESUME_EXTRACTION_IN_PROGRESS` | the CV is queued or being read **(Phase 4)** |
| 409 | `FEED_POLL_IN_PROGRESS` | the feed source is being polled right now **(Phase 5)** |
| 409 | `FEED_DISABLED` | the job feed is switched off (`FEED_ENABLED=false`) **(Phase 5)** |
| 409 | `APPLICATION_ALREADY_EXISTS` | the job is already tracked; the message names the existing application **(Phase 7)** |
| 409 | `INVALID_STATUS_TRANSITION` | the tracker status move isn't allowed (the message lists the allowed next statuses; `force=true` corrects a mistake) **(Phase 7)** |
| 409 | `GENERATION_IN_PROGRESS` | the application already has a `PENDING` or `RUNNING` generation (the message names it) **(Phase 7)** |
| 409 | `FINDINGS_NOT_ACKNOWLEDGED` | approving a version with a `BLOCKING` finding without `acknowledgeFindings=true` **(Phase 7)** |
| 429 | `REGENERATE_RATE_LIMITED` | `regenerate=true` repeated for a job (`/jobs/{id}/matches`) or, **(Phase 6)**, for a candidate (`/candidates/{id}/matches`, `/profile/matches`) within the regenerate window (`Retry-After: n`); the message names the job or the candidate **(extension, Phase 3)** |
| 409 | `NOTIFICATION_NOT_CONFIGURED` | `POST /notifications/test` while email isn't configured **(Phase 5)** |
| 429 | `FEED_POLL_RATE_LIMITED` | a poll on request within 60 s of the source's last poll (`Retry-After: n`) **(Phase 5)** |
| 429 | `FEED_LOOKUP_RATE_LIMITED` | a company lookup while another runs, or within 10 s of the last one (`Retry-After: n`) **(Phase 5)** |
| 429 | `NOTIFICATION_TEST_RATE_LIMITED` | a test email within 30 s of the last attempt, failed attempts included (`Retry-After: n`) **(Phase 5)** |
| 502 | `NOTIFICATION_DELIVERY_FAILED` | the test email couldn't be delivered; the message names the cause, host:port and the SMTP code, never the server's reply text or a credential. The only 502 in the API; a container-level 502 on `/error` still reports `INTERNAL_ERROR` **(Phase 5)** |
| 503 | `MATCHES_BUSY` | another request is recomputing these matches (`Retry-After: 2`); `/jobs/{id}/matches`, `/matches/**` and, **(Phase 6)**, `/candidates/{id}/matches` and `/profile/matches` (with the candidate message "Matches for this candidate are being recalculated by another request. Please try again in a few seconds.") only |
| 503 | `SERVICE_BUSY` | any other endpoint lost a lock conflict with a concurrent update; nothing was saved, retry (`Retry-After: 2`) **(Phase 5)**. Phase 6: also `PUT /jobs/{id}`, `PUT /candidates/{id}` and `PUT /profile` after a 10 s wait for a match lock. **(Phase 7)** also `/applications/**` writes (`PUT`, `DELETE`, generation requests, edits, approvals) after a 10 s wait for the application row lock |
| 503 | `DATABASE_UNAVAILABLE` | database unreachable (`Retry-After: 5`) |
| 503 | `UPLOAD_BUSY` | every PDF reader is busy; retry the upload in a minute **(Phase 4)** |
| 500 | `INTERNAL_ERROR` | unexpected; message includes the request id to quote |

## Notes for Implementation

- Matching and AI explanation generation are decoupled internally: if the LLM call fails,
  times out, is refused or returns invalid output, the endpoint still returns `200` with the
  score, `aiExplanation: null` and a deterministic **template** `explanation` built from the
  breakdown (with a `reason`), rather than failing the whole request. Scoring commits before
  any model call, so the LLM never holds a DB lock or connection. This is a deliberate
  reliability decision: an example of designing for a third-party dependency's failure modes.
- Pagination and filtering are included from the start rather than added later, since
  retrofitting pagination onto an existing API is a common real-world pain point.
- No authentication: single owner, not public yet (see `PRODUCTION_READINESS.md`, section 6).
  The `/api/profile/**` and (Phase 7) `/api/applications/**` endpoints return personal data;
  the server binds to `127.0.0.1` by default for that reason.
- Tailored documents (Phase 7) are generated in the background like CV extraction, never on a
  request thread: the request returns `202` with a pollable generation, and a restart re-runs
  unfinished work.
