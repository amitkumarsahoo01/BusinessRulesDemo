import { Component, ElementRef, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

interface Account {
  username: string;
  label: string;
  /** Demo accounts share the password "demo"; the administrator has its own. */
  demoPassword: boolean;
}

export const LANDING_PAGE = '/dashboard';

@Component({
  selector: 'app-login-page',
  imports: [FormsModule],
  templateUrl: './login-page.html',
  styleUrl: './login-page.scss',
})
export class LoginPage {
  readonly demoAccounts: Account[] = [
    { username: 'analyst', label: 'Analyst (rule-author)', demoPassword: true },
    { username: 'approver', label: 'Approver (rule-approver)', demoPassword: true },
    { username: 'auditor', label: 'Auditor (read-only)', demoPassword: true },
  ];
  readonly adminAccount: Account = { username: 'admin', label: 'Administrator', demoPassword: false };

  username = '';
  password = 'demo';
  readonly selected = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  private readonly passwordInput = viewChild<ElementRef<HTMLInputElement>>('passwordInput');

  constructor(
    private readonly auth: AuthService,
    private readonly router: Router,
  ) {}

  isAdminSelected(): boolean {
    return this.selected() === this.adminAccount.username || this.username.trim() === this.adminAccount.username;
  }

  selectAccount(account: Account): void {
    this.selected.set(account.username);
    this.username = account.username;
    this.error.set(null);
    if (account.demoPassword) {
      this.password = 'demo';
    } else {
      // Never prefill the admin password; put the cursor where it goes.
      this.password = '';
      queueMicrotask(() => this.passwordInput()?.nativeElement.focus());
    }
  }

  submit(): void {
    if (!this.username || !this.password) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.auth.login(this.username.trim(), this.password).subscribe({
      next: () => {
        this.loading.set(false);
        this.router.navigateByUrl(LANDING_PAGE);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Invalid username or password.');
      },
    });
  }
}
