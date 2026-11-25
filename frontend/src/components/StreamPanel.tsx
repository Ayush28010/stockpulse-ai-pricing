import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { streamPricing } from '../api';
import { money, signedPct } from '../format';
import type { PricingSuggestion, Product } from '../types';
import { ConfidenceBar, SourceBadge } from './Badges';

/** Live SSE view: reasoning tokens arrive first, then the persisted PENDING suggestion. */
export function StreamPanel({ product, onClose }: { product: Product; onClose: () => void }) {
  const [status, setStatus] = useState('Connecting…');
  const [text, setText] = useState('');
  const [fallback, setFallback] = useState<string | null>(null);
  const [suggestion, setSuggestion] = useState<PricingSuggestion | null>(null);
  const [error, setError] = useState<string | null>(null);
  const qc = useQueryClient();
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const controller = new AbortController();
    streamPricing(
      product.id,
      {
        onStatus: setStatus,
        onToken: (t) => setText((prev) => prev + t),
        onFallback: setFallback,
        onSuggestion: (s) => {
          setSuggestion(s);
          setStatus('Suggestion queued for approval');
          qc.invalidateQueries();
        },
        onError: setError,
      },
      controller.signal,
    ).catch((e) => {
      if (e.name !== 'AbortError') setError(String(e.message ?? e));
    });
  }, [product.id, qc]);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <h3>
            Live AI reasoning · {product.name} <span className="muted mono">{product.sku}</span>
          </h3>
          <button onClick={onClose}>Close</button>
        </header>
        <p className="muted small">{status}</p>
        <pre className="stream">
          {text}
          {!suggestion && !error && <span className="cursor">▍</span>}
        </pre>
        {fallback && <p className="note fallback">Fallback to rules: {fallback}</p>}
        {error && <p className="note error">{error}</p>}
        {suggestion && (
          <div className="card pricing">
            <div className="card-top">
              <span className="kind">Pricing · MANUAL</span>
              <SourceBadge source={suggestion.generatedBy} reason={suggestion.fallbackReason} />
            </div>
            <div className="headline">
              {money(suggestion.currentPrice)} → <b>{money(suggestion.recommendedPrice)}</b>{' '}
              <span className={`dir dir-${suggestion.direction.toLowerCase()}`}>
                {suggestion.direction} {suggestion.direction !== 'HOLD' && signedPct(suggestion.changePct)}
              </span>
            </div>
            <div className="tactic">{suggestion.tactic.replace(/_/g, ' ')}</div>
            <ConfidenceBar value={suggestion.confidence} />
            <p className="reasoning">{suggestion.reasoning}</p>
            <p className="muted small">Pending in the review queue; accept or reject it there.</p>
          </div>
        )}
      </div>
    </div>
  );
}
