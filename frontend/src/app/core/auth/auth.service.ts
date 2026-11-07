import { HttpClient } from '@angular/common/http';
import { Injectable, computed, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ADMIN_ROLE, User } from '../models/auth.models';

const STORAGE_KEY = 'dmn-rules-demo.user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly currentUserSignal = signal<User | null>(this.readStoredUser());

  readonly currentUser = this.currentUserSignal.asReadonly();
  readonly isLoggedIn = computed(() => this.currentUserSignal() !== null);
  /** Only controls what the UI shows; the server independently verifies the token on admin calls. */
  readonly isAdmin = computed(() => this.currentUserSignal()?.role === ADMIN_ROLE);

  constructor(private readonly http: HttpClient) {}

  login(username: string, password: string): Observable<User> {
    return this.http.post<User>('/api/auth/login', { username, password }).pipe(
      tap((user) => {
        this.currentUserSignal.set(user);
        this.storeUser(user);
      }),
    );
  }

  logout(): void {
    this.currentUserSignal.set(null);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // private browsing / storage disabled: nothing to clean up
    }
  }

  private readStoredUser(): User | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? (JSON.parse(raw) as User) : null;
    } catch {
      return null;
    }
  }

  private storeUser(user: User): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(user));
    } catch {
      // private browsing / storage disabled: session still works for this tab via the signal
    }
  }
}
