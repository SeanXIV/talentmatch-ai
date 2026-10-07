# Roadmap

Each phase produces something that runs and is worth committing on its own —
no phase depends on a later one being finished to be demonstrable.

## Phase 0 — Planning (this document set)
- [x] README with problem statement and tech stack
- [x] Architecture diagram
- [x] Data model and ER diagram
- [x] API specification
- [x] Roadmap

## Phase 1 — Data Layer
- [x] Set up PostgreSQL (local via Docker, `scripts/start_db.sh`)
- [x] Write schema migration scripts (Flyway `V1__init_schema.sql`, tables from `DATA_MODEL.md`)
- [x] Generate candidates/jobs data with a seeded Faker synthetic generator
      (`scripts/etl/generate_synthetic.py`); a real job-postings source adapter
      comes later
- [x] Write Python ETL script (`scripts/etl/clean_and_load.py`): validate, clean,
      normalize skills, load to Postgres
- [x] Data-quality cleaning with a rejects report (`rejects.csv`, `summary.json`)
- [x] Idempotent upsert load (reruns with the same input change nothing)
- [x] Write pytest tests for the ETL script (bad rows, missing fields, duplicate skills)

**Demonstrable output:** a populated database and a tested, reusable ingestion script.

## Phase 2 — Backend API
- [x] Add the Maven `pom.xml` (Spring Boot 3.5, Java 17, `flyway-core` +
      `flyway-database-postgresql`, Maven wrapper) — the first Phase 2 step
- [x] Scaffold Spring Boot project (Java 17, Spring Data JPA, Flyway at startup in dev,
      `ddl-auto=validate`, Actuator health, `prod` profile)
- [x] Implement entities and repositories matching the schema
- [x] Implement `GET /candidates`, `GET /candidates/{id}` (plus `POST`/`PUT`/`DELETE`)
- [x] Implement `GET /jobs`, `GET /jobs/{id}` (plus `POST`/`PUT`/`DELETE`)
- [x] Implement `GET/POST /skills` (case-insensitive names, never auto-created)
- [x] Implement the skill-overlap match scoring logic (no AI yet): pure `ScoringEngine`,
      configurable weights, deterministic breakdown + one-line summary per match
- [x] Implement `GET /jobs/{id}/matches` (score only, `aiExplanation: null`): cached in
      `job_match`, staleness via V2 link-touch triggers, per-job advisory lock, explicit
      "not matchable" state for jobs with no skills
- [x] Implement `POST /matches/recompute` (202 + pollable run resource)
- [x] Consistent error format with codes, request ids and field errors
- [x] Write JUnit tests for the match scoring logic
- [x] Write JUnit integration tests for the endpoints (Testcontainers, PostgreSQL 16)

**Demonstrable output:** a working, tested REST API returning real match scores.

## Phase 3 — AI Explanation Layer
- [x] Add LangChain4j and run Ollama locally: langchain4j-ollama core module + own
      `@Configuration` (the Spring Boot starters are beta-only); default model
      `qwen2.5:7b-instruct`
- [x] Define the AI Explanation Service as a LangChain4j `AiServices` interface
      (`ExplanationAssistant`: Java-built prompt in, typed `MatchExplanation` out)
- [x] Wire it into the match endpoint, with graceful fallback if the call fails
      (deterministic template explanation, request budget + PENDING, single-flight,
      failure backoff, circuit breaker, regenerate rate limit)
- [x] Persist explanations in the `job_match` table so they aren't regenerated needlessly
      (V3: payload, prompt hash for staleness, model, generated-at; guarded write)
- [x] Write tests using a mocked `ChatModel` (no real model calls in CI): `FakeChatModel`
      drives the AI integration tests; a stub HTTP server covers the provider wire tests
- [x] OpenAI/Claude optional profiles (core modules `langchain4j-open-ai` /
      `langchain4j-anthropic`), so the provider can be swapped via config without code
      changes (only enable for a final polished demo, since hosted providers are paid)

**Demonstrable output:** matches that explain themselves in plain English.

## Direction change (2026-10-05): job seeker first

The owner will use TalentMatch to **find and apply to jobs for themselves**: upload one
complete CV, get the newest matching openings as soon as they go live, and have the AI
draft a tailored CV and cover letter for each one, reviewed by the owner before anything is
sent. Phases 1–3 carry over unchanged (scoring, caching, explanations and fallbacks work in
either direction). The recruiter features stay but are no longer the priority. Going public
for other users is a possible later step; multi-user concerns are out of scope until then.

Principles for everything below:
- **Speed to apply:** the goal is to be among the first applicants, so new postings must be
  detected, scored and announced within minutes.
- **Never invent:** tailored documents may select, reorder and rephrase what is in the
  master profile, but never add experience, skills, certificates or numbers that aren't
  there (same rule as "AI explains, never scores").
- **Human in the loop:** the AI drafts and the owner reviews and submits. Full automation
  is a later, separate decision (see "Later").

## Phase 4 — Your master profile (resume upload)
- [ ] `POST /api/profile/resume`: upload a **PDF** (DOCX later, see "Later"); keep the
      original file; list (`GET /api/profile/resumes`) and delete uploads
- [ ] Extract the text with **Apache PDFBox** (bounded memory and time; scanned PDFs are
      rejected, no OCR) and have the AI turn it into a structured profile in the background:
      contact, summary, experience (roles, dates, technologies, achievements), projects,
      skills (with years only where the CV states them), certifications, education, languages
- [ ] Never invent: a hand-built response schema lets the model answer `null`; names not
      found in the CV become warnings for the owner; years not stated next to the skill are
      dropped
- [ ] Owner reviews and edits the extracted profile before it is saved (nothing is
      trusted until confirmed); skills map onto the `skill` table, new ones only after
      confirmation
- [ ] Store it as the owner's candidate plus profile tables (V4 migration), so the
      existing scoring works against real jobs
- [ ] Tests: extraction with a fake `ChatModel`, malformed and scanned-PDF files, edits

**Demonstrable output:** your full CV as a structured, editable profile in the database.

## Phase 5 — Fresh job feed (be first to apply)
- [ ] Source adapters for **company applicant-tracking boards** with public JSON APIs
      (Greenhouse, Lever, Ashby, …) for a watchlist of companies: postings appear there
      first. Plus one **aggregator API** for breadth, picked for the owner's region. No
      scraping of sites whose terms forbid it.
- [ ] Poll on a schedule (every few minutes per source, within each API's limits, using
      conditional requests where supported); dedup across sources; record
      `first_seen_at`, `posted_at`, closed postings
- [ ] Extract skills from each posting's description (real postings have no skill
      list): AI-suggested skills validated against the `skill` table (see
      PRODUCTION_READINESS "Jobs with no skills")
- [ ] Score every new posting against the master profile as it arrives
- [ ] **Notify** the owner within minutes when a new posting scores above a threshold
      (channel to be chosen: e.g. email, Telegram, ntfy push)
- [ ] Decide where polling runs (in the Spring app vs. the Python ETL) at phase start
- [ ] **Job preferences** (V5): target job titles, regions (South Africa + remote), seniority,
      salary floor, work permits, notice period. Entered by hand by the owner and never
      AI-extracted from the CV; stored separately from the CV profile and used to filter the
      feed. The watchlist is refreshed when the confirmed profile `version` changes (Phase 4)

**Demonstrable output:** a phone notification minutes after a matching job goes live.

## Phase 6 — Jobs for me
- [ ] `GET /api/candidates/{id}/matches`: rank jobs for a candidate (the reverse of
      Phase 2), filters for posted-since, location/remote, minimum score, and a
      newest-first sort
- [ ] Explanations written for the job seeker: why you fit and what you're missing

**Demonstrable output:** a ranked, explained list of the newest jobs that fit you.

## Phase 7 — Tailored CV and cover letter
- [ ] For one job, generate a tailored CV (pick, order and rephrase entries from the
      master profile to match the posting) and a cover letter
- [ ] Grounding validator: every role, project, skill, certificate and number in the
      output must exist in the master profile; anything else is rejected or flagged
- [ ] Export DOCX and PDF; keep every version; owner edits and approves
- [ ] Application tracker: status (new → shortlisted → applied → interview →
      offer/rejected), applied date, notes, and which CV/cover-letter version was sent

**Demonstrable output:** from notification to a reviewed, tailored application in minutes.

## Phase 8 — Frontend
- [ ] Scaffold a React app
- [ ] Job feed (newest matches first) with score, explanation and "generate application"
- [ ] Review screen for the tailored CV and cover letter (edit, approve, download)
- [ ] Application tracker board; master-profile editor
- [ ] Basic loading and error states (including `PENDING` explanations)

**Demonstrable output:** a usable personal job-hunting dashboard.

## Phase 9 — Containerization & always-on deployment
- [ ] Dockerfiles for the backend and frontend; `docker-compose.yml` with Postgres
- [ ] Deploy somewhere **always on** (a laptop that sleeps misses new postings): a small
      VPS or AWS; managed Postgres with backups
- [x] GitHub Actions CI runs ETL and backend tests (incl. Testcontainers) on every push
- [ ] Deployment pipeline and a CI badge in the README

**Demonstrable output:** a live service that watches for jobs 24/7.

## Phase 10 — Polish
- [ ] Architecture/data-model diagrams as images in the README
- [ ] Short demo walkthrough (video or GIF)
- [ ] "What I built and why", including the AI-fallback, persisted-match and
      never-invent decisions

## Later (separate decisions)
- **DOCX CVs:** Phase 4 accepts PDF only (PDFBox). DOCX would need another parser (e.g.
  Apache POI, or Tika) with the same size, time and memory bounds; until then, export the CV
  as PDF.
- **Full automation:** submit applications automatically only where the platform allows
  it, and only after the drafting quality has proven itself under review.
- **Public, multi-user version:** auth, per-user data isolation, quotas
  (PRODUCTION_READINESS section 6).

## Minimum Viable Version

For the job-seeker goal, the smallest useful version is **Phases 4, 5 and 7**: your
profile, a fresh job feed with notifications, and tailored drafts. A UI (Phase 8) and
always-on hosting (Phase 9) make it pleasant and reliable, but the API can be used
directly until then.
