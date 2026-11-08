/** Reference data for the REST API section of the Help page. Keep in sync with decision-service's controllers. */

export type HttpMethod = 'GET' | 'POST';

/** Who may call it, beyond being authenticated. */
export type Access = 'open' | 'any' | 'users';

export interface ApiEndpoint {
  method: HttpMethod;
  path: string;
  summary: string;
  access: Access;
  /** Query parameters, body, or notes, one per line. */
  details?: string[];
}

export interface ApiGroup {
  title: string;
  description: string;
  endpoints: ApiEndpoint[];
}

export const ACCESS_LABELS: Record<Access, string> = {
  open: 'No auth',
  any: 'Any account',
  users: 'Users only',
};

export const ACCESS_HELP: Record<Access, string> = {
  open: 'No credentials needed.',
  any: 'Any authenticated account, including auditor and api-client.',
  users: 'Rule authors, approvers and admins (not auditor or api-client); lifecycle rules still apply, e.g. the approver must differ from the author.',
};

export const API_GROUPS: ApiGroup[] = [
  {
    title: 'Decisions',
    description: 'Run a decision model. Only the DMN engine decides; every evaluation is audited.',
    endpoints: [
      {
        method: 'POST',
        path: '/api/decisions/{modelKey}/evaluate',
        summary: 'Evaluate the live (latest published) version, or a specific one. Recorded in the audit trail.',
        access: 'any',
        details: [
          'Body: the model inputs as JSON, e.g. {"Applicant": {...}, "Loan": {...}}',
          '?version=1.1.0  evaluate a specific published version',
          '?trace=true  include the ordered execution trace with matched rule rows',
          'Returns: executionId, modelVersion, outputs, status, trace',
        ],
      },
      {
        method: 'POST',
        path: '/api/decisions/{modelKey}/simulate',
        summary: 'Dry run: same as evaluate, but nothing is written to the audit trail.',
        access: 'any',
        details: ['Body and ?version= as for evaluate', '?trace=  defaults to true'],
      },
    ],
  },
  {
    title: 'Audit (executions)',
    description: 'Past evaluations with their inputs, outputs and trace. Read-only and append-only.',
    endpoints: [
      {
        method: 'GET',
        path: '/api/executions',
        summary: 'Search executions, newest first, paged.',
        access: 'any',
        details: [
          '?modelKey=  ?version=  ?caller=  ?status=SUCCEEDED|FAILED',
          '?from=  ?to=  ISO-8601 instants, e.g. 2026-11-01T00:00:00Z',
          '?page=0  ?size=20',
        ],
      },
      {
        method: 'GET',
        path: '/api/executions/{id}',
        summary: 'One execution with inputs, outputs, ordered trace (matched rows) and audit hash.',
        access: 'any',
      },
    ],
  },
  {
    title: 'Models (read)',
    description: 'Inspect models, versions and their structure.',
    endpoints: [
      { method: 'GET', path: '/api/models', summary: 'All models with live version and any draft in progress.', access: 'any' },
      { method: 'GET', path: '/api/models/{modelKey}/versions', summary: 'Every version of a model with its status.', access: 'any' },
      {
        method: 'GET',
        path: '/api/models/{modelKey}/resolve',
        summary: 'The version an evaluate call would use.',
        access: 'any',
        details: ['?version=  optional; defaults to the latest published version'],
      },
      { method: 'GET', path: '/api/models/{modelKey}/history', summary: 'Change history: create, edit, validate, approve, publish, rollback.', access: 'any' },
      { method: 'GET', path: '/api/models/{modelKey}/versions/{v1}/diff/{v2}', summary: 'Rule-by-rule differences between two versions.', access: 'any' },
      { method: 'GET', path: '/api/models/{modelKey}/drafts/{versionId}', summary: 'One version, including its DMN XML and SHA-256.', access: 'any' },
      { method: 'GET', path: '/api/models/{modelKey}/drafts/{versionId}/structure', summary: 'Inputs, decisions and their dependencies.', access: 'any' },
      { method: 'GET', path: '/api/models/{modelKey}/drafts/{versionId}/graph', summary: 'Full decision requirements graph and data types.', access: 'any' },
      { method: 'GET', path: '/api/models/{modelKey}/drafts/{versionId}/decisions', summary: 'Decisions in a version and their kind.', access: 'any' },
      { method: 'GET', path: '/api/models/{modelKey}/drafts/{versionId}/decisions/{decisionName}', summary: 'One decision table: hit policy, columns, rules.', access: 'any' },
      { method: 'GET', path: '/api/models/{modelKey}/drafts/{versionId}/analysis', summary: 'Static analysis: gaps, overlaps and other findings.', access: 'any' },
      { method: 'GET', path: '/api/models/{modelKey}/test-cases', summary: 'Saved regression test cases.', access: 'any' },
    ],
  },
  {
    title: 'Authoring and lifecycle',
    description: 'Change rules. Every change is a draft that must be validated, tested, approved by someone else, then published.',
    endpoints: [
      {
        method: 'POST',
        path: '/api/models/import',
        summary: 'Upload a .dmn file as a new model (v1.0.0) or the next draft of an existing one.',
        access: 'users',
        details: ['multipart/form-data: file=@model.dmn, optional modelKey', 'Max 2 MB; always creates a DRAFT'],
      },
      {
        method: 'POST',
        path: '/api/models/{modelKey}/data-types/import',
        summary: 'Add DMN data types from a JSON Schema (preview, or write them into the draft).',
        access: 'users',
        details: [
          'JSON: {"schema": <schema object or text>, "apply": false|true, "rootName", "createInputs", "replaceExisting"}',
          'apply=false only previews; apply=true updates the draft or branches one from the published version',
        ],
      },
      {
        method: 'POST',
        path: '/api/models/{modelKey}/drafts',
        summary: 'Create or update the draft: edited decision-table rows, or a whole DMN document.',
        access: 'users',
        details: [
          'Body: { "guidedTableEdit": { decisionName, hitPolicy, rules: [{ description, inputEntries: [...], outputEntry }] }, "changeNote": "..." }',
          'or: { "dmnXml": "<definitions ...>", "changeNote": "..." }',
        ],
      },
      { method: 'POST', path: '/api/models/{modelKey}/drafts/{versionId}/validate', summary: 'Validate the draft (schema, FEEL, table analysis).', access: 'users' },
      { method: 'POST', path: '/api/models/{modelKey}/drafts/{versionId}/tests/run', summary: 'Run the saved test cases against the draft.', access: 'users' },
      {
        method: 'POST',
        path: '/api/models/{modelKey}/drafts/{versionId}/approve',
        summary: 'Approve a tested draft. The approver must not be its author (409 otherwise).',
        access: 'users',
      },
      { method: 'POST', path: '/api/models/{modelKey}/drafts/{versionId}/publish', summary: 'Publish an approved draft; it goes live immediately.', access: 'users' },
      {
        method: 'POST',
        path: '/api/models/{modelKey}/rollback',
        summary: 'Republish an earlier version as a new version.',
        access: 'users',
        details: ['?toVersion=1.0.0'],
      },
      {
        method: 'POST',
        path: '/api/models/{modelKey}/test-cases',
        summary: 'Save a regression test case.',
        access: 'users',
        details: ['Body: { name, inputs: {...}, expectedOutputs: {...} }'],
      },
    ],
  },
  {
    title: 'Dashboard',
    description: 'Aggregates behind the Dashboard page.',
    endpoints: [
      {
        method: 'GET',
        path: '/api/dashboard/executions',
        summary: 'Execution totals, latency, daily counts per model and outcome distributions.',
        access: 'any',
        details: ['?days=30  (1 to 365)', '?modelKey=  optional filter'],
      },
      { method: 'GET', path: '/api/dashboard/rules', summary: 'One summary per model: live version, decisions, rule counts, usage.', access: 'any', details: ['?days=30'] },
    ],
  },
  {
    title: 'Session and health',
    description: '',
    endpoints: [
      {
        method: 'POST',
        path: '/api/auth/login',
        summary: 'Exchange a username and password for a session token (web-app users; not api-client).',
        access: 'open',
        details: ['Body: { "username": "...", "password": "..." }', 'Returns: username, displayName, role, token (send as Authorization: Bearer <token>)'],
      },
      { method: 'GET', path: '/api/auth/me', summary: 'Who the supplied credentials belong to.', access: 'any' },
      { method: 'GET', path: '/api/health', summary: 'Liveness check.', access: 'open' },
    ],
  },
];

export const SAMPLE_EVALUATE_BODY = {
  Applicant: { age: 34, creditScore: 640, income: 72000, employmentYears: 5 },
  Loan: { amount: 25000, termMonths: 48 },
};
