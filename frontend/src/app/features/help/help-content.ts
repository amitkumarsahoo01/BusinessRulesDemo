export interface HelpStep {
  title: string;
  /** Where in the UI to do it. */
  where: string;
  detail: string;
}

export interface RoleGuide {
  /** Matches the `role` value returned by the login API. */
  role: string;
  label: string;
  summary: string;
  steps: HelpStep[];
  /** Things this role cannot do, so users are not surprised. */
  limits: string[];
}

/** Lifecycle shown to everyone, so each role can see where its steps fit. */
export const LIFECYCLE = ['DRAFT', 'VALIDATED', 'TESTED', 'APPROVED', 'PUBLISHED'];

export const ROLE_GUIDES: RoleGuide[] = [
  {
    role: 'rule-author',
    label: 'Rule author (analyst)',
    summary: 'You create and change rules as drafts and prove they work. Someone else approves and publishes them.',
    steps: [
      {
        title: 'Find a model',
        where: 'Rules',
        detail: 'Each card is a model. The badges show the latest published version and any draft in progress.',
      },
      {
        title: 'Import a DMN file',
        where: 'Rules → Import a DMN file',
        detail:
          'Drop a .dmn file (up to 2 MB). A new model key becomes v1.0.0; an existing key gets its next draft version. Imports are always drafts and never go live directly.',
      },
      {
        title: 'Understand the model',
        where: 'Open a model → Structure tab',
        detail:
          'Shows inputs, decisions, business knowledge and how they connect. Click an element for details, and see data types and allowed values below the diagram.',
      },
      {
        title: 'Edit a decision table',
        where: 'Open a model → Decision tables tab',
        detail:
          'Change cells, add or remove rows, or change the hit policy, then click Save draft. Published versions are never changed: saving creates or updates a draft.',
      },
      {
        title: 'Draw or restructure the model',
        where: 'DMN Editor',
        detail:
          'The full diagram editor: add inputs, decisions and requirements, edit tables and boxed expressions, or start a new model. Save draft works like the table editor (a published version is never changed), and Create draft model adds a new model as v1.0.0.',
      },
      {
        title: 'Or describe a change in English',
        where: 'Decision tables tab → Describe a rule',
        detail: 'The AI proposes the change as a draft. It never publishes anything.',
      },
      {
        title: 'Add test cases',
        where: 'Decision tables tab → Test bench',
        detail: 'Give a name, the inputs as JSON, and the expected outputs as JSON, then Save test case.',
      },
      {
        title: 'Validate and run tests',
        where: 'Decision tables tab → Validate, Run saved tests',
        detail:
          'Validate checks the model. Run saved tests compares expected with actual results and shows the trace. When both pass, the status moves to TESTED.',
      },
      {
        title: 'Hand over for approval',
        where: 'Tell an approver',
        detail: 'You cannot approve your own change. Ask an approver to open the model and approve it.',
      },
      {
        title: 'Compare versions',
        where: 'Bottom of a model page → Version diff',
        detail: 'Pick two versions and click Show diff to see which rules changed.',
      },
    ],
    limits: ['You cannot approve a version you authored.'],
  },
  {
    role: 'rule-approver',
    label: 'Rule approver',
    summary: 'You review tested drafts, approve them and publish them. You can also roll back.',
    steps: [
      {
        title: 'Find drafts waiting for you',
        where: 'Rules',
        detail: 'Cards with a TESTED badge are ready for review.',
      },
      {
        title: 'Review the change',
        where: 'Open a model → Version diff',
        detail: 'Select the published version and the new draft, then Show diff. Check the Structure tab to see the wider model.',
      },
      {
        title: 'Check the evidence',
        where: 'Decision tables tab → Run saved tests',
        detail: 'Confirm the tests pass and expand a result to read its trace.',
      },
      {
        title: 'Approve',
        where: 'Decision tables tab → Approve',
        detail: 'Enabled only when the status is TESTED. The approver must be a different person from the author.',
      },
      {
        title: 'Publish',
        where: 'Decision tables tab → Publish',
        detail: 'Enabled when the status is APPROVED. The version becomes live and immutable.',
      },
      {
        title: 'Roll back',
        where: 'Bottom of a model page → Rollback to…',
        detail: 'Pick an earlier published version and click Republish this version if a release causes problems.',
      },
      {
        title: 'Investigate decisions',
        where: 'Audit, then Ask Bob (bottom-right)',
        detail: 'Search past executions and open one to see which rows matched and why.',
      },
    ],
    limits: ['You cannot approve a version you authored yourself.'],
  },
  {
    role: 'auditor',
    label: 'Auditor (read-only)',
    summary: 'You can see everything and change nothing. Use it to check how and why decisions were made.',
    steps: [
      {
        title: 'Browse models',
        where: 'Rules',
        detail: 'Open any model to see its status, versions and change history.',
      },
      {
        title: 'Read the structure',
        where: 'Open a model → Structure tab',
        detail: 'See inputs, decisions, data types and how they link. You can download the DMN XML.',
      },
      {
        title: 'Search past decisions',
        where: 'Audit',
        detail: 'Filter executions, then open one to see its inputs, outputs, model version and ordered trace.',
      },
      {
        title: 'Ask questions about the rules',
        where: 'Ask Bob (bottom-right on every page)',
        detail: 'Ask in plain English. Answers cite the model version, decision, row numbers and execution IDs, and are read-only.',
      },
    ],
    limits: [
      'The import panel is hidden for you.',
      'Editing, approving and publishing are blocked for your role, in the app and in the REST API.',
    ],
  },
  {
    role: 'admin',
    label: 'Administrator',
    summary: 'You choose which language model powers Ask Bob and keep its cost within budget. You do not author or approve rules.',
    steps: [
      {
        title: 'Check what is active',
        where: 'Admin',
        detail: 'The top of the page shows the current provider and model, and who set it.',
      },
      {
        title: 'Pick a provider and model',
        where: 'Admin → LLM provider',
        detail:
          'Choose Anthropic, OpenAI (or an OpenAI-compatible gateway), Google Gemini or a self-hosted Ollama, then pick a model. Providers whose API key is not set on the server cannot be chosen.',
      },
      {
        title: 'Test before you switch',
        where: 'Admin → Test connection',
        detail: 'Sends one tiny prompt with the new settings without changing anything for other users.',
      },
      {
        title: 'Save and apply',
        where: 'Admin → Save and apply',
        detail: 'Takes effect right away for new questions, with no restart. Every change is recorded in Change history.',
      },
      {
        title: 'Watch the spend and set a budget',
        where: 'Admin → LLM spend & budget',
        detail:
          'Shows what every model has cost this month (all providers combined) against a monthly budget. When the budget is used up, Ask Bob stops calling paid providers until the 1st; Ollama keeps working. Keep the price table in line with each provider pricing page.',
      },
    ],
    limits: [
      'API keys are never typed into the app; they come from server environment variables.',
      'Changing the model never changes a decision: decisions always come from the DMN engine.',
    ],
  },
];

export const COMMON_TIPS: string[] = [
  'Ask Bob (bottom-right on every page) answers questions about the page you are on.',
  'Only the DMN engine makes decisions. The AI explains and cites; it never decides.',
  'Every evaluation is recorded in the Audit area and cannot be edited.',
  'The first request after a quiet period can take several seconds on the cloud demo.',
];
