import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DataTypeImportResult } from '../../core/models/rules.models';
import { SchemaImportPanel } from './schema-import-panel';

const URL = '/api/models/loan-eligibility/data-types/import';
const SCHEMA = '{"title":"Guarantor","type":"object","properties":{"age":{"type":"integer","minimum":18}}}';

function result(overrides: Partial<DataTypeImportResult> = {}): DataTypeImportResult {
  return {
    baseVersion: '1.2.0',
    types: [
      {
        name: 'Guarantor',
        typeRef: null,
        collection: false,
        allowedValues: null,
        components: [{ name: 'age', typeRef: 'number', collection: false, allowedValues: '>= 18', components: [] }],
      },
    ],
    inputs: [],
    skippedInputs: [],
    conflicts: [],
    warnings: [],
    applied: false,
    version: null,
    messages: [],
    ...overrides,
  };
}

describe('SchemaImportPanel', () => {
  let httpMock: HttpTestingController;

  function create() {
    TestBed.configureTestingModule({
      imports: [SchemaImportPanel],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(SchemaImportPanel);
    fixture.componentRef.setInput('modelKey', 'loan-eligibility');
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => httpMock.verify());

  it('previews first without writing, then imports and reports the result', () => {
    const fixture = create();
    const panel = fixture.componentInstance;
    const imported = vi.fn();
    panel.imported.subscribe(imported);
    panel.schemaText = SCHEMA;
    panel.createInputs = true;

    panel.runPreview();
    const preview = httpMock.expectOne(URL);
    expect(preview.request.body).toEqual({
      schema: SCHEMA, rootName: null, replaceExisting: false, createInputs: true, apply: false, changeNote: null,
    });
    preview.flush(result({ inputs: ['age'], warnings: ['"required" was ignored'] }));
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid=schema-preview]')?.textContent).toContain('Guarantor');
    expect(el.textContent).toContain('>= 18');
    expect(el.textContent).toContain('New input data nodes: age');
    expect(el.textContent).toContain('"required" was ignored');

    panel.runImport();
    const apply = httpMock.expectOne(URL);
    expect(apply.request.body.apply).toBe(true);
    apply.flush(result({ applied: true }));
    expect(imported).toHaveBeenCalledWith(expect.objectContaining({ applied: true }));
  });

  it('blocks the import while names clash, until replacing is ticked', async () => {
    const fixture = create();
    const panel = fixture.componentInstance;
    panel.schemaText = SCHEMA;
    panel.runPreview();
    httpMock.expectOne(URL).flush(result({ conflicts: ['Guarantor'] }));
    fixture.detectChanges();

    const importButton = () =>
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) =>
        b.textContent?.includes('into draft'),
      ) as HTMLButtonElement;
    expect(importButton().disabled).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Already in the model: Guarantor');

    // Ticking the real checkbox (as a user would) keeps the preview and unblocks the import.
    const replace = (fixture.nativeElement as HTMLElement).querySelector('input[name=replaceExisting]') as HTMLInputElement;
    replace.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(panel.preview()).not.toBeNull();
    expect(importButton().disabled).toBe(false);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('They will be replaced.');
  });

  it('catches invalid JSON locally and shows server errors', () => {
    const fixture = create();
    const panel = fixture.componentInstance;
    panel.schemaText = '{nope';
    panel.runPreview();
    httpMock.expectNone(URL);
    expect(panel.error()).toContain('not valid JSON');

    panel.schemaText = '{"type":"string"}';
    panel.runPreview();
    httpMock.expectOne(URL).flush({ detail: 'The schema defines no object types to import' }, { status: 400, statusText: 'Bad Request' });
    expect(panel.error()).toContain('no object types');
    expect(panel.preview()).toBeNull();
  });
});
