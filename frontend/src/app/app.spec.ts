import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

describe('App', () => {
  beforeEach(() => {
    try {
      localStorage.clear();
    } catch {
      // no-op in environments without storage
    }
  });

  async function setUp(): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  }

  it('should create the app', async () => {
    await setUp();
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows no nav shell when logged out', async () => {
    await setUp();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.app-title')).toBeNull();
  });

  it('shows the app shell with the title once a user is stored', async () => {
    localStorage.setItem(
      'dmn-rules-demo.user',
      JSON.stringify({ username: 'analyst', displayName: 'Alex Analyst', role: 'rule-author' }),
    );
    await setUp();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.app-title')?.textContent).toContain('DMN Rules Platform');
    expect(compiled.querySelector('.user-name')?.textContent).toContain('Alex Analyst');
    expect(compiled.querySelector('.admin-link')).toBeNull();
    const navLabels = Array.from(compiled.querySelectorAll('.app-nav a')).map((a) => a.textContent?.trim());
    expect(navLabels).toEqual(['Dashboard', 'Rules', 'DMN Editor', 'Audit', 'Help']);
    expect(compiled.querySelector('.bob-launcher')?.textContent).toContain('Ask Bob');
  });

  it('shows the Admin link only to an admin', async () => {
    localStorage.setItem(
      'dmn-rules-demo.user',
      JSON.stringify({ username: 'admin', displayName: 'Platform Admin', role: 'admin', token: 't' }),
    );
    await setUp();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.admin-link')?.textContent).toContain('Admin');
  });
});
