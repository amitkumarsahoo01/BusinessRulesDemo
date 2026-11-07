-- Active LLM provider, chosen in the admin console and applied without a restart. A single row
-- (id = 1). API keys are deliberately NOT stored here: they only ever come from env vars
-- (CLAUDE.md rule 8); this table holds which provider/model to use and, for self-hosted or
-- OpenAI-compatible endpoints, the base URL.
CREATE TABLE ai_llm_settings (
    id          smallint PRIMARY KEY CHECK (id = 1),
    provider    text NOT NULL,          -- anthropic | openai | ollama
    model       text NOT NULL,
    base_url    text,
    updated_by  text NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- Who changed the provider, when, from what to what. Append-only, like the other audit tables.
CREATE TABLE ai_llm_settings_audit (
    id             bigserial PRIMARY KEY,
    provider       text NOT NULL,
    model          text NOT NULL,
    base_url       text,
    prev_provider  text,
    prev_model     text,
    changed_by     text NOT NULL,
    changed_at     timestamptz NOT NULL DEFAULT now()
);
