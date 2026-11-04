import { Component, computed, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { COMMON_TIPS, LIFECYCLE, ROLE_GUIDES, RoleGuide } from './help-content';

@Component({
  selector: 'app-help-page',
  imports: [RouterLink],
  templateUrl: './help-page.html',
  styleUrl: './help-page.scss',
})
export class HelpPage {
  readonly guides = ROLE_GUIDES;
  readonly lifecycle = LIFECYCLE;
  readonly tips = COMMON_TIPS;

  /** The role whose guide is shown; starts as the signed-in user's own role. */
  private readonly picked = signal<string | null>(null);

  readonly ownRole = computed(() => this.auth.currentUser()?.role ?? null);

  readonly activeRole = computed(() => this.picked() ?? this.ownRole() ?? this.guides[0].role);

  readonly guide = computed<RoleGuide>(
    () => this.guides.find((g) => g.role === this.activeRole()) ?? this.guides[0],
  );

  constructor(private readonly auth: AuthService) {}

  show(role: string): void {
    this.picked.set(role);
  }
}
