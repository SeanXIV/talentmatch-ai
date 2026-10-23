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
- Master profile from your CV (PDF → AI draft → reviewed profile), matched like any candidate
- Job feed: watches company job boards (Greenhouse, Lever, Ashby) and Adzuna searches, scores
  new postings against your profile, filters them by your preferences and emails you the good
  ones within minutes
- Tailored applications: a CV and cover letter drafted for one job from your profile, checked
  so nothing is invented, edited and approved by you, exported as PDF or DOCX, plus an
  application tracker (status, applied date, notes, versions sent)
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
personal job hunting (see [`ROADMAP.md`](./ROADMAP.md)). Phase 4 is complete: upload your
CV as a PDF, let the AI turn it into a draft profile, review it and save it as your master
profile, which is then matched against jobs like any candidate. Phase 5 (job feed) is
implemented and being hardened: the app polls the company boards and Adzuna searches you
watch, finds skills in each new posting (dictionary first, AI later), checks it against your
preferences, scores it against your profile and emails you when it scores above your
threshold. Phase 6 (jobs for me) is implemented: `GET /api/profile/matches` ranks the jobs
for your profile, with filters for date, country/remote, location and minimum score, a
newest-first sort, and an explanation written to you (why you fit, what you're missing).
Phase 7 (tailored applications) is implemented: track a job, let the AI draft a tailored CV and
cover letter from your profile in the background (every fact copied from your profile, every
rewritten claim checked), edit and approve versions, download them as PDF or DOCX, and record
when you applied and which versions you sent.

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

### Quick start (job feed and email alerts)

With the API running and your profile saved (above), add the companies to watch. Use the board
name from the company's careers URL (`boards.greenhouse.io/<token>`, `jobs.lever.co/<site>`,
`jobs.ashbyhq.com/<name>`); the board is checked before it is saved:

```bash
curl -s -X POST -H 'Content-Type: application/json' localhost:8080/api/feed/sources \
  -d '{"kind": "GREENHOUSE", "boardToken": "acme"}'
curl -s -X POST -H 'Content-Type: application/json' localhost:8080/api/feed/sources \
  -d '{"kind": "LEVER", "boardToken": "acme", "companyName": "Acme"}'
```

Don't know the board name? Look the company up: a few likely board names are checked on
Greenhouse, Lever and Ashby (about 10 s; nothing is added). Add what it `found` with the call
above, after checking it is the right company. Budget, spacing and guess count are set by
`talentmatch.feed.lookup.budget|min-interval|max-candidates` (defaults `10s`, `10s`, `3`):

```bash
curl -s -X POST -H 'Content-Type: application/json' localhost:8080/api/feed/sources/lookup \
  -d '{"companyName": "Acme Payments"}'
```

The first poll of a board records what is already open (the baseline); only postings that
appear after it (or were published in the last 24 h) count as new. Save your preferences so the
feed is filtered (all fields optional; see [`API_SPEC.md`](./API_SPEC.md), "Preferences"):

```bash
curl -s -X PUT -H 'Content-Type: application/json' localhost:8080/api/preferences \
  -d '{"preferences": {"targetTitles": ["Backend Engineer"], "regions": {"countries": ["ZA"]}}}'
```

Email alerts need an SMTP server. Set these before starting the app (Gmail shown; it needs an
[app password](https://myaccount.google.com/apppasswords) on an account with 2-step
verification, and `NOTIFY_EMAIL_FROM` must be that account's address):

```bash
export SMTP_HOST=smtp.gmail.com SMTP_PORT=587 SMTP_SECURITY=STARTTLS
export SMTP_USERNAME=you@gmail.com SMTP_PASSWORD='<app password>'
export NOTIFY_EMAIL_FROM=you@gmail.com NOTIFY_EMAIL_TO=you@gmail.com
./mvnw spring-boot:run
```

Send a test email, then turn notifications on (they are off until you do):

```bash
curl -s -X POST localhost:8080/api/notifications/test
curl -s -X PUT -H 'Content-Type: application/json' localhost:8080/api/notifications/settings \
  -d '{"enabled": true, "minScore": 0.6, "maxPerHour": 20}'
```

For local checks without a real mailbox, run [Mailpit](https://mailpit.axllent.org)
(`docker run -d -p 1025:1025 -p 8025:8025 axllent/mailpit`), set `SMTP_HOST=127.0.0.1
SMTP_PORT=1025 SMTP_SECURITY=NONE` (no username or password) and open http://localhost:8025.

Adzuna searches (South Africa) need free API keys from https://developer.adzuna.com: set
`ADZUNA_APP_ID` and `ADZUNA_APP_KEY`. Searches are then derived from your preferences' target
titles, and you can add your own with `{"kind": "ADZUNA", "options": {"what": "java developer"}}`.

Check how it is doing with `curl -s localhost:8080/api/feed/status` (sources, backlog, email
state and the median time from posting to email) and `GET /api/feed/jobs` for the jobs found.
`FEED_ENABLED=false` switches polling off. **The laptop must be awake to poll**: nothing is
checked while it sleeps.

### Quick start (jobs for me)

With your profile saved, list the newest jobs from the last two days that fit you at least 60%,
each with an explanation written to you:

```bash
curl -s 'localhost:8080/api/profile/matches?sort=newest&postedSince=P2D&minScore=0.6' \
  | jq '.matches[] | {rank, title, company, listedAt, scorePercent, explanationStatus, text: .explanation.text}'
```

Add `&country=ZA` to keep jobs in South Africa plus remote jobs (a job with no location data
stays in, flagged `LOCATION_UNKNOWN`), `&remote=only` for remote jobs only, or `&origin=feed` to
hide jobs you typed in yourself (and the ETL's sample jobs). The default sort is by score
(`sort=score`). The top 5 get an AI explanation (with Ollama on a CPU the first one can take
minutes: the item says `PENDING`, reload later); the rest get a template built from the skill
breakdown. Any candidate's list is at `GET /api/candidates/{id}/matches` with the same
parameters (without your preference verdicts). All parameters are in
[`API_SPEC.md`](./API_SPEC.md), "Jobs for a candidate".

### Quick start (tailored CV, cover letter and tracker)

With your profile saved and a job you want (its id from `/api/profile/matches`, a notification
email or `/api/feed/jobs`), track it and have the AI draft both documents:

```bash
curl -s -X POST -H 'Content-Type: application/json' localhost:8080/api/applications \
  -d '{"jobId": "<job-id>", "generate": true}' | jq '{id, status, generation: .latestGeneration.id}'
# 201 Created; the generation is PENDING
```

Poll the generation until `status` is `SUCCEEDED` (or `FAILED`, whose `hint` says what to do).
The CV is stored first, so it can be read while the letter is still being written:

```bash
curl -s localhost:8080/api/applications/<app-id>/generations/<gen-id> | jq '{status, failureReason, hint, results}'
```

Read each document (`results[].documentId`) and its `findings`. `BLOCKING` findings (a claim in
the letter that isn't in your profile, or a profile saved since) must be read and acknowledged
before approval:

```bash
curl -s localhost:8080/api/applications/<app-id>/documents/<doc-id> | jq '{kind, version, findings, content}'
curl -s -X POST -H 'Content-Type: application/json' \
  localhost:8080/api/applications/<app-id>/documents/<doc-id>/approve -d '{"acknowledgeFindings": true}'
```

To change the wording first, send the edited `content` as a new version
(`POST /api/applications/<app-id>/documents` with `{"basedOn": "<doc-id>", "content": {…}}`) and
approve that one. Facts copied from your profile (titles, employers, dates, …) can't be edited
there: change your profile and generate again (`POST /api/applications/<app-id>/generations`).
Approve the cover letter the same way. Download the approved files (the CV shown; the same for
the cover letter's id), then record that you applied (the date defaults to today and the
sent versions to the newest approved ones):

```bash
curl -s -o cv.pdf 'localhost:8080/api/applications/<app-id>/documents/<cv-doc-id>/file?format=pdf'
curl -s -o cv.docx 'localhost:8080/api/applications/<app-id>/documents/<cv-doc-id>/file?format=docx'
curl -s -X PUT -H 'Content-Type: application/json' localhost:8080/api/applications/<app-id> \
  -d '{"status": "APPLIED"}' | jq '{status, appliedOn, sentCv, sentCoverLetter}'
```

Downloading a version before approving it gives a draft with a "DRAFT – not approved in
TalentMatch" footer and a `-draft` file name. `GET /api/applications?status=APPLIED,INTERVIEW`
lists your applications; later moves are `INTERVIEW`, `OFFER`, `REJECTED` or `WITHDRAWN`
(`force: true` corrects a mistake). Everything is in [`API_SPEC.md`](./API_SPEC.md),
"Applications".

**Timing:** with Ollama on a CPU-only machine both documents take **20–30 minutes** (CV about
15–20, letter about 5–10; `TALENTMATCH_APPLICATIONS_GENERATION_CALL_TIMEOUT`, default `45m` per
document, at most `2h`). Keep the computer awake; an interrupted generation re-runs on the next
start and keeps a document it already finished. While it runs, match explanations show templates
(`AI_BUSY`). With a GPU or a hosted provider it takes minutes. `kinds: ["CV"]` generates only one
document.

**Your profile stays on your machine** with the default Ollama provider. With the `claude` or
`openai` profile the generation ends as `FAILED/REMOTE_GENERATION_DISABLED` unless you opt in with
`TALENTMATCH_APPLICATIONS_ALLOW_REMOTE_GENERATION=true`; then your profile (roles, employers,
highlights, skills, education; never your name, contact details or links) and the posting are
sent to that provider. This is separate from the CV-reading opt-in.

_(Full-stack `docker-compose up` instructions will be added once the backend and
frontend land.)_

## Data Source

Uses a seeded, synthetic dataset generated with Faker
(`scripts/etl/generate_synthetic.py`) — no real personal or client data is used
anywhere in this project. Real postings come only from the job feed (Phase 5), which reads
official job-board and Adzuna APIs for the companies and searches you choose.

## License

MIT
