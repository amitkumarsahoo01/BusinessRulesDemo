import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { ModelVersion } from '../../core/models/rules.models';
import { installFakeDmnEditor } from '../../shared/dmn-editor/fake-dmn-editor.testing';
import { DmnEditorPage, NEW_MODEL } from './dmn-editor-page';

const settle = () => new Promise((r) => setTimeout(r));

function version(overrides: Partial<ModelVersion>): ModelVersion {
  return {
    id: 'v-1',
    modelKey: 'loan-eligibility',
    version: '1.1.0',
    status: 'PUBLISHED',
    dmnXml: '<definitions id="published"/>',
    xmlSha256: 'x',
    changeNote: null,
    authoredBy: 'analyst',
    approvedBy: null,
    createdAt: '2026-11-01T00:00:00Z',
    publishedAt: null,
    ...overrides,
  };
}

describe('DmnEditorPage', () => {
  let httpMock: HttpTestingController;
  let fake: ReturnType<typeof installFakeDmnEditor>;

  function setup(query: Record<string, string> = {}, role = 'analyst') {
    localStorage.setItem('dmn-rules-demo.user', JSON.stringify({ username: role, displayName: role, role, token: 't' }));
    TestBed.configureTestingModule({
      imports: [DmnEditorPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(query) } } },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(DmnEditorPage);
    fixture.detectChanges();
    httpMock.expectOne('/api/models').flush([
      {
        modelKey: 'loan-eligibility',
        name: 'Loan Eligibility',
        namespace: 'ns',
        owner: 'analyst',
        latestPublishedVersion: '1.1.0',
        inProgressVersion: null,
        inProgressStatus: null,
      },
    ]);
    return fixture;
  }

  beforeEach(() => {
    fake = installFakeDmnEditor();
  });

  afterEach(() => {
    httpMock.verify();
    fake.uninstall();
    localStorage.removeItem('dmn-rules-demo.user');
  });

  it('opens the latest published version and saves edits as a new draft, not over the published one', async () => {
    const fixture = setup({ model: 'loan-eligibility' });
    httpMock.expectOne('/api/models/loan-eligibility/versions').flush([version({})]);
    fixture.detectChanges();
    await settle();
    expect(await fake.state.opened[0].initialContent).toBe('<definitions id="published"/>');

    fake.edit('<definitions id="edited"/>');
    fixture.detectChanges();
    fixture.componentInstance.changeNote = 'Added VIP rule';
    await fixture.componentInstance.save();

    const req = httpMock.expectOne('/api/models/loan-eligibility/drafts');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ dmnXml: '<definitions id="edited"/>', guidedTableEdit: null, changeNote: 'Added VIP rule' });
    req.flush(version({ id: 'v-2', version: '1.1.1', status: 'DRAFT', dmnXml: '<definitions id="edited"/>' }));
    httpMock.expectOne('/api/models').flush([]);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('Saved as new draft v1.1.1');
    expect(text).toContain('published version is unchanged');
    expect(fixture.componentInstance.dirty()).toBe(false);
  });

  it('creates a brand-new model through the import endpoint with the chosen key', async () => {
    const fixture = setup();
    fixture.componentInstance.select(NEW_MODEL);
    fixture.detectChanges();
    await settle();
    fake.edit('<definitions id="fresh"/>');
    fixture.componentInstance.newModelKey = 'Bad Key';
    await fixture.componentInstance.save();
    httpMock.expectNone('/api/models/import');
    expect(fixture.componentInstance.error()).toContain('Model key');

    fixture.componentInstance.newModelKey = 'shipping-fees';
    await fixture.componentInstance.save();
    const req = httpMock.expectOne('/api/models/import');
    const body = req.request.body as FormData;
    expect(body.get('modelKey')).toBe('shipping-fees');
    expect(await (body.get('file') as File).text()).toBe('<definitions id="fresh"/>');
    req.flush({
      version: version({ modelKey: 'shipping-fees', version: '1.0.0', status: 'DRAFT', dmnXml: '<definitions id="fresh"/>' }),
      newModel: true,
      messages: [],
    });
    httpMock.expectOne('/api/models').flush([]);
    fixture.detectChanges();

    expect(fixture.componentInstance.selectedKey()).toBe('shipping-fees');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Created model "shipping-fees"');
  });

  it('opens read-only for auditors and offers no save or new model', async () => {
    const fixture = setup({ model: 'loan-eligibility' }, 'auditor');
    httpMock.expectOne('/api/models/loan-eligibility/versions').flush([version({})]);
    fixture.detectChanges();
    await settle();

    expect(fake.state.opened[0].readOnly).toBe(true);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).not.toContain('Save draft');
    expect(el.querySelector('option[value]')?.textContent).not.toContain('New model');
    expect(el.textContent).toContain('read-only access');
  });
});
