import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from './api';
import type { CreateProductInput } from './types';

export type Notify = (kind: 'ok' | 'error', message: string) => void;

/** All writes invalidate every query so the console reflects new state immediately, then keeps polling. */
export function useActions(notify: Notify) {
  const qc = useQueryClient();
  const done = (message: string) => () => {
    notify('ok', message);
    qc.invalidateQueries();
  };
  const fail = (e: Error) => notify('error', e.message);

  return {
    decidePricing: useMutation({
      mutationFn: (v: { id: number; status: 'ACCEPTED' | 'REJECTED' }) => api.decidePricing(v.id, v.status),
      onSuccess: (r) =>
        done(
          r.suggestion.status === 'ACCEPTED'
            ? `${r.product.sku}: price is now $${r.product.currentPrice.toFixed(2)}`
            : `${r.product.sku}: pricing suggestion rejected, price unchanged`,
        )(),
      onError: fail,
    }),
    decideReorder: useMutation({
      mutationFn: (v: { id: number; status: 'ACCEPTED' | 'REJECTED' }) => api.decideReorder(v.id, v.status),
      onSuccess: (r) =>
        done(
          r.suggestion.status === 'ACCEPTED'
            ? `${r.product.sku}: ${r.suggestion.recommendedQuantity} units received, stock ${r.product.stockLevel}`
            : `${r.product.sku}: reorder rejected`,
        )(),
      onError: fail,
    }),
    placeOrder: useMutation({
      mutationFn: (v: { id: string; quantity: number }) => api.placeOrder(v.id, v.quantity),
      onSuccess: (r) =>
        done(
          `Sale recorded: ${r.product.sku} stock ${r.product.stockLevel}, velocity ${r.product.demandVelocity}/24h` +
            (r.product.belowReorderThreshold ? ' (below threshold, agent triggered)' : ''),
        )(),
      onError: fail,
    }),
    updateStock: useMutation({
      mutationFn: (v: { id: string; stockLevel: number }) => api.updateStock(v.id, v.stockLevel),
      onSuccess: (p) => done(`${p.sku}: stock set to ${p.stockLevel}`)(),
      onError: fail,
    }),
    suggestPricing: useMutation({
      mutationFn: (id: string) => api.suggestPricing(id),
      onSuccess: (s) => done(`${s.productSku}: pricing suggestion ready (${s.generatedBy})`)(),
      onError: fail,
    }),
    suggestReorder: useMutation({
      mutationFn: (id: string) => api.suggestReorder(id),
      onSuccess: (s) => done(`${s.productSku}: reorder suggestion ready (${s.generatedBy})`)(),
      onError: fail,
    }),
    setStrategy: useMutation({
      mutationFn: (name: string) => api.setStrategy(name),
      onSuccess: (s) => done(`Active strategy: ${s.active} (applies to the next trigger, no restart)`)(),
      onError: fail,
    }),
    createProduct: useMutation({
      mutationFn: (input: CreateProductInput) => api.createProduct(input),
      onSuccess: (p) => done(`Created ${p.id} (${p.sku})`)(),
      onError: fail,
    }),
  };
}

export type Actions = ReturnType<typeof useActions>;
