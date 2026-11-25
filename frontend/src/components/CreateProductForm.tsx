import { useState, type FormEvent } from 'react';
import type { Actions } from '../actions';
import { CATEGORIES, type Category } from '../types';

export function CreateProductForm({ actions }: { actions: Actions }) {
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({
    sku: '', name: '', category: 'HOME' as Category, currentPrice: '', stockLevel: '', reorderThreshold: '',
    costPrice: '', marginFloorPct: '',
  });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    actions.createProduct.mutate(
      {
        sku: f.sku.trim().toUpperCase(),
        name: f.name.trim(),
        category: f.category,
        currentPrice: Number(f.currentPrice),
        stockLevel: Number(f.stockLevel),
        reorderThreshold: Number(f.reorderThreshold),
        costPrice: f.costPrice ? Number(f.costPrice) : undefined,
        marginFloorPct: f.marginFloorPct ? Number(f.marginFloorPct) : undefined,
      },
      { onSuccess: () => setOpen(false) },
    );
  };

  if (!open) return <button onClick={() => setOpen(true)}>+ New product</button>;
  return (
    <form className="create" onSubmit={submit}>
      <input required placeholder="SKU-HOME-003" value={f.sku} onChange={set('sku')} />
      <input required placeholder="Name" value={f.name} onChange={set('name')} />
      <select value={f.category} onChange={set('category')}>
        {CATEGORIES.map((c) => <option key={c}>{c}</option>)}
      </select>
      <input required type="number" step="0.01" min="0.01" placeholder="Price" value={f.currentPrice} onChange={set('currentPrice')} />
      <input required type="number" min="0" placeholder="Stock" value={f.stockLevel} onChange={set('stockLevel')} />
      <input required type="number" min="0" placeholder="Reorder threshold" value={f.reorderThreshold} onChange={set('reorderThreshold')} />
      <input type="number" step="0.01" min="0.01" placeholder="Cost (optional)" value={f.costPrice} onChange={set('costPrice')} />
      <input type="number" min="0" max="100" placeholder="Margin floor % (opt.)" value={f.marginFloorPct} onChange={set('marginFloorPct')} />
      <button type="submit" disabled={actions.createProduct.isPending}>Create</button>
      <button type="button" onClick={() => setOpen(false)}>Cancel</button>
    </form>
  );
}
