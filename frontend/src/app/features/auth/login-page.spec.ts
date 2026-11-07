import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { guestGuard } from '../../core/auth/guest.guard';
import { LoginPage } from './login-page';

describe('LoginPage', () => {
  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  function render() {
    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('offers an Administrator option alongside the demo accounts', () => {
    const { el } = render();
    const chips = Array.from(el.querySelectorAll('.account-chip')).map((c) => c.textContent?.trim());
    expect(chips).toContain('Administrator');
    expect(chips).toContain('Analyst (rule-author)');
  });

  it('selecting Administrator fills the username but never the password', () => {
    const { fixture, el } = render();
    const page = fixture.componentInstance;

    el.querySelector<HTMLButtonElement>('.account-chip.admin')!.click();
    fixture.detectChanges();

    expect(page.username).toBe('admin');
    expect(page.password).toBe('');
    expect(el.querySelector('.hint')?.textContent).toContain('own password');
  });

  it('demo accounts still prefill the shared demo password', () => {
    const { fixture } = render();
    const page = fixture.componentInstance;
    page.selectAccount(page.demoAccounts[0]);
    expect(page.username).toBe('analyst');
    expect(page.password).toBe('demo');
  });

  it('lands on the dashboard after signing in', () => {
    const { fixture } = render();
    const page = fixture.componentInstance;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    page.selectAccount(page.adminAccount);
    page.password = 'secret';
    page.submit();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/auth/login')
      .flush({ username: 'admin', displayName: 'Platform Admin', role: 'admin', token: 't' });

    expect(navigate).toHaveBeenCalledWith('/dashboard');
  });
});

describe('guestGuard', () => {
  it('sends a signed-in user from /login to the dashboard', () => {
    localStorage.setItem('dmn-rules-demo.user', JSON.stringify({ username: 'a', displayName: 'A', role: 'auditor' }));
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient()] });
    const result = TestBed.runInInjectionContext(() => guestGuard({} as never, {} as never)) as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(result)).toBe('/dashboard');
  });

  it('lets a signed-out user see the login page', () => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient()] });
    expect(TestBed.runInInjectionContext(() => guestGuard({} as never, {} as never))).toBe(true);
  });
});
