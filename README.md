# StockPulse: AI Inventory & Dynamic Pricing Engine

When stock crosses a threshold or demand spikes, StockPulse notices on its own, asks an LLM (or a rule engine) for a **price change and a reorder quantity**, and queues both for a merchandiser to approve. Prices and stock change **only** when a human accepts.

```
order / stock change ──▶ event (after commit) ──▶ async agent ──▶ CommerceStrategy (AI | RULE)
                                                                     │  validated, guardrailed, rule fallback
merchandiser console ◀── poll every 4s ◀── PENDING pricing + reorder suggestions
        └── Accept ──▶ price updated / stock received (one transaction)
```

- **Design decisions:** [ADR.md](ADR.md). Twelve entries, each Context → Options → Decision → Tradeoffs, with links into the code and a closing order → low stock → suggestions → accept walkthrough.
- **Layout:** `/backend` (Spring Boot 3.3, Java 17+, SQLite) and `/frontend` (React 18 + Vite + TypeScript).
- **Tests:** 51 passing (`cd backend && ./mvnw test`), including an end-to-end test of the agentic loop.

## Where each requirement lives

| Brief task | Where to look |
|---|---|
| T-1 Domain model and API | [`Product`](backend/src/main/java/com/stockpulse/product/Product.java), [`Suggestion`](backend/src/main/java/com/stockpulse/suggestion/Suggestion.java), [`ProductStatus`](backend/src/main/java/com/stockpulse/domain/ProductStatus.java) state machines; controllers in [`api/`](backend/src/main/java/com/stockpulse/api); nullable sprint 2 fields `costPrice`, `marginFloorPct`, `supplierId`; [`schema.sql`](backend/src/main/resources/schema.sql) |
| T-2 Pluggable commerce engine | [`CommerceStrategy`](backend/src/main/java/com/stockpulse/advisor/CommerceStrategy.java) interface, [`RuleBasedStrategy`](backend/src/main/java/com/stockpulse/advisor/RuleBasedStrategy.java), [`StrategyRegistry`](backend/src/main/java/com/stockpulse/advisor/StrategyRegistry.java) (runtime switch), [`CommerceAdvisor`](backend/src/main/java/com/stockpulse/advisor/CommerceAdvisor.java) (shared by HTTP and async callers) |
| T-3 AI commerce advisor | [`AiCommerceStrategy`](backend/src/main/java/com/stockpulse/ai/AiCommerceStrategy.java), prompts in [`resources/prompts/`](backend/src/main/resources/prompts) (inventory-low and demand-spike are separate documents), [`AiAdviceValidator`](backend/src/main/java/com/stockpulse/ai/AiAdviceValidator.java) (bounds), rule fallback; SSE bonus in `POST /products/{id}/suggest-pricing/stream` |
| T-4 Agentic recommendation loop | [`RecommendationTriggerHandler`](backend/src/main/java/com/stockpulse/agent/RecommendationTriggerHandler.java) (`@Async` + `AFTER_COMMIT`), [`TriggerEvaluator`](backend/src/main/java/com/stockpulse/agent/TriggerEvaluator.java), [`RecommendationService`](backend/src/main/java/com/stockpulse/recommendation/RecommendationService.java) (idempotency) |
| Human checkpoint | [`SuggestionService.decidePricing / decideReorder`](backend/src/main/java/com/stockpulse/suggestion/SuggestionService.java): prices and stock change only on accept, in one transaction |
| T-5 Merchandising console | [`frontend/src`](frontend/src): review queue with trigger badges, catalog board, simulate sale, accept/reject, filters, stock heatmap, margin, price history, live SSE panel |
| T-6 ADR and walkthrough | [ADR.md](ADR.md), [scripts/walkthrough.ps1](scripts/walkthrough.ps1), [scripts/walkthrough.sh](scripts/walkthrough.sh) |

---

## Run it (about 3 minutes)

### Prerequisites
- **JDK 17+.** A JDK, not a JRE: `javac -version` must work. On Windows, a JRE-only install fails with *"No compiler is provided in this environment"*.
- **Node 18+.**
- Maven is **not** required; the wrapper (`mvnw`) is included.

### 1. Backend (http://localhost:8080)

```bash
cd backend
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```

- On first start it creates `backend/data/stockpulse.db`, seeds the 8 Addendum A products, and runs the agent on any product that starts low or spiking (`INITIAL` suggestions).
- **LLM key (optional).** Without a key everything still works: the AI strategy falls back to rules and every suggestion is labelled `RULE_FALLBACK` with the reason. To enable the LLM, use either:
  - an environment variable: `export LLM_API_KEY=...` (PowerShell: `$env:LLM_API_KEY="..."`), or
  - `backend/application-local.properties` (git-ignored; template in `application-local.properties.example`).
- **Provider.** Any OpenAI-compatible endpoint works: set `LLM_BASE_URL`, `LLM_MODEL`, and optionally `LLM_PRODUCT_HEADER` (sent as the `product` header the LiteLLM gateway needs). Groq, Ollama and Gemini examples are in the template file.

### 2. Frontend (http://localhost:5173)

```bash
cd frontend
npm install
npm run dev                      # Windows with restricted script policy: npm.cmd install / npm.cmd run dev
```

The UI calls `http://localhost:8080` by default; override it with `VITE_API_BASE_URL`.

### 3. Tests

```bash
cd backend && ./mvnw test        # 51 tests: rules, validator, broken-LLM fallbacks, prompts, SSE splitter, end-to-end loop
```

### Reset the demo data
Stop the backend, delete `backend/data/`, and start again.

---

## 5-minute demo script

1. **Open the console.** The review queue already holds `INITIAL · auto` suggestions for the seeded products that start low or spiking (the T-shirt, lamp, charger, hoodie and pour-over set). Each card shows the tactic, the % change, confidence, the AI reasoning, and a source badge (*AI* / *Rule fallback*).
2. **Inventory-low path.**
   - In the catalog, click **Sell** on **PRD-003 Organic Cotton T-Shirt** (stock 8 → 7, below threshold 15). The request returns at once; a toast says *agent triggered*.
   - Within a few seconds, a pricing card and a reorder card appear with the **Inventory low · auto** badge.
   - Click **Accept price**. The price changes (e.g. $24.99 → $27.49), the older INITIAL suggestion is superseded, and the product returns to ACTIVE.
   - Click **History** to see the price chart.
3. **Accept the reorder.** Stock increases by the recommended quantity (simulated inbound shipment).
4. **Demand-spike path.**
   - Click **Sell** about 7 times on **PRD-008 Hoodie** (velocity 15 → 22, above 3× the apparel average). A **Demand spike · auto** pair appears, separate from its inventory-low pair.
   - On a fresh database it takes 7 orders. Each earlier T-shirt order raises the apparel average and adds about 1.5 to the spike threshold.
5. **Runtime switch.** Change *Active strategy* to `RULE` in the header, then click **Price** on any product: the new card says *Rule-based*. Switch back to `AI`. No restart.
   - The same switch works as pure config: in DBeaver run `UPDATE app_settings SET setting_value='RULE' WHERE setting_key='commerce.strategy'`. The change is picked up within 5 s.
6. **Streaming (SSE).** Click **Stream ▸** on a product. The AI reasoning streams token by token, then the suggestion lands in the queue.
7. **Resilience.** Restart the backend without `LLM_API_KEY` (or with a wrong key) and sell PRD-007: suggestions still appear, labelled *Rule fallback* with the reason.

The same flow is available from the command line: `scripts/walkthrough.sh` (bash) or `scripts/walkthrough.ps1` (PowerShell).

---

## API summary

| Method | Path | Notes |
|---|---|---|
| POST | `/products` | Create (validated; optional `costPrice`, `marginFloorPct`, `supplierId`) |
| GET | `/products?status=&category=` | Filterable catalog with velocity vs category average and pending counts |
| GET | `/products/{id}` | |
| PATCH | `/products/{id}/stock` | `{"stockLevel":5}`; fires the loop if below threshold |
| POST | `/products/{id}/orders` | `{"quantity":1}` (body optional); fires the loop on low stock or a spike |
| POST | `/products/{id}/suggest-pricing` | On demand (MANUAL) |
| POST | `/products/{id}/suggest-reorder` | On demand (MANUAL) |
| POST | `/products/{id}/suggest-pricing/stream` | SSE: `status`, `token`, `fallback`, `suggestion`, `error` |
| GET | `/products/{id}/price-history` | |
| GET | `/pricing-suggestions?status=&productId=&limit=` | |
| PATCH | `/pricing-suggestions/{id}` | `{"status":"ACCEPTED"\|"REJECTED"}`; accept updates the price atomically |
| GET | `/reorder-suggestions?status=&productId=&limit=` | |
| PATCH | `/reorder-suggestions/{id}` | Accept increments stock atomically |
| GET / PUT | `/settings/strategy` | `{"strategy":"AI"\|"RULE"}`; runtime switch, persisted |

Errors are RFC 7807 JSON with a `code`, e.g. `INSUFFICIENT_STOCK`, `VALIDATION_FAILED`, `STALE_SUGGESTION`, `INVALID_STATE_TRANSITION`.

```bash
curl -X POST localhost:8080/products/PRD-003/orders -H 'Content-Type: application/json' -d '{"quantity":1}'
sleep 10
curl 'localhost:8080/pricing-suggestions?status=PENDING&productId=PRD-003'
curl -X PATCH localhost:8080/pricing-suggestions/<id> -H 'Content-Type: application/json' -d '{"status":"ACCEPTED"}'
```

---

## Configuration (`backend/src/main/resources/application.yml`)

| Property | Default | Meaning |
|---|---|---|
| `stockpulse.strategy.default-name` (`STOCKPULSE_STRATEGY`) | `AI` | Initial strategy; changed at runtime via `PUT /settings/strategy` |
| `stockpulse.trigger.spike-multiplier` | `3.0` | DEMAND_SPIKE when velocity > N × category average |
| `stockpulse.rule.*` | 2.0× / +10% / +5% / 3× | Rule-based strategy from the brief |
| `stockpulse.guardrail.min/max-price-ratio` | `0.25` / `4.0` | Hard bounds; AI output outside them is rejected and rules are used |
| `stockpulse.guardrail.flag-change-pct` | `30` | Soft band; larger changes are flagged for review |
| `stockpulse.db.path` (`STOCKPULSE_DB_PATH`) | `./data/stockpulse.db` | SQLite file |
| `llm.base-url` / `llm.model` / `llm.api-key` | LiteLLM / gpt-5.6-luna / *(none)* | `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY` |
| `llm.read-timeout` | `25s` | Then rule fallback |

## Inspecting the database (DBeaver)
1. Choose **Database → New Database Connection → SQLite → Next**.
2. Set **Path** to `<repo>/backend/data/stockpulse.db` and click **Finish**. If prompted, let DBeaver download the driver.
3. Expand **Tables** and open `pricing_suggestions` or `products`; press F5 to refresh after actions in the UI.

## Project structure

```
backend/src/main/java/com/stockpulse/
  advisor/         CommerceStrategy contract, RuleBasedStrategy, StrategyRegistry, CommerceAdvisor, guardrails
  ai/              AiCommerceStrategy, LlmClient (OpenAI-compatible), PromptFactory, parser, validator, SSE splitter
  agent/           InventoryChangedEvent, TriggerEvaluator, RecommendationTriggerHandler (@Async, AFTER_COMMIT)
  recommendation/  RecommendationService: the shared HTTP + async generation path
  product/         Product, SaleOrder, PriceHistory, ProductService, status policy, seed
  suggestion/      PricingSuggestion, ReorderSuggestion, SuggestionService (human checkpoint)
  api/             Controllers, DTOs, RFC 7807 error handling
backend/src/main/resources/prompts/   system, inventory-low, demand-spike, manual-review (plain text)
frontend/src/                         App, ReviewQueue, CatalogBoard, StreamPanel, HistoryPanel, api.ts
```
