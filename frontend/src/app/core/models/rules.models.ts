import { TraceStep } from './audit.models';

export interface ModelSummary {
  modelKey: string;
  name: string;
  namespace: string;
  owner: string;
  latestPublishedVersion: string | null;
  inProgressVersion: string | null;
  inProgressStatus: string | null;
}

export interface ModelVersion {
  id: string;
  modelKey: string;
  version: string;
  status: 'DRAFT' | 'VALIDATED' | 'TESTED' | 'APPROVED' | 'PUBLISHED' | 'RETIRED';
  dmnXml: string;
  xmlSha256: string;
  changeNote: string | null;
  authoredBy: string;
  approvedBy: string | null;
  createdAt: string;
  publishedAt: string | null;
}

export interface DecisionSummary {
  name: string;
  kind: 'LITERAL_EXPRESSION' | 'DECISION_TABLE';
}

export interface DecisionTableColumn {
  label: string | null;
  expression: string | null;
  typeRef: string | null;
}

export interface DecisionRule {
  id: string | null;
  description: string | null;
  inputEntries: string[];
  outputEntry: string | null;
}

export interface DecisionTable {
  decisionName: string;
  hitPolicy: string;
  inputs: DecisionTableColumn[];
  output: DecisionTableColumn | null;
  rules: DecisionRule[];
}

export interface GuidedTableEdit {
  decisionName: string;
  hitPolicy: string;
  rules: DecisionRule[];
}

export interface UpdateDraftRequest {
  dmnXml: string | null;
  guidedTableEdit: GuidedTableEdit | null;
  changeNote: string | null;
}

export interface ValidationMessage {
  severity: 'TRACE' | 'INFO' | 'WARN' | 'ERROR';
  message: string;
  sourceId: string | null;
}

export interface ValidationResult {
  ok: boolean;
  status: string;
  messages: ValidationMessage[];
}

export interface TestCaseResult {
  testCaseId: string;
  name: string;
  passed: boolean;
  actualOutputs: Record<string, unknown>;
  expectedOutputs: Record<string, unknown>;
  trace: TraceStep[];
}

export interface TestRunResult {
  allPassed: boolean;
  status: string;
  results: TestCaseResult[];
}

export interface TestCase {
  id: string;
  modelKey: string;
  name: string;
  inputs: Record<string, unknown>;
  expectedOutputs: Record<string, unknown>;
  createdBy: string;
  createdAt: string;
}

export interface CreateTestCaseRequest {
  name: string;
  inputs: Record<string, unknown>;
  expectedOutputs: Record<string, unknown>;
}

export interface RuleDiff {
  changeType: 'ADDED' | 'REMOVED' | 'CHANGED';
  ruleId: string;
  before: string | null;
  after: string | null;
}

export interface DecisionDiff {
  decisionName: string;
  kind: 'LITERAL_EXPRESSION' | 'DECISION_TABLE';
  changeType: 'ADDED' | 'REMOVED' | 'CHANGED' | 'UNCHANGED';
  beforeText: string | null;
  afterText: string | null;
  ruleDiffs: RuleDiff[];
}

export interface RuleChangeAudit {
  id: number;
  modelKey: string;
  versionId: string | null;
  action: string;
  actor: string;
  diffJson: string | null;
  at: string;
}

export type GraphNodeType = 'INPUT_DATA' | 'DECISION' | 'BKM' | 'KNOWLEDGE_SOURCE';

export interface GraphNode {
  id: string;
  name: string;
  type: GraphNodeType;
  typeRef: string | null;
  kind: 'DECISION_TABLE' | 'LITERAL_EXPRESSION' | 'FUNCTION' | 'OTHER' | null;
  hitPolicy: string | null;
  ruleCount: number | null;
  expressionText: string | null;
  description: string | null;
}

export interface GraphEdge {
  from: string;
  to: string;
  kind: 'INFORMATION' | 'KNOWLEDGE' | 'AUTHORITY';
}

export interface ItemDefinition {
  name: string;
  typeRef: string | null;
  collection: boolean;
  allowedValues: string | null;
  components: ItemDefinition[];
}

export interface ModelGraph {
  name: string | null;
  namespace: string | null;
  nodes: GraphNode[];
  edges: GraphEdge[];
  itemDefinitions: ItemDefinition[];
}

export interface ImportResult {
  version: ModelVersion;
  newModel: boolean;
  messages: ValidationMessage[];
}
