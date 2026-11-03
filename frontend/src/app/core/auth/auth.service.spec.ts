import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    try {
      localStorage.clear();
    } catch {
      // no-op
    }
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('starts logged out when nothing is stored', () => {
    expect(service.isLoggedIn()).toBe(false);
    expect(service.currentUser()).toBeNull();
  });

  it('login stores the returned user and flips isLoggedIn', () => {
    service.login('analyst', 'demo').subscribe();

    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'analyst', password: 'demo' });
    req.flush({ username: 'analyst', displayName: 'Alex Analyst', role: 'rule-author' });

    expect(service.isLoggedIn()).toBe(true);
    expect(service.currentUser()?.displayName).toBe('Alex Analyst');
    expect(JSON.parse(localStorage.getItem('dmn-rules-demo.user')!).username).toBe('analyst');
  });

  it('logout clears the current user and storage', () => {
    service.login('analyst', 'demo').subscribe();
    httpMock.expectOne('/api/auth/login').flush({ username: 'analyst', displayName: 'Alex', role: 'rule-author' });

    service.logout();

    expect(service.isLoggedIn()).toBe(false);
    expect(localStorage.getItem('dmn-rules-demo.user')).toBeNull();
  });
});
