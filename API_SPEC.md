# API Specification

Base URL (local): `http://localhost:8080/api`

All responses are JSON. All list endpoints support `page` and `size` query
parameters for pagination.

## Candidates

### `GET /candidates`
List all candidates.

**Query params:** `page`, `size`, `skill` (optional filter by skill name)

**Response `200`**
```json
{
  "content": [
    { "id": "uuid", "fullName": "string", "email": "string", "summary": "string" }
  ],
  "page": 0,
  "totalPages": 3
}
```

### `GET /candidates/{id}`
Get a single candidate, including their skills.

**Response `200`**
```json
{
  "id": "uuid",
  "fullName": "string",
  "email": "string",
  "summary": "string",
  "skills": [ { "name": "Java", "yearsExperience": 2 } ]
}
```

**Response `404`** — candidate not found

---

## Jobs

### `GET /jobs`
List all jobs.

**Query params:** `page`, `size`, `skill` (optional filter by required skill)

### `GET /jobs/{id}`
Get a single job, including its required skills.

**Response `404`** — job not found

---

## Matches

### `GET /jobs/{id}/matches`
Return ranked candidate matches for a job, computing them if they don't
already exist for this job.

**Query params:** `limit` (default 10), `regenerate` (boolean, default false —
forces recomputation and a fresh AI explanation even if a match already exists)

**Response `200`**
```json
{
  "jobId": "uuid",
  "matches": [
    {
      "candidateId": "uuid",
      "candidateName": "string",
      "score": 0.82,
      "aiExplanation": "string",
      "computedAt": "2026-08-20T10:00:00Z"
    }
  ]
}
```

### `POST /matches/recompute`
Trigger recomputation of matches for all jobs (batch operation, intended for
an admin/cron use case rather than the dashboard).

**Response `202`** — accepted, processing started

---

## Error Format

All errors follow a consistent shape:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Job with id {id} does not exist",
  "path": "/api/jobs/{id}"
}
```

## Notes for Implementation

- Matching and AI explanation generation should be decoupled internally: if the
  LLM API call fails or times out, the endpoint should still return the score
  with `aiExplanation: null` rather than failing the whole request. This is a
  deliberate reliability decision, not an oversight — it's worth calling out
  explicitly in interviews as an example of designing for a third-party
  dependency's failure modes.
- Pagination and filtering are included from the start rather than added
  later, since retrofitting pagination onto an existing API is a common
  real-world pain point worth avoiding by design.
