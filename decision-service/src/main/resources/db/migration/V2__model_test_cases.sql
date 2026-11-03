-- Saved test cases for the test bench (M3). Running them against a draft is what lets a draft
-- move from VALIDATED to TESTED; publish is blocked unless the draft is TESTED or later.
CREATE TABLE model_test_case (
    id                uuid PRIMARY KEY,
    model_key         text NOT NULL REFERENCES dmn_model,
    name              text NOT NULL,
    inputs            jsonb NOT NULL,
    expected_outputs  jsonb NOT NULL,
    created_by        text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_model_test_case_model_key ON model_test_case (model_key);
