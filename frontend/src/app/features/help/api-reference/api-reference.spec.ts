import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { API_GROUPS } from './api-endpoints';
import { ApiReference } from './api-reference';
import { HelpPage } from '../help-page';
import { provideHttpClient } from '@angular/common/http';

describe('ApiReference', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ApiReference] }).compileComponents();
  });

  it('documents the decision endpoints and Basic auth with this site as the base URL', () => {
    const fixture = TestBed.createComponent(ApiReference);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('/api/decisions/{modelKey}/evaluate');
    expect(text).toContain('/api/executions/{id}');
    expect(text).toContain('HTTP Basic');
    expect(text).toContain('api-client');
    expect(text).toContain(`curl -u analyst:demo`);
    expect(text).toContain(`${window.location.origin}/api/decisions/loan-eligibility/evaluate`);
  });

  it('lists every endpoint once and filters them', () => {
    const fixture = TestBed.createComponent(ApiReference);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const total = API_GROUPS.reduce((n, g) => n + g.endpoints.length, 0);
    expect(el.querySelectorAll('.endpoint').length).toBe(total);

    fixture.componentInstance.filter.set('simulate');
    fixture.detectChanges();
    const paths = Array.from(el.querySelectorAll('.endpoint .path')).map((p) => p.textContent);
    expect(paths).toEqual(['/api/decisions/{modelKey}/simulate']);
  });

  it('has no duplicate method + path entries', () => {
    const keys = API_GROUPS.flatMap((g) => g.endpoints.map((e) => `${e.method} ${e.path}`));
    expect(new Set(keys).size).toBe(keys.length);
  });
});

describe('HelpPage tabs', () => {
  it('opens the REST API tab from ?tab=api', async () => {
    await TestBed.configureTestingModule({
      imports: [HelpPage],
      providers: [provideRouter([{ path: 'help', component: HelpPage }]), provideHttpClient()],
    }).compileComponents();
    const { RouterTestingHarness } = await import('@angular/router/testing');
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/help?tab=api');
    expect(harness.routeNativeElement?.querySelector('app-api-reference')).not.toBeNull();

    await harness.navigateByUrl('/help');
    expect(harness.routeNativeElement?.querySelector('app-api-reference')).toBeNull();
  });
});
