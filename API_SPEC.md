# API Specification

Base URL (local): `http://localhost:8080/api`

All requests and responses are JSON with camelCase field names. Timestamps are
ISO-8601 UTC (`2026-10-01T10:00:00Z`). Ids are UUIDs.

Items marked **(extension)** were added in Phase 2 on top of the Phase 0 draft, and items
marked **(extension, Phase 3)** were added with the AI explanation layer; they are all
additive, so clients written against the draft keep working. The **Profile** endpoints
(Phase 4) are new.

> **Personal data, no auth.** `/api/profile/**` returns the owner's CV and contact details
> (PII) to anyone who can reach the port. The server listens on `127.0.0.1` by default
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
PUT /api/profile."`, Phase 4).

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
(the source part is omitted when the job has no posting).

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
**Errors:** `400 VALIDATION_FAILED`; `400 MALFORMED_REQUEST` for unknown fields or enum values.

---

## Job feed sources (Phase 5)

The watchlist of company job boards the feed polls. Each ACTIVE source is polled every
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
| `NOT_FOUND` / `UNAUTHORIZED` | the board is gone or refused access; the source stays `ACTIVE` in case it comes back | after 6 h |

`consecutiveFailures` resets on `OK`/`NOT_MODIFIED`; `lastError` is a short sanitized note (no
response body, no URL query, no key). The first successful poll sets `baselineAt`: postings seen
then count as already known (not new) unless they were published within the last 24 h. A posting
that leaves a board's listing is closed; one that comes back is reopened.

**`FeedSourceResponse`**
```json
{ "id": "…", "kind": "LEVER", "managedBy": "OWNER", "state": "ACTIVE", "companyName": "Acme",
  "boardToken": "acme", "options": { "leverInstance": "eu" }, "pollIntervalSeconds": null,
  "effectivePollIntervalSeconds": 300, "nextPollAt": "…", "lastPolledAt": null, "lastSuccessAt": null,
  "lastStatus": null, "lastError": null, "consecutiveFailures": 0, "openPostings": 0,
  "baselineAt": null, "createdAt": "…", "warnings": [] }
```
`effectivePollIntervalSeconds` is the interval actually used: `pollIntervalSeconds`, or the default
for the kind (company boards: 300), and never below the minimum (company boards: 120).
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
| `kind` | required: `GREENHOUSE`, `LEVER` or `ASHBY`. `ADZUNA` is not available yet (`400` on `kind`) |
| `boardToken` | required; `[A-Za-z0-9._-]{1,100}`, the board name from the URL (`boards.greenhouse.io/<token>`, `jobs.lever.co/<site>`, `jobs.ashbyhq.com/<name>`) |
| `companyName` | optional, max 200; Greenhouse fills it from the board when omitted |
| `options.leverInstance` | Lever only: `"eu"` (jobs.eu.lever.co) or `"global"` (default) |
| `pollIntervalSeconds` | optional, 120..86400 for company boards |
| `verify` | default `true`: check the board with the provider first (waits at most 10 s) |

Greenhouse and Ashby tokens are case-insensitive (`Acme` and `acme` are the same source). **Lever
site names are case-sensitive** and kept as given.

**Response `201`** with `Location: /api/feed/sources/{id}`. `warnings` explains anything to watch:
- `"The board exists but has no open postings right now."`
- `"Couldn't reach Lever to check the board; it will be checked on the first poll."` (network
  error, timeout or 5xx; the source is saved anyway). Rate limits and odd answers get a similar note.
- `verify: false` saves without a check and says so.

**Errors:**
- `400 VALIDATION_FAILED`: invalid fields, or the provider has no such board (field `boardToken`:
  `"Greenhouse has no job board 'acme'. Check the token in the board URL (boards.greenhouse.io/<token>)."`).
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

---

## Job feed (Phase 5)

### `GET /feed/status`
**Response `200`** (this step reports the poller; more sections are added as the feed grows)
```json
{ "enabled": true, "schedulerEnabled": true,
  "sources": { "total": 4, "active": 3, "failing": 1, "lastSuccessAt": "…" },
  "processing": { "pending": 12 } }
```
- `schedulerEnabled`: sources are polled automatically (the feed and its scheduler are both on).
- `sources.failing`: ACTIVE sources whose last poll failed.
- `processing.pending`: feed jobs found or changed by a poll and waiting to be processed (skills,
  filter, score).

The actuator health component `feed` is `UP`, or `DEGRADED` when an ACTIVE source failed 3 times
in a row or has had no successful poll for more than 3 × its interval (details: counts and source
ids). It never turns overall health `DOWN`.

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
matches stale, as for any candidate.

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
never adopted or merged).

### `GET /profile`
The saved master profile (same body as the `PUT` response, `warnings: []`).
**Errors:** `404 PROFILE_NOT_FOUND` ("You have not saved a profile yet. …")

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
| 400 | `MALFORMED_REQUEST` | body is not valid JSON, has an unknown field, or a field has the wrong type |
| 400 | `RESUME_UNREADABLE` | uploaded PDF can't be read (damaged, protected, scanned, too many pages, too slow) **(Phase 4)** |
| 404 | `CANDIDATE_NOT_FOUND` / `JOB_NOT_FOUND` / `SKILL_NOT_FOUND` / `RECOMPUTE_RUN_NOT_FOUND` / `RESUME_NOT_FOUND` / `PROFILE_NOT_FOUND` / `PREFERENCES_NOT_FOUND` / `SKILL_ALIAS_NOT_FOUND` / `FEED_SOURCE_NOT_FOUND` | resource missing |
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
| 429 | `REGENERATE_RATE_LIMITED` | `regenerate=true` repeated for a job within the regenerate window (`Retry-After: n`) **(extension, Phase 3)** |
| 429 | `FEED_POLL_RATE_LIMITED` | a poll on request within 60 s of the source's last poll (`Retry-After: n`) **(Phase 5)** |
| 503 | `MATCHES_BUSY` | another request is recomputing this job's matches (`Retry-After: 2`) |
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
  The `/api/profile/**` endpoints return personal data; the server binds to `127.0.0.1` by
  default for that reason.
