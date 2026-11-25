import type { Actions } from '../actions';
import { money, signedPct, timeAgo } from '../format';
import type { PricingSuggestion, Product, ReorderSuggestion } from '../types';
import { ConfidenceBar, SourceBadge, StatusBadge, TriggerBadge } from './Badges';

interface Props {
  products: Product[];
  pricing: PricingSuggestion[];
  reorders: ReorderSuggestion[];
  actions: Actions;
}

/** Products in PRICE_REVIEW_PENDING or with any pending suggestion, with inline AI reasoning and decisions. */
export function ReviewQueue({ products, pricing, reorders, actions }: Props) {
  const byId = new Map(products.map((p) => [p.id, p]));
  const ids = new Set<string>([
    ...pricing.map((s) => s.productId),
    ...reorders.map((s) => s.productId),
    ...products.filter((p) => p.status === 'PRICE_REVIEW_PENDING').map((p) => p.id),
  ]);
  const queue = [...ids]
    .map((id) => byId.get(id))
    .filter((p): p is Product => !!p)
    .sort((a, b) => a.stockLevel / Math.max(a.reorderThreshold, 1) - b.stockLevel / Math.max(b.reorderThreshold, 1));

  if (queue.length === 0) {
    return <p className="empty">Nothing to review. Simulate a sale below to watch the agent react.</p>;
  }

  return (
    <div className="queue">
      {queue.map((p) => (
        <article key={p.id} className="queue-item">
          <header className="queue-head">
            <div>
              <h3>
                {p.name} <span className="muted mono">{p.sku}</span>
              </h3>
              <div className="facts">
                <StatusBadge status={p.status} />
                <span>
                  Stock <b className={p.belowReorderThreshold ? 'warn' : ''}>{p.stockLevel}</b> / threshold {p.reorderThreshold}
                </span>
                <span>Price <b>{money(p.currentPrice)}</b></span>
                <span>
                  Velocity <b>{p.demandVelocity}</b>/24h ({p.velocityRatio}× avg {p.categoryAverageVelocity})
                  {p.demandSpike && <span className="spike"> spike</span>}
                </span>
              </div>
            </div>
          </header>
          <div className="cards">
            {pricing
              .filter((s) => s.productId === p.id)
              .map((s) => (
                <PricingCard key={`p${s.id}`} s={s} actions={actions} />
              ))}
            {reorders
              .filter((s) => s.productId === p.id)
              .map((s) => (
                <ReorderCard key={`r${s.id}`} s={s} actions={actions} />
              ))}
          </div>
        </article>
      ))}
    </div>
  );
}

function PricingCard({ s, actions }: { s: PricingSuggestion; actions: Actions }) {
  const busy = actions.decidePricing.isPending && actions.decidePricing.variables?.id === s.id;
  return (
    <div className={`card pricing ${s.flagged ? 'flagged' : ''}`}>
      <div className="card-top">
        <span className="kind">Pricing</span>
        <TriggerBadge trigger={s.triggerReason} />
        <SourceBadge source={s.generatedBy} reason={s.fallbackReason} />
        <span className="muted small">{timeAgo(s.createdAt)}</span>
      </div>
      <div className="headline">
        {money(s.currentPrice)} <span className="arrow">→</span> <b>{money(s.recommendedPrice)}</b>{' '}
        <span className={`dir dir-${s.direction.toLowerCase()}`}>
          {s.direction} {s.direction !== 'HOLD' && signedPct(s.changePct)}
        </span>
      </div>
      <div className="tactic">{s.tactic.replace(/_/g, ' ')}</div>
      <ConfidenceBar value={s.confidence} />
      <p className="reasoning">{s.reasoning}</p>
      {s.flagged && <p className="note flag">⚑ {s.flagNote}</p>}
      {s.fallbackReason && <p className="note fallback">Fallback: {s.fallbackReason}</p>}
      <div className="buttons">
        <button className="accept" disabled={busy} onClick={() => actions.decidePricing.mutate({ id: s.id, status: 'ACCEPTED' })}>
          Accept price
        </button>
        <button className="reject" disabled={busy} onClick={() => actions.decidePricing.mutate({ id: s.id, status: 'REJECTED' })}>
          Reject
        </button>
      </div>
    </div>
  );
}

function ReorderCard({ s, actions }: { s: ReorderSuggestion; actions: Actions }) {
  const busy = actions.decideReorder.isPending && actions.decideReorder.variables?.id === s.id;
  return (
    <div className="card reorder">
      <div className="card-top">
        <span className="kind">Reorder</span>
        <TriggerBadge trigger={s.triggerReason} />
        <SourceBadge source={s.generatedBy} reason={s.fallbackReason} />
        <span className="muted small">{timeAgo(s.createdAt)}</span>
      </div>
      <div className="headline">
        Order <b>{s.recommendedQuantity}</b> units <span className="muted">· lead time {s.leadTimeDays}d · stock at advice {s.currentStock}</span>
      </div>
      <ConfidenceBar value={s.confidence} />
      <p className="reasoning">{s.reasoning}</p>
      {s.fallbackReason && <p className="note fallback">Fallback: {s.fallbackReason}</p>}
      <div className="buttons">
        <button className="accept" disabled={busy} onClick={() => actions.decideReorder.mutate({ id: s.id, status: 'ACCEPTED' })}>
          Accept &amp; receive stock
        </button>
        <button className="reject" disabled={busy} onClick={() => actions.decideReorder.mutate({ id: s.id, status: 'REJECTED' })}>
          Reject
        </button>
      </div>
    </div>
  );
}
