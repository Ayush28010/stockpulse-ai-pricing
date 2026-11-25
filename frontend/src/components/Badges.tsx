import type { GeneratedBy, ProductStatus, TriggerReason } from '../types';

const TRIGGER_LABEL: Record<TriggerReason, string> = {
  INVENTORY_LOW: 'Inventory low · auto',
  DEMAND_SPIKE: 'Demand spike · auto',
  INITIAL: 'Initial · auto',
  MANUAL: 'Manual request',
};

export function TriggerBadge({ trigger }: { trigger: TriggerReason }) {
  return (
    <span className={`badge trigger-${trigger.toLowerCase()}`} title={`triggerReason = ${trigger}`}>
      {TRIGGER_LABEL[trigger]}
    </span>
  );
}

export function SourceBadge({ source, reason }: { source: GeneratedBy; reason?: string | null }) {
  const label = source === 'AI' ? 'AI' : source === 'RULE' ? 'Rule-based' : 'Rule fallback';
  return (
    <span className={`badge source-${source.toLowerCase()}`} title={reason ?? undefined}>
      {label}
    </span>
  );
}

export function StatusBadge({ status }: { status: ProductStatus }) {
  return <span className={`badge status-${status.toLowerCase()}`}>{status.replace(/_/g, ' ')}</span>;
}

export function ConfidenceBar({ value }: { value: number }) {
  const pct = Math.round(value * 100);
  const tone = pct >= 75 ? 'high' : pct >= 50 ? 'mid' : 'low';
  return (
    <div className="confidence" title={`confidence ${value.toFixed(2)}`}>
      <div className={`confidence-fill ${tone}`} style={{ width: `${pct}%` }} />
      <span>{pct}% confidence</span>
    </div>
  );
}
