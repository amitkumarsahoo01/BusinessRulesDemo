import { DatePipe, JsonPipe } from '@angular/common';
import { Component, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ModelsApiService } from '../../core/api/models-api.service';
import {
  DecisionDiff,
  DecisionRule,
  DecisionSummary,
  DecisionTable,
  ModelSummary,
  ModelVersion,
  RuleChangeAudit,
  TestCase,
  TestRunResult,
  ValidationResult,
} from '../../core/models/rules.models';
import { TraceView } from '../../shared/trace-view/trace-view';

const HIT_POLICIES = ['UNIQUE', 'FIRST', 'PRIORITY', 'ANY', 'COLLECT', 'RULE_ORDER', 'OUTPUT_ORDER'];

@Component({
  selector: 'app-rule-editor-page',
  imports: [FormsModule, RouterLink, TraceView, JsonPipe, DatePipe],
  templateUrl: './rule-editor-page.html',
  styleUrl: './rule-editor-page.scss',
})
export class RuleEditorPage implements OnInit {
  readonly hitPolicies = HIT_POLICIES;

  readonly modelKey = signal('');
  readonly model = signal<ModelSummary | null>(null);
  readonly versions = signal<ModelVersion[]>([]);
  readonly currentVersion = signal<ModelVersion | null>(null);
  readonly decisions = signal<DecisionSummary[]>([]);
  readonly selectedDecisionName = signal<string | null>(null);
  readonly decisionTable = signal<DecisionTable | null>(null);

  readonly editableHitPolicy = signal('FIRST');
  readonly editableRules = signal<DecisionRule[]>([]);

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly validationResult = signal<ValidationResult | null>(null);
  readonly testRunResult = signal<TestRunResult | null>(null);
  readonly busyAction = signal<string | null>(null);
  readonly actionError = signal<string | null>(null);

  readonly testCases = signal<TestCase[]>([]);
  newTestCaseName = '';
  newTestCaseInputsJson = '{}';
  newTestCaseExpectedJson = '{}';
  readonly testCaseError = signal<string | null>(null);

  readonly diffFromVersion = signal<string | null>(null);
  readonly diffToVersion = signal<string | null>(null);
  readonly diffResult = signal<DecisionDiff[] | null>(null);

  readonly history = signal<RuleChangeAudit[]>([]);
  rollbackToVersion = '';

  readonly isCurrentDraft = computed(() => {
    const v = this.currentVersion();
    return v !== null && v.status !== 'PUBLISHED' && v.status !== 'RETIRED';
  });

  readonly selectedDecisionIsTable = computed(
    () => this.decisions().find((d) => d.name === this.selectedDecisionName())?.kind === 'DECISION_TABLE',
  );

  constructor(
    private readonly route: ActivatedRoute,
    private readonly modelsApi: ModelsApiService,
  ) {}

  ngOnInit(): void {
    const key = this.route.snapshot.paramMap.get('modelKey')!;
    this.modelKey.set(key);
    this.loadAll();
  }

  private loadAll(): void {
    this.loading.set(true);
    this.modelsApi.listModels().subscribe((models) => {
      this.model.set(models.find((m) => m.modelKey === this.modelKey()) ?? null);
    });
    this.modelsApi.listVersions(this.modelKey()).subscribe((versions) => {
      this.versions.set(versions);
      const inProgress = versions.find((v) => !['PUBLISHED', 'RETIRED'].includes(v.status));
      const latestPublished = [...versions].reverse().find((v) => v.status === 'PUBLISHED');
      const current = inProgress ?? latestPublished ?? null;
      this.currentVersion.set(current);
      if (versions.length >= 2) {
        this.diffFromVersion.set(versions[versions.length - 2].version);
        this.diffToVersion.set(versions[versions.length - 1].version);
      }
      if (current) {
        this.loadDecisions(current);
      } else {
        this.loading.set(false);
      }
    });
    this.loadTestCases();
    this.loadHistory();
  }

  private loadDecisions(version: ModelVersion): void {
    this.modelsApi.listDecisions(this.modelKey(), version.id).subscribe((decisions) => {
      this.decisions.set(decisions);
      const firstTable = decisions.find((d) => d.kind === 'DECISION_TABLE');
      if (firstTable) {
        this.selectDecision(firstTable.name);
      } else {
        this.loading.set(false);
      }
    });
  }

  selectDecision(name: string): void {
    this.selectedDecisionName.set(name);
    this.decisionTable.set(null);
    const version = this.currentVersion();
    if (!version) {
      return;
    }
    const kind = this.decisions().find((d) => d.name === name)?.kind;
    if (kind !== 'DECISION_TABLE') {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.modelsApi.readDecisionTable(this.modelKey(), version.id, name).subscribe((table) => {
      this.decisionTable.set(table);
      this.editableHitPolicy.set(table.hitPolicy);
      this.editableRules.set(table.rules.map((r) => ({ ...r, inputEntries: [...r.inputEntries] })));
      this.loading.set(false);
    });
  }

  addRow(): void {
    const columnCount = this.decisionTable()?.inputs.length ?? 1;
    this.editableRules.set([
      ...this.editableRules(),
      { id: null, description: '', inputEntries: Array(columnCount).fill('-'), outputEntry: '""' },
    ]);
  }

  removeRow(index: number): void {
    const rules = [...this.editableRules()];
    rules.splice(index, 1);
    this.editableRules.set(rules);
  }

  updateCell(ruleIndex: number, columnIndex: number, value: string): void {
    const rules = [...this.editableRules()];
    const entries = [...rules[ruleIndex].inputEntries];
    entries[columnIndex] = value;
    rules[ruleIndex] = { ...rules[ruleIndex], inputEntries: entries };
    this.editableRules.set(rules);
  }

  updateOutput(ruleIndex: number, value: string): void {
    const rules = [...this.editableRules()];
    rules[ruleIndex] = { ...rules[ruleIndex], outputEntry: value };
    this.editableRules.set(rules);
  }

  updateDescription(ruleIndex: number, value: string): void {
    const rules = [...this.editableRules()];
    rules[ruleIndex] = { ...rules[ruleIndex], description: value };
    this.editableRules.set(rules);
  }

  saveDraft(): void {
    const decisionName = this.selectedDecisionName();
    if (!decisionName) {
      return;
    }
    this.saving.set(true);
    this.actionError.set(null);
    this.modelsApi
      .saveDraft(this.modelKey(), {
        dmnXml: null,
        guidedTableEdit: {
          decisionName,
          hitPolicy: this.editableHitPolicy(),
          rules: this.editableRules(),
        },
        changeNote: `Edited ${decisionName} via guided editor`,
      })
      .subscribe({
        next: (version) => {
          this.saving.set(false);
          this.currentVersion.set(version);
          this.validationResult.set(null);
          this.testRunResult.set(null);
          this.refreshVersionsAndHistory();
        },
        error: (err) => {
          this.saving.set(false);
          this.actionError.set(err?.error?.detail ?? 'Failed to save draft.');
        },
      });
  }

  validate(): void {
    const version = this.currentVersion();
    if (!version) return;
    this.busyAction.set('validate');
    this.actionError.set(null);
    this.modelsApi.validate(this.modelKey(), version.id).subscribe({
      next: (result) => {
        this.busyAction.set(null);
        this.validationResult.set(result);
        if (result.ok) {
          this.patchCurrentVersionStatus('VALIDATED');
        }
      },
      error: (err) => {
        this.busyAction.set(null);
        this.actionError.set(err?.error?.detail ?? 'Validation failed.');
      },
    });
  }

  runTests(): void {
    const version = this.currentVersion();
    if (!version) return;
    this.busyAction.set('test');
    this.actionError.set(null);
    this.modelsApi.runTests(this.modelKey(), version.id).subscribe({
      next: (result) => {
        this.busyAction.set(null);
        this.testRunResult.set(result);
        if (result.allPassed) {
          this.patchCurrentVersionStatus('TESTED');
        }
      },
      error: (err) => {
        this.busyAction.set(null);
        this.actionError.set(err?.error?.detail ?? 'Test run failed.');
      },
    });
  }

  approve(): void {
    const version = this.currentVersion();
    if (!version) return;
    this.busyAction.set('approve');
    this.actionError.set(null);
    this.modelsApi.approve(this.modelKey(), version.id).subscribe({
      next: (updated) => {
        this.busyAction.set(null);
        this.currentVersion.set(updated);
        this.refreshVersionsAndHistory();
      },
      error: (err) => {
        this.busyAction.set(null);
        this.actionError.set(err?.error?.detail ?? 'Approval failed.');
      },
    });
  }

  publish(): void {
    const version = this.currentVersion();
    if (!version) return;
    this.busyAction.set('publish');
    this.actionError.set(null);
    this.modelsApi.publish(this.modelKey(), version.id).subscribe({
      next: (updated) => {
        this.busyAction.set(null);
        this.currentVersion.set(updated);
        this.loadAll();
      },
      error: (err) => {
        this.busyAction.set(null);
        this.actionError.set(err?.error?.detail ?? 'Publish failed.');
      },
    });
  }

  rollback(): void {
    if (!this.rollbackToVersion) return;
    this.busyAction.set('rollback');
    this.actionError.set(null);
    this.modelsApi.rollback(this.modelKey(), this.rollbackToVersion).subscribe({
      next: () => {
        this.busyAction.set(null);
        this.rollbackToVersion = '';
        this.loadAll();
      },
      error: (err) => {
        this.busyAction.set(null);
        this.actionError.set(err?.error?.detail ?? 'Rollback failed.');
      },
    });
  }

  addTestCase(): void {
    this.testCaseError.set(null);
    let inputs: Record<string, unknown>;
    let expected: Record<string, unknown>;
    try {
      inputs = JSON.parse(this.newTestCaseInputsJson);
      expected = JSON.parse(this.newTestCaseExpectedJson);
    } catch {
      this.testCaseError.set('Inputs and expected outputs must be valid JSON.');
      return;
    }
    if (!this.newTestCaseName.trim()) {
      this.testCaseError.set('Give the test case a name.');
      return;
    }
    this.modelsApi
      .createTestCase(this.modelKey(), { name: this.newTestCaseName, inputs, expectedOutputs: expected })
      .subscribe({
        next: () => {
          this.newTestCaseName = '';
          this.newTestCaseInputsJson = '{}';
          this.newTestCaseExpectedJson = '{}';
          this.loadTestCases();
        },
        error: () => this.testCaseError.set('Failed to save test case.'),
      });
  }

  loadDiff(): void {
    const from = this.diffFromVersion();
    const to = this.diffToVersion();
    if (!from || !to) return;
    this.modelsApi.diff(this.modelKey(), from, to).subscribe((result) => this.diffResult.set(result));
  }

  private loadTestCases(): void {
    this.modelsApi.listTestCases(this.modelKey()).subscribe((cases) => this.testCases.set(cases));
  }

  private loadHistory(): void {
    this.modelsApi.history(this.modelKey()).subscribe((rows) => this.history.set(rows));
  }

  private refreshVersionsAndHistory(): void {
    this.modelsApi.listVersions(this.modelKey()).subscribe((versions) => this.versions.set(versions));
    this.loadHistory();
    this.modelsApi.listModels().subscribe((models) => {
      this.model.set(models.find((m) => m.modelKey === this.modelKey()) ?? null);
    });
  }

  private patchCurrentVersionStatus(status: ModelVersion['status']): void {
    const v = this.currentVersion();
    if (v) {
      this.currentVersion.set({ ...v, status });
    }
  }
}
