# Data Model

The schema is created by the Flyway migrations in `src/main/resources/db/migration/`
(PostgreSQL 16):

- `V1__init_schema.sql`: tables, constraints, indexes, `set_updated_at()` triggers.
- `V2__skill_link_staleness.sql`: link-touch triggers that make skill changes visible to
  match staleness (see below).
- `V3__match_explanation.sql`: persisted AI explanations with prompt-hash staleness
  (Phase 3; see "AI explanations" below).

## Entity-Relationship Diagram

```mermaid
erDiagram
    CANDIDATE ||--o{ CANDIDATE_SKILL : has
    SKILL ||--o{ CANDIDATE_SKILL : "used in"
    JOB ||--o{ JOB_SKILL : requires
    SKILL ||--o{ JOB_SKILL : "used in"
    CANDIDATE ||--o{ JOB_MATCH : "scored in"
    JOB ||--o{ JOB_MATCH : "scored in"

    CANDIDATE {
        uuid id PK
        string full_name
        string email UK
        text summary
        timestamp created_at
        timestamp updated_at
    }

    JOB {
        uuid id PK
        string title
        string company
        text description
        timestamp created_at
        timestamp updated_at
    }

    SKILL {
        uuid id PK
        string name UK
        string category
        timestamp updated_at
    }

    CANDIDATE_SKILL {
        uuid candidate_id FK
        uuid skill_id FK
        int years_experience
    }

    JOB_SKILL {
        uuid job_id FK
        uuid skill_id FK
        boolean required
    }

    JOB_MATCH {
        uuid id PK
        uuid candidate_id FK
        uuid job_id FK
        double score
        text ai_explanation
        jsonb explanation_payload
        string explanation_input_hash
        string explanation_model
        timestamp explanation_generated_at
        timestamp computed_at
        timestamp updated_at
    }
```

## Table Notes

### `candidate`
Core candidate record. `summary` is free text (e.g. a short bio) used as
context when generating AI explanations. `email` is the natural key: it must
be stored normalized (lowercase and trimmed, enforced by a check constraint)
and is unique.

### `job`
Core job record. `description` provides context for AI explanations in the
same way `candidate.summary` does. The pair `(title, company)` is unique.

### `skill`
A normalized skill lookup table (e.g. "Java", "SQL", "React") shared between
candidates and jobs, so matching is done on a consistent vocabulary rather
than free-text string comparison. Names are stored trimmed and are unique
case-insensitively, via a unique index on `lower(name)`.

### `candidate_skill` / `job_skill`
Join tables linking candidates and jobs to their skills, keyed by
`(candidate_id, skill_id)` and `(job_id, skill_id)`.
`candidate_skill.years_experience` is optional but must be `>= 0` (the ETL
also caps it at 60). `job_skill.required` (default `true`) distinguishes
must-have skills from nice-to-have, which feeds directly into the match
scoring weights.

### `job_match`
A computed, persisted result: a score between a candidate and a job, plus the
AI-generated explanation for that score. Persisting matches (rather than only
computing on demand) means explanations aren't re-generated — and re-billed —
every time the dashboard is opened. There is at most one row per
`(candidate_id, job_id)` pair (unique constraint), and `score` is a
`double precision` constrained to `[0, 1]`.

`job_match` is written **only** by the API's set-based upsert
(`INSERT ... SELECT FROM unnest(...) ON CONFLICT (candidate_id, job_id) DO UPDATE SET score,
computed_at`), never through JPA. `computed_at` means "verified fresh at": every upsert
advances it to `now()`, even when the score is unchanged. `ai_explanation` and the V3
`explanation_*` columns are never touched by the upsert; they are written only by the guarded
explanation `UPDATE` (below).

### AI explanations (V3)

| Column | Type | Meaning |
|---|---|---|
| `ai_explanation` (V1) | `text` | the AI explanation text (`NULL` = none) |
| `explanation_payload` | `jsonb` | validated AI output `{headline, explanation, strengths[], gaps[]}` |
| `explanation_input_hash` | `char(64)` | sha256 hex of the exact prompt (system + user message) it was generated from |
| `explanation_model` | `varchar(200)` | provider/model label, e.g. `ollama/qwen2.5:7b-instruct` |
| `explanation_generated_at` | `timestamptz` | when it was generated |

Check constraint `ck_job_match_explanation_complete`: all five columns are `NULL`, or all are
set (non-blank text, a JSON object payload, a 64-char lower-case hex hash, a non-blank model
and a timestamp). An explanation is never partially recorded. The new columns are not mapped
in the `JobMatch` entity (`validate` ignores unmapped columns).

**Explanation staleness rule (not `computed_at`).** A stored explanation is *fresh* iff
`explanation_input_hash` equals the hash of the prompt the API would send now. If
`ai_explanation` is set but the hash differs, the explanation is `STALE`. `computed_at` is
deliberately not used: every score refresh advances it, even when nothing changed. Any change
the model would see (candidate name/summary/skills, job title/company/description/skills,
score, truncated context, system prompt) changes the hash; changes it never sees (email, text
beyond the truncation point, `computed_at`, the model) do not. Rows whose payload cannot be
parsed are treated as having no explanation.

**Guarded write.** Explanations are generated outside the scoring transaction and saved with
one autocommit statement:

```sql
UPDATE job_match m SET ai_explanation = …, explanation_payload = CAST(… AS jsonb), …
  FROM candidate c, job j
 WHERE m.job_id = :jobId AND m.candidate_id = :candidateId
   AND c.id = m.candidate_id AND j.id = m.job_id
   AND c.updated_at = :candidateUpdatedAt AND j.updated_at = :jobUpdatedAt
   AND m.score = :expectedScore
```

The timestamps and score are the values read with the page. Equality detects any committed
edit of the candidate, the job or their skill links (V2 triggers), and any rescore, so an
explanation generated from outdated inputs is discarded (0 rows) instead of stored.

### `updated_at` and the `set_updated_at()` trigger
`candidate`, `job`, `skill`, and `job_match` each have an `updated_at`
column. A shared `set_updated_at()` trigger function sets it to `now()` on
every `UPDATE`, so it stays correct even when the ETL's
`ON CONFLICT DO UPDATE` upserts don't set it explicitly.

### Link-touch triggers (V2) and match staleness
`candidate_skill` / `job_skill` have no timestamps of their own. V2 adds statement-level
`AFTER INSERT / UPDATE / DELETE` triggers (one per event, using transition tables
`new_links` / `old_links`) that bump the parent's `updated_at` whenever a link row really
changes:

- `trg_candidate_skill_touch_{ins,upd,del}` → `touch_candidate_from_links()`
- `trg_job_skill_touch_{ins,upd,del}` → `touch_job_from_links()`

One parent `UPDATE` runs per statement. Statements touching zero rows (no-op ETL reruns,
whose conditional `DO UPDATE ... WHERE` and prune `DELETE` affect nothing) bump nothing. The
`updated_at IS DISTINCT FROM now()` guard skips parents already touched in the same
transaction (e.g. a candidate inserted together with its links). Deleting a skill cascades
to its links and therefore bumps every affected candidate and job. Because triggers live in
the database, they catch both writers: the API and the ETL.

**Staleness rule.** A `job_match` row is stale when

```
computed_at < GREATEST(candidate.updated_at, job.updated_at)
```

and a missing row counts as stale. The API recomputes stale rows on read
(`GET /jobs/{id}/matches`) or in batch (`POST /matches/recompute`).

## Match Scoring Rule

Each of the job's skills is worth points: by default a **required** skill is worth
**10 points** and a **nice-to-have** skill **5 points** (configurable via
`talentmatch.scoring.required-weight` / `nice-to-have-weight`, validated at startup). A
candidate earns a skill's points if they have that skill; extra candidate skills and years
of experience do not affect the score. The stored score is normalized:

```
score = earned points / max points   (0..1)
```

A job with no skills is **not matchable**: it is never scored (no `0/0`, no rows written),
and the API returns an explicit `JOB_HAS_NO_SKILLS` state. Changing the weights does not mark
rows stale; run `POST /api/matches/recompute` afterwards.

## Design Decisions

- **Why normalize skills into their own table** instead of storing them as
  text arrays on candidate/job: it enables consistent matching logic and
  makes "find all candidates with skill X" a simple join instead of a text
  search.
- **Why persist `job_match` rows** instead of computing scores live on every
  request: keeps the AI Explanation Service's LLM calls cheap and fast to
  re-display, and gives a natural place to add a "recompute matches" endpoint
  later without changing the read path.
- **UUID primary keys** throughout, rather than auto-increment integers, since
  this mirrors patterns used in most production systems working with
  externally-referenced or eventually-distributed data.
- **Natural-key unique constraints** (normalized email, `(title, company)`,
  `lower(skill.name)`) let the ETL upsert idempotently: reloading the same
  input changes nothing.
