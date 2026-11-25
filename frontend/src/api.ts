import type {
  Category,
  CreateProductInput,
  DecisionResponse,
  PriceHistoryEntry,
  PricingSuggestion,
  Product,
  ProductStatus,
  ReorderSuggestion,
  StrategySettings,
  SuggestionStatus,
} from './types';

export const API_BASE: string = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export class ApiError extends Error {
  constructor(public status: number, message: string, public code?: string) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(API_BASE + path, {
      ...init,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...init?.headers },
    });
  } catch {
    throw new ApiError(0, `Cannot reach backend at ${API_BASE}. Is it running?`);
  }
  if (!res.ok) {
    let message = res.statusText;
    let code: string | undefined;
    try {
      const problem = await res.json();
      message = problem.detail ?? problem.title ?? message;
      code = problem.code;
      if (Array.isArray(problem.errors) && problem.errors.length) {
        message += ': ' + problem.errors.map((e: { field: string; message: string }) => `${e.field} ${e.message}`).join(', ');
      }
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(res.status, message, code);
  }
  return res.json() as Promise<T>;
}

const qs = (params: Record<string, string | number | undefined>) => {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== '');
  return entries.length ? '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString() : '';
};

export const api = {
  products: (filters: { status?: ProductStatus; category?: Category } = {}) =>
    request<Product[]>('/products' + qs(filters)),
  createProduct: (input: CreateProductInput) =>
    request<Product>('/products', { method: 'POST', body: JSON.stringify(input) }),
  placeOrder: (id: string, quantity: number) =>
    request<{ orderId: number; product: Product }>(`/products/${id}/orders`, {
      method: 'POST',
      body: JSON.stringify({ quantity }),
    }),
  updateStock: (id: string, stockLevel: number) =>
    request<Product>(`/products/${id}/stock`, { method: 'PATCH', body: JSON.stringify({ stockLevel }) }),
  suggestPricing: (id: string) => request<PricingSuggestion>(`/products/${id}/suggest-pricing`, { method: 'POST' }),
  suggestReorder: (id: string) => request<ReorderSuggestion>(`/products/${id}/suggest-reorder`, { method: 'POST' }),
  priceHistory: (id: string) => request<PriceHistoryEntry[]>(`/products/${id}/price-history`),
  pricingSuggestions: (status?: SuggestionStatus, productId?: string, limit = 100) =>
    request<PricingSuggestion[]>('/pricing-suggestions' + qs({ status, productId, limit })),
  reorderSuggestions: (status?: SuggestionStatus, productId?: string, limit = 100) =>
    request<ReorderSuggestion[]>('/reorder-suggestions' + qs({ status, productId, limit })),
  decidePricing: (id: number, status: 'ACCEPTED' | 'REJECTED') =>
    request<DecisionResponse<PricingSuggestion>>(`/pricing-suggestions/${id}`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    }),
  decideReorder: (id: number, status: 'ACCEPTED' | 'REJECTED') =>
    request<DecisionResponse<ReorderSuggestion>>(`/reorder-suggestions/${id}`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    }),
  strategy: () => request<StrategySettings>('/settings/strategy'),
  setStrategy: (strategy: string) =>
    request<StrategySettings>('/settings/strategy', { method: 'PUT', body: JSON.stringify({ strategy }) }),
};

export interface StreamHandlers {
  onStatus?: (message: string) => void;
  onToken: (text: string) => void;
  onFallback?: (reason: string) => void;
  onSuggestion: (s: PricingSuggestion) => void;
  onError: (message: string) => void;
}

/** POST-based SSE (EventSource cannot POST), parsed from the fetch body stream. */
export async function streamPricing(productId: string, handlers: StreamHandlers, signal?: AbortSignal) {
  const res = await fetch(`${API_BASE}/products/${productId}/suggest-pricing/stream`, {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
    signal,
  });
  if (!res.ok || !res.body) {
    handlers.onError(`Stream failed (HTTP ${res.status})`);
    return;
  }
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer = (buffer + value).replace(/\r\n/g, '\n');
    let sep: number;
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      dispatch(buffer.slice(0, sep), handlers);
      buffer = buffer.slice(sep + 2);
    }
  }
  if (buffer.trim()) dispatch(buffer, handlers);
}

function dispatch(raw: string, h: StreamHandlers) {
  let event = 'message';
  const data: string[] = [];
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  }
  if (!data.length) return;
  let payload: any;
  try {
    payload = JSON.parse(data.join('\n'));
  } catch {
    return;
  }
  switch (event) {
    case 'status':
      h.onStatus?.(payload.message);
      break;
    case 'token':
      h.onToken(payload.text);
      break;
    case 'fallback':
      h.onFallback?.(payload.reason);
      break;
    case 'suggestion':
      h.onSuggestion(payload as PricingSuggestion);
      break;
    case 'error':
      h.onError(payload.message);
      break;
  }
}
