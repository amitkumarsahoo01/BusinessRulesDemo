-- Conversations and pinned context. ai_interaction_audit (the per-message log) already exists —
-- it was created by decision-service's V1 migration on this same database. This service's own
-- Flyway history table (spring.flyway.table=flyway_schema_history_ai) keeps its migrations from
-- colliding with decision-service's on the shared instance.
CREATE TABLE ai_conversation (
    id                   uuid PRIMARY KEY,
    created_by           text NOT NULL,
    pinned_model_key     text,
    pinned_version       text,
    pinned_execution_id  uuid,
    created_at           timestamptz NOT NULL DEFAULT now()
);
