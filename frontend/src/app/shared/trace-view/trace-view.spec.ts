import { TestBed } from '@angular/core/testing';
import { TraceView } from './trace-view';

describe('TraceView', () => {
  it('shows an empty state with no trace', async () => {
    await TestBed.configureTestingModule({ imports: [TraceView] }).compileComponents();
    const fixture = TestBed.createComponent(TraceView);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.empty')?.textContent).toContain('No trace captured');
  });

  it('renders matched rule badges for a DECISION_TABLE step', async () => {
    await TestBed.configureTestingModule({ imports: [TraceView] }).compileComponents();
    const fixture = TestBed.createComponent(TraceView);
    fixture.componentRef.setInput('trace', [
      {
        seq: 0,
        nodeType: 'DECISION_TABLE',
        nodeName: 'Risk Category',
        matchedRules: [3],
        inputs: null,
        result: null,
        messages: null,
        durationMs: null,
      },
      {
        seq: 1,
        nodeType: 'DECISION',
        nodeName: 'Risk Category',
        matchedRules: null,
        inputs: {},
        result: 'MEDIUM',
        messages: [],
        durationMs: 1,
      },
    ]);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.rule-badge')?.textContent?.trim()).toBe('3');
    expect(compiled.textContent).toContain('MEDIUM');
  });
});
