# Production Readiness

A living checklist of things that are **deliberately dev/MVP choices today**
and **must change before production**. None of these are bugs. They are
shortcuts we took on purpose, written down so they don't get forgotten.

**How to use this file**

- Whenever you take a dev-only shortcut, add an entry to the matching section:
  a short title, **Now (dev)** (what we do today, with file/path refs),
  **Production** (what it should become), **Why**, and **When** (a phase from
  [`ROADMAP.md`](./ROADMAP.md), or "before first prod deploy").
- When the production version is in place, tick the box (`[x]`) and link the
  PR or commit. Don't delete ticked items; they record what was done.
- Items marked **OPEN DECISION** or **PROPOSED** still need the owner to
  confirm them before anyone builds them.

## 1. Data

- [ ] **Synthetic dataset → real job-postings source**
  - **Now (dev):** seeded Faker generator `scripts/etl/generate_synthetic.py`
    is the only source adapter; no real data anywhere.
  - **Production:** add a real job-postings source as a new adapter
    implementing `talentmatch_etl.sources.base.SourceAdapter`. Check the
    dataset's licence before using it. Keep raw files out of git and fetch
    them with a download script.
  - **Why:** realistic data distribution; licence compliance; repo size and
    provenance.
  - **When:** after Phase 2 (optional for the portfolio); before first prod deploy.

- [ ] **Candidate data is PII**
  - **Now (dev):** synthetic candidates only (names/emails from Faker).
  - **Production:** recorded consent; a retention and deletion policy
    (including `job_match` rows and explanations); encryption at rest (RDS)
    and in transit (TLS); never copy real PII into dev/test.
  - **Why:** legal obligations (e.g. GDPR) and user trust.
  - **When:** before first prod deploy.

- [ ] **ETL scheduling, alerting and `--strict` policy**: OPEN DECISION
  - **Now (dev):** `scripts/etl/clean_and_load.py` is run by hand from the CLI.
    Rejects are written to `scripts/etl/data/reports/rejects.csv` and
    `summary.json`. `--strict` means any reject loads nothing and exits `2`;
    without it, clean rows load and exit `0`.
  - **Production:** a scheduled job (e.g. cron, EventBridge, AWS Batch) with
    alerting on reject volume (count/ratio from `summary.json`). Decide the
    strict policy: all-or-nothing, a reject-ratio threshold, or load-and-alert.
  - **Why:** unattended ingestion must fail loudly, and must not silently
    drop or partially load data.
  - **When:** before first prod deploy.

## 2. Database & schema migrations

- [ ] **Migrations run by the app at startup → separate pipeline step**
  - **Now (dev):** `scripts/start_db.sh` runs Flyway via Docker
    (`flyway/flyway:10 migrate`) against `src/main/resources/db/migration`.
    Decided for Phase 2: Spring Boot also runs Flyway at startup
    (`spring.flyway.enabled=true`, `classpath:db/migration`) for convenience.
    CI (`.github/workflows/ci.yml`) applies the SQL files with `psql`, not Flyway.
  - **Production:**
    - Run migrations as a separate deploy-pipeline step (Flyway CLI or a
      one-off job) **before** the new app version starts.
    - The `application-prod` profile sets `spring.flyway.enabled=false` (or
      validate-only); Hibernate `ddl-auto=validate` everywhere, always.
    - The app connects as a least-privilege DB user with no DDL rights.
      Migrations use a separate migration user.
    - Take a backup/snapshot before every migration.
    - `spring.flyway.clean-disabled=true`.
    - Never edit an applied migration; add `V2__...` and later.
    - Prefer backward-compatible (expand/contract) migrations so the old and
      new app versions can both run during a deploy.
  - **Why:** schema changes become explicit, reviewable and recoverable. A
    compromised or buggy app can't alter or drop the schema, and rolling
    deploys don't break.
  - **When:** `ddl-auto=validate` and `clean-disabled` in Phase 2; the rest
    before first prod deploy (Phase 5).
  - **Done in Phase 2:** `spring.jpa.hibernate.ddl-auto=validate` and
    `spring.flyway.clean-disabled=true` (`src/main/resources/application.yml`);
    `application-prod.yml` sets `spring.flyway.enabled=false`. Schema changes since V1 are
    additive migrations (`V2__skill_link_staleness.sql`). Still open: the pipeline step,
    least-privilege users, backups.

- [ ] **Local Docker Postgres → AWS RDS**
  - **Now (dev):** container `talentmatch-postgres` (`postgres:16`, volume
    `talentmatch-pgdata`) started by `scripts/start_db.sh`, plain TCP on localhost.
  - **Production:** RDS PostgreSQL 16 with `sslmode=require`, automated
    backups and point-in-time recovery, and HikariCP pool size matched to RDS
    `max_connections` × app instances.
  - **Why:** durability, managed patching, encrypted connections, no
    connection exhaustion.
  - **When:** Phase 5.

## 3. Secrets & configuration

- [ ] **Default credentials → secrets manager**
  - **Now (dev):** `talentmatch`/`talentmatch` in `scripts/.env.example`, as
    fallbacks in `scripts/start_db.sh` and the ETL defaults, and as CI-only
    service credentials in `.github/workflows/ci.yml`.
  - **Production:** credentials come from AWS Secrets Manager or SSM Parameter
    Store. The prod profile has **no** default credentials and fails fast at
    startup if any are missing.
  - **Why:** a default password that works is a breach waiting to happen.
  - **When:** before first prod deploy.
  - **Done in Phase 2:** the API's dev defaults live only in `application.yml`
    (`DB_PASSWORD:talentmatch`); `application-prod.yml` has no credential defaults and
    requires `sslmode=require`, so a missing variable fails startup. Still open: the
    secrets manager.

- [x] **Hard-coded scoring weights → configuration** (Phase 2)
  - **Now:** `talentmatch.scoring.required-weight` (10) and `nice-to-have-weight` (5) in
    `application.yml`, bound to the validated `ScoringProperties` record (1..1000; invalid
    values fail startup) and passed to the pure `ScoringEngine`.
  - **Note:** changing weights does not mark cached scores stale; run
    `POST /api/matches/recompute` after a change (the API logs a WARN when a cached score
    differs from the engine).
  - **Why:** lets us tune weights without a code change.

## 4. AI / LLM

- [ ] **Local Ollama → hosted provider**
  - **Now (dev):** Ollama local model by default (`ARCHITECTURE.md`, "AI
    Tooling and Provider Choice").
  - **Production:** hosted provider (OpenAI/Claude) selected by Spring profile.
    API keys live in the secrets manager. Add per-request timeouts, retries with
    backoff, rate limits and a monthly cost cap, and log token usage per request.
  - **Why:** output quality, plus control over cost and failure modes for a
    paid third-party dependency.
  - **When:** Phase 3 (profile), before first prod deploy (limits, caps).

- [ ] **Synchronous explanation generation → async**
  - **Now (dev):** planned to generate synchronously inside
    `GET /jobs/{id}/matches`, with a timeout (`API_SPEC.md`).
  - **Production:** generate in the background (queue/worker). The API returns
    scores immediately with an explanation status the frontend can poll.
  - **Why:** LLM latency must not block or time out the match endpoint.
  - **When:** before first prod deploy.

- [ ] **Deterministic fallback explanation**
  - **Now (dev):** planned fallback is `aiExplanation: null` when the LLM
    fails (`API_SPEC.md`, "Notes for Implementation").
  - **Production:** a template explanation built from matched and missing
    skills when the LLM is unavailable, clearly labelled as non-AI.
  - **Why:** recruiters still get a useful reason during an outage.
  - **When:** Phase 3.

## 5. Matching behaviour (DECIDED 2026-10-01)

- [ ] **Jobs with no skills**
  - **Now (Phase 2 part done):** such jobs are never scored. `GET /api/jobs/{id}/matches`
    returns `200` with `matchable: false`, `reason: "JOB_HAS_NO_SKILLS"` and an
    "add skills" message; nothing is written. `POST/PUT /api/jobs` require at least one
    skill; ETL-loaded jobs may still have none. Batch recompute lists them as skipped.
  - **Production:** additionally, the LLM suggests skills from `job.description` for the
    recruiter to confirm.
  - **Why:** a score of `0` reads as "nobody fits" when the real issue is "no
    criteria".
  - **When:** LLM suggestions after Phase 3.

- [x] **Match staleness when skills change** (Phase 2)
  - **Now:** `V2__skill_link_staleness.sql` adds statement-level link-touch triggers that
    bump the parent's `updated_at` on any real `candidate_skill` / `job_skill` insert,
    update or delete (API and ETL alike; no-op ETL reruns bump nothing). A row is stale
    when `computed_at < GREATEST(candidate.updated_at, job.updated_at)` (`DATA_MODEL.md`).
  - **Why:** without it, persisted matches silently go out of date.

## 6. API, security & UX

- [ ] **No authentication → secured API**
  - **Now (dev):** no auth in Phases 2 to 4 (`API_SPEC.md`). Since Phase 2 this includes
    the write endpoints (`POST/PUT/DELETE` candidates and jobs, `POST /skills`) and
    `POST /matches/recompute`: anyone who can reach the API can change data or start a
    batch recompute.
  - **Production:** Spring Security with OAuth2/JWT, with admin-only access to
    `POST /matches/recompute`. CORS restricted to the frontend origin. Request
    validation on all inputs. Rate limiting, especially on `regenerate=true`
    (which triggers paid LLM calls). HTTPS only.
  - **Why:** candidate PII, LLM cost abuse.
  - **When:** before first prod deploy.

## 7. Observability & operations

- [ ] **Health, logs, metrics, alerts**
  - **Now (dev):** console logs, with the request id in every log line
    (`X-Request-Id`, MDC). **Done in Phase 2:** Actuator `/actuator/health` with
    liveness/readiness probes (details hidden in the `prod` profile); error responses use
    one `ApiError` shape with codes and never include stack traces, exception class
    names or SQL (`server.error.include-*=never`, `GlobalExceptionHandler`,
    `ApiErrorAttributes`). Still open: JSON logs, metrics, alerting.
  - **Production:** Spring Boot Actuator liveness/readiness probes; structured
    (JSON) logs; metrics (latency, error rate, LLM tokens/cost, ETL rejects);
    alerting. Error responses use the `API_SPEC.md` error format and never
    leak stack traces.
  - **Why:** you can't operate what you can't see; stack traces leak internals.
  - **When:** Actuator in Phase 2; the rest before first prod deploy.

## 8. CI/CD & dev environment

- [ ] **DB integration tests skip instead of fail**
  - **Now (dev):** `tests/etl/conftest.py` skips integration tests when
    PostgreSQL (or psycopg) is unreachable.
  - **Production:** in CI, an unreachable DB is a failure (e.g. a
    `REQUIRE_DB=1` env flag set in `.github/workflows/ci.yml`). Local runs
    may still skip.
  - **Why:** a green build must mean the DB tests actually ran.
  - **When:** Phase 2.

- [ ] **WSL Docker access** (dev-only, not a prod concern)
  - **Now (dev):** if Docker Desktop's WSL integration is off, use
    `DOCKER=docker.exe` (see `README.md`).
  - **Production:** n/a. Enable WSL integration for a smoother setup.
  - **When:** n/a.

- [ ] **Backend CI cache expression unverified**
  - **Now (dev):** `hashFiles(format('{0}/pom.xml', env.BACKEND_DIR))` (resolves to
    `./pom.xml`) in `.github/workflows/ci.yml` hasn't run yet because there's no
    `pom.xml`.
  - **Production:** confirm the Maven cache hits on the first Phase 2 CI run.
  - **Why:** an untested expression may silently pick the wrong cache.
  - **When:** Phase 2 (first `pom.xml` push).

- [ ] **No deployment pipeline**
  - **Now (dev):** CI only builds and tests. Nothing deploys.
  - **Production:** a pipeline that builds and pushes images, runs migrations
    (section 2), then deploys to EC2/Elastic Beanstalk, with a rollback path.
  - **Why:** repeatable, auditable releases.
  - **When:** Phase 5.

- [ ] **Backend integration tests need Docker**
  - **Now (dev):** `./mvnw verify` runs `*IT` tests against Testcontainers PostgreSQL 16.
    They deliberately do **not** use `disabledWithoutDocker`, so a missing Docker daemon
    fails the build instead of silently skipping (GitHub-hosted runners have Docker).
  - **Production:** keep it that way in CI; never add a skip-without-Docker switch.
  - **Why:** a green build must mean the integration tests actually ran.
  - **When:** Phase 2 (keep).

## 9. Backend API (Phase 2) dev-only shortcuts

- [ ] **In-memory recompute runs**
  - **Now (dev):** `RecomputeService` keeps run status in memory: one run at a time, last
    20 runs, lost on restart, executor is per instance (`AsyncConfig`, single thread).
    Two app instances could each run a batch at once (still safe: per-job advisory locks).
  - **Production:** a persisted job table or a real scheduler/queue (e.g. a `recompute_run`
    table, or AWS Batch/EventBridge), with cluster-wide "one at a time".
  - **Why:** status must survive restarts and be consistent across instances.
  - **When:** before running more than one instance.

- [ ] **Staleness race window**
  - **Now (dev):** an edit transaction that started before a recompute read the data but
    commits after it (sub-second window) gets `updated_at` = its own start time, which can
    be earlier than the recompute's `computed_at`, so that one change may not mark the row
    stale. The next change to that candidate/job, `regenerate=true` or a full
    `POST /matches/recompute` fixes it.
  - **Production:** compare against a commit-ordered version (e.g. a per-row version
    counter or `clock_timestamp()`-based touch plus a "dirty" flag) if exactness matters.
  - **Why:** rare, self-healing, but not strictly correct.
  - **When:** before relying on matches for automated decisions.

- [ ] **Last-write-wins updates**
  - **Now (dev):** `PUT` has no optimistic locking (no `version` column); concurrent edits
    of the same candidate/job silently overwrite each other.
  - **Production:** add a `version` column (`@Version`) and `If-Match`/ETag support,
    returning `409`/`412` on conflicts.
  - **Why:** prevents lost updates when two recruiters edit the same record.
  - **When:** before multi-user use.

- [ ] **Stale check scans all candidates per GET**
  - **Now (dev):** `GET /jobs/{id}/matches` runs one `candidate LEFT JOIN job_match` query
    over all candidates to find stale rows. Fine for thousands of candidates.
  - **Production:** maintain a dirty set (e.g. a `job_match_dirty` table filled by the
    triggers), or recompute asynchronously on change; add indexes as data grows.
  - **Why:** latency grows linearly with the number of candidates.
  - **When:** when candidates reach ~100k or p95 latency demands it.

- [ ] **Advisory lock assumes a single database**
  - **Now (dev):** per-job recomputes are serialized with
    `pg_advisory_xact_lock(hashtextextended('job_match:' || jobId, 0))`; correct for one
    PostgreSQL primary (any number of app instances). A 64-bit hash collision would only
    serialize two unrelated jobs, never corrupt data.
  - **Production:** keep a single writer primary, or move to row-level locks/a queue if the
    database is ever sharded.
  - **Why:** advisory locks are not shared across separate databases.
  - **When:** only if the data store changes.
