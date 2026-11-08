-- LLM spend tracking and a monthly budget across every provider (Anthropic, OpenAI, Gemini, Ollama).

-- One row per model call. A chat message that uses tools makes several calls (one per tool round),
-- and each one is billed by the provider, so each one is recorded. Append-only like the other audit
-- tables: rows are never updated or deleted. Cost is computed at insert time from ai_llm_price, so
-- later price edits do not rewrite history.
CREATE TABLE ai_llm_usage (
    id             bigserial PRIMARY KEY,
    provider       text NOT NULL,
    model          text NOT NULL,
    input_tokens   integer NOT NULL,
    output_tokens  integer NOT NULL,
    cost_usd       numeric(14, 6) NOT NULL,
    -- true when the provider returned no token counts and they were estimated from text length
    estimated      boolean NOT NULL DEFAULT false,
    -- true when no price row matched the model and the provider's highest price was used instead
    price_fallback boolean NOT NULL DEFAULT false,
    at             timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ai_llm_usage_at_idx ON ai_llm_usage (at);

-- USD per million tokens. A model matches the longest model_prefix of its provider, so
-- 'claude-sonnet-4-5' also prices 'claude-sonnet-4-5-20250929'. Editable in the admin console.
CREATE TABLE ai_llm_price (
    provider        text NOT NULL,
    model_prefix    text NOT NULL,
    input_per_mtok  numeric(10, 4) NOT NULL CHECK (input_per_mtok >= 0),
    output_per_mtok numeric(10, 4) NOT NULL CHECK (output_per_mtok >= 0),
    updated_by      text NOT NULL DEFAULT 'seed',
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, model_prefix)
);

-- List prices as of November 2026. Verify against each provider's pricing page and adjust in the
-- admin console; long-context surcharges and cache discounts are not modelled (the estimate errs high).
INSERT INTO ai_llm_price (provider, model_prefix, input_per_mtok, output_per_mtok) VALUES
    ('anthropic', 'claude-opus-5-5',   4.00, 20.00),
    ('anthropic', 'claude-sonnet-5-5', 2.00, 10.00),
    ('anthropic', 'claude-haiku-5-5',  0.10,  0.50),
    ('anthropic', 'claude-sonnet-4-5', 3.00, 15.00),
    ('anthropic', 'claude-haiku-4-5',  1.00,  5.00),
    ('openai',    'gpt-4.1-mini',      0.40,  1.60),
    ('openai',    'gpt-4.1',           2.00,  8.00),
    ('openai',    'gpt-4o-mini',       0.15,  0.60),
    ('openai',    'gpt-4o',            2.50, 10.00),
    ('openai',    'gpt-5',             1.25, 10.00),
    ('gemini',    'gemini-2.5-pro',    1.25, 10.00),
    ('gemini',    'gemini-2.5-flash-lite', 0.10, 0.40),
    ('gemini',    'gemini-2.5-flash',  0.30,  2.50);

-- The monthly budget (single row, id = 1). Absent until an admin saves one; until then the
-- AI_MONTHLY_BUDGET_USD / AI_BUDGET_WARN_PERCENT env defaults apply.
CREATE TABLE ai_llm_budget (
    id            smallint PRIMARY KEY CHECK (id = 1),
    monthly_limit_usd numeric(10, 2) NOT NULL CHECK (monthly_limit_usd >= 0),
    warn_percent  integer NOT NULL CHECK (warn_percent BETWEEN 1 AND 100),
    -- true: chat is refused once the month's spend reaches the limit. false: warn only.
    hard_stop     boolean NOT NULL DEFAULT true,
    updated_by    text NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now()
);
