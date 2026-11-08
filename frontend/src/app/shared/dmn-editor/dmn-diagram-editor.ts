import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewEncapsulation,
  effect,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { DmnEditorApi, loadDmnEditor } from './dmn-editor-loader';

/**
 * Angular wrapper around the embedded Apache KIE DMN editor: opens it in ngAfterViewInit, swaps
 * content when [xml] changes, reports dirty state, and closes it on destroy. The editor only edits
 * XML in the browser. Saving is the parent's job, and the server turns it into a draft version
 * (published versions are never changed in place).
 */
@Component({
  selector: 'app-dmn-diagram-editor',
  template: `
    @if (status() === 'loading') {
      <div class="dmn-editor-state">Loading the DMN editor…</div>
    } @else if (status() === 'error') {
      <div class="dmn-editor-state error" role="alert">
        {{ error() }}
        <button type="button" class="btn" (click)="retry()">Try again</button>
      </div>
    }
    <div #host class="dmn-editor-host" [class.hidden]="status() !== 'ready'"></div>
  `,
  styles: `
    app-dmn-diagram-editor { display: block; position: relative; height: 100%; min-height: 520px; }
    app-dmn-diagram-editor .dmn-editor-host { position: absolute; inset: 0; }
    app-dmn-diagram-editor .dmn-editor-host.hidden { visibility: hidden; }
    app-dmn-diagram-editor .dmn-editor-host iframe { width: 100%; height: 100%; border: 0; display: block; }
    app-dmn-diagram-editor .dmn-editor-state {
      position: absolute; inset: 0; display: flex; gap: 0.75rem; align-items: center; justify-content: center;
      color: var(--color-muted); font-size: 0.9rem;
    }
    app-dmn-diagram-editor .dmn-editor-state.error { color: var(--color-danger); flex-direction: column; }
  `,
  // The editor appends an iframe that Angular's emulated encapsulation cannot reach; the selectors above are scoped by tag name.
  encapsulation: ViewEncapsulation.None,
})
export class DmnDiagramEditor implements AfterViewInit, OnDestroy {
  /** DMN XML to show. Empty string opens a new, empty model. */
  readonly xml = input.required<string>();
  readonly fileName = input('model.dmn');
  readonly readOnly = input(false);

  readonly dirtyChange = output<boolean>();
  readonly ready = output<void>();

  readonly status = signal<'loading' | 'ready' | 'error'>('loading');
  readonly error = signal<string | null>(null);

  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private api: DmnEditorApi | null = null;
  private appliedXml: string | null = null;
  private destroyed = false;
  private readonly onChange = (dirty: boolean) => this.dirtyChange.emit(dirty);

  constructor() {
    // Later [xml] changes (another model picked, or a reload after save) replace the content in place.
    effect(() => {
      const xml = this.xml();
      untracked(() => {
        if (this.api && xml !== this.appliedXml) {
          this.appliedXml = xml;
          void this.api.setContent(this.fileName(), xml).then(() => this.api?.markAsSaved());
        }
      });
    });
  }

  ngAfterViewInit(): void {
    void this.open();
  }

  retry(): void {
    void this.open();
  }

  private async open(): Promise<void> {
    this.status.set('loading');
    this.error.set(null);
    try {
      const editor = await loadDmnEditor();
      if (this.destroyed) {
        return;
      }
      this.appliedXml = this.xml();
      this.api = editor.open({
        container: this.host().nativeElement,
        initialContent: Promise.resolve(this.appliedXml),
        initialFileNormalizedPosixPathRelativeToTheWorkspaceRoot: this.fileName(),
        readOnly: this.readOnly(),
        onError: () => {
          this.status.set('error');
          this.error.set('The editor could not open this model. The XML may not be valid DMN.');
        },
      });
      this.api.subscribeToContentChanges(this.onChange);
      this.status.set('ready');
      this.ready.emit();
    } catch (e) {
      this.status.set('error');
      this.error.set(e instanceof Error ? e.message : 'Could not load the DMN editor.');
    }
  }

  /** The editor's current XML. */
  getContent(): Promise<string> {
    if (!this.api) {
      return Promise.reject(new Error('The DMN editor is not open yet.'));
    }
    return this.api.getContent();
  }

  /** Call after the content was persisted; clears the dirty flag. */
  markAsSaved(xml?: string): void {
    if (xml !== undefined) {
      this.appliedXml = xml;
    }
    this.api?.markAsSaved();
  }

  /** Replaces the content even if it equals the last [xml] value (used to discard edits). */
  reload(xml: string): void {
    this.appliedXml = xml;
    void this.api?.setContent(this.fileName(), xml).then(() => this.api?.markAsSaved());
  }

  undo(): void {
    void this.api?.undo();
  }

  redo(): void {
    void this.api?.redo();
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    if (this.api) {
      this.api.unsubscribeToContentChanges(this.onChange);
      this.api.close();
      this.api = null;
    }
  }
}
