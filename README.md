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
template fallback so every match always has an explanation. The project is now aimed at
personal job hunting (see [`ROADMAP.md`](./ROADMAP.md)). Phase 4 (in progress): upload your
CV as a PDF, let the AI turn it into a draft profile, review it and save it as your master
profile, which is then matched against jobs like any candidate.

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
`DB_PASSWORD` (talentmatch), plus `DB_POOL_SIZE`, `SERVER_PORT` and `SERVER_ADDRESS`. The API
listens on `127.0.0.1` only by default, because it has no authentication and `/api/profile`
holds your CV; set `SERVER_ADDRESS=0.0.0.0` only on a network you trust. If you set `DB_PORT=5433`
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

**Slow (CPU-only) machines:** the defaults (`call-timeout` 60s, 2 parallel calls, top 5)
assume a GPU or a fast CPU. Without a GPU, `qwen2.5:7b-instruct` can write as slowly as ~1.7
tokens/s (measured on a 4-core, 8 GB WSL2 box), so one explanation (~500 tokens in, ~80 out)
takes about 2 minutes. Every call then times out, the `ai` circuit opens, and you only ever
see template explanations. Nothing is broken, the model is just too slow for the defaults.
Check your speed with `ollama run qwen2.5:7b-instruct --verbose "hi"` (`eval rate`), then
either give the model more time:

```bash
TALENTMATCH_AI_CALL_TIMEOUT=300s TALENTMATCH_AI_MAX_CONCURRENCY=1 TALENTMATCH_AI_TOP_N=2 \
    ./mvnw spring-boot:run
```

or use a smaller model (faster, somewhat plainer wording):

```bash
ollama pull qwen2.5:1.5b-instruct
OLLAMA_MODEL=qwen2.5:1.5b-instruct TALENTMATCH_AI_CALL_TIMEOUT=120s ./mvnw spring-boot:run
```

Either way the first request returns `PENDING`. Explanations finish in the background and
are stored, so later requests return them as `READY` straight away.

**Reading a CV on a slow machine** takes much longer than an explanation, because the model
copies your whole CV into JSON. At ~1.7 tokens/s, a 2–3 page CV (about 1.3–2.2k tokens in and
2–3.5k tokens out) takes **20–40 minutes**: a few minutes of prompt evaluation, then the
writing. The extraction timeout is 60 minutes
(`TALENTMATCH_PROFILE_EXTRACTION_CALL_TIMEOUT`, at most `2h`). To estimate your time, run
`ollama run qwen2.5:7b-instruct --verbose "hi"` and read the two rates it prints:
`prompt eval rate` (tokens/s for reading the input) and `eval rate` (tokens/s for writing).
Roughly: minutes ≈ (input tokens ÷ prompt eval rate + output tokens ÷ eval rate) ÷ 60. Keep
the computer **awake** (no sleep or hibernate) until the CV shows `SUCCEEDED`; if the app stops
meanwhile, the CV is read again from the start on the next start. While a CV is being read,
match explanations show templates (`reason: "AI_BUSY"`).

**WSL note:** Ollama installed on Windows listens on Windows' `127.0.0.1`. From WSL2, either
enable mirrored networking (`networkingMode=mirrored` under `[wsl2]` in
`%UserProfile%\.wslconfig`, then `wsl --shutdown`), or set `OLLAMA_HOST=0.0.0.0` on Windows
and `OLLAMA_BASE_URL=http://<windows-host-ip>:11434` in WSL. Alternatively run Ollama inside
WSL or via Docker Desktop (the Docker command above).

### Quick start (your profile from a CV)

With the API running (and Ollama, as above), upload your CV as a PDF (text-based, at most
5 MB; a scanned image won't work):

```bash
curl -i -F "file=@/path/to/cv.pdf" localhost:8080/api/profile/resume
# 202 Accepted, Location: /api/profile/resume/<resume-id>
```

The AI reads it in the background. Poll until `status` is `SUCCEEDED` (or `FAILED`, whose
`message` says what to do):

```bash
curl -s localhost:8080/api/profile/resume/<resume-id> | jq '{status, message, warnings}'
```

Check the `draft` and each `warnings` entry (things the AI returned that aren't in your CV, or
years it removed). Then save the draft, edited as needed, as your master profile:

```bash
curl -s localhost:8080/api/profile/resume/<resume-id> \
  | jq '{resumeId: .id, createMissingSkills: true, profile: .draft}' > profile.json
# edit profile.json, then:
curl -s -X PUT -H 'Content-Type: application/json' -d @profile.json localhost:8080/api/profile
```

The response's `candidateId` is you as a candidate: your matches appear in
`GET /api/jobs/<job-id>/matches`. `GET /api/profile` shows the saved profile,
`GET /api/profile/resumes` lists your uploads and `DELETE /api/profile/resume/<id>` removes one.
With `createMissingSkills: false` (the default), skills that aren't in the skill table are
reported as errors instead of created. Without AI (`AI_ENABLED=false`) the upload ends as
`FAILED/AI_DISABLED`; write `profile.json` by hand and `PUT` it the same way.

Your CV stays on your machine with the default Ollama provider. With the `claude` or
`openai` profile the upload ends as `FAILED/REMOTE_EXTRACTION_DISABLED` unless you opt in with
`TALENTMATCH_PROFILE_ALLOW_REMOTE_EXTRACTION=true`; then its whole text is sent to that
provider. Each save creates a new profile `version` (earlier ones are kept as history).

_(Full-stack `docker-compose up` instructions will be added once the backend and
frontend land.)_

## Data Source

Uses a seeded, synthetic dataset generated with Faker
(`scripts/etl/generate_synthetic.py`) — no real personal or client data is used
anywhere in this project. A real public job-postings source can be added later as
another ETL source adapter.

## License

MIT
