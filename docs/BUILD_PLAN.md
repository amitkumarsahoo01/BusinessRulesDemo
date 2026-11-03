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
- [x] Draft CRUD from guided-table JSON ↔ DMN XML (round-trip tested).
- [x] Validate (DMN validator: schema, FEEL compile, overlaps/gaps where available), run saved test cases.
- [x] Approve (must be different user than author), publish (immutable version, SHA-256), rollback.
- [x] Version diff endpoint; rule_change_audit on every action.
- Accept: analyst edits a row, approver approves, publish → next evaluation uses new version without restart.
  - Verified: `ModelLifecycleIntegrationTest` (Testcontainers Postgres) drives the full flow end to end through the real HTTP API — analyst edits a decision-table row via the guided-JSON endpoint, validates (DMNValidator: schema/model/compilation/decision-table analysis), saves and runs a regression test case (TESTED), a same-user approve attempt is rejected 409 (four-eyes), a different user approves and publishes, and the very next `evaluate` call (no restart) picks up the new version and reflects the edited rule. Also covers: version diff shows the changed rule, rollback republishes the prior content as a new version and evaluate reflects that immediately, and `/api/models` + `/api/models/{key}/history` show the resulting state/audit trail. `DmnXmlEditorTest` proves the guided-table JSON ↔ DMN XML round trip is lossless (unchanged rows re-serialize to equivalent behavior; an edited row changes the evaluated outcome). `DmnValidationServiceTest` confirms the analyser catches an introduced UNIQUE-hit-policy overlap.
  - Two more real API/library issues found and fixed this milestone (both logged in docs/DECISIONS.md): `DMNValidator.validateUsing(...).theseModels(Definitions...)` can't do schema validation (must use the `Reader`/`File` overload instead), and `pg_notify(...)` must run via `JdbcTemplate.queryForList`/`query`, not `.update()` (Postgres rejects a result-returning statement on `executeUpdate`).

### M4 — Angular UI
- [x] Login, app shell with nav: Rules, Ask AI, Audit.
- [x] Rules: model list; guided decision-table editor (typed cells, hit-policy dropdown, add/remove rows, inline validation errors); test bench with trace highlighting; diff + approve/publish.
- [x] Audit: execution search; execution detail with ordered trace and matched rows highlighted; change history.
- [x] Polished look suitable for a demo (consistent theme, empty states, loading states).
- Accept: full author → test → approve → publish → evaluate → inspect trace flow in the browser.
  - Verified with a real headless Chromium (Playwright) driving the live `docker compose` stack end to end, not just curl/unit tests: login as analyst → rules list (model cards with status badges) → open loan-eligibility → switch to the Eligibility decision table → widen the "under 21" rule to "under 25" → save draft (status flips to DRAFT) → Validate (passes, warnings shown) → save a regression test case and Run saved tests (TESTED, pass/fail with expected-vs-actual and a trace view showing 1-indexed matched rows) → sign out, sign in as approver → Approve → Publish (status PUBLISHED) → Audit search shows the real 320 seeded executions with filters/paging → open an execution and see its full trace with matched-row badges and the audit hash. Screenshots captured at every step confirmed the rendered UI, not just that network calls succeeded. The Angular unit suite (Vitest) also passes: `AuthService`, `TraceView`, `RulesList`, and the app shell's logged-in/logged-out states.
  - Backend got two small additions to support the UI: `POST /api/auth/login` (checks the seeded `app_user` table; frontend remembers the username and keeps sending it as `X-User`, no JWT yet — see docs/DECISIONS.md) and `GET /api/models/{key}/drafts/{id}/decisions[/{name}]` (lists a version's decisions / reads one table) so the editor doesn't need to hardcode decision names per model. `AdHocEvaluator` now also captures a trace (reusing M2's `TraceCapturingListener`) so the test bench can highlight matched rows, not just show pass/fail.

### M5 — AI service + chat
- [x] Spring AI 1.1.x `ChatClient`, provider from env (`AI_PROVIDER=anthropic|openai|ollama`, `AI_MODEL`, key vars).
- [x] Tools: listModels, getModelStructure, getDecisionTable, diffVersions, getExecution, queryExecutions, simulate, analyzeTable, proposeChange (draft only).
- [x] pgvector retrieval over rule annotations, change notes, policy docs.
- [x] `POST /api/ai/conversations/{id}/messages` streaming via SSE; log to ai_interaction_audit.
- [x] Chat UI: streaming markdown, context chips (pin model/execution), "How I got this" tool panel, suggested questions.
- [x] "Ask AI about this execution" button from Audit; "Describe a rule" box in builder → creates a draft the normal lifecycle still has to approve/publish.
- Accept: these demo questions answer correctly with citations:
  1. "Explain the loan-eligibility model in plain English."
  2. "Why was execution <id> declined?"
  3. "Would they be approved with a credit score of 700?" (uses simulate)
  4. "What changed between 1.0.0 and 1.1.0 and how many past cases would flip?"
  5. "Are there gaps or overlaps in Risk Category?"
  - Verified everything a real API key isn't required for: full backend test suite (`RuleToolsIntegrationTest`, 9 tests) exercises every tool — listModels, getModelStructure, getDecisionTable, analyzeModel, diffVersions, simulate, queryExecutions, estimateImpact (a composite tool that re-simulates sampled past executions against another version in one call, built specifically for demo question 4 so the LLM doesn't need dozens of tool calls to answer it), and searchDocs — against a **real** decision-service container with the real seeded data, and real local embeddings (no API key: spring-ai-starter-model-transformers, ONNX, in-process). `ConversationControllerTest` covers conversation creation, pinned context, and history retrieval. The live `docker compose` stack was also exercised directly: ai-service starts cleanly with no key configured (chat itself correctly fails without one; nothing else breaks), nginx proxies `/api/ai/*` correctly, 17 retrieval documents (2 policy docs + 15 rule-row annotations pulled live from decision-service) get embedded on first start, and the chat/describe-a-rule UI renders correctly (Playwright). The actual 5 demo questions, answered by the real model, are in `ChatEndToEndTest` — it's gated on `ANTHROPIC_API_KEY` being set (this sandbox doesn't have one; see docs/DECISIONS.md) and is the next thing to run once a key is configured: `ANTHROPIC_API_KEY=sk-... ./mvnw -pl ai-service test -Dtest=ChatEndToEndTest`.
  - Three real bugs found and fixed this milestone (all logged in docs/DECISIONS.md): the default ONNX embedding model URL (media.githubusercontent.com) is blocked in this sandbox and silently serves a git-lfs pointer file instead of the real binary from the fallback host — pinned to Hugging Face Hub instead; `ai-service` was missing its own `flyway-core` dependency entirely (silently did nothing) and then, once added, needed `baseline-on-migrate` + a `baseline-version` below its own V1 (the default baseline version equals V1, which silently skips V1 as "already covered"); and `docker-compose.yml` never gave `ai-service` its datasource or decision-service URL env vars at all (an M1-era placeholder that M5 forgot to fill in), so it tried to reach `localhost:5432` inside its own container.

### M6 — Deploy and share
- [x] Production compose: Caddy reverse proxy with automatic HTTPS for `${DEMO_DOMAIN}`, frontend static via nginx, services internal only.
- [x] `.env.example` documenting every variable; `deploy/README.md` with VM steps (any Linux VM with Docker, 4 vCPU / 8 GB RAM; open ports 80/443; point DNS A record).
- [x] Demo reset: `RESET_DEMO_ON_START=true` reseeds data; nightly reset optional.
- [x] Basic rate limit on AI endpoint to protect the API key on a public demo.
- Accept: fresh VM → clone → set `.env` → `docker compose up -d` → https://<domain> works end to end.
  - Verified everything that doesn't require a real domain/VM/cert: `docker compose -f deploy/docker-compose.prod.yml config` validates cleanly (Caddy is the only service publishing 80/443; postgres/decision-service/ai-service/frontend have no published ports, confirming "services internal only"); `nginx -t` passes against the updated `nginx.conf` with its new `limit_req_zone`/`limit_req` directives (validated on a test network with resolvable upstream hostnames, since nginx checks upstream DNS at startup). Exercised live via the dev compose stack (`docker-compose.yml`, which shares the same app images/env contract as the prod file): fresh `up --build` seeds 2 models/3 versions/~320 executions and embeds 17 retrieval documents into pgvector exactly as in M1-M5; restarting `decision-service`+`ai-service` without wiping the volume logs `RESET_DEMO_ON_START=true: clearing existing demo data before reseeding` / `...clearing previously embedded documents before re-embedding` and comes back with the same clean counts, confirming the M6 reset promise actually holds end-to-end (not just unit-level) and that decision-service's new UUIDs after truncate don't orphan ai-service's re-embedded annotations. Firing 20 rapid requests at `/api/ai/conversations` through nginx returned `200` for the first 6 (1 immediate + burst 5) then `429` for the rest, confirming the rate limit protects the AI endpoint/API key as intended. HTTPS/Let's Encrypt issuance itself cannot be verified without a real public domain + VM, so that part of the accept criterion rests on Caddy's well-established automatic-HTTPS behavior plus the config review above, not a live cert — see docs/DECISIONS.md.


### M7 — Cloud Run deploy
- [x] `deploy/gcp/deploy.sh`: idempotent deploy of decision-service, ai-service and the nginx frontend to Cloud Run, backed by private-IP Cloud SQL Postgres 16 (Direct VPC egress) with secrets in Secret Manager.
- [x] Services honor Cloud Run's injected `PORT`; nginx config is an envsubst template (`deploy/nginx/nginx.conf.template`) so one file serves docker-compose and Cloud Run.
- [x] Cost: ai-service scales to zero (embedding model baked into the image); Hikari pools capped at 3 so `db-f1-micro` does not run out of connection slots.
- Accept: `deploy.sh` on a billing-enabled project serves the app at the frontend's `run.app` URL, and Ask AI answers through it.
  - Verified live on project dmn-rules-demo-110320173 (us-central1): `/` and `/api/models` return 200 through the frontend, and an Ask AI question streamed a real answer via the Anthropic key from Secret Manager. Local docker-compose stack re-verified with the templated nginx config. Gotchas found and fixed: Postgres 16 defaults to Cloud SQL Enterprise Plus (needs `--edition ENTERPRISE` for `db-f1-micro`), and connection-slot exhaustion blocks new revisions from starting while old idle instances still hold pools.

## Demo script (5 minutes)
1. Login as analyst → Rules → open loan-eligibility → show decision table and annotations.
2. Ask AI: "Explain this model" → plain-English answer with row citations.
3. Audit → open a DECLINED execution → trace highlights matched rows → "Ask AI about this execution".
4. Ask "Would they pass with credit score 700?" → simulate result.
5. Builder: "Describe a rule": "applicants under 21 need review" → accept proposed row → test bench.
6. Login as approver → approve + publish → evaluate again → new version in audit trail with change history.
