import { useCallback, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, API_BASE } from './api';
import { useActions } from './actions';
import { CatalogBoard } from './components/CatalogBoard';
import { CreateProductForm } from './components/CreateProductForm';
import { HistoryPanel } from './components/HistoryPanel';
import { ReviewQueue } from './components/ReviewQueue';
import { StreamPanel } from './components/StreamPanel';
import type { Category, Product, ProductStatus } from './types';

const POLL_MS = 4000;

interface Toast {
  id: number;
  kind: 'ok' | 'error';
  message: string;
}

export default function App() {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const notify = useCallback((kind: Toast['kind'], message: string) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, kind, message }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === 'error' ? 8000 : 4500);
  }, []);
  const actions = useActions(notify);

  const [filters, setFilters] = useState<{ category?: Category; status?: ProductStatus }>({});
  const [streaming, setStreaming] = useState<Product | null>(null);
  const [history, setHistory] = useState<Product | null>(null);

  const all = useQuery({ queryKey: ['products', 'all'], queryFn: () => api.products(), refetchInterval: POLL_MS });
  const catalog = useQuery({ queryKey: ['products', filters], queryFn: () => api.products(filters), refetchInterval: POLL_MS });
  const pricing = useQuery({ queryKey: ['pricing', 'PENDING'], queryFn: () => api.pricingSuggestions('PENDING'), refetchInterval: POLL_MS });
  const reorders = useQuery({ queryKey: ['reorder', 'PENDING'], queryFn: () => api.reorderSuggestions('PENDING'), refetchInterval: POLL_MS });
  const strategy = useQuery({ queryKey: ['strategy'], queryFn: api.strategy, refetchInterval: 15000 });

  const loadError = all.error ?? pricing.error ?? reorders.error;
  const pendingCount = (pricing.data?.length ?? 0) + (reorders.data?.length ?? 0);

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <h1>
            Stock<em>Pulse</em> <span className="muted">merchandising console</span>
          </h1>
          <p className="muted small">
            Agent proposes on inventory signals; you approve. Prices and stock only change when you accept.
          </p>
        </div>
        <div className="strategy">
          <label>
            Active strategy
            <select
              value={strategy.data?.active ?? ''}
              disabled={!strategy.data || actions.setStrategy.isPending}
              onChange={(e) => actions.setStrategy.mutate(e.target.value)}
            >
              {strategy.data?.available.map((s) => (
                <option key={s.name} value={s.name} title={s.description}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          {strategy.data && (
            <span className={`badge ${strategy.data.llmConfigured ? 'source-ai' : 'source-rule_fallback'}`}>
              {strategy.data.llmConfigured ? `LLM: ${strategy.data.llmModel}` : 'LLM key missing → rule fallback'}
            </span>
          )}
          <span className={`poll ${all.isFetching ? 'on' : ''}`} title={`Polling every ${POLL_MS / 1000}s`}>
            ● live
          </span>
        </div>
      </header>

      {loadError && (
        <div className="banner error">
          {loadError.message} <span className="muted">(API {API_BASE})</span>
        </div>
      )}

      <section>
        <h2>
          Review queue <span className="count">{pendingCount}</span>
        </h2>
        {all.isLoading || pricing.isLoading || reorders.isLoading ? (
          <p className="muted">Loading…</p>
        ) : (
          <ReviewQueue products={all.data ?? []} pricing={pricing.data ?? []} reorders={reorders.data ?? []} actions={actions} />
        )}
      </section>

      <section>
        <div className="section-head">
          <h2>Catalog board</h2>
          <CreateProductForm actions={actions} />
        </div>
        {catalog.isLoading ? (
          <p className="muted">Loading…</p>
        ) : catalog.isError ? (
          <p className="note error">{catalog.error.message}</p>
        ) : (
          <CatalogBoard
            products={catalog.data ?? []}
            filters={filters}
            onFilters={setFilters}
            actions={actions}
            onStream={setStreaming}
            onHistory={setHistory}
          />
        )}
      </section>

      {streaming && <StreamPanel product={streaming} onClose={() => setStreaming(null)} />}
      {history && <HistoryPanel product={history} onClose={() => setHistory(null)} />}

      <div className="toasts">
        {toasts.map((t) => (
          <div key={t.id} className={`toast ${t.kind}`}>
            {t.message}
          </div>
        ))}
      </div>
    </div>
  );
}
