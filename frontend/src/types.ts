export type Category = 'ELECTRONICS' | 'APPAREL' | 'HOME';
export type ProductStatus = 'ACTIVE' | 'PRICE_REVIEW_PENDING' | 'OUT_OF_STOCK';
export type SuggestionStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED';
export type TriggerReason = 'INITIAL' | 'INVENTORY_LOW' | 'DEMAND_SPIKE' | 'MANUAL';
export type GeneratedBy = 'AI' | 'RULE' | 'RULE_FALLBACK';
export type PriceDirection = 'INCREASE' | 'DECREASE' | 'HOLD';
export type PricingTactic = 'PROTECT_INVENTORY' | 'CLEARANCE' | 'CAPITALIZE_ON_DEMAND' | 'HOLD';

export const CATEGORIES: Category[] = ['ELECTRONICS', 'APPAREL', 'HOME'];
export const STATUSES: ProductStatus[] = ['ACTIVE', 'PRICE_REVIEW_PENDING', 'OUT_OF_STOCK'];

export interface Product {
  id: string;
  sku: string;
  name: string;
  category: Category;
  currentPrice: number;
  stockLevel: number;
  reorderThreshold: number;
  belowReorderThreshold: boolean;
  demandVelocity: number;
  categoryAverageVelocity: number;
  velocityRatio: number;
  demandSpike: boolean;
  status: ProductStatus;
  costPrice: number | null;
  marginFloorPct: number | null;
  marginPct: number | null;
  supplierId: string | null;
  pendingPricingCount: number;
  pendingReorderCount: number;
  updatedAt: string;
}

interface SuggestionBase {
  id: number;
  productId: string;
  productSku: string;
  productName: string;
  confidence: number;
  reasoning: string;
  status: SuggestionStatus;
  triggerReason: TriggerReason;
  generatedBy: GeneratedBy;
  strategyName: string;
  fallbackReason: string | null;
  decisionNote: string | null;
  createdAt: string;
  decidedAt: string | null;
}

export interface PricingSuggestion extends SuggestionBase {
  currentPrice: number;
  recommendedPrice: number;
  changePct: number;
  direction: PriceDirection;
  tactic: PricingTactic;
  flagged: boolean;
  flagNote: string | null;
}

export interface ReorderSuggestion extends SuggestionBase {
  currentStock: number;
  recommendedQuantity: number;
  leadTimeDays: number;
  supplierId: string | null;
}

export interface DecisionResponse<T> {
  suggestion: T;
  product: Product;
}

export interface PriceHistoryEntry {
  id: number;
  oldPrice: number;
  newPrice: number;
  pricingSuggestionId: number | null;
  triggerReason: TriggerReason | null;
  changedAt: string;
}

export interface StrategySettings {
  active: string;
  fallback: string;
  available: { name: string; description: string }[];
  llmConfigured: boolean;
  llmModel: string;
}

export interface CreateProductInput {
  sku: string;
  name: string;
  category: Category;
  currentPrice: number;
  stockLevel: number;
  reorderThreshold: number;
  costPrice?: number;
  marginFloorPct?: number;
  supplierId?: string;
}
