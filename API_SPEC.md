# API Specification

Base URL (local): `http://localhost:8080/api`

All requests and responses are JSON with camelCase field names. Timestamps are
ISO-8601 UTC (`2026-10-01T10:00:00Z`). Ids are UUIDs.

Items marked **(extension)** were added in Phase 2 on top of the Phase 0 draft, and items
marked **(extension, Phase 3)** were added with the AI explanation layer; they are all
additive, so clients written against the draft keep working.

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

**Response `200`** candidate detail. **Errors:** `400`, `404 CANDIDATE_NOT_FOUND`, `409 EMAIL_ALREADY_EXISTS`

### `DELETE /candidates/{id}` **(extension)**
**Response `204`**. Also deletes the candidate's skills and match rows.
**Errors:** `400 INVALID_ID`, `404 CANDIDATE_NOT_FOUND`

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
      "skillCount": 4, "matchable": true }
  ],
  "page": 0, "size": 20, "totalPages": 3, "totalElements": 50
}
```
`skillCount` and `matchable` are **(extension)**. `matchable` is `false` for jobs with no
skills (possible for ETL-loaded jobs).

### `GET /jobs/{id}`
**Response `200`**
```json
{
  "id": "uuid", "title": "Backend Engineer", "company": "Acme", "description": "...",
  "matchable": true,
  "skills": [ { "skillId": "uuid", "name": "Java", "category": "Language", "required": true } ],
  "createdAt": "2026-10-01T10:00:00Z", "updatedAt": "2026-10-01T10:00:00Z"
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
| `company` | required, max 200; `(title, company)` unique after trimming |
| `description` | optional, max 20000 |
| `skills` | **at least 1**, max 100 (`"Add at least one skill so candidates can be ranked for this job."`) |
| `skills[i].name` | required, must exist, no duplicates |
| `skills[i].required` | optional, default `true` |

**Errors:** `400 VALIDATION_FAILED` / `MALFORMED_REQUEST`, `409 JOB_ALREADY_EXISTS`

### `PUT /jobs/{id}` **(extension)**
Full replace, links diffed like candidates. **Response `200`**.
**Errors:** `400`, `404 JOB_NOT_FOUND`, `409 JOB_ALREADY_EXISTS`

### `DELETE /jobs/{id}` **(extension)**
**Response `204`** (also deletes the job's skills and match rows). **Errors:** `400`, `404`

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
(`"A skill named 'kubernetes' already exists as 'Kubernetes' (id …)."`)

No `PUT`/`DELETE` for skills in Phase 2.

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
| 404 | `CANDIDATE_NOT_FOUND` / `JOB_NOT_FOUND` / `SKILL_NOT_FOUND` / `RECOMPUTE_RUN_NOT_FOUND` | resource missing |
| 404 | `ENDPOINT_NOT_FOUND` | no such endpoint (`"No endpoint GET /api/foo."`) |
| 405 | `METHOD_NOT_ALLOWED` | wrong HTTP method (`Allow` header lists the supported ones) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | body is not `application/json` |
| 409 | `EMAIL_ALREADY_EXISTS` / `JOB_ALREADY_EXISTS` / `SKILL_ALREADY_EXISTS` | natural-key conflict |
| 409 | `DATA_CONFLICT` | other conflicting concurrent change |
| 409 | `RECOMPUTE_ALREADY_RUNNING` | a batch recompute is active |
| 429 | `REGENERATE_RATE_LIMITED` | `regenerate=true` repeated for a job within the regenerate window (`Retry-After: n`) **(extension, Phase 3)** |
| 503 | `MATCHES_BUSY` | another request is recomputing this job's matches (`Retry-After: 2`) |
| 503 | `DATABASE_UNAVAILABLE` | database unreachable (`Retry-After: 5`) |
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
