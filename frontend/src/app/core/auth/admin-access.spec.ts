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
  it('sends X-User and the bearer token to /api only', () => {
    storeUser({ username: 'admin', displayName: 'Admin', role: 'admin', token: 'signed.token' });
    setUp();
    const http = TestBed.inject(HttpClient);
    const mock = TestBed.inject(HttpTestingController);

    http.get('/api/ai/admin/llm').subscribe();
    http.get('https://example.com/elsewhere').subscribe();

    const api = mock.expectOne('/api/ai/admin/llm');
    expect(api.request.headers.get('X-User')).toBe('admin');
    expect(api.request.headers.get('Authorization')).toBe('Bearer signed.token');
    const external = mock.expectOne('https://example.com/elsewhere');
    expect(external.request.headers.has('Authorization')).toBe(false);
  });
});
