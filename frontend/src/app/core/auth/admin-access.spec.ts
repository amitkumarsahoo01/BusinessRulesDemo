import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { userHeaderInterceptor } from '../http/user-header.interceptor';
import { adminGuard } from './admin.guard';

function storeUser(user: object | null): void {
  localStorage.clear();
  if (user) {
    localStorage.setItem('dmn-rules-demo.user', JSON.stringify(user));
  }
}

function setUp(): void {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([userHeaderInterceptor])),
      provideHttpClientTesting(),
    ],
  });
}

function runGuard(): boolean | UrlTree {
  return TestBed.runInInjectionContext(() => adminGuard({} as never, {} as never)) as boolean | UrlTree;
}

describe('adminGuard', () => {
  it('lets the admin in', () => {
    storeUser({ username: 'admin', displayName: 'Admin', role: 'admin', token: 't' });
    setUp();
    expect(runGuard()).toBe(true);
  });

  it('sends other roles to the dashboard', () => {
    storeUser({ username: 'analyst', displayName: 'Alex', role: 'rule-author', token: 't' });
    setUp();
    const result = runGuard();
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/dashboard');
  });

  it('sends signed-out users to login', () => {
    storeUser(null);
    setUp();
    expect(TestBed.inject(Router).serializeUrl(runGuard() as UrlTree)).toBe('/login');
  });
});

describe('userHeaderInterceptor', () => {
  it('sends the bearer token (not a self-declared X-User) to /api only', () => {
    storeUser({ username: 'admin', displayName: 'Admin', role: 'admin', token: 'signed.token' });
    setUp();
    const http = TestBed.inject(HttpClient);
    const mock = TestBed.inject(HttpTestingController);

    http.get('/api/ai/admin/llm').subscribe();
    http.get('https://example.com/elsewhere').subscribe();

    const api = mock.expectOne('/api/ai/admin/llm');
    expect(api.request.headers.get('Authorization')).toBe('Bearer signed.token');
    expect(api.request.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    expect(api.request.headers.has('X-User')).toBe(false);
    const external = mock.expectOne('https://example.com/elsewhere');
    expect(external.request.headers.has('Authorization')).toBe(false);
  });

  it('signs out and goes to login when the server says the session is invalid', () => {
    storeUser({ username: 'analyst', displayName: 'Alex', role: 'rule-author' }); // pre-token session
    setUp();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    TestBed.inject(HttpClient).get('/api/models').subscribe({ error: () => undefined });
    TestBed.inject(HttpTestingController).expectOne('/api/models').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(localStorage.getItem('dmn-rules-demo.user')).toBeNull();
    expect(navigate).toHaveBeenCalledWith('/login');
  });

  it('does not sign out on a failed login attempt', () => {
    storeUser(null);
    setUp();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    TestBed.inject(HttpClient).post('/api/auth/login', {}).subscribe({ error: () => undefined });
    TestBed.inject(HttpTestingController).expectOne('/api/auth/login').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(navigate).not.toHaveBeenCalled();
  });
});
