# TalentMatch AI

A full-stack candidate–job matching platform with AI-generated match explanations.

Built as a portfolio project to demonstrate end-to-end software engineering across
backend APIs, data pipelines, relational databases, a React frontend, cloud deployment,
and applied AI — the same shape of problem solved in production CRM/recruitment tooling,
rebuilt from scratch with a generic, public dataset.

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

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — system architecture and component diagram
- [`docs/DATA_MODEL.md`](docs/DATA_MODEL.md) — database schema and entity-relationship diagram
- [`docs/API_SPEC.md`](docs/API_SPEC.md) — REST API specification
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — phased build plan with milestones

## Project Status

🚧 In planning — architecture, data model, and API are specified before implementation
begins. See `docs/ROADMAP.md` for current phase.

## Getting Started

_(To be completed once the initial implementation lands — will include `docker-compose up`
instructions for running the full stack locally.)_

## Data Source

Uses a public, synthetic job-postings/resume dataset (e.g. from Kaggle) — no real
personal or client data is used anywhere in this project.

## License

MIT
