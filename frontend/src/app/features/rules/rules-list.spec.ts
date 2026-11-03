import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
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
});
