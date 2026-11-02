Paste this as your first message in Claude Code, from the root of an empty project folder
that already contains CLAUDE.md and docs/ (ARCHITECTURE.md, BUILD_PLAN.md):

---

Read CLAUDE.md, docs/ARCHITECTURE.md and docs/BUILD_PLAN.md fully before doing anything.

We are building the demo described in docs/BUILD_PLAN.md. Work milestone by milestone (M1 → M6).
For each milestone:
1. Briefly state your plan for the milestone and any version choices (check current stable versions
   of Apache KIE, Spring Boot 3.5.x, Spring AI 1.1.x and Angular before pinning them).
2. Implement it with tests.
3. Run the build and tests and fix failures until they pass.
4. Tick the milestone's checkboxes in docs/BUILD_PLAN.md, commit with a clear message, then continue.

Rules:
- Follow the "Non-negotiable design rules" in CLAUDE.md.
- If a library API differs from what the docs assume (e.g. DMN event listener method names), check
  the actual dependency sources/javadoc rather than guessing.
- Only stop to ask me when a decision is truly mine (e.g. which LLM provider key I'll use) — otherwise
  pick a sensible default, note it in docs/DECISIONS.md, and keep going.

Start with M1 now.
