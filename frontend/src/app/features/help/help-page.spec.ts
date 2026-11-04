import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HelpPage } from './help-page';

function createWithRole(role: string | null) {
  localStorage.clear();
  if (role) {
    localStorage.setItem(
      'dmn-rules-demo.user',
      JSON.stringify({ username: 'u', displayName: 'U', role }),
    );
  }
  TestBed.configureTestingModule({
    imports: [HelpPage],
    providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
  });
  const fixture = TestBed.createComponent(HelpPage);
  fixture.detectChanges();
  return fixture;
}

describe('HelpPage', () => {
  afterEach(() => localStorage.clear());

  it('opens on the signed-in user\'s own role', () => {
    const el = createWithRole('rule-approver').nativeElement as HTMLElement;
    expect(el.querySelector('.guide h2')?.textContent).toContain('Rule approver');
    expect(el.querySelector('.role-tab.active')?.textContent).toContain('you');
    expect(el.textContent).toContain('Publish');
  });

  it('shows read-only guidance for auditors', () => {
    const el = createWithRole('auditor').nativeElement as HTMLElement;
    expect(el.querySelector('.guide h2')?.textContent).toContain('Auditor');
    expect(el.textContent).toContain('import panel is hidden');
  });

  it('lets the user read another role\'s guide', () => {
    const fixture = createWithRole('rule-author');
    const el = fixture.nativeElement as HTMLElement;
    const tabs = el.querySelectorAll<HTMLButtonElement>('.role-tab');
    tabs[1].click();
    fixture.detectChanges();
    expect(el.querySelector('.guide h2')?.textContent).toContain('Rule approver');
  });

  it('falls back to the first guide for an unknown role', () => {
    const el = createWithRole('something-new').nativeElement as HTMLElement;
    expect(el.querySelector('.guide h2')?.textContent).toContain('Rule author');
  });
});
