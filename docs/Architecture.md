# Kogito DMN Rules Platform with Embedded AI — Architecture

Nov 1, 2026 · @Amit Sahoo

## Overview

The platform runs business decisions as DMN models on Kogito (Spring Boot), records every evaluation and every rule change in PostgreSQL, and puts an LLM-backed analyst on top so users can ask why a rule exists, what it does and why a given decision came out the way it did. An Angular app gives business users a chat with that analyst, a guided rule builder and an audit explorer.

**Goals**

- Evaluate DMN decisions over REST with predictable latency; the AI is never in the decision path.
- Keep an immutable, queryable audit trail: who changed which rule, and which rule versions, inputs and fired rows produced each outcome.
- Let users understand rules in plain language, grounded in the real model and real traces, not in the LLM's guesses.
- Let non-developers author and change decision tables safely, with validation, testing and approval before deploy.

**Key decisions**

| Area | Choice | Why |
| --- | --- | --- |
| Decision runtime | Kogito DMN engine embedded in Spring Boot | Team standard; DMN 1.x standard models stay portable |
| Audit store | PostgreSQL with JSONB for inputs, outputs and traces | Relational for queries and retention, JSONB for variable payloads |
| AI layer | Spring AI or LangChain4j behind a provider port, switchable by config | Swap Anthropic, OpenAI/Azure or a local Ollama model without code changes |
| Frontend | Angular with a DMN-aware rule builder and streaming chat | Team standard |

**Out of scope for v1:** BPMN processes, rule execution inside the LLM, and multi-region active-active.

## High-level architecture

Four tiers: the Angular app, a gateway, two Spring Boot services and PostgreSQL. The decision service owns evaluation and audit; the AI service only reads through tools.

&#91;embedded content: platform architecture · 4 tiers\]

The AI service reaches rules and traces through the decision service's APIs and a read replica, never by evaluating decisions itself, so an LLM outage cannot stop decisions.

## Decision engine service

The decision service is a Spring Boot 3.5 app on Java 21 using the Apache KIE 10.2 DMN engine, which supports DMN 1.6 and pins Spring Boot 3.5.10 ([KIE 10.2 release](https://kie.apache.org/blog/kie_10_2_0_release)). Kogito supports Spring Boot alongside Quarkus, though Quarkus is the project's recommended runtime ([Kogito docs](https://kie.apache.org/docs/10.1.x/kogito)).

**The core design tension: Kogito compiles DMN at build time.** Kogito's codegen turns `.dmn` files into REST endpoints when the app is built. Rules authored in the UI would then need a rebuild and redeploy before they run. Two runtime modes resolve this:

| Mode | How models load | Use for | Change speed |
| --- | --- | --- | --- |
| Dynamic (default) | `kie-dmn-core` `DMNRuntimeBuilder` compiles published model versions from PostgreSQL at startup and on publish events | Business-authored rules changed through the UI | Seconds after approval |
| Codegen | Kogito Spring Boot starter generates endpoints from `.dmn` files in Git | Stable, developer-owned models needing typed endpoints | One CI/CD cycle |

Both modes run the same Apache KIE DMN engine, so semantics and FEEL behaviour are identical.

**Model lifecycle**

1. **Draft** — saved on every edit; not executable.
2. **Validated** — DMN schema, FEEL compilation and decision-table checks pass (overlaps, gaps, hit-policy violations via the engine's validator).
3. **Tested** — attached test scenarios pass against the draft.
4. **Approved** — a second user with the `rule-approver` role signs off (four-eyes).
5. **Published** — becomes an immutable version (`model_key` + semantic version + SHA-256 of the XML); the runtime registry hot-swaps it.
6. **Retired** — no longer served, kept forever for audit replay.

**Runtime registry.** An in-memory map of `(modelKey, version) → compiled DMNModel`, warmed at startup. A publish writes the version row and a `model_published` event; every instance reloads via PostgreSQL `LISTEN/NOTIFY` (or Kafka if already present). Callers may pin a version or take the latest published one.

**Evaluation endpoint.** `POST /api/decisions/{modelKey}/evaluate` returns outputs plus an `executionId`. Clients that need the trace inline pass `?trace=true`.

## Audit history and execution traces

Every evaluation and every model change lands in PostgreSQL as an append-only record, captured in-process by a `DMNRuntimeEventListener` rather than through Kafka.

**Why not the stock tracing add-on.** Kogito's `kie-addons-springboot-tracing-decision` emits trace events to Kafka topics for the Trusty service to consume ([example](https://apache.googlesource.com/incubator-kie-kogito-examples/+/HEAD/kogito-springboot-examples/dmn-tracing-springboot)). That adds Kafka plus a separate service just to reach a database. A custom listener writes the same information straight to PostgreSQL. If Kafka already exists in your estate, the add-on can feed the same tables through a small consumer instead.

**What the listener captures per evaluation**

- Before/after each decision: name, inputs in scope, result, status, duration.
- Each decision table hit: matched rule row indexes and the output each produced (`afterEvaluateDecisionTable`).
- BKM and context-entry evaluations, plus every DMN message (warnings, FEEL errors).
- Envelope: model key and version hash, caller identity, correlation ID, total latency.

**Write path.** Trace events buffer in memory for the request, then go to PostgreSQL in one batch. Two policies, set per model:

- `STRICT` — written in the request transaction; the evaluation fails if the audit write fails. Use for regulated decisions.
- `ASYNC` — handed to a bounded queue and written by a background worker; the response is not delayed. Overflow spills to a local file and is replayed.

**Schema (core tables)**

```sql
-- Versioned models: drafts are mutable, published rows are immutable
CREATE TABLE dmn_model (
  model_key       text PRIMARY KEY,
  name            text NOT NULL,
  namespace       text NOT NULL,
  owner           text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE dmn_model_version (
  id              uuid PRIMARY KEY,
  model_key       text NOT NULL REFERENCES dmn_model,
  version         text NOT NULL,           -- semantic version
  status          text NOT NULL,           -- DRAFT|VALIDATED|TESTED|APPROVED|PUBLISHED|RETIRED
  dmn_xml         text NOT NULL,
  xml_sha256      char(64) NOT NULL,
  change_note     text,
  authored_by     text NOT NULL,
  approved_by     text,
  published_at    timestamptz,
  UNIQUE (model_key, version)
);

-- One row per evaluation
CREATE TABLE decision_execution (
  id              uuid PRIMARY KEY,
  model_version_id uuid NOT NULL REFERENCES dmn_model_version,
  correlation_id  text,
  caller          text NOT NULL,
  inputs          jsonb NOT NULL,
  outputs         jsonb,
  status          text NOT NULL,           -- SUCCEEDED|FAILED
  duration_ms     integer NOT NULL,
  row_hash        char(64) NOT NULL,       -- sha256(prev_hash || row), per model
  executed_at     timestamptz NOT NULL DEFAULT now()
) PARTITION BY RANGE (executed_at);      -- monthly partitions

-- Ordered trace steps within an evaluation
CREATE TABLE decision_trace_step (
  execution_id    uuid NOT NULL,
  seq             integer NOT NULL,
  node_type       text NOT NULL,           -- DECISION|DECISION_TABLE|BKM|CONTEXT_ENTRY
  node_name       text NOT NULL,
  matched_rules   integer[],               -- decision-table row indexes
  inputs          jsonb,
  result          jsonb,
  messages        jsonb,
  duration_ms     integer,
  PRIMARY KEY (execution_id, seq)
);

-- Who did what to rules (separate from evaluations)
CREATE TABLE rule_change_audit (
  id              bigserial PRIMARY KEY,
  model_key       text NOT NULL,
  version_id      uuid,
  action          text NOT NULL,           -- CREATE|EDIT|VALIDATE|APPROVE|PUBLISH|RETIRE|ROLLBACK
  actor           text NOT NULL,
  diff            jsonb,                   -- structural diff of rows/expressions
  at              timestamptz NOT NULL DEFAULT now()
);
```

**Integrity and retention.** The app's database role has `INSERT`/`SELECT` only on audit tables; no `UPDATE` or `DELETE`. Each execution row stores a hash chained to the previous one per model, so tampering is detectable. Monthly partitions let old data move to cheaper storage or be dropped by retention policy. GIN indexes on `inputs` support questions like "all denials where creditScore < 600".

## AI analysis service

The AI service answers questions about rules by calling tools that read the real DMN models and audit data; the LLM explains, it never decides. It is a separate Spring Boot app so it scales and fails independently of decision evaluation.

**Provider-agnostic layer.** Use Spring AI 1.1.x, the line that targets Spring Boot 3.5 (Spring AI 2.x needs Boot 4) ([Spring AI](https://github.com/psilberk/spring-ai)). Its `ChatClient` gives one API over Anthropic, OpenAI, Azure OpenAI, Ollama, Bedrock and others, with tool calling ([Spring AI 1.1 GA](https://spring.io/blog/2025/11/12/spring-ai-1-1-GA-released/)). Wrap it in your own `RuleAnalyst` port so business code never imports a vendor type.

```yaml
rules-ai:
  provider: anthropic        # anthropic | openai | azure-openai | ollama
  chat-model: <model-id>
  embedding-provider: ollama # can differ from chat provider
  max-tool-calls: 8
  redact-pii: true
```

Per-tenant or per-feature routing (e.g. a local Ollama model for PII-heavy questions, a hosted model otherwise) is a small router in front of the port.

**Grounding: tools over guesswork.** The model gets read-only tools backed by the decision service and the audit DB:

| Tool | Returns |
| --- | --- |
| `listModels(query)` | Models and versions matching a name or tag |
| `getModelStructure(modelKey, version)` | Decisions, inputs, data types, dependencies (DRG) as compact JSON |
| `getDecisionTable(modelKey, decision)` | Rows, conditions, outputs, hit policy, annotations |
| `diffVersions(modelKey, v1, v2)` | Rows and expressions added, removed or changed |
| `getExecution(executionId)` | Inputs, outputs and the ordered trace with matched rows |
| `queryExecutions(filter)` | Aggregates: outcome counts, rule hit frequency over a window |
| `simulate(modelKey, version, inputs)` | Dry-run evaluation, never written to the audit log |
| `analyzeTable(modelKey, decision)` | Engine-computed gaps, overlaps, unreachable and never-hit rows |

`simulate` is how the AI answers what-if questions with real engine output. `analyzeTable` uses the DMN validator plus hit statistics, so findings are computed, not hallucinated.

**Retrieval for documentation.** Rule annotations, change notes and policy documents are chunked and embedded into `pgvector` in the same PostgreSQL. This answers "why does this rule exist" questions that structure alone cannot.

**What users can ask**

- Explain: "What does the Loan Eligibility model do, in plain English?"
- Why: "Why was application 8841 declined?" (reads the trace, names the matched rows)
- What-if: "Would they pass with a credit score of 680?" (calls `simulate`)
- Impact: "What changes between v2.3 and v2.4, and how many past cases would flip?" (diff + replay on sampled executions)
- Quality: "Are there gaps or overlapping rows in Risk Category?"
- Draft: "Add a rule: applicants under 21 need a co-signer" (returns a proposed table change for the builder, never auto-publishes)

**Guardrails**

- Tools are read-only except `proposeChange`, which creates a draft that still goes through validation and approval.
- Tool calls run with the end user's identity, so row-level access to models and executions is enforced in the backend, not by the prompt.
- PII fields marked in the data dictionary are masked before any prompt leaves the network.
- Every answer cites the model version, decision and row numbers or execution IDs it used; the UI links them.
- Each conversation, prompt, tool call and token count is logged to `ai_interaction_audit` for cost and review.

## Angular frontend

The Angular app has three workspaces — Rules, Ask AI and Audit — built as standalone components with signals, lazy-loaded routes and Angular Material (or PrimeNG for its richer data grid).

**1. Rule builder (two levels of editing)**

- **Guided table editor (primary, for business users).** A spreadsheet-like grid for a decision table: columns are typed inputs from the data dictionary, cells use pickers (ranges, lists, dates) that emit valid FEEL, and the hit policy is a dropdown with plain-language help. It edits the DMN XML underneath, so nothing is lost.
- **Full DMN diagram editor (power users).** Embed `@kie-tools/dmn-editor-standalone`, Apache KIE's DMN editor packaged as one JavaScript file for any web app, available from 10.1 ([KIE editors](https://kie.apache.org/tools/editors), [npm README](https://cdn.jsdelivr.net/npm/@kie-tools/dmn-editor-standalone@10.2.0/README.md)). Wrap it in an Angular component that calls `open()` in `ngAfterViewInit`, subscribes to content changes, and `close()`s on destroy.
- **Live feedback.** Every edit triggers debounced server validation; errors pin to the exact cell. A side panel lists AI findings (gaps, overlaps, never-hit rows).
- **Test bench.** Enter inputs, run against the draft, see outputs and the trace highlighted on the table. Save cases as regression tests that block publish if they fail.
- **Natural-language assist.** "Describe the rule" box → AI proposes rows → shown as a diff the user accepts or rejects row by row.
- **Review and publish.** Side-by-side version diff, impact estimate (how many recent executions would change outcome), approve and publish buttons gated by role.

**2. Ask AI (chat)**

- Streaming responses over Server-Sent Events, rendered as Markdown.
- Context chips: pin a model, a version or an execution ID so questions are scoped ("explain this decision").
- Citations render as links that open the exact table row or trace step.
- Tool-call transparency: a collapsible "How I got this" shows which tools ran.
- Suggested follow-ups and a thumbs up/down that writes to the AI audit table.

**3. Audit explorer**

- Searchable execution list (model, version, outcome, date, caller, input filters).
- Trace view: the DRG diagram with evaluated nodes highlighted in order, plus the decision table with matched rows lit up.
- Rule change history per model with diffs and who approved what.
- "Ask AI about this execution" button that opens chat with the execution pinned.

**Frontend structure**

```text
src/app/
  core/        auth (OIDC), http interceptors, SSE client, error handling
  shared/      FEEL input pickers, diff viewer, trace highlighter
  features/
    rules/     model list, guided-table-editor, dmn-editor-host, test-bench, publish
    chat/      chat-shell, message-stream, citation-link, context-chips
    audit/     execution-search, trace-view, change-history
```

## API design

All APIs are REST/JSON behind one gateway, documented with OpenAPI; chat streams over SSE.

| Method | Path | Service | Purpose |
| --- | --- | --- | --- |
| POST | `/api/decisions/{modelKey}/evaluate` | Decision | Evaluate latest published (or `?version=`); returns outputs + `executionId` |
| POST | `/api/decisions/{modelKey}/simulate` | Decision | Dry run against any version or draft; not audited as a real decision |
| GET | `/api/models` | Rules mgmt | List models with status and latest version |
| POST | `/api/models/{modelKey}/drafts` | Rules mgmt | Create or update a draft (DMN XML or guided-table JSON) |
| POST | `/api/models/{modelKey}/drafts/{id}/validate` | Rules mgmt | Schema, FEEL and table-completeness checks |
| POST | `/api/models/{modelKey}/drafts/{id}/tests/run` | Rules mgmt | Run saved test cases |
| POST | `/api/models/{modelKey}/drafts/{id}/approve` | Rules mgmt | Approver sign-off |
| POST | `/api/models/{modelKey}/drafts/{id}/publish` | Rules mgmt | Create immutable version, notify runtimes |
| GET | `/api/models/{modelKey}/versions/{v1}/diff/{v2}` | Rules mgmt | Structural diff |
| GET | `/api/executions` | Audit | Search with filters and paging |
| GET | `/api/executions/{id}` | Audit | Execution with full ordered trace |
| GET | `/api/models/{modelKey}/history` | Audit | Rule change log |
| POST | `/api/ai/conversations` | AI | Start a conversation, optionally with pinned context |
| POST | `/api/ai/conversations/{id}/messages` | AI | Send a question; response is `text/event-stream` |
| POST | `/api/ai/rule-drafts` | AI | Natural language to proposed table rows |

**Evaluate example**

```json
POST /api/decisions/loan-eligibility/evaluate
{ "Applicant": { "age": 34, "creditScore": 640, "income": 72000 },
  "Loan": { "amount": 25000, "termMonths": 48 } }

200 OK
{ "executionId": "6f1c…", "modelVersion": "2.4.0",
  "outputs": { "Eligibility": "REVIEW", "Risk Category": "MEDIUM" } }
```

The rules-management and audit APIs can live in the decision service at first and split out later if load demands it.

## Key flows

**A. Author and publish a rule change**

1. Analyst opens a model in the guided editor; a draft version is created from the latest published one.
2. Edits save as the draft; each save is validated and logged to `rule_change_audit` with a structural diff.
3. Optional: analyst asks AI to draft rows from a sentence and accepts them row by row.
4. Analyst runs the test bench; failing saved cases block the next step.
5. Impact preview replays a sample of recent executions against the draft and shows outcomes that would flip.
6. An approver reviews the diff and impact, then approves.
7. Publish writes an immutable version and sends `model_published`; every decision-service instance compiles and swaps it in.
8. Rollback = republish the previous version; also audited.

**B. Evaluate a decision with audit**

1. Client calls `evaluate`; the registry resolves the compiled model version.
2. The KIE DMN engine evaluates; the event listener records every decision, table hit and message in order.
3. On completion the trace is written as one batch (`STRICT` in-transaction or `ASYNC` via the queue).
4. Response returns outputs and `executionId`; the AI service is not involved.

**C. User asks the AI about a rule or a result**

1. User types "Why was application 8841 declined?" with the execution pinned.
2. AI service builds a prompt with the system policy, user role and pinned context, then calls the configured LLM.
3. The LLM calls `getExecution`, then `getDecisionTable` for each table that fired.
4. Tools return real trace and rule data, filtered by the user's permissions and PII-masked.
5. The LLM writes the explanation with citations to model version, decision and row numbers.
6. Answer streams to the chat; the whole exchange is logged to `ai_interaction_audit`.

## Security, governance, observability and deployment

**Security**

- OIDC login (Keycloak, Entra ID or Okta) with Spring Security resource servers; Angular uses authorization code + PKCE.
- Roles: `rule-viewer`, `rule-author`, `rule-approver`, `decision-client` (service accounts), `auditor`, `admin`. Authors cannot approve their own drafts.
- Model-level permissions (by business domain) apply to the UI, the APIs and the AI tools alike.
- LLM keys live in a secrets manager; outbound LLM traffic goes through an egress proxy with an allowlist.

**Governance**

- Every published version is reproducible: XML, hash, approver and tests are stored with it.
- Any past decision can be replayed against its exact version to prove the outcome.
- AI answers are advisory and labelled as such; AI-drafted rules always pass human approval.

**Observability**

- Micrometer metrics per model: evaluations/sec, p95 latency, error rate, outcome distribution, rule-hit counts.
- OpenTelemetry traces across gateway → decision service → DB, and chat → AI service → LLM → tools.
- AI dashboards: tokens and cost per provider, tool-call counts, latency, thumbs-down rate.

**Deployment**

| Component | Packaging | Scaling |
| --- | --- | --- |
| Angular app | Static bundle on Nginx or a CDN | Stateless |
| Decision service | Container, Spring Boot | Horizontal; each pod holds the compiled registry |
| AI service | Container, Spring Boot | Horizontal; bounded by LLM rate limits |
| PostgreSQL 16+ with `pgvector` | Managed service or operator | Primary + read replica for audit queries and AI tools |
| Optional Kafka | Existing cluster | Only if you adopt the stock tracing add-on |

Local development runs everything with one `docker-compose` file, including Ollama so no API key is needed.

## Delivery roadmap and open questions

Build in four phases so audited decisions run before AI or authoring features land.

1. **Phase 1 — Decision core and audit.** Spring Boot decision service with the dynamic model registry, evaluate API, event listener, PostgreSQL schema and Flyway migrations, one sample model. Exit: every evaluation traced and queryable.
2. **Phase 2 — Authoring.** Draft/validate/test/approve/publish lifecycle, Angular guided table editor, embedded DMN editor, test bench, version diff. Exit: a business user ships a rule change with no developer.
3. **Phase 3 — AI analysis.** AI service with Spring AI behind the provider port, read-only tools, chat UI with streaming and citations, pgvector retrieval. Exit: answers cite real rows and executions on a benchmark question set.
4. **Phase 4 — Advanced.** AI-drafted rules, impact replay, table quality findings, cost dashboards, hash-chained audit verification.

**Open questions**

- [ ] Do rule changes need to deploy without a release (dynamic registry), or is a CI/CD redeploy acceptable (pure Kogito codegen)?
- [ ] Which decisions are regulated and need `STRICT` audit writes?
- [ ] How long must executions be retained, and can inputs contain PII that must be masked at rest?
- [ ] Which LLM providers are approved by security, and is any data barred from leaving the network?
- [ ] Is there an existing Kafka cluster and identity provider to reuse?
- [ ] Expected peak evaluations per second and latency target?

## Sources

- [Apache KIE 10.2.0 release announcement](https://kie.apache.org/blog/kie_10_2_0_release)
- [Kogito documentation 10.1.x](https://kie.apache.org/docs/10.1.x/kogito)
- [DMN Tracing Spring Boot example](https://apache.googlesource.com/incubator-kie-kogito-examples/+/HEAD/kogito-springboot-examples/dmn-tracing-springboot)
- [Apache KIE standalone editors](https://kie.apache.org/tools/editors)
- [@kie-tools/dmn-editor-standalone 10.2.0 README](https://cdn.jsdelivr.net/npm/@kie-tools/dmn-editor-standalone@10.2.0/README.md)
- [Spring AI 1.1 GA release](https://spring.io/blog/2025/11/12/spring-ai-1-1-GA-released/)
- [Spring AI repository (Boot compatibility)](https://github.com/psilberk/spring-ai)
