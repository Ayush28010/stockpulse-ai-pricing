import { useQuery } from '@tanstack/react-query';
import { api } from '../api';
import { money, timeAgo } from '../format';
import type { PriceHistoryEntry, Product } from '../types';
import { SourceBadge, TriggerBadge } from './Badges';

export function HistoryPanel({ product, onClose }: { product: Product; onClose: () => void }) {
  const history = useQuery({ queryKey: ['history', product.id], queryFn: () => api.priceHistory(product.id) });
  const pricing = useQuery({ queryKey: ['pricing', 'all', product.id], queryFn: () => api.pricingSuggestions(undefined, product.id, 50) });
  const reorders = useQuery({ queryKey: ['reorder', 'all', product.id], queryFn: () => api.reorderSuggestions(undefined, product.id, 50) });

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <h3>
            {product.name} <span className="muted mono">{product.sku}</span>
          </h3>
          <button onClick={onClose}>Close</button>
        </header>

        <h4>Price history</h4>
        {history.isLoading ? <p className="muted">Loading…</p> : <PriceChart entries={history.data ?? []} current={product.currentPrice} />}

        <h4>Suggestion log</h4>
        {pricing.isError || reorders.isError ? <p className="note error">Could not load suggestions.</p> : null}
        <table className="log">
          <thead>
            <tr>
              <th>Type</th>
              <th>Trigger</th>
              <th>Source</th>
              <th>Recommendation</th>
              <th>Status</th>
              <th>Note</th>
              <th>When</th>
            </tr>
          </thead>
          <tbody>
            {[
              ...(pricing.data ?? []).map((s) => ({
                key: `p${s.id}`,
                type: 'Pricing',
                rec: `${money(s.currentPrice)} → ${money(s.recommendedPrice)} (${s.tactic})`,
                ...s,
              })),
              ...(reorders.data ?? []).map((s) => ({ key: `r${s.id}`, type: 'Reorder', rec: `${s.recommendedQuantity} units`, ...s })),
            ]
              .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
              .map((s) => (
                <tr key={s.key}>
                  <td>{s.type}</td>
                  <td>
                    <TriggerBadge trigger={s.triggerReason} />
                  </td>
                  <td>
                    <SourceBadge source={s.generatedBy} reason={s.fallbackReason} />
                  </td>
                  <td>{s.rec}</td>
                  <td className={`st-${s.status.toLowerCase()}`}>{s.status}</td>
                  <td className="small muted">{s.decisionNote ?? ''}</td>
                  <td className="small muted">{timeAgo(s.createdAt)}</td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function PriceChart({ entries, current }: { entries: PriceHistoryEntry[]; current: number }) {
  if (entries.length === 0) {
    return <p className="muted">No accepted price changes yet. Current price {money(current)}.</p>;
  }
  const points = [{ price: entries[0].oldPrice, label: 'start' }, ...entries.map((e) => ({ price: e.newPrice, label: timeAgo(e.changedAt) }))];
  const W = 640, H = 180, P = 32;
  const prices = points.map((p) => p.price);
  const min = Math.min(...prices) * 0.97, max = Math.max(...prices) * 1.03;
  const x = (i: number) => P + (i * (W - 2 * P)) / Math.max(points.length - 1, 1);
  const y = (v: number) => H - P - ((v - min) / (max - min || 1)) * (H - 2 * P);
  const path = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i)},${y(p.price)}`).join(' ');
  return (
    <svg className="chart" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Price history chart">
      <path d={path} fill="none" stroke="currentColor" strokeWidth="2" />
      {points.map((p, i) => (
        <g key={i}>
          <circle cx={x(i)} cy={y(p.price)} r="4" />
          <text x={x(i)} y={y(p.price) - 10} textAnchor="middle">{money(p.price)}</text>
          <text x={x(i)} y={H - 8} textAnchor="middle" className="axis">{p.label}</text>
        </g>
      ))}
    </svg>
  );
}
