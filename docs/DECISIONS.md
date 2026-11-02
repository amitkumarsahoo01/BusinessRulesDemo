# Decisions Log

Defaults picked without asking, with rationale. Update this file whenever a non-obvious call is made.

## Versions (checked against live Maven Central / npm / Apache KIE release pages on 2026-11-01)

| Component | Version | Why |
| --- | --- | --- |
| Java | 21 (Temurin/OpenJDK build) | Required by Apache KIE 10.2 and Spring Boot 3.5; installed via `apt install openjdk-21-jdk` since no JDK was present in the environment. |
| Spring Boot | 3.5.16 | Last patch of the 3.5.x line (confirmed via `maven-metadata.xml` on repo.maven.apache.org; the line went EOL 2026-06-30 and Spring Boot has moved to 4.x). CLAUDE.md pins Spring Boot 3.5 specifically because Spring AI 1.1.x does not support Boot 4 — this is an intentional, explicit project requirement, not an oversight. Acceptable for a demo; flagged here so a future maintainer knows it's a deliberate pin, not staleness. |
| Apache KIE (`kie-dmn-core`, `kie-dmn-validation`) | 10.2.0 | Latest release (2026-03-28), DMN 1.6 support, matches docs/Architecture.md. GroupId is still `org.kie` (no rename to `org.apache.kie` as of this release). |
| Spring AI | 1.1.8 | Last patch of the 1.1.x line (1.1.x targets Boot 3.5; 2.x requires Boot 4, matching the CLAUDE.md constraint). |
| Angular | 22.2.5 (CLI/core) | Current stable at scaffold time. Required bumping the sandbox's Node from 24.3.0 to the Node 24 LTS line (24.22.2) via nvm — the Angular 22 CLI's engines check rejects 24.3.0. |
| Node.js | 24.22.2 (LTS "Krypton") | Minimum Angular 22 supports in the 24.x line; set as the nvm default in this environment. |
| PostgreSQL / pgvector | `pgvector/pgvector:pg16` image | Matches "PostgreSQL 16 + pgvector" in CLAUDE.md. |

## Architecture / scope decisions

- **No separate gateway service.** BUILD_PLAN.md's demo scope simplifies to "2 Spring Boot apps + nginx". nginx in `deploy/nginx/nginx.conf` does path-based routing: `/api/ai/*` → ai-service, `/api/*` (else) → decision-service, everything else → the Angular static bundle with `try_files` fallback to `index.html` for client-side routing. This nginx config is reused as-is for the M6 production compose (CLAUDE.md says Caddy fronts nginx+services in prod; nginx's internal routing role doesn't change).
- **Maven multi-module layout.** Root `pom.xml` is a `pom`-packaged aggregator that imports `spring-boot-dependencies` and `spring-ai-bom` via `dependencyManagement` (rather than using `spring-boot-starter-parent` as the Maven parent), so the repo can have one custom root parent shared by both modules while still getting Spring Boot's managed dependency versions. A Maven wrapper (`mvnw`) is generated at the root for reproducible container builds that don't depend on a host-installed Maven.
- **Angular test runner.** Angular 22's `ng test` defaults to the new `@angular/build:unit-test` builder backed by Vitest + jsdom, not Karma/Chrome. Kept the default — it avoids needing a headless browser in CI/containers and is now the framework default.
- **`spring-boot-maven-plugin` needs an explicit `repackage` execution.** Because the root `pom.xml` imports `spring-boot-dependencies` via `dependencyManagement` instead of using `spring-boot-starter-parent` as the Maven parent, the default `repackage` execution that `starter-parent` normally wires in is not present. Without it the packaged jar has no `Main-Class` manifest entry (`no main manifest attribute, in app.jar` at container start). Fixed by declaring the `repackage` execution explicitly in the root `pluginManagement` for `spring-boot-maven-plugin`.
- **M1 scope kept minimal on purpose.** `decision-service` and `ai-service` ship only `spring-boot-starter-web` + `actuator` + a `/api/health` controller in M1. Flyway, the Postgres driver, kie-dmn-core, and Spring AI dependencies are added when M2/M5 actually use them, so each milestone's diff reflects only what that milestone needs.

## Open — still the user's call

- Which LLM provider (`AI_PROVIDER`) and API key to use for M5 — deferred until that milestone; `anthropic` is wired as the compose default but no key is set.
