import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ModelsApiService } from '../../core/api/models-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { ImportResult, ModelSummary } from '../../core/models/rules.models';

const MAX_UPLOAD_BYTES = 2 * 1024 * 1024;

@Component({
  selector: 'app-rules-list',
  imports: [RouterLink],
  templateUrl: './rules-list.html',
  styleUrl: './rules-list.scss',
})
export class RulesList implements OnInit {
  readonly models = signal<ModelSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  /** Auditors are read-only (see the login page); everyone else may import drafts. */
  readonly canImport = computed(() => this.auth.currentUser()?.role !== 'auditor');
  readonly importing = signal(false);
  readonly importError = signal<string | null>(null);
  readonly dragging = signal(false);
  /** Set after a successful import that came back with validation findings the user should see. */
  readonly importResult = signal<ImportResult | null>(null);

  constructor(
    private readonly modelsApi: ModelsApiService,
    private readonly router: Router,
    private readonly auth: AuthService,
  ) {}

  ngOnInit(): void {
    this.loadModels();
  }

  private loadModels(): void {
    this.modelsApi.listModels().subscribe({
      next: (models) => {
        this.models.set(models);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load models.');
        this.loading.set(false);
      },
    });
  }

  onFileChosen(event: Event, modelKey: string): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) {
      this.importFile(file, modelKey);
    }
  }

  onDrop(event: DragEvent, modelKey: string): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      this.importFile(file, modelKey);
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  private importFile(file: File, modelKey: string): void {
    this.importError.set(null);
    this.importResult.set(null);
    if (!/\.(dmn|xml)$/i.test(file.name)) {
      this.importError.set('Choose a .dmn (or .xml) file.');
      return;
    }
    if (file.size > MAX_UPLOAD_BYTES) {
      this.importError.set('That file is larger than the 2 MB limit.');
      return;
    }
    this.importing.set(true);
    this.modelsApi.importDmn(file, modelKey).subscribe({
      next: (result) => {
        this.importing.set(false);
        const findings = result.messages.filter((m) => m.severity === 'ERROR' || m.severity === 'WARN');
        if (findings.length === 0) {
          void this.router.navigate(['/rules', result.version.modelKey]);
          return;
        }
        // Imported, but the user should see what the validator found before moving on.
        this.importResult.set(result);
        this.loadModels();
      },
      error: (err: HttpErrorResponse) => {
        this.importing.set(false);
        this.importError.set(err.error?.detail ?? 'Import failed. Is this a valid DMN file?');
      },
    });
  }

  dismissResult(): void {
    this.importResult.set(null);
  }
}
