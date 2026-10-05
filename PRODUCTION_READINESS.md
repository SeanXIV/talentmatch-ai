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
  - **Now (dev):** synthetic candidates only (names/emails from Faker). From Phase 4 the
    owner's real CV (contact details, employment history) is stored too.
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
    before first prod deploy (Phase 9).
  - **Done in Phase 2:** `spring.jpa.hibernate.ddl-auto=validate` and
    `spring.flyway.clean-disabled=true` (`src/main/resources/application.yml`);
    `application-prod.yml` sets `spring.flyway.enabled=false`. Schema changes since V1 are
    additive migrations (`V2__skill_link_staleness.sql`, `V3__match_explanation.sql`). Still open: the pipeline step,
    least-privilege users, backups.

- [ ] **Local Docker Postgres → AWS RDS**
  - **Now (dev):** container `talentmatch-postgres` (`postgres:16`, volume
    `talentmatch-pgdata`) started by `scripts/start_db.sh`, plain TCP on localhost.
  - **Production:** RDS PostgreSQL 16 with `sslmode=require`, automated
    backups and point-in-time recovery, and HikariCP pool size matched to RDS
    `max_connections` × app instances.
  - **Why:** durability, managed patching, encrypted connections, no
    connection exhaustion.
  - **When:** Phase 9.

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
  - **Done in Phase 3:** hosted providers selected by Spring profile
    (`application-openai.yml`, `application-claude.yml`; `SPRING_PROFILES_ACTIVE=claude` or
    `prod,claude`), keys only from env vars (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`), masked
    in `toString()`, startup fails with a clear message when missing; per-call HTTP timeouts
    (`call-timeout`), no blocking retries (`maxRetries=0`), failure backoff, circuit breaker,
    regenerate rate limit; token counts in the per-generation INFO log line.
  - **Still open:** secrets manager, monthly cost cap, token-usage metrics/dashboards.
  - **Why:** output quality, plus control over cost and failure modes for a
    paid third-party dependency.
  - **When:** before first prod deploy (secrets manager, caps).

- [ ] **Claude `effort` is not sent**
  - **Now (Phase 3):** LangChain4j 1.20.2 writes `customParameters` as extra top-level keys,
    so `output_config.effort` came out as a duplicate `output_config` next to the
    structured-output `output_config.format`. Structured output wins: `effort` must stay blank
    (API default, high) and a non-blank `talentmatch.ai.claude.effort` fails startup
    (`ClaudeChatModelConfig`). Higher effort means more thinking tokens, so more latency and
    cost per explanation; output cut off at `max-tokens` falls back to the template.
  - **Production:** upgrade LangChain4j once it merges `output_config` (or gains an effort
    option), set `effort: low`, and re-enable the wire-test assertion for both keys
    (`ProviderWireTest`).
  - **Why:** control over latency and cost with a paid provider.
  - **When:** before using the `claude` profile beyond demos.

- [ ] **Local model needs real hardware**
  - **Now (dev):** measured 2026-10-05 on a CPU-only WSL2 box (4 cores, 8 GB):
    `qwen2.5:7b-instruct` writes ~1.7 tokens/s, so one explanation (~510 tokens in, ~80 out)
    takes ~2 minutes. With the defaults (`call-timeout` 60s, 2 parallel, top 5) every call
    times out and only templates appear. The smoke test passed with `call-timeout=300s`,
    `max-concurrency=1`, `top-n=2` (README, "Slow (CPU-only) machines").
  - **Production:** run Ollama on a GPU host (or a box with enough RAM for the model plus the
    app), or use a hosted provider; size `call-timeout`, `max-concurrency` and `top-n` from the
    measured `eval rate`, not the defaults.
  - **Why:** explanations that never arrive in time make the AI layer pure overhead.
  - **When:** before relying on AI explanations day to day.

- [ ] **Synchronous explanation generation → async**
  - **Now (Phase 3):** `GET /jobs/{id}/matches` waits for the page's explanations up to a
    request budget (`talentmatch.ai.request-budget`, 8s default) on a bounded in-process
    executor (`aiExecutor`); unfinished generations keep running in the background, persist
    themselves, and the item is reported `PENDING` ("reload in a few seconds").
  - **Production:** a real queue/worker with persisted job state, and polling or SSE so the
    frontend refreshes without a full reload.
  - **Why:** LLM latency must not block or time out the match endpoint, and background work
    must survive restarts.
  - **When:** before first prod deploy.

- [x] **Deterministic fallback explanation** (Phase 3)
  - **Now:** every match carries an `explanation` object; when no current AI explanation is
    available it is a deterministic template built from matched and missing skills
    (`ExplanationFallbackRenderer`), labelled `source: "TEMPLATE"` with a `reason` and a
    user-facing `note`. `aiExplanation` stays `null` unless the AI text is current.
  - **Why:** recruiters still get a useful reason during an outage.

- [ ] **Per-instance AI throttling and dedup**
  - **Now (dev):** the regenerate rate limiter, single-flight map, failure backoff and circuit
    breaker (`com.talentmatch.ai`) live in memory, per app instance.
  - **Production:** a shared rate limiter (Redis/bucket4j or an API gateway), distributed
    locks or queue-level dedup, and shared circuit state if several instances run.
  - **Why:** with N instances, limits and dedup are N times looser.
  - **When:** before running more than one instance.

- [ ] **In-flight generations are lost on restart**
  - **Now (dev):** background generations run on the in-process `aiExecutor`; a restart drops
    them (the executor does not wait on shutdown). The next GET regenerates.
  - **Production:** a persisted queue (see "Synchronous → async").
  - **When:** before first prod deploy.

- [ ] **Cost caps and quotas**
  - **Now (dev):** only the per-job regenerate rate limit, top-N and the executor size bound
    spend.
  - **Production:** monthly cost caps and per-tenant quotas with alerts.
  - **When:** before enabling a paid provider in production.

- [ ] **API key management and rotation**
  - **Now (dev):** keys only from environment variables; never committed, no defaults.
  - **Production:** keys from a secrets manager, with rotation and least-privilege
    project keys.
  - **When:** before first prod deploy.

- [ ] **Candidate PII sent to third-party providers**: OPEN DECISION
  - **Now (dev):** hosted profiles send the candidate's name and summary (never the email)
    to OpenAI/Anthropic; data is synthetic. Ollama keeps everything local. From Phase 4 this
    is the owner's own CV: sending it to a hosted provider is the owner's choice (decide per
    feature: CV extraction and tailoring send far more than a name and summary).
  - **Production:** DPA with the provider, candidate consent, data-residency review, or
    pseudonymize names before sending.
  - **When:** before using real candidate data with a hosted provider.

- [ ] **Prompt-injection review and output-filter hardening**
  - **Now (dev):** untrusted text is tagged, escaped and truncated; output is validated
    (grounded skills, length limits, no emails/URLs/prompt echoes) (`ARCHITECTURE.md`).
  - **Production:** a red-team review, more output filters (e.g. toxicity/PII classifiers),
    and monitoring of rejected outputs.
  - **When:** before first prod deploy.

- [ ] **Offline model evaluation**
  - **Now (dev):** no evaluation set; quality is checked by eye.
  - **Production:** a golden set with grounding/hallucination metrics, run before switching
    models or prompts.
  - **When:** before switching models in production.

- [ ] **A model change does not invalidate explanations** (by design)
  - **Now (dev):** the staleness hash covers the prompt, not the model, so switching provider
    keeps existing explanations (labelled with their original `model`). Use
    `regenerate=true` to refresh them.
  - **Production:** decide whether a model upgrade should trigger a background refresh.
  - **When:** when models change in production.

- [ ] **Passive AI health**
  - **Now (dev):** the `ai` health component reflects the circuit breaker only; it never
    probes the model, so a provider outage shows up only after real requests fail.
  - **Production:** optional synthetic probe with alerting, kept out of readiness.
  - **When:** before first prod deploy.

- [ ] **Explanation retention and deletion**
  - **Now (dev):** explanations live in `job_match` until the row is deleted (cascades from
    candidate/job deletion).
  - **Production:** part of the PII retention/deletion policy (section 1).
  - **When:** before first prod deploy.

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
  - **Now (dev):** no auth (`API_SPEC.md`); single owner, not public (ROADMAP "Direction change"). Since Phase 2 this includes
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
    **Done in Phase 3:** Micrometer timer `talentmatch.ai.explanations` (tags provider, model,
    outcome = success|timeout|provider_error|refused|invalid_output|rejected) and counter
    `talentmatch.ai.fallbacks` (tag reason); one INFO line per generation with
    provider/model/outcome/latency/token counts (never prompt text, names or keys); passive
    `ai` health component (DEGRADED never fails overall health). `/actuator/metrics` is not
    exposed yet.
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

- [x] **Backend CI cache expression unverified** (verified 2026-10-05: CI run 37343908865
  logs `Cache hit for: setup-java-Linux-x64-maven-wrapper-…`)
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
  - **When:** Phase 9.

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

## 10. Job-seeker features (Phases 4–7, PROPOSED 2026-10-05)

- [ ] **Job sources: terms, limits and reliability**
  - **Now (dev):** no real job source yet (synthetic data only).
  - **Production:** only sources with official APIs or feeds whose terms allow this use (ATS
    job-board APIs, aggregator APIs with a key); no scraping of sites that forbid it.
    Respect rate limits and use conditional requests; back off on errors; alert when a
    source fails or returns nothing for too long (a silently dead source means missed jobs).
  - **Why:** banned keys or IPs, legal exposure, and missed postings that defeat "be first".
  - **When:** Phase 5.

- [ ] **Always-on poller**
  - **Now (dev):** everything runs on a laptop that sleeps.
  - **Production:** the poller and notifier run 24/7 on an always-on host, with a heartbeat
    alert if polling stops; one active poller even with several app instances.
  - **Why:** postings found hours late lose the speed advantage.
  - **When:** Phase 9 (until then, polling only while the laptop is awake).

- [ ] **Notification channel secrets and delivery**
  - **Now (dev):** no notifications yet.
  - **Production:** channel credentials (bot token, SMTP password, …) from a secrets
    manager; delivery failures retried and logged; no duplicate alerts for the same posting.
  - **When:** Phase 5.

- [ ] **Uploaded CV files**
  - **Now (dev):** not built yet.
  - **Production:** size and type limits, content-type sniffing (not just the extension),
    files stored outside the web root, encrypted at rest and covered by backups and the
    retention policy (section 1).
  - **When:** Phase 4.

- [ ] **Never-invent check on tailored documents**
  - **Now (dev):** not built yet.
  - **Production:** a grounding validator rejects any role, skill, certificate, date or
    number not in the confirmed master profile, plus a golden-set evaluation before
    changing models or prompts (section 4, "Offline model evaluation").
  - **Why:** a fabricated claim on a real application damages the owner's credibility.
  - **When:** Phase 7.

- [ ] **Model choice for CV extraction and tailoring**: OPEN DECISION
  - **Now (dev):** local `qwen2.5:7b-instruct` on CPU (~1.7 tokens/s, see section 4 "Local
    model needs real hardware"): a full CV or cover letter would take many minutes, and a
    7B model's writing quality may not be good enough for applications.
  - **Production:** decide per feature between a GPU-backed local model and a hosted
    provider (quality and speed vs. cost and sending the CV off the machine).
  - **When:** before Phase 7 (extraction in Phase 4 can run async on the local model).

