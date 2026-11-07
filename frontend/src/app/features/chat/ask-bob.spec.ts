import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AskBob, contextFromUrl } from './ask-bob';

describe('contextFromUrl', () => {
  it('pins the model on a rule page', () => {
    expect(contextFromUrl('/rules/loan-eligibility?tab=structure')).toEqual({ modelKey: 'loan-eligibility', executionId: null });
  });

  it('pins the execution on an audit detail page', () => {
    const id = '0b6f0f8e-3c1f-4c8e-9a5e-6f2b1d0c9a11';
    expect(contextFromUrl(`/audit/${id}`)).toEqual({ modelKey: null, executionId: id });
  });

  it('has no context elsewhere', () => {
    expect(contextFromUrl('/dashboard')).toEqual({ modelKey: null, executionId: null });
    expect(contextFromUrl('/audit')).toEqual({ modelKey: null, executionId: null });
  });
});

describe('AskBob', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AskBob],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('shows a labelled launcher and opens a panel that starts a conversation', () => {
    const fixture = TestBed.createComponent(AskBob);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    const launcher = el.querySelector<HTMLButtonElement>('.bob-launcher')!;
    expect(launcher.textContent).toContain('Ask Bob');
    expect(el.querySelector('.bob-panel')).toBeNull();

    launcher.click();
    fixture.detectChanges();

    expect(el.querySelector('.bob-panel')).not.toBeNull();
    const http = TestBed.inject(HttpTestingController);
    const start = http.expectOne('/api/ai/conversations');
    expect(start.request.body).toEqual({ pinnedModelKey: null, pinnedVersion: null, pinnedExecutionId: null });
  });
});
