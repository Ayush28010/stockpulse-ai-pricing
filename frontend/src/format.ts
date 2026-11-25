export const money = (v: number | null | undefined) =>
  v === null || v === undefined ? '—' : `$${v.toFixed(2)}`;

export const signedPct = (v: number) => `${v > 0 ? '+' : ''}${v.toFixed(1)}%`;

export const timeAgo = (iso: string) => {
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  return new Date(iso).toLocaleDateString();
};

/** Stock heat by cover vs threshold: 0 = out, <1 = below threshold, <2 = watch, else healthy. */
export const stockHeat = (stock: number, threshold: number) => {
  if (stock === 0) return 'heat-out';
  const ratio = threshold === 0 ? 10 : stock / threshold;
  if (ratio < 1) return 'heat-low';
  if (ratio < 2) return 'heat-watch';
  return 'heat-ok';
};
