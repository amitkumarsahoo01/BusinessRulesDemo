import { OTHER_COLOR, SERIES_COLORS, formatRelative, labelEvery, niceScale, outcomeTone, seriesColors, trendPercent } from './dashboard-charts';

describe('dashboard chart helpers', () => {
  it('niceScale picks a round max at or above the data and integer ticks', () => {
    expect(niceScale(17)).toEqual({ max: 20, ticks: [0, 5, 10, 15, 20] });
    expect(niceScale(3).max).toBeGreaterThanOrEqual(3);
    expect(niceScale(3).ticks.every((t) => Number.isInteger(t))).toBe(true);
    expect(niceScale(0)).toEqual({ max: 4, ticks: [0, 1, 2, 3, 4] });
    expect(niceScale(1234).max).toBeGreaterThanOrEqual(1234);
  });

  it('assigns colors by entity in fixed alphabetical order, independent of input order', () => {
    const a = seriesColors(['loan', 'insurance']);
    const b = seriesColors(['insurance', 'loan', 'insurance']);
    expect(a.get('insurance')).toBe(SERIES_COLORS[0]);
    expect(a.get('loan')).toBe(SERIES_COLORS[1]);
    expect(b).toEqual(a);
  });

  it('folds series beyond the palette into the neutral Other color instead of generating hues', () => {
    const keys = Array.from({ length: SERIES_COLORS.length + 2 }, (_, i) => `m${String(i).padStart(2, '0')}`);
    const colors = seriesColors(keys);
    expect(colors.get(keys[keys.length - 1])).toBe(OTHER_COLOR);
  });

  it('trendPercent compares to the previous period, null when there is none', () => {
    expect(trendPercent(120, 100)).toBe(20);
    expect(trendPercent(50, 100)).toBe(-50);
    expect(trendPercent(10, 0)).toBeNull();
    expect(trendPercent(318, 2)).toBeNull();
  });

  it('labels about eight x-axis ticks at most', () => {
    expect(labelEvery(7)).toBe(1);
    expect(labelEvery(30)).toBe(4);
    expect(labelEvery(90)).toBe(12);
  });

  it('maps well-known outcome words to status tones', () => {
    expect(outcomeTone('APPROVED')).toBe('good');
    expect(outcomeTone('review')).toBe('warn');
    expect(outcomeTone('DECLINED')).toBe('bad');
    expect(outcomeTone('Gold tier')).toBe('neutral');
  });

  it('formats relative times', () => {
    const now = Date.parse('2026-11-07T12:00:00Z');
    expect(formatRelative(null, now)).toBe('never');
    expect(formatRelative('2026-11-07T11:30:00Z', now)).toBe('30 min ago');
    expect(formatRelative('2026-11-06T12:00:00Z', now)).toBe('yesterday');
    expect(formatRelative('2026-11-01T12:00:00Z', now)).toBe('6 days ago');
  });
});
