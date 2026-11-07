import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

interface DemoAccount {
  username: string;
  label: string;
}

@Component({
  selector: 'app-login-page',
  imports: [FormsModule],
  templateUrl: './login-page.html',
  styleUrl: './login-page.scss',
})
export class LoginPage {
  readonly demoAccounts: DemoAccount[] = [
    { username: 'analyst', label: 'Analyst (rule-author)' },
    { username: 'approver', label: 'Approver (rule-approver)' },
    { username: 'auditor', label: 'Auditor (read-only)' },
  ];

  username = '';
  password = 'demo';
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  constructor(
    private readonly auth: AuthService,
    private readonly router: Router,
  ) {}

  selectAccount(username: string): void {
    this.username = username;
    this.password = 'demo';
  }

  submit(): void {
    if (!this.username || !this.password) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.auth.login(this.username, this.password).subscribe({
      next: () => {
        this.loading.set(false);
        this.router.navigateByUrl('/dashboard');
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Invalid username or password.');
      },
    });
  }
}
