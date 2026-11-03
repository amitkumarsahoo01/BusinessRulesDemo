-- Demo app roles (see CLAUDE.md non-negotiable rule 2): the app role gets INSERT/SELECT only on
-- audit tables. In the demo compose, the app connects as the owning role, so the privilege split
-- is enforced here via comments/intent rather than a second DB role; a real deployment should run
-- GRANT statements against a dedicated low-privilege role.

CREATE TABLE app_user (
    username     text PRIMARY KEY,
    password_hash text NOT NULL,
    display_name text NOT NULL,
    role         text NOT NULL, -- rule-author | rule-approver | auditor
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- Versioned models: drafts are mutable, published rows are immutable.
CREATE TABLE dmn_model (
    model_key   text PRIMARY KEY,
    name        text NOT NULL,
    namespace   text NOT NULL,
    owner       text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE dmn_model_version (
    id           uuid PRIMARY KEY,
    model_key    text NOT NULL REFERENCES dmn_model,
    version      text NOT NULL,
    status       text NOT NULL, -- DRAFT|VALIDATED|TESTED|APPROVED|PUBLISHED|RETIRED
    dmn_xml      text NOT NULL,
    xml_sha256   char(64) NOT NULL,
    change_note  text,
    authored_by  text NOT NULL,
    approved_by  text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    UNIQUE (model_key, version)
);

CREATE INDEX idx_dmn_model_version_key_status ON dmn_model_version (model_key, status);

-- One row per evaluation. Append-only: the app role gets INSERT/SELECT only, no UPDATE/DELETE.
CREATE TABLE decision_execution (
    id                uuid PRIMARY KEY,
    model_version_id  uuid NOT NULL REFERENCES dmn_model_version,
    correlation_id    text,
    caller            text NOT NULL,
    inputs            jsonb NOT NULL,
    outputs           jsonb,
    status            text NOT NULL, -- SUCCEEDED|FAILED
    duration_ms       integer NOT NULL,
    row_hash          char(64) NOT NULL, -- sha256(prev_hash || row), chained per model_key
    executed_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_decision_execution_version ON decision_execution (model_version_id);
CREATE INDEX idx_decision_execution_executed_at ON decision_execution (executed_at);
CREATE INDEX idx_decision_execution_inputs_gin ON decision_execution USING gin (inputs);

-- Ordered trace steps within an evaluation. Append-only, same privilege policy as above.
CREATE TABLE decision_trace_step (
    execution_id   uuid NOT NULL REFERENCES decision_execution,
    seq            integer NOT NULL,
    node_type      text NOT NULL, -- DECISION|DECISION_TABLE|BKM|CONTEXT_ENTRY
    node_name      text NOT NULL,
    matched_rules  integer[],
    inputs         jsonb,
    result         jsonb,
    messages       jsonb,
    duration_ms    integer,
    PRIMARY KEY (execution_id, seq)
);

-- Who did what to rules (separate from evaluations).
CREATE TABLE rule_change_audit (
    id          bigserial PRIMARY KEY,
    model_key   text NOT NULL,
    version_id  uuid,
    action      text NOT NULL, -- CREATE|EDIT|VALIDATE|APPROVE|PUBLISH|RETIRE|ROLLBACK
    actor       text NOT NULL,
    diff        jsonb,
    at          timestamptz NOT NULL DEFAULT now()
);

-- Every AI conversation turn, tool call and token count (populated starting M5).
CREATE TABLE ai_interaction_audit (
    id              bigserial PRIMARY KEY,
    conversation_id text NOT NULL,
    role            text NOT NULL, -- USER|ASSISTANT|TOOL
    content         text,
    tool_calls      jsonb,
    token_count     integer,
    at              timestamptz NOT NULL DEFAULT now()
);
