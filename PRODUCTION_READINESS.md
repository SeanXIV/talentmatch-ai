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

- [ ] **Hard-coded scoring weights → configuration**
  - **Now (dev):** required = 10, nice-to-have = 5 (`DATA_MODEL.md`, "Match
    Scoring Rule"). Planned as constants in the Phase 2 Match Service.
  - **Production:** externalized properties (e.g. `talentmatch.scoring.required-weight`)
    with validation and documented defaults.
  - **Why:** lets us tune weights without a code change or redeploy of logic.
  - **When:** Phase 2 if cheap, otherwise before first prod deploy.

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
  - **Now (dev):** undefined. `score = earned / max` is `0/0` when a job has
    no `job_skill` rows.
  - **Production:** such jobs are not scored. The API returns an explicit
    "not matchable / add skills" state instead of `0`. Later, the LLM suggests
    skills from `job.description` for the recruiter to confirm.
  - **Why:** a score of `0` reads as "nobody fits" when the real issue is "no
    criteria".
  - **When:** Phase 2 (explicit state); LLM suggestions after Phase 3.

- [ ] **Match staleness when skills change**
  - **Now (dev):** `candidate_skill` / `job_skill` have no `updated_at`
    (`DATA_MODEL.md`), so skill changes don't mark `job_match` rows stale.
  - **Production:** add a `V2__` migration, e.g. a trigger that bumps the
    parent's `updated_at` on link insert/update/delete, or a dedicated
    `skills_updated_at` column. Compare it with `job_match.computed_at`.
  - **Why:** without it, persisted matches silently go out of date.
  - **When:** before relying on staleness detection.

## 6. API, security & UX

- [ ] **No authentication → secured API**
  - **Now (dev):** no auth planned for Phases 2 to 4 (`API_SPEC.md`).
  - **Production:** Spring Security with OAuth2/JWT, with admin-only access to
    `POST /matches/recompute`. CORS restricted to the frontend origin. Request
    validation on all inputs. Rate limiting, especially on `regenerate=true`
    (which triggers paid LLM calls). HTTPS only.
  - **Why:** candidate PII, LLM cost abuse.
  - **When:** before first prod deploy.

## 7. Observability & operations

- [ ] **Health, logs, metrics, alerts**
  - **Now (dev):** console logs only.
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
