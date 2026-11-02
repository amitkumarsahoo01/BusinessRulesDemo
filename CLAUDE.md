# DMN Rules Platform with Embedded AI — Project Memory

This file is loaded automatically by Claude Code. Read `docs/ARCHITECTURE.md` (full design) and
`docs/BUILD_PLAN.md` (demo scope, milestones, acceptance criteria) before writing code.

## What we are building
A demo-ready rules platform:
- **Decision service** — Spring Boot 3.5 / Java 21, Apache KIE 10.2 DMN engine (Kogito family).
  Evaluates DMN models, records every evaluation as an audit record + ordered execution trace in PostgreSQL.
- **AI service** — Spring Boot 3.5, Spring AI 1.1.x (NOT 2.x — that needs Boot 4). Provider-agnostic
  (Anthropic / OpenAI / Ollama via config). Answers questions about rules by calling read-only tools.
- **Frontend** — Angular (latest stable), standalone components + signals. Three areas:
  Rule builder, Ask AI chat, Audit explorer.
- **PostgreSQL 16 + pgvector**, one `docker-compose.yml` runs everything.

## Non-negotiable design rules
1. The LLM never makes decisions. Decisions only come from the DMN engine. AI explains via tools.
2. Audit tables are append-only. App DB role gets INSERT/SELECT only on audit tables.
3. Published model versions are immutable (store XML + SHA-256). Edits create a new draft version.
4. Models load dynamically from PostgreSQL at runtime (`DMNRuntimeBuilder` from `kie-dmn-core`)
   so UI-authored rules go live without a rebuild. Do not rely on Kogito build-time codegen for these.
5. Execution traces are captured with a `DMNRuntimeEventListener`
   (before/after decision, `afterEvaluateDecisionTable` for matched rows, messages). No Kafka.
6. AI tool calls are read-only, except `proposeChange`, which only creates a draft.
7. Every AI answer cites model version / decision / row numbers / execution IDs.
8. No secrets in code. All provider keys come from env vars.

## Layout
```
/decision-service   Spring Boot: DMN engine, model registry, rules mgmt API, audit API, Flyway
/ai-service         Spring Boot: Spring AI ChatClient, tools, SSE chat endpoint, pgvector retrieval
/frontend           Angular app (served by nginx in prod, proxy to /api)
/deploy             docker-compose.yml, Caddyfile, .env.example
/docs               ARCHITECTURE.md, BUILD_PLAN.md
/seed               sample DMN models + seed data generator
```

## Commands (keep these working)
- `docker compose -f deploy/docker-compose.yml up --build` — whole stack at http://localhost
- `./mvnw -pl decision-service test` and `./mvnw -pl ai-service test`
- `cd frontend && npm ci && npm start` — dev server with proxy to backend
- `cd frontend && npm test`

## Conventions
- Maven multi-module at repo root; Java 21; Lombok avoided, use records.
- Flyway migrations in `decision-service/src/main/resources/db/migration` (V1__..., V2__...).
- REST under `/api/...`; OpenAPI via springdoc; JSON errors with `problem+json`.
- Frontend: feature folders `features/rules`, `features/chat`, `features/audit`; no NgModules.
- Write tests alongside each feature; a milestone is done only when its acceptance checks pass.
- After each milestone: update the checklist in `docs/BUILD_PLAN.md` and commit.
