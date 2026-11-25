import { useState } from 'react';
import type { Actions } from '../actions';
import { money, stockHeat } from '../format';
import { CATEGORIES, STATUSES, type Category, type Product, type ProductStatus } from '../types';
import { StatusBadge } from './Badges';

interface Props {
  products: Product[];
  filters: { category?: Category; status?: ProductStatus };
  onFilters: (f: { category?: Category; status?: ProductStatus }) => void;
  actions: Actions;
  onStream: (p: Product) => void;
  onHistory: (p: Product) => void;
}

export function CatalogBoard({ products, filters, onFilters, actions, onStream, onHistory }: Props) {
  return (
    <>
      <div className="filters">
        <label>
          Category
          <select
            value={filters.category ?? ''}
            onChange={(e) => onFilters({ ...filters, category: (e.target.value || undefined) as Category | undefined })}
          >
            <option value="">All</option>
            {CATEGORIES.map((c) => (
              <option key={c}>{c}</option>
            ))}
          </select>
        </label>
        <label>
          Status
          <select
            value={filters.status ?? ''}
            onChange={(e) => onFilters({ ...filters, status: (e.target.value || undefined) as ProductStatus | undefined })}
          >
            <option value="">All</option>
            {STATUSES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </label>
        <span className="legend">
          Stock heat: <i className="heat-out">out</i> <i className="heat-low">below threshold</i>{' '}
          <i className="heat-watch">&lt; 2× threshold</i> <i className="heat-ok">healthy</i>
        </span>
      </div>
      <div className="table-wrap">
        <table className="catalog">
          <thead>
            <tr>
              <th>Product</th>
              <th>Category</th>
              <th className="num">Price</th>
              <th className="num">Margin</th>
              <th className="num">Stock / thr.</th>
              <th className="num">Velocity /24h</th>
              <th>Status</th>
              <th>Pending</th>
              <th>Simulate</th>
              <th>Ask the advisor</th>
            </tr>
          </thead>
          <tbody>
            {products.map((p) => (
              <Row key={p.id} p={p} actions={actions} onStream={onStream} onHistory={onHistory} />
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

function Row({ p, actions, onStream, onHistory }: { p: Product; actions: Actions; onStream: (p: Product) => void; onHistory: (p: Product) => void }) {
  const [qty, setQty] = useState(1);
  const [stock, setStock] = useState<string>('');
  const selling = actions.placeOrder.isPending && actions.placeOrder.variables?.id === p.id;
  const asking =
    (actions.suggestPricing.isPending && actions.suggestPricing.variables === p.id) ||
    (actions.suggestReorder.isPending && actions.suggestReorder.variables === p.id);

  return (
    <tr>
      <td>
        <div className="strong">{p.name}</div>
        <div className="muted mono small">
          {p.id} · {p.sku}
        </div>
      </td>
      <td>{p.category}</td>
      <td className="num">{money(p.currentPrice)}</td>
      <td className="num" title={p.costPrice ? `cost ${money(p.costPrice)}` : 'no cost price (sprint 2 field)'}>
        {p.marginPct === null ? <span className="muted">—</span> : `${p.marginPct.toFixed(1)}%`}
      </td>
      <td className={`num heat ${stockHeat(p.stockLevel, p.reorderThreshold)}`}>
        {p.stockLevel} / {p.reorderThreshold}
      </td>
      <td className="num">
        {p.demandVelocity} <span className="muted small">({p.velocityRatio}×)</span>
        {p.demandSpike && <span className="spike"> spike</span>}
      </td>
      <td>
        <StatusBadge status={p.status} />
      </td>
      <td className="small">
        {p.pendingPricingCount + p.pendingReorderCount === 0 ? (
          <span className="muted">—</span>
        ) : (
          <>
            {p.pendingPricingCount} price · {p.pendingReorderCount} reorder
          </>
        )}
      </td>
      <td>
        <div className="inline">
          <input type="number" min={1} max={1000} value={qty} onChange={(e) => setQty(Math.max(1, Number(e.target.value) || 1))} />
          <button disabled={selling || p.stockLevel === 0} onClick={() => actions.placeOrder.mutate({ id: p.id, quantity: qty })}>
            {selling ? '…' : 'Sell'}
          </button>
        </div>
        <div className="inline">
          <input type="number" min={0} placeholder="stock" value={stock} onChange={(e) => setStock(e.target.value)} />
          <button
            disabled={stock === '' || actions.updateStock.isPending}
            onClick={() => {
              actions.updateStock.mutate({ id: p.id, stockLevel: Number(stock) });
              setStock('');
            }}
          >
            Set
          </button>
        </div>
      </td>
      <td>
        <div className="inline wrap">
          <button disabled={asking} onClick={() => actions.suggestPricing.mutate(p.id)}>
            Price
          </button>
          <button disabled={asking} onClick={() => actions.suggestReorder.mutate(p.id)}>
            Reorder
          </button>
          <button onClick={() => onStream(p)} title="Stream the AI reasoning live (SSE)">
            Stream ▸
          </button>
          <button onClick={() => onHistory(p)}>History</button>
        </div>
        {asking && <div className="muted small">Thinking…</div>}
      </td>
    </tr>
  );
}
