import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ApiReference } from './api-reference/api-reference';
import { COMMON_TIPS, LIFECYCLE, ROLE_GUIDES, RoleGuide } from './help-content';

export type HelpTab = 'guide' | 'api';

@Component({
  selector: 'app-help-page',
  imports: [RouterLink, ApiReference],
  templateUrl: './help-page.html',
  styleUrl: './help-page.scss',
})
export class HelpPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly guides = ROLE_GUIDES;
  readonly lifecycle = LIFECYCLE;
  readonly tips = COMMON_TIPS;

  /** "User guide" or "REST API", driven by the URL (/help?tab=api) so links and Back work. */
  private readonly queryParams = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });
  readonly tab = computed<HelpTab>(() => (this.queryParams().get('tab') === 'api' ? 'api' : 'guide'));

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

  showTab(tab: HelpTab): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: { tab: tab === 'api' ? 'api' : null } });
  }
}
