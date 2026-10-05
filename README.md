# TalentMatch AI

A full-stack candidate–job matching platform with AI-generated match explanations.

Built as a portfolio project to demonstrate end-to-end software engineering across
backend APIs, data pipelines, relational databases, a React frontend, cloud deployment,
and applied AI — the same shape of problem solved in production CRM/recruitment tooling,
rebuilt from scratch with a generic, synthetic dataset.

## Problem Statement

Matching candidates to job openings by hand is slow and inconsistent. This project
ingests candidate and job data, scores how well a candidate fits a role based on
skill overlap, and uses an LLM to explain *why* a match was suggested in plain
English — turning a scoring number into something a recruiter can actually act on.

## Features

- Clean, validated candidate and job data loaded from raw CSV into PostgreSQL
- REST API for candidates, jobs, and match results (Spring Boot)
- Rule-based skill-overlap matching engine
- AI-generated natural-language explanation for each match, via LangChain4j
  (Ollama locally by default, swappable to a hosted API)
- React dashboard to browse candidates, jobs, and match results
- Fully containerized (Docker) and deployed to AWS
- Automated tests (JUnit, pytest) and CI on every push (GitHub Actions)

## Tech Stack

| Layer | Technology |
|---|---|
| Backend API | Java 17, Spring Boot 3, Spring Data JPA |
| Data pipeline | Python 3, pandas |
| Database | PostgreSQL |
| AI integration | LangChain4j, running against Ollama (local, free) by default — swappable to OpenAI/Claude via config |
| Frontend | React, JavaScript |
| Infrastructure | Docker, AWS (EC2/Elastic Beanstalk, RDS, S3), GitHub Actions |
| Testing | JUnit 5, pytest |

## Documentation

- [`ARCHITECTURE.md`](./ARCHITECTURE.md) — system architecture and component diagram
- [`DATA_MODEL.md`](./DATA_MODEL.md) — database schema and entity-relationship diagram
- [`API_SPEC.md`](./API_SPEC.md) — REST API specification
- [`ROADMAP.md`](./ROADMAP.md) — phased build plan with milestones
- [`PRODUCTION_READINESS.md`](./PRODUCTION_READINESS.md) — dev/MVP shortcuts that must change before production

## Project Status

🚧 Phase 1 (data layer) is complete: PostgreSQL schema via Flyway, plus a Python ETL
that generates, cleans, and loads data. Phase 2 (Spring Boot API) is implemented:
candidates, jobs and skills CRUD, cached skill-overlap match scoring with a deterministic
breakdown, batch recompute, and a consistent error format. Phase 3 (AI explanations) is
implemented: LangChain4j explanations for the top matches (local Ollama by default,
OpenAI/Claude via profiles), persisted with prompt-hash staleness, with a deterministic
template fallback so every match always has an explanation. Phase 4 (frontend) is next.
See [`ROADMAP.md`](./ROADMAP.md) for details.

## Getting Started

### Quick start (data layer)

Requires Python >= 3.10 and Docker. Run from the repository root:

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r scripts/etl/requirements.txt
bash scripts/start_db.sh                       # Postgres 16 + Flyway migrations

python scripts/etl/generate_synthetic.py --seed 42 --candidates 200 --jobs 50 \
    --dirty-ratio 0.1 --out scripts/etl/data/raw
python scripts/etl/clean_and_load.py --input scripts/etl/data/raw \
    --reports scripts/etl/data/reports
```

Run the tests:

```bash
pip install -r scripts/etl/requirements-dev.txt
python -m pytest tests/etl
bash tests/db/test_schema.sh
```

**WSL note:** enable Docker Desktop's WSL integration for your distro so `docker`
works inside WSL, or run the schema test against the Windows CLI with
`DOCKER=docker.exe bash tests/db/test_schema.sh`.

See [`scripts/etl/README.md`](./scripts/etl/README.md) for ETL options and outputs.

### Quick start (backend API)

Requires Java 17 (no Maven install needed: use the wrapper). With the database from the
data-layer quick start running:

```bash
bash scripts/start_db.sh          # if not already running
./mvnw spring-boot:run            # applies pending Flyway migrations (e.g. V2), starts on :8080

curl localhost:8080/api/jobs
curl "localhost:8080/api/jobs/<job-id>/matches?limit=5"
curl localhost:8080/actuator/health
```

Connection settings come from environment variables with local-dev defaults:
`DB_HOST` (localhost), `DB_PORT` (5432), `DB_NAME` (talentmatch), `DB_USER` (talentmatch),
`DB_PASSWORD` (talentmatch), plus `DB_POOL_SIZE` and `SERVER_PORT`. If you set `DB_PORT=5433`
in `scripts/.env`, export it for the API too (`DB_PORT=5433 ./mvnw spring-boot:run`). If
the database is unreachable, startup fails with a message telling you how to fix it.
The `prod` profile (`SPRING_PROFILES_ACTIVE=prod`) has no defaults, requires SSL and does
not run migrations.

Run the backend tests (unit + integration):

```bash
./mvnw verify
```

Integration tests use Testcontainers (PostgreSQL 16), so **Docker must be running**; they
fail, rather than skip, without it. **WSL note:** Testcontainers needs the Docker daemon
reachable from inside WSL, so enable Docker Desktop's WSL integration for your distro
(`DOCKER=docker.exe` is not enough for Testcontainers).

See [`API_SPEC.md`](./API_SPEC.md) for every endpoint, parameter and error code.

### Quick start (AI explanations)

Match explanations come from a local [Ollama](https://ollama.com) model by default (free, no
API key). Get the model once, either natively:

```bash
ollama pull qwen2.5:7b-instruct
```

or with Docker (add `--gpus=all` after `-d` for NVIDIA GPUs):

```bash
docker run -d --name talentmatch-ollama -p 11434:11434 -v talentmatch-ollama:/root/.ollama ollama/ollama
docker exec talentmatch-ollama ollama pull qwen2.5:7b-instruct
```

Then start the API as usual:

```bash
./mvnw spring-boot:run
curl "localhost:8080/api/jobs/<job-id>/matches?limit=5"
```

The top 5 matches get AI explanations (`explanationStatus: "READY"`, `explanation.source:
"AI"`); the rest, and any match whose AI explanation is unavailable, get a template
explanation built from the skill breakdown. The first request for a job may return
`PENDING` while the model is still writing: reload after a few seconds. The app starts and
serves matches **without** Ollama too (template explanations, `ai` health component
`DEGRADED`).

| Variant | How |
|---|---|
| Low-RAM machine | `ollama pull qwen2.5:3b-instruct`, then `OLLAMA_MODEL=qwen2.5:3b-instruct ./mvnw spring-boot:run` |
| Ollama elsewhere | `OLLAMA_BASE_URL=http://host:11434 ./mvnw spring-boot:run` |
| No AI at all | `AI_ENABLED=false ./mvnw spring-boot:run` (template explanations only) |
| Hosted Claude (paid; demos only) | `ANTHROPIC_API_KEY=… SPRING_PROFILES_ACTIVE=claude ./mvnw spring-boot:run` |
| Hosted OpenAI (paid; demos only) | `OPENAI_API_KEY=… SPRING_PROFILES_ACTIVE=openai ./mvnw spring-boot:run` |

Other overrides: `CLAUDE_MODEL`, `ANTHROPIC_BASE_URL`, `OPENAI_MODEL`, `OPENAI_BASE_URL`.
Never commit API keys. `regenerate=true` forces fresh explanations for the top matches and is
limited to once per job per minute (`429` otherwise).

**WSL note:** Ollama installed on Windows listens on Windows' `127.0.0.1`. From WSL2, either
enable mirrored networking (`networkingMode=mirrored` under `[wsl2]` in
`%UserProfile%\.wslconfig`, then `wsl --shutdown`), or set `OLLAMA_HOST=0.0.0.0` on Windows
and `OLLAMA_BASE_URL=http://<windows-host-ip>:11434` in WSL. Alternatively run Ollama inside
WSL or via Docker Desktop (the Docker command above).

_(Full-stack `docker-compose up` instructions will be added once the backend and
frontend land.)_

## Data Source

Uses a seeded, synthetic dataset generated with Faker
(`scripts/etl/generate_synthetic.py`) — no real personal or client data is used
anywhere in this project. A real public job-postings source can be added later as
another ETL source adapter.

## License

MIT
