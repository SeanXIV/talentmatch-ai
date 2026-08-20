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
- [ ] Set up PostgreSQL (local via Docker)
- [ ] Write schema migration scripts (tables from `DATA_MODEL.md`)
- [ ] Source a public synthetic candidates/jobs dataset
- [ ] Write Python ETL script: validate, clean, normalize skills, load to Postgres
- [ ] Write pytest tests for the ETL script (bad rows, missing fields, duplicate skills)

**Demonstrable output:** a populated database and a tested, reusable ingestion script.

## Phase 2 — Backend API
- [ ] Scaffold Spring Boot project (Java 17, Spring Data JPA)
- [ ] Implement entities and repositories matching the schema
- [ ] Implement `GET /candidates`, `GET /candidates/{id}`
- [ ] Implement `GET /jobs`, `GET /jobs/{id}`
- [ ] Implement the skill-overlap match scoring logic (no AI yet)
- [ ] Implement `GET /jobs/{id}/matches` (score only, `aiExplanation: null`)
- [ ] Write JUnit tests for the match scoring logic
- [ ] Write JUnit integration tests for the endpoints (e.g. Testcontainers or an in-memory DB)

**Demonstrable output:** a working, tested REST API returning real match scores.

## Phase 3 — AI Explanation Layer
- [ ] Add LangChain4j and the Ollama Spring Boot starter; run Ollama locally
- [ ] Define the AI Explanation Service as a LangChain4j `AiServices` interface
      (candidate + job in, typed explanation object out)
- [ ] Wire it into the match endpoint, with graceful fallback if the call fails
- [ ] Persist explanations in the `match` table so they aren't regenerated needlessly
- [ ] Write tests using a mocked `ChatModel` (no real model calls in CI)
- [ ] Add the OpenAI/Claude Spring Boot starter as an optional profile, so the
      provider can be swapped via config without code changes (only enable for
      a final polished demo, since hosted providers are paid)

**Demonstrable output:** matches that explain themselves in plain English.

## Phase 4 — Frontend
- [ ] Scaffold a React app
- [ ] Build a jobs list view
- [ ] Build a job detail view showing ranked candidate matches with explanations
- [ ] Build a candidates list/detail view
- [ ] Basic loading and error states

**Demonstrable output:** a usable dashboard, screenshots for the README.

## Phase 5 — Containerization & Deployment
- [ ] Dockerfile for the backend
- [ ] Dockerfile for the frontend
- [ ] `docker-compose.yml` running Postgres + backend + frontend together locally
- [ ] Deploy to AWS (RDS for Postgres, EC2 or Elastic Beanstalk for the app)
- [ ] Add a basic GitHub Actions workflow: run backend and ETL tests on every push

**Demonstrable output:** a live URL, and a CI badge in the README.

## Phase 6 — Polish
- [ ] Add architecture/data-model diagrams as images to the README (already
      drafted in Phase 0 — just needs final review once the real system exists)
- [ ] Record a short demo walkthrough (video or GIF)
- [ ] Write the "what I built and why" section, including the AI-failure
      fallback decision and the persisted-match design decision as concrete
      talking points

**Demonstrable output:** a portfolio-ready repository.

## Minimum Viable Version

If time is limited, **Phases 1 and 2** alone already produce a genuinely
resume-worthy result: a cleaned dataset, a real database, a tested Spring Boot
API doing real matching logic. Everything after that adds breadth, not
credibility — do them in order, but don't feel behind if you stop after Phase 2
for a while before continuing.
