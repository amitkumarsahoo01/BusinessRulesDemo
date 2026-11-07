import { DecimalPipe, PercentPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DashboardApiService } from '../../core/api/dashboard-api.service';
import { DailyBucket, ExecutionStats, RuleSummary } from '../../core/models/dashboard.models';
import {
  OTHER_COLOR,
  formatDay,
  formatRelative,
  labelEvery,
  niceScale,
  outcomeTone,
  seriesColors,
  trendPercent,
} from './dashboard-charts';

interface Segment {
  modelKey: string;
  count: number;
  color: string;
}

@Component({
  selector: 'app-dashboard-page',
  imports: [RouterLink, DecimalPipe, PercentPipe],
  templateUrl: './dashboard-page.html',
  styleUrl: './dashboard-page.scss',
})
export class DashboardPage {
  private readonly api = inject(DashboardApiService);

  readonly ranges = [7, 30, 90];
  readonly days = signal(30);
  readonly modelFilter = signal<string | null>(null);

  readonly stats = signal<ExecutionStats | null>(null);
  readonly rules = signal<RuleSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly hovered = signal<number | null>(null);

  readonly formatDay = formatDay;
  readonly formatRelative = formatRelative;
  readonly outcomeTone = outcomeTone;

  /** Fixed color per model, from the full model list so filtering never repaints a model. */
  readonly colors = computed(() => seriesColors(this.rules().map((r) => r.modelKey)));

  readonly legend = computed(() => {
    const stats = this.stats();
    if (!stats) return [];
    return stats.byModel
      .map((m) => m.modelKey)
      .sort()
      .map((key) => ({ modelKey: key, color: this.colorOf(key) }));
  });

  readonly scale = computed(() => niceScale(Math.max(0, ...(this.stats()?.daily.map((d) => d.total) ?? [0]))));
  readonly labelStep = computed(() => labelEvery(this.stats()?.daily.length ?? 1));

  readonly trend = computed(() => {
    const s = this.stats();
    return s ? trendPercent(s.total, s.previousPeriodTotal) : null;
  });

  readonly successRate = computed(() => {
    const s = this.stats();
    return s && s.total > 0 ? s.succeeded / s.total : null;
  });

  readonly liveModels = computed(() => this.rules().filter((r) => r.liveVersion).length);
  readonly draftsInFlight = computed(() => this.rules().filter((r) => r.inProgressVersion).length);
  readonly totalRules = computed(() =>
    this.rules().reduce((sum, r) => sum + r.decisions.reduce((s, d) => s + (d.ruleCount ?? 0), 0), 0),
  );

  readonly busiestDay = computed(() => {
    const daily = this.stats()?.daily ?? [];
    return daily.reduce<DailyBucket | null>((best, d) => (d.total > (best?.total ?? 0) ? d : best), null);
  });

  readonly hoveredDay = computed(() => {
    const i = this.hovered();
    const daily = this.stats()?.daily;
    return i === null || !daily ? null : daily[i];
  });

  constructor() {
    this.load();
  }

  setDays(days: number): void {
    if (days !== this.days()) {
      this.days.set(days);
      this.load();
    }
  }

  setModel(modelKey: string): void {
    this.modelFilter.set(modelKey || null);
    this.loadStats();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.rules(this.days()).subscribe({
      next: (rules) => this.rules.set(rules),
      error: () => this.error.set('Could not load the rule summary.'),
    });
    this.loadStats();
  }

  private loadStats(): void {
    this.api.executions(this.days(), this.modelFilter()).subscribe({
      next: (stats) => {
        this.stats.set(stats);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load execution statistics.');
        this.loading.set(false);
      },
    });
  }

  colorOf(modelKey: string): string {
    return this.colors().get(modelKey) ?? OTHER_COLOR;
  }

  /** Stacked segments for one day, top of the column first (so the rounded cap sits on top). */
  segments(day: DailyBucket): Segment[] {
    return Object.entries(day.byModel)
      .filter(([, count]) => count > 0)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([modelKey, count]) => ({ modelKey, count, color: this.colorOf(modelKey) }))
      .reverse();
  }

  columnHeight(day: DailyBucket): number {
    return (day.total / this.scale().max) * 100;
  }

  showLabel(index: number): boolean {
    const n = this.stats()?.daily.length ?? 0;
    // Count back from today so the most recent day is always labeled.
    return (n - 1 - index) % this.labelStep() === 0;
  }

  /**
   * Tooltip sits beside the hovered column (right of it in the left half of the chart, left of it
   * in the right half) so it never covers the bar being read.
   */
  tooltipStyle(index: number): Record<string, string> {
    const n = this.stats()?.daily.length ?? 1;
    const offset = 0.5 / n * 100 + 1; // half a column plus a small gap, in %
    const center = ((index + 0.5) / n) * 100;
    return center < 50 ? { left: `${center + offset}%` } : { right: `${100 - center + offset}%` };
  }

  share(count: number, total: number): number {
    return total > 0 ? count / total : 0;
  }

  modelName(modelKey: string): string {
    return this.rules().find((r) => r.modelKey === modelKey)?.name ?? modelKey;
  }

  ruleCount(rule: RuleSummary): number {
    return rule.decisions.reduce((s, d) => s + (d.ruleCount ?? 0), 0);
  }
}
