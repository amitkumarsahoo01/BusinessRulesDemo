/**
 * Pure helpers for the dashboard charts. Colors are the validated reference categorical palette,
 * assigned in fixed order by model key (alphabetical across *all* models), so a model keeps its
 * color when filters change what's on screen.
 */
export const SERIES_COLORS = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#6250d6', '#e34948'];
export const OTHER_COLOR = '#9ca3af';

export function seriesColors(allModelKeys: string[]): Map<string, string> {
  const sorted = [...new Set(allModelKeys)].sort();
  const colors = new Map<string, string>();
  sorted.forEach((key, i) => colors.set(key, i < SERIES_COLORS.length ? SERIES_COLORS[i] : OTHER_COLOR));
  return colors;
}

/** A round axis maximum (1, 2, 2.5, 5 × 10^n) at or above `value`, and evenly spaced ticks up to it. */
export function niceScale(value: number, tickCount = 4): { max: number; ticks: number[] } {
  if (!Number.isFinite(value) || value <= 0) {
    return { max: tickCount, ticks: Array.from({ length: tickCount + 1 }, (_, i) => i) };
  }
  const rawStep = value / tickCount;
  const magnitude = Math.pow(10, Math.floor(Math.log10(rawStep)));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((s) => s >= rawStep) ?? 10 * magnitude;
  // Integer counts: never a fractional step.
  const intStep = Math.max(1, Math.ceil(step));
  const max = intStep * Math.ceil(value / intStep);
  const ticks: number[] = [];
  for (let t = 0; t <= max; t += intStep) {
    ticks.push(t);
  }
  return { max, ticks };
}

/**
 * Percent change vs the previous period, or null when the previous period is too small to compare
 * against meaningfully (2 → 318 would read as "+15800%", which says nothing useful).
 */
export function trendPercent(current: number, previous: number, minBase = 10): number | null {
  if (previous < minBase) {
    return null;
  }
  return Math.round(((current - previous) / previous) * 100);
}

/** Show at most ~`maxLabels` x-axis labels, always including the last (today). */
export function labelEvery(days: number, maxLabels = 8): number {
  return Math.max(1, Math.ceil(days / maxLabels));
}

export function formatDay(isoDate: string, withWeekday = false): string {
  const d = new Date(`${isoDate}T00:00:00Z`);
  return d.toLocaleDateString('en-US', {
    timeZone: 'UTC',
    month: 'short',
    day: 'numeric',
    ...(withWeekday ? { weekday: 'short' } : {}),
  });
}

export function formatRelative(iso: string | null, now = Date.now()): string {
  if (!iso) {
    return 'never';
  }
  const minutes = Math.round((now - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  return days === 1 ? 'yesterday' : `${days} days ago`;
}

export type OutcomeTone = 'good' | 'warn' | 'bad' | 'neutral';

/** Status tone for well-known outcome words; everything else stays neutral (labels carry the meaning). */
export function outcomeTone(value: string): OutcomeTone {
  const v = value.toUpperCase();
  if (/^(APPROVED?|ACCEPT(ED)?|ELIGIBLE|PASS(ED)?|LOW|YES|TRUE)$/.test(v)) return 'good';
  if (/^(REVIEW|REFER(RED)?|MANUAL|PENDING|MEDIUM|HOLD)$/.test(v)) return 'warn';
  if (/^(DECLINED?|REJECT(ED)?|INELIGIBLE|FAIL(ED)?|HIGH|NO|FALSE|DENIED)$/.test(v)) return 'bad';
  return 'neutral';
}
