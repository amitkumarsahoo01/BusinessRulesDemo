import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ModelsApiService } from '../../core/api/models-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { DataTypeImportResult, ModelSummary, ModelVersion, ValidationMessage } from '../../core/models/rules.models';
import { DmnDiagramEditor } from '../../shared/dmn-editor/dmn-diagram-editor';
import { SchemaImportPanel } from './schema-import-panel';

/** Value of the model picker that starts a blank model instead of opening an existing one. */
export const NEW_MODEL = '__new__';
const MODEL_KEY = /^[a-z0-9][a-z0-9-]{1,62}$/;

/**
 * The full DMN diagram editor (Apache KIE) for power users: draw decisions, inputs, requirements
 * and boxed expressions. Saving never publishes. On an existing model it updates the draft in
 * progress, or branches a new draft from the latest published version (which stays unchanged).
 * A new model is imported as version 1.0.0 in DRAFT. Either way it then goes through the usual
 * validate, test, approve and publish steps on the model page.
 */
@Component({
  selector: 'app-dmn-editor-page',
  imports: [FormsModule, RouterLink, DmnDiagramEditor, SchemaImportPanel],
  templateUrl: './dmn-editor-page.html',
  styleUrl: './dmn-editor-page.scss',
})
export class DmnEditorPage implements OnInit {
  private readonly modelsApi = inject(ModelsApiService);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly editor = viewChild(DmnDiagramEditor);

  readonly newModel = NEW_MODEL;
  readonly readOnly = computed(() => this.auth.currentUser()?.role === 'auditor');

  readonly models = signal<ModelSummary[]>([]);
  readonly selectedKey = signal<string | null>(null);
  readonly version = signal<ModelVersion | null>(null);
  /** What the editor shows; null until something is chosen. */
  readonly xml = signal<string | null>(null);
  readonly dirty = signal(false);

  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly validating = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly validation = signal<ValidationMessage[] | null>(null);
  readonly showSchemaImport = signal(false);

  changeNote = '';
  newModelKey = '';

  readonly isNew = computed(() => this.selectedKey() === NEW_MODEL);
  readonly isDraft = computed(() => {
    const v = this.version();
    return !!v && v.status !== 'PUBLISHED' && v.status !== 'RETIRED';
  });
  readonly fileName = computed(() => `${this.isNew() ? 'new-model' : this.selectedKey()}.dmn`);

  ngOnInit(): void {
    this.modelsApi.listModels().subscribe({
      next: (models) => {
        this.models.set(models);
        const requested = this.route.snapshot.queryParamMap.get('model');
        if (requested && (requested === NEW_MODEL || models.some((m) => m.modelKey === requested))) {
          this.select(requested);
        }
      },
      error: (e: HttpErrorResponse) => this.error.set(this.describe(e, 'Could not load models.')),
    });
  }

  select(key: string | null): void {
    if (!key || key === this.selectedKey()) {
      return;
    }
    this.selectedKey.set(key);
    this.version.set(null);
    this.validation.set(null);
    this.notice.set(null);
    this.error.set(null);
    this.dirty.set(false);
    this.showSchemaImport.set(false);
    this.changeNote = '';
    void this.router.navigate([], { relativeTo: this.route, queryParams: { model: key }, replaceUrl: true });
    if (key === NEW_MODEL) {
      this.newModelKey = '';
      this.xml.set('');
      return;
    }
    this.loadVersion(key);
  }

  private loadVersion(key: string): void {
    this.loading.set(true);
    this.modelsApi.listVersions(key).subscribe({
      next: (versions) => {
        this.loading.set(false);
        const inProgress = versions.find((v) => v.status !== 'PUBLISHED' && v.status !== 'RETIRED');
        const latestPublished = [...versions].reverse().find((v) => v.status === 'PUBLISHED');
        const current = inProgress ?? latestPublished ?? versions[versions.length - 1] ?? null;
        this.version.set(current);
        this.xml.set(current?.dmnXml ?? '');
      },
      error: (e: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(this.describe(e, 'Could not load this model.'));
      },
    });
  }

  onDirty(dirty: boolean): void {
    this.dirty.set(dirty);
    if (dirty) {
      this.notice.set(null);
    }
  }

  async save(): Promise<void> {
    const editor = this.editor();
    const key = this.selectedKey();
    if (!editor || !key || this.readOnly()) {
      return;
    }
    if (this.isNew() && !MODEL_KEY.test(this.newModelKey.trim())) {
      this.error.set('Model key: 2–63 lowercase letters, digits or dashes, starting with a letter or digit (e.g. shipping-fees).');
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);
    let xml: string;
    try {
      xml = await editor.getContent();
    } catch (e) {
      this.saving.set(false);
      this.error.set(e instanceof Error ? e.message : 'Could not read the diagram.');
      return;
    }
    if (this.isNew()) {
      this.saveNew(xml, this.newModelKey.trim());
    } else {
      this.saveDraft(key, xml);
    }
  }

  private saveDraft(key: string, xml: string): void {
    const wasPublished = !this.isDraft();
    this.modelsApi
      .saveDraft(key, { dmnXml: xml, guidedTableEdit: null, changeNote: this.changeNote.trim() || 'Edited in DMN editor' })
      .subscribe({
        next: (saved) => {
          this.saving.set(false);
          this.editor()?.markAsSaved(saved.dmnXml);
          this.dirty.set(false);
          this.version.set(saved);
          this.xml.set(saved.dmnXml);
          this.validation.set(null);
          this.notice.set(
            wasPublished
              ? `Saved as new draft v${saved.version}. The published version is unchanged until this draft is approved and published.`
              : `Draft v${saved.version} updated.`,
          );
          this.refreshModels();
        },
        error: (e: HttpErrorResponse) => {
          this.saving.set(false);
          this.error.set(this.describe(e, 'Could not save the draft.'));
        },
      });
  }

  private saveNew(xml: string, modelKey: string): void {
    const file = new File([xml], `${modelKey}.dmn`, { type: 'application/xml' });
    this.modelsApi.importDmn(file, modelKey).subscribe({
      next: (result) => {
        this.saving.set(false);
        this.editor()?.markAsSaved(result.version.dmnXml);
        this.dirty.set(false);
        this.refreshModels();
        // Switch the page to the model that now exists, without reloading the editor.
        this.selectedKey.set(result.version.modelKey);
        this.version.set(result.version);
        this.xml.set(result.version.dmnXml);
        this.validation.set(result.messages);
        this.notice.set(`Created model "${result.version.modelKey}" as draft v${result.version.version}.`);
        void this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { model: result.version.modelKey },
          replaceUrl: true,
        });
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        this.error.set(this.describe(e, 'Could not create the model.'));
      },
    });
  }

  validate(): void {
    const key = this.selectedKey();
    const v = this.version();
    if (!key || !v || !this.isDraft()) {
      return;
    }
    this.validating.set(true);
    this.error.set(null);
    this.modelsApi.validate(key, v.id).subscribe({
      next: (result) => {
        this.validating.set(false);
        this.validation.set(result.messages);
        if (result.ok) {
          this.version.set({ ...v, status: 'VALIDATED' });
        }
      },
      error: (e: HttpErrorResponse) => {
        this.validating.set(false);
        this.error.set(this.describe(e, 'Validation failed.'));
      },
    });
  }

  /** Throws away unsaved edits: back to the saved version (or an empty model). */
  discard(): void {
    const saved = this.isNew() ? '' : (this.version()?.dmnXml ?? '');
    this.editor()?.reload(saved);
    this.dirty.set(false);
    this.notice.set(null);
  }

  async download(): Promise<void> {
    const editor = this.editor();
    if (!editor) {
      return;
    }
    const xml = await editor.getContent();
    const url = URL.createObjectURL(new Blob([xml], { type: 'application/xml' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = this.fileName();
    link.click();
    URL.revokeObjectURL(url);
  }

  /** Import replaces the editor content with the saved draft, so it waits until edits are saved. */
  canImportSchema(): boolean {
    return !this.readOnly() && !this.isNew() && !!this.version() && !this.dirty() && !this.saving();
  }

  onSchemaImported(result: DataTypeImportResult): void {
    const saved = result.version;
    if (!saved) {
      return;
    }
    const wasPublished = !this.isDraft();
    this.showSchemaImport.set(false);
    this.version.set(saved);
    // A new [xml] value makes the open editor load the updated draft in place.
    this.xml.set(saved.dmnXml);
    this.validation.set(result.messages);
    const names = result.types.map((t) => t.name).join(', ');
    const inputs = result.inputs.length ? ` and ${result.inputs.length} input node(s)` : '';
    this.notice.set(
      `Imported ${result.types.length} data type(s) (${names})${inputs} into ${wasPublished ? 'new ' : ''}draft v${saved.version}. ` +
        'Open the Data types tab to see them.',
    );
    this.refreshModels();
  }

  undo(): void {
    this.editor()?.undo();
  }

  redo(): void {
    this.editor()?.redo();
  }

  hasErrors(messages: ValidationMessage[]): boolean {
    return messages.some((m) => m.severity === 'ERROR');
  }

  private refreshModels(): void {
    this.modelsApi.listModels().subscribe((models) => this.models.set(models));
  }

  private describe(e: HttpErrorResponse, fallback: string): string {
    return (e.error && (e.error.detail as string)) || fallback;
  }
}
