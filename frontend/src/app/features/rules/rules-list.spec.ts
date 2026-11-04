import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RulesList } from './rules-list';

describe('RulesList', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RulesList],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('renders a card per model once loaded', () => {
    const fixture = TestBed.createComponent(RulesList);
    fixture.detectChanges();

    httpMock.expectOne('/api/models').flush([
      {
        modelKey: 'loan-eligibility',
        name: 'Loan Eligibility',
        namespace: 'https://dmn-rules-demo/loan-eligibility',
        owner: 'analyst',
        latestPublishedVersion: '1.1.0',
        inProgressVersion: '1.2.0',
        inProgressStatus: 'DRAFT',
      },
    ]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelectorAll('.model-card').length).toBe(1);
    expect(compiled.textContent).toContain('Loan Eligibility');
    expect(compiled.textContent).toContain('Published 1.1.0');
  });

  it('shows an empty state when there are no models', () => {
    const fixture = TestBed.createComponent(RulesList);
    fixture.detectChanges();
    httpMock.expectOne('/api/models').flush([]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.empty')?.textContent).toContain('No models yet');
  });

  function chooseFile(fixture: ReturnType<typeof TestBed.createComponent<RulesList>>, name: string): void {
    httpMock.expectOne('/api/models').flush([]);
    fixture.detectChanges();
    const file = new File(['<definitions/>'], name, { type: 'application/xml' });
    const input = fixture.nativeElement.querySelector('input[type=file]') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [file] });
    input.dispatchEvent(new Event('change'));
  }

  it('uploads a chosen .dmn file as multipart and opens the new model when it is clean', () => {
    const fixture = TestBed.createComponent(RulesList);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture.detectChanges();
    chooseFile(fixture, 'shipping.dmn');

    const req = httpMock.expectOne('/api/models/import');
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBe(true);
    expect((req.request.body as FormData).get('file')).toBeInstanceOf(File);
    req.flush({ version: { modelKey: 'shipping', version: '1.0.0' }, newModel: true, messages: [] });

    expect(navigate).toHaveBeenCalledWith(['/rules', 'shipping']);
  });

  it('shows validator findings from an import instead of navigating away', () => {
    const fixture = TestBed.createComponent(RulesList);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture.detectChanges();
    chooseFile(fixture, 'shipping.dmn');

    httpMock.expectOne('/api/models/import').flush({
      version: { modelKey: 'shipping', version: '1.0.0' },
      newModel: true,
      messages: [{ severity: 'WARN', message: 'Rule 2 overlaps rule 1', sourceId: null }],
    });
    httpMock.expectOne('/api/models').flush([]);
    fixture.detectChanges();

    expect(navigate).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Rule 2 overlaps rule 1');
  });

  it('shows the server problem detail when an import is rejected', () => {
    const fixture = TestBed.createComponent(RulesList);
    fixture.detectChanges();
    chooseFile(fixture, 'bad.dmn');

    httpMock
      .expectOne('/api/models/import')
      .flush({ detail: 'The DMN file contains no decisions' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.error')?.textContent).toContain('no decisions');
  });

  it('rejects files that are not .dmn/.xml without calling the server', () => {
    const fixture = TestBed.createComponent(RulesList);
    fixture.detectChanges();
    chooseFile(fixture, 'notes.txt');
    fixture.detectChanges();

    httpMock.expectNone('/api/models/import');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Choose a .dmn');
  });

  it('hides the import panel for the read-only auditor role', () => {
    localStorage.setItem(
      'dmn-rules-demo.user',
      JSON.stringify({ username: 'auditor', displayName: 'Jordan Auditor', role: 'auditor' }),
    );
    try {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        imports: [RulesList],
        providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
      });
      const fixture = TestBed.createComponent(RulesList);
      fixture.detectChanges();
      TestBed.inject(HttpTestingController).expectOne('/api/models').flush([]);
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).querySelector('.import-panel')).toBeNull();
    } finally {
      localStorage.removeItem('dmn-rules-demo.user');
    }
  });
});
