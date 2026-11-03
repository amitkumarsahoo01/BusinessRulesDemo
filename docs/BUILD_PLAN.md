# Demo Build Plan

Goal: a deployable, shareable demo of the DMN rules platform. One `docker compose up` on a VM,
reachable over HTTPS, with seeded data so the demo works the moment it starts.

## Demo scope (simplified from the full architecture)
| Area | Full design | Demo version |
| --- | --- | --- |
| Auth | OIDC (Keycloak/Entra/Okta) | Built-in login with 3 seeded demo users and roles (JWT issued by decision-service) |
| Services | Gateway + decision + rules mgmt + audit + AI | 2 Spring Boot apps (decision-service includes rules mgmt + audit) + nginx |
| Audit write | STRICT or ASYNC per model | STRICT only |
| Partitioning / hash chain | Monthly partitions, hash chain | Hash chain yes, partitions no |
| DMN editor | Guided table editor + embedded KIE DMN editor | Guided table editor required; embedded `@kie-tools/dmn-editor-standalone` as stretch |
| LLM | Provider router | One provider chosen by `AI_PROVIDER` env var |

Demo users (password `demo` for all, shown on login page):
- `analyst` — rule-author
- `approver` — rule-approver
- `auditor` — auditor (read-only)

## Seed content
- Model `loan-eligibility` (DMN 1.5/1.6): inputs Applicant(age, creditScore, income, employmentYears),
  Loan(amount, termMonths). Decisions: `Debt Ratio` (literal FEEL), `Risk Category`
  (decision table, UNIQUE hit policy), `Eligibility` (decision table, FIRST hit policy:
  APPROVED / REVIEW / DECLINED). Rule annotations explain each row's business reason.
- Versions: 1.0.0 (published), 1.1.0 (published, tightened credit threshold), 1.2.0 (draft).
- Model `insurance-premium` — second smaller example.
- ~300 seeded executions spread over the last 30 days across versions, with full traces.
- A short policy document per model, embedded into pgvector.

## Milestones (tick when acceptance checks pass)

### M1 — Skeleton and infra
- [x] Maven multi-module, Angular app, docker-compose with postgres (pgvector image), both services, nginx.
- [x] Health endpoints; compose brings everything up.
- Accept: `docker compose up` → http://localhost shows the Angular shell; `/api/health` OK.
  - Verified: `./mvnw test` passes for decision-service and ai-service; `npm run build` and `npm test` pass for frontend; `docker compose -f deploy/docker-compose.yml up --build` brings up postgres (pgvector, healthy), decision-service, ai-service and nginx/frontend — `curl http://localhost/` returns the Angular shell and `curl http://localhost/api/health` (proxied through nginx) returns `{"status":"UP","service":"decision-service"}`.

### M2 — Decision core + audit
- [x] Flyway schema: dmn_model, dmn_model_version, decision_execution (with row_hash),
      decision_trace_step, rule_change_audit, ai_interaction_audit, demo users.
- [x] Model registry: compile published versions from DB with `DMNRuntimeBuilder`; reload on publish (LISTEN/NOTIFY).
- [x] `DMNRuntimeEventListener` capturing ordered trace incl. matched decision-table rows.
- [x] `POST /api/decisions/{modelKey}/evaluate` (+ `?version=`), `POST .../simulate` (not audited).
- [x] `GET /api/executions` (filters, paging), `GET /api/executions/{id}`.
- [x] Seed loader + execution generator.
- Accept: evaluating loan-eligibility returns outputs + executionId; trace shows matched row numbers; integration test with Testcontainers.
  - Verified: `DecisionServiceIntegrationTest` (Testcontainers Postgres) — evaluate returns outputs + executionId, trace shows 1-indexed matched rows, GET /api/executions/{id} round-trips, simulate writes nothing, unknown model returns 404 problem+json, and a directly-inserted version + `pg_notify('model_published', ...)` hot-swaps into the registry within 15s with no restart. `DmnSeedFixturesTest` validates all 4 seed DMN files compile/evaluate correctly (incl. the 1.0.0→1.1.0 threshold-tightening behavior change). Also exercised live via `docker compose up --build`: evaluate through nginx returns correct outputs/trace. Scoping notes (insurance-premium + pgvector docs deferred to M5, auth is schema-only, simulate is published-version-only for now) are in docs/DECISIONS.md.

### M3 — Rule authoring lifecycle
- [ ] Draft CRUD from guided-table JSON ↔ DMN XML (round-trip tested).
- [ ] Validate (DMN validator: schema, FEEL compile, overlaps/gaps where available), run saved test cases.
- [ ] Approve (must be different user than author), publish (immutable version, SHA-256), rollback.
- [ ] Version diff endpoint; rule_change_audit on every action.
- Accept: analyst edits a row, approver approves, publish → next evaluation uses new version without restart.

### M4 — Angular UI
- [ ] Login, app shell with nav: Rules, Ask AI, Audit.
- [ ] Rules: model list; guided decision-table editor (typed cells, FEEL pickers, hit-policy dropdown, add/remove rows, inline validation errors); test bench with trace highlighting; diff + approve/publish.
- [ ] Audit: execution search; execution detail with ordered trace and matched rows highlighted; change history.
- [ ] Polished look suitable for a demo (consistent theme, empty states, loading states).
- Accept: full author → test → approve → publish → evaluate → inspect trace flow in the browser.

### M5 — AI service + chat
- [ ] Spring AI 1.1.x `ChatClient`, provider from env (`AI_PROVIDER=anthropic|openai|ollama`, `AI_MODEL`, key vars).
- [ ] Tools: listModels, getModelStructure, getDecisionTable, diffVersions, getExecution, queryExecutions, simulate, analyzeTable, proposeChange (draft only).
- [ ] pgvector retrieval over rule annotations, change notes, policy docs.
- [ ] `POST /api/ai/conversations/{id}/messages` streaming via SSE; log to ai_interaction_audit.
- [ ] Chat UI: streaming markdown, context chips (pin model/execution), clickable citations, "How I got this" tool panel, suggested questions.
- [ ] "Ask AI about this execution" button from Audit; "Describe a rule" box in builder → proposed rows as accept/reject diff.
- Accept: these demo questions answer correctly with citations:
  1. "Explain the loan-eligibility model in plain English."
  2. "Why was execution <id> declined?"
  3. "Would they be approved with a credit score of 700?" (uses simulate)
  4. "What changed between 1.0.0 and 1.1.0 and how many past cases would flip?"
  5. "Are there gaps or overlaps in Risk Category?"

### M6 — Deploy and share
- [ ] Production compose: Caddy reverse proxy with automatic HTTPS for `${DEMO_DOMAIN}`, frontend static via nginx, services internal only.
- [ ] `.env.example` documenting every variable; `deploy/README.md` with VM steps (any Linux VM with Docker, 4 vCPU / 8 GB RAM; open ports 80/443; point DNS A record).
- [ ] Demo reset: `RESET_DEMO_ON_START=true` reseeds data; nightly reset optional.
- [ ] Basic rate limit on AI endpoint to protect the API key on a public demo.
- Accept: fresh VM → clone → set `.env` → `docker compose up -d` → https://<domain> works end to end.

## Demo script (5 minutes)
1. Login as analyst → Rules → open loan-eligibility → show decision table and annotations.
2. Ask AI: "Explain this model" → plain-English answer with row citations.
3. Audit → open a DECLINED execution → trace highlights matched rows → "Ask AI about this execution".
4. Ask "Would they pass with credit score 700?" → simulate result.
5. Builder: "Describe a rule": "applicants under 21 need review" → accept proposed row → test bench.
6. Login as approver → approve + publish → evaluate again → new version in audit trail with change history.
