import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ModelsApiService } from '../../core/api/models-api.service';
import { DataTypeImportResult, ItemDefinition } from '../../core/models/rules.models';

const MAX_FILE_BYTES = 512 * 1024;

/**
 * Imports a JSON Schema as DMN data types. Preview first (nothing is written), then import into
 * the draft. The server does the conversion, so the rules (and their tests) live in one place.
 */
@Component({
  selector: 'app-schema-import-panel',
  imports: [FormsModule, NgTemplateOutlet],
  templateUrl: './schema-import-panel.html',
  styleUrl: './schema-import-panel.scss',
})
export class SchemaImportPanel {
  private readonly api = inject(ModelsApiService);

  readonly modelKey = input.required<string>();
  /** Fires with the server's result once the types are in the draft. */
  readonly imported = output<DataTypeImportResult>();
  readonly closed = output<void>();

  schemaText = '';
  rootName = '';
  replaceExisting = false;
  createInputs = false;

  readonly preview = signal<DataTypeImportResult | null>(null);
  readonly busy = signal<'preview' | 'import' | null>(null);
  readonly error = signal<string | null>(null);

  onFile(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) {
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      this.error.set('The schema file is larger than 512 KB.');
      return;
    }
    void file.text().then((text) => {
      this.schemaText = text;
      this.invalidate();
    });
  }

  /** Any change to the inputs makes the shown preview stale. */
  invalidate(): void {
    this.preview.set(null);
    this.error.set(null);
  }

  runPreview(): void {
    this.send(false);
  }

  runImport(): void {
    this.send(true);
  }

  private send(apply: boolean): void {
    const text = this.schemaText.trim();
    if (!text) {
      this.error.set('Paste a JSON Schema or choose a .json file.');
      return;
    }
    try {
      JSON.parse(text);
    } catch (e) {
      this.error.set(`That is not valid JSON: ${e instanceof Error ? e.message : e}`);
      return;
    }
    this.busy.set(apply ? 'import' : 'preview');
    this.error.set(null);
    this.api
      .importDataTypes(this.modelKey(), {
        schema: text,
        rootName: this.rootName.trim() || null,
        replaceExisting: this.replaceExisting,
        createInputs: this.createInputs,
        apply,
        changeNote: null,
      })
      .subscribe({
        next: (result) => {
          this.busy.set(null);
          if (apply) {
            this.imported.emit(result);
          } else {
            this.preview.set(result);
          }
        },
        error: (e: HttpErrorResponse) => {
          this.busy.set(null);
          this.error.set((e.error && (e.error.detail as string)) || 'The schema could not be imported.');
        },
      });
  }

  blockedByConflicts(p: DataTypeImportResult): boolean {
    return p.conflicts.length > 0 && !this.replaceExisting;
  }

  describe(t: ItemDefinition): string {
    if (t.components.length) {
      return t.collection ? 'list of structures' : 'structure';
    }
    return (t.collection ? 'list of ' : '') + (t.typeRef ?? 'Any');
  }
}
