# StockPulse: Architecture Decision Records

Stack: Java 17+ (built and tested on 21), Spring Boot 3.3, Maven (wrapper included), Spring Data JPA, SQLite, React 18 + Vite + TypeScript.
LLM: any OpenAI-compatible chat endpoint (LiteLLM gateway by default; Groq, Ollama and Gemini's OpenAI-compatible endpoint by config).

Each entry follows **Context → Options → Decision → Tradeoffs** and points to the code that implements it.

| # | Decision |
|---|---|
| [ADR-1](#adr-1-where-commerce-logic-lives) | Where commerce logic lives |
| [ADR-2](#adr-2-unified-contract-and-one-ai-call-with-per-half-fallback) | Unified contract and one AI call with per-half fallback |
| [ADR-3](#adr-3-runtime-strategy-switching-via-a-registry) | Runtime strategy switching via a registry |
| [ADR-4](#adr-4-the-llm-decides-the-tactic-two-situation-specific-prompts) | The LLM decides the tactic; two situation-specific prompts |
| [ADR-5](#adr-5-llm-failure-handling-and-output-validation) | LLM failure handling and output validation |
| [ADR-6](#adr-6-agentic-loop-trigger-decoupling-and-idempotency) | Agentic loop: trigger, decoupling, idempotency |
| [ADR-7](#adr-7-human-checkpoint-and-accept-side-effects) | Human checkpoint and accept side effects |
| [ADR-8](#adr-8-demand-velocity-and-spike-definition) | Demand velocity and spike definition |
| [ADR-9](#adr-9-persistence-on-sqlite) | Persistence on SQLite |
| [ADR-10](#adr-10-api-contract-and-validation) | API contract and validation |
| [ADR-11](#adr-11-frontend-react-18) | Frontend: React 18 |
| [ADR-12](#adr-12-extensibility-seams-and-deliberate-exclusions) | Extensibility seams and deliberate exclusions |

---

## ADR-1 Where commerce logic lives

**Context.** Pricing rules, reorder rules, prompting, event publishing and persistence could easily pile up in one `ProductService`. Sprint 2 adds competitor prices, margin floors and suppliers, all of which would make that class grow.

**Options.**
1. Everything in a `ProductService`.
2. A rich domain model: pricing logic as methods on `Product`.
3. A dedicated **advisor** layer that only computes recommendations from an immutable snapshot, with thin services around it for persistence and orchestration.

**Decision.** Option 3, with one-way dependencies:

```
api ──▶ product / suggestion / recommendation ──▶ advisor ◀── ai
                     ▲                                │
                     └────── agent (events) ──────────┘   (advisor never touches repositories)
```

| Package | Responsibility |
|---|---|
| [`advisor`](backend/src/main/java/com/stockpulse/advisor) | `CommerceStrategy` contract, `RuleBasedStrategy`, `StrategyRegistry`, `CommerceAdvisor` facade, `PriceGuardrail` chain. Input is a [`ProductContext`](backend/src/main/java/com/stockpulse/advisor/ProductContext.java) record; no JPA, no HTTP. |
| [`ai`](backend/src/main/java/com/stockpulse/ai) | `AiCommerceStrategy`, `LlmClient`, `PromptFactory` + templates, parser, validator. |
| [`recommendation`](backend/src/main/java/com/stockpulse/recommendation/RecommendationService.java) | Load context, then advise, then persist. **The one path shared by HTTP and async callers.** |
| [`agent`](backend/src/main/java/com/stockpulse/agent) | `InventoryChangedEvent`, `TriggerEvaluator`, async `RecommendationTriggerHandler`. |
| [`product`](backend/src/main/java/com/stockpulse/product) | Entities, stock/order use cases, status policy, seed. |
| [`suggestion`](backend/src/main/java/com/stockpulse/suggestion) | Suggestion entities, dedupe-aware persistence, accept/reject (the human checkpoint). |
| [`api`](backend/src/main/java/com/stockpulse/api) | Controllers, DTOs, RFC 7807 errors. |

State transitions live on the entities ([`Product.transitionTo`](backend/src/main/java/com/stockpulse/product/Product.java), [`Suggestion.accept/reject`](backend/src/main/java/com/stockpulse/suggestion/Suggestion.java)) and the enums define which transitions are legal ([`ProductStatus`](backend/src/main/java/com/stockpulse/domain/ProductStatus.java), [`SuggestionStatus`](backend/src/main/java/com/stockpulse/domain/SuggestionStatus.java)).

**Tradeoffs.** More classes and DTO mapping than a single service. Strategies are pure functions of `ProductContext`, so they are unit-tested without Spring or a database ([`AiCommerceStrategyTest`](backend/src/test/java/com/stockpulse/ai/AiCommerceStrategyTest.java), [`RuleBasedStrategyTest`](backend/src/test/java/com/stockpulse/advisor/RuleBasedStrategyTest.java)). The cost is that context has to be loaded up front, even fields a given strategy ignores.

---

## ADR-2 Unified contract and one AI call with per-half fallback

**Context.** Every trigger needs a price recommendation and a reorder recommendation for the same situation, and the two are correlated. Low stock with strong demand argues for "raise price **and** reorder big"; clearance argues for "cut price **and** don't restock". Cost and latency matter, and so does independent failure.

**Options.**
1. Two contracts (`PricingStrategy`, `ReorderStrategy`) and two LLM calls.
2. One contract, one call, all-or-nothing validation.
3. One contract, one call, **each half validated and replaced independently**.

**Decision.** Option 3.

```java
public interface CommerceStrategy {          // advisor/CommerceStrategy.java
    String name();
    String description();
    Advice advise(ProductContext ctx, TriggerReason trigger, Set<SuggestionType> wanted);
}
public record Advice(PricingAdvice pricing, ReorderAdvice reorder) {}
```

- `wanted` lets `POST /suggest-pricing` request only pricing, while the agentic loop requests only the types that have no PENDING row yet.
- [`AiCommerceStrategy`](backend/src/main/java/com/stockpulse/ai/AiCommerceStrategy.java) makes **one** completion returning `{pricing, reorder}`. If the price is absurd but the quantity is fine, only the pricing half becomes `RULE_FALLBACK` (covered by the tests `absurdPriceFallsBackOnlyForPricingHalf` and `badQuantityFallsBackOnlyForReorderHalf`).
- Every persisted suggestion records `generatedBy` (`AI | RULE | RULE_FALLBACK`), `strategyName` and `fallbackReason`, so the UI and audit trail show exactly what happened.

**Tradeoffs.** One failed call loses both halves (rule fallback covers it). The prompt is longer. Pricing and reorder cannot be served by different providers without a composite strategy, which is easy to add later as a third `CommerceStrategy`. Two calls would have doubled latency and token cost, and lost the joint reasoning ("I cleared, so I reordered 1 unit") that the walkthrough relies on.

---

## ADR-3 Runtime strategy switching via a registry

**Context.** The active strategy must change without a code change or restart, behave identically from HTTP threads and `@Async` handlers, and a sprint 2 `CompetitorAwareStrategy` must plug in just by implementing the contract.

**Options.**
1. `@ConditionalOnProperty` (needs a restart).
2. Spring Cloud `@RefreshScope` / actuator refresh (extra dependency, global side effects).
3. Inject every `CommerceStrategy` bean into a name-keyed **registry** and resolve the active name on each call.

**Decision.** Option 3: [`StrategyRegistry`](backend/src/main/java/com/stockpulse/advisor/StrategyRegistry.java).
- Discovers `List<CommerceStrategy>` at startup and rejects duplicate names. `RULE` must exist because it is the fallback.
- The active name is runtime **config** in the `app_settings` table, seeded from `stockpulse.strategy.default-name` (env `STOCKPULSE_STRATEGY`). It can be changed two ways, neither needing a restart:
  - `PUT /settings/strategy {"strategy":"RULE"}` (the UI header dropdown), or
  - editing the row directly (`UPDATE app_settings SET setting_value='RULE' WHERE setting_key='commerce.strategy'`, e.g. in DBeaver).

  The registry caches the value and re-reads it every `stockpulse.strategy.refresh-interval` (5 s).
- `GET /settings/strategy` lists the active and available strategies.
- [`CommerceAdvisor`](backend/src/main/java/com/stockpulse/advisor/CommerceAdvisor.java) calls `registry.active()` per invocation, so the next request or trigger uses the new strategy. Covered by the integration tests `strategySwitchesAtRuntimeForOnDemandCalls` and `strategyEditedDirectlyInConfigTableAppliesWithoutRestart`.

**Tradeoffs.** One global strategy, not per category (a `Map<Category,String>` setting would be the next step). A direct table edit takes up to 5 s to apply; the API switch is immediate. Persisting the switch costs one table, but the choice survives restarts and is auditable. A switch does not affect an async run already in flight.

---

## ADR-4 The LLM decides the tactic; two situation-specific prompts

**Context.** Low stock is genuinely ambiguous: raise the price to protect scarce units, or discount to clear them? A spike is a different decision (capitalize vs hold, transient vs sustained). The model has to make and **explain** that call, not just emit a number.

**Options.**
1. LLM returns a number; rules pick the tactic.
2. LLM picks a tactic plus numbers; the server validates and derives the direction.
3. LLM also picks which strategy runs (a meta-selector).

**Decision.** Option 2. The model returns:

```json
{"pricing":{"tactic":"PROTECT_INVENTORY|CLEARANCE|CAPITALIZE_ON_DEMAND|HOLD","recommendedPrice":27.49,"confidence":0.88,"reasoning":"..."},
 "reorder":{"recommendedQuantity":196,"leadTimeDays":10,"confidence":0.82,"reasoning":"..."}}
```

`direction` is **derived server-side** from recommended vs current price and never trusted from the model.

[`PromptFactory`](backend/src/main/java/com/stockpulse/ai/PromptFactory.java) chooses one of three plain-text templates in [`resources/prompts/`](backend/src/main/resources/prompts). All three share a [system prompt](backend/src/main/resources/prompts/system.txt) that defines the schema and hard rules.

| Template | Leads with | Decision framing |
|---|---|---|
| [`inventory-low.txt`](backend/src/main/resources/prompts/inventory-low.txt) | Stock vs threshold, shortfall, % of threshold, days of cover, lead time and a stock-out warning | PROTECT_INVENTORY vs CLEARANCE vs HOLD, keyed on *why* stock is low (velocity vs category average). Reorder must be consistent with the tactic (no big reorder after a clearance). Out-of-stock note: there is nothing to clear. |
| [`demand-spike.txt`](backend/src/main/resources/prompts/demand-spike.txt) | Velocity, category average, ×-ratio, spike threshold, days of cover | The model must first state whether the spike is **transient or sustained**. CAPITALIZE (3–8%) vs HOLD; warns about gouging above 15%; reorder sized to *sustained* demand, not the peak. |
| [`manual-review.txt`](backend/src/main/resources/prompts/manual-review.txt) | Neutral | The model states which situation applies; HOLD is explicitly acceptable. |

Both automatic prompts include the category average, the rule-based baseline (so the model explains when it beats it), cost and margin floor if known, and cross-signals (the low-stock prompt says if demand is *also* spiking). [`PromptFactoryTest`](backend/src/test/java/com/stockpulse/ai/PromptFactoryTest.java) asserts the two documents differ and carry their key context.

`INITIAL` (a product created or seeded while already low or spiking) uses whichever template matches its situation.

**Tradeoffs.** The model can contradict itself (tactic HOLD with a different price). The validator corrects or flags that (ADR-5), so a contradiction is visible rather than silently trusted. The meta-selector (option 3) was rejected: it is non-deterministic, hard to test, and ADR-3 already gives operators explicit control.

---

## ADR-5 LLM failure handling and output validation

**Context.** LLMs time out, hit quotas, return prose or code fences instead of JSON, and occasionally propose $0 or $999,999. The async path must never silently drop a suggestion.

**Decision.** Defence in three layers:

1. **Transport** ([`OpenAiCompatibleLlmClient`](backend/src/main/java/com/stockpulse/ai/OpenAiCompatibleLlmClient.java)): connect timeout 5 s, read timeout 25 s, JSON mode on, no retries. A missing key, timeout, 429, 401/403, other non-2xx or empty content each raise `LlmUnavailableException` with a specific message. Error bodies are truncated and the key is never logged.
2. **Parsing and validation** ([`AiAdviceParser`](backend/src/main/java/com/stockpulse/ai/AiAdviceParser.java), [`AiAdviceValidator`](backend/src/main/java/com/stockpulse/ai/AiAdviceValidator.java)), applied **per half**:
   - Extract the first `{…}` (tolerates code fences and stray prose).
   - Price must be numeric, > 0, rounded to 2 dp, and within the **hard bounds 0.25×–4× current**, otherwise it is rejected and the rule-based result is used.
   - Confidence must be present and finite. 0–100 values are read as percent; the result is clamped to [0, 1].
   - Reasoning must be non-blank (truncated to 2000 characters).
   - Quantity must be a positive **whole** number ≤ max(20 × threshold, 60 days × velocity). The cap scales with demand: the model's 255 units for a 15-orders/day hoodie is sensible, while 100,000 is not.
   - Lead time must be 1–180 days, otherwise the category default is used.
   - Tactic/price contradictions: HOLD with a different price is held at the current price; CLEARANCE with an increase is flagged; CLEARANCE on an out-of-stock item is flagged. A missing tactic is inferred from the direction and flagged.
3. **Guardrails on every strategy's output** ([`PriceGuardrail`](backend/src/main/java/com/stockpulse/advisor/PriceGuardrail.java) chain in `CommerceAdvisor`):
   - [`MarginFloorGuardrail`](backend/src/main/java/com/stockpulse/advisor/MarginFloorGuardrail.java) raises any price below cost × (1 + floor%) and flags it.
   - [`LargeChangeFlagGuardrail`](backend/src/main/java/com/stockpulse/advisor/LargeChangeFlagGuardrail.java) is a soft band: changes over ±30% are kept but **flagged** for review.

The safety net: `CommerceAdvisor` catches any exception a strategy throws and fills every wanted half from `RuleBasedStrategy`. The async path therefore always persists a pricing **and** a reorder suggestion. Tests: [`AiCommerceStrategyTest`](backend/src/test/java/com/stockpulse/ai/AiCommerceStrategyTest.java) covers timeout, quota, malformed JSON, code fences, absurd and zero price, negative quantity, and stream failure. [`AiAdviceValidatorTest`](backend/src/test/java/com/stockpulse/ai/AiAdviceValidatorTest.java) covers the bounds.

**Options considered.** Retry with backoff, which was rejected for the async path because the rule fallback *is* the retry and keeps the queue moving. A circuit breaker, deferred because one node with a 25 s timeout does not need it yet.

**Tradeoffs.** The bounds are heuristics (configurable under `stockpulse.guardrail.*`). With no retries, a transient blip yields a rule-based suggestion that is clearly labelled, which the merchandiser can re-request manually.

---

## ADR-6 Agentic loop: trigger, decoupling, idempotency

**Context.** Stock and order endpoints must return immediately. Recommendations must fire *because something changed*, not on a timer. INVENTORY_LOW and DEMAND_SPIKE can both fire for the same product.

**Options.**
1. Call the advisor inline in the controller (blocks on the LLM, couples HTTP to AI).
2. A `@Scheduled` poller (fires on a timer, not a change; wasteful and delayed).
3. `ApplicationEventPublisher`, then `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` on a bounded executor.
4. An external broker (Kafka or Rabbit). Overkill for a single node.

**Decision.** Option 3.

```
POST /products/{id}/orders ─▶ ProductService.recordOrder  [tx: sell, insert sale_order, recompute velocity,
                                                           derive status, publish InventoryChangedEvent]
                            ◀─ 201 returned after commit (no LLM on this path)
AFTER_COMMIT ─▶ agent-* thread ─▶ RecommendationTriggerHandler
     ─▶ TriggerEvaluator: stock < threshold ⇒ INVENTORY_LOW ; (order) velocity > 3× peers ⇒ DEMAND_SPIKE
     ─▶ for each fired trigger: RecommendationService.generateForTrigger
          ├─ skip types already PENDING for (product, trigger)        ← idempotency
          ├─ load ProductContext (short read tx)
          ├─ CommerceAdvisor.advise(...)       (no DB connection held during the LLM call)
          └─ SuggestionService.save (short write tx, re-checks pending, product → PRICE_REVIEW_PENDING)
UI polls every 4 s ─▶ suggestion appears with its trigger badge
```

Code: [`ProductService.recordOrder`](backend/src/main/java/com/stockpulse/product/ProductService.java), [`RecommendationTriggerHandler`](backend/src/main/java/com/stockpulse/agent/RecommendationTriggerHandler.java), [`TriggerEvaluator`](backend/src/main/java/com/stockpulse/agent/TriggerEvaluator.java), [`RecommendationService`](backend/src/main/java/com/stockpulse/recommendation/RecommendationService.java), executor in [`AsyncConfig`](backend/src/main/java/com/stockpulse/config/AsyncConfig.java).

**One handler, triggers evaluated independently.** If both fire, each gets its own run with its own prompt and its own badge. The idempotency key is `(product, triggerReason, type)`, exactly as the brief defines duplicates. A product that is already low (pending INVENTORY_LOW) and then goes viral still gets a DEMAND_SPIKE recommendation. The seeded hoodie demo depends on this, because it starts below threshold. Merging both into one INVENTORY_LOW run, as the draft proposed, would have hidden the spike.

**Idempotency, three layers.**
1. A pre-check for which types lack a PENDING row.
2. An in-memory in-flight guard per `(product, trigger)`, so a burst of orders during a 7 s LLM call starts one run, not five.
3. A SQLite **partial unique index** `(product_id, trigger_reason) WHERE status='PENDING'` in [`schema.sql`](backend/src/main/resources/schema.sql). A concurrent duplicate fails to insert and is logged.

Covered by the integration test `orderDropsStockBelowThresholdThenAcceptUpdatesPrice` (3 orders → exactly one pending pair).

**Triggers.** Stock PATCH, orders and accepted reorders evaluate INVENTORY_LOW. Only orders evaluate DEMAND_SPIKE, because only orders move velocity. Product creation and seeding emit **INITIAL** if the product starts low or spiking.

**Tradeoffs.**
- In-process events are lost if the JVM dies between commit and handler. The next inventory change re-triggers; a transactional outbox is the durable upgrade.
- If the executor queue (500 tasks) is full, the rejection is logged at ERROR rather than blocking HTTP.
- A *rejected* suggestion can re-fire on the next order, because only PENDING rows dedupe. A per-product cooldown is a sprint 2 item, and the single place to add it is `SuggestionService.typesWithoutPending`.
- Two triggers mean two LLM calls for that product. That is acceptable because both are rare edge events.

---

## ADR-7 Human checkpoint and accept side effects

**Context.** Prices and stock must never change without a merchandiser's accept. Several suggestions can be pending for one product (INITIAL, INVENTORY_LOW, DEMAND_SPIKE, MANUAL), so a stale one must not overwrite a newer price.

**Decision.** In [`SuggestionService`](backend/src/main/java/com/stockpulse/suggestion/SuggestionService.java), each decision is **one `@Transactional` unit**:

- **Accept pricing.**
  - Require PENDING, otherwise 409 `INVALID_STATE_TRANSITION`.
  - Require `suggestion.currentPrice == product.currentPrice`, otherwise 409 `STALE_SUGGESTION`.
  - Set the live price and write a `price_history` row.
  - **Supersede** every other PENDING pricing suggestion for that product, marking it REJECTED with a note, because it was computed against the old price.
- **Accept reorder.**
  - `stockLevel += recommendedQuantity` (simulated inbound shipment).
  - Supersede the other PENDING reorders so the same shortfall is not ordered twice.
  - Publish `REORDER_RECEIVED`: if stock is still below threshold, the loop re-evaluates.
- **Reject.** Nothing changes except the suggestion.
- **Pricing and reorder are independent.** Accepting one leaves the other pending (AGT-4).
- **Status is derived, not hand-set.** [`ProductStatusPolicy`](backend/src/main/java/com/stockpulse/product/ProductStatusPolicy.java) runs `ProductStatus.derive(stock, hasPendingPricing)` after every write:
  - OUT_OF_STOCK when stock is 0;
  - otherwise PRICE_REVIEW_PENDING while a pricing suggestion is pending;
  - otherwise ACTIVE.

  So accepting the last pricing suggestion returns the product to ACTIVE, and accepting a reorder on an out-of-stock product moves it to ACTIVE (or to PRICE_REVIEW_PENDING if pricing is still pending).
- **Manual requests supersede.** A new manual request replaces the previous PENDING MANUAL suggestion of that type, because the merchandiser explicitly asked for a fresh answer.
- **Locking.** `@Version` optimistic locking on products and suggestions turns concurrent edits into 409 `CONCURRENT_MODIFICATION`.

**Tradeoffs.** Superseding takes away the choice between two alternative prices once one is accepted; the alternative (stale-price 409s) is noisier and easy to get wrong. Auto-apply, even for high confidence, is deliberately out of scope (ADR-12).

---

## ADR-8 Demand velocity and spike definition

**Context.** The brief defines velocity as orders in the last 24 h. The seed has static velocities and no order history.

**Decision.**
- `sale_orders` stores every simulated sale. Velocity is `count(orders in trailing 24 h)`, recomputed in the order transaction and cached on `products.demand_velocity`. A scheduled refresh (every 5 min) lets old orders age out. It only refreshes the cache and never fires the loop.
- [`SeedDataLoader`](backend/src/main/java/com/stockpulse/product/SeedDataLoader.java) inserts synthetic orders spread over the last 23 h, so the computed velocity equals the Addendum A value.
- The category average is the mean velocity of the *other* products in the category, floored at 1.0 so sparse categories cannot divide by zero and a single sale cannot count as a spike.
- Spike is `velocity > 3.0 × average` (`stockpulse.trigger.spike-multiplier`). The rule-based pricing threshold is 2.0× (`stockpulse.rule.demand-multiplier`), per the brief.

**Tradeoffs.** Counting orders, not units, follows the brief but under-weights bulk orders. Peer sets of two are noisy. The count query is indexed on `(product_id, created_at)`.

---

## ADR-9 Persistence on SQLite

**Context.** SQLite and DBeaver were available locally; the brief allows H2 or Postgres. The app must run from the README in under 5 minutes with zero setup.

**Options.** H2 in-memory (zero setup, data lost on restart), Postgres (production-like, needs Docker), or a SQLite file (zero setup, persistent, inspectable in DBeaver).

**Decision.** A SQLite file at `backend/data/stockpulse.db` (git-ignored, created on first start), using `sqlite-jdbc` and `hibernate-community-dialects`.
- **Schema.** [`schema.sql`](backend/src/main/resources/schema.sql) owns the schema (`ddl-auto=none`), because CHECK constraints and partial unique indexes are part of the design and JPA annotations cannot express them.
- **Connections.** `journal_mode=WAL`, `busy_timeout=5000` and `foreign_keys=on` are set via the JDBC URL. The Hikari pool has **one connection**: SQLite allows a single writer, and serialising transactions removes SQLITE_BUSY races. This is affordable only because no transaction is held across an LLM call (ADR-6).
- **Types.** Money is `BigDecimal` with 2 dp, normalised by [`MoneyConverter`](backend/src/main/java/com/stockpulse/common/MoneyConverter.java). Instants are stored as epoch millis via [`InstantMillisConverter`](backend/src/main/java/com/stockpulse/common/InstantMillisConverter.java), so SQLite compares them numerically.
- **Tables.** `products`, `sale_orders`, `pricing_suggestions`, `reorder_suggestions`, `price_history`, `app_settings`.

**Tradeoffs.** Single node, single writer, and no migration tool (`CREATE IF NOT EXISTS` only). The production path is Postgres plus Flyway. The JPA mapping is dialect-neutral apart from the partial index, which Postgres also supports.

---

## ADR-10 API contract and validation

Paths follow the brief exactly (no `/api` prefix). Errors are RFC 7807 `ProblemDetail` with a stable `code` ([`GlobalExceptionHandler`](backend/src/main/java/com/stockpulse/api/GlobalExceptionHandler.java)). Framework errors keep their real status (400/404/405/415, not 500) and stack traces never leak.

| Endpoint | Behaviour and constraints |
|---|---|
| `POST /products` | `sku` 3–40 chars `[A-Z0-9-]`, unique (409 `DUPLICATE_SKU`); `name` ≤ 120; `category` enum; `currentPrice` 0.01–999999.99 with 2 dp; `stockLevel` and `reorderThreshold` 0–1,000,000; optional `costPrice`, `marginFloorPct` 0–100, `supplierId`. The id is generated as `PRD-nnn`. Returns 201 with `Location`. |
| `GET /products?status=&category=` | Optional enum filters (an invalid value gives 400 `INVALID_PARAMETER` listing the allowed values). Rows include category average, velocity ratio, spike flag, margin and pending counts. |
| `GET /products/{id}` | 404 if unknown. |
| `PATCH /products/{id}/stock` | `{"stockLevel": 0..1000000}`. Returns immediately; the loop runs after commit. |
| `POST /products/{id}/orders` | Body optional, `{"quantity": 1..1000}` (default 1). More than stock gives 409 `INSUFFICIENT_STOCK`. 201. |
| `POST /products/{id}/suggest-pricing` and `/suggest-reorder` | Synchronous MANUAL request, bounded by the 25 s LLM timeout and then rule fallback. 201. Supersedes the previous pending MANUAL suggestion. |
| `POST /products/{id}/suggest-pricing/stream` | SSE events `status`, `token` (reasoning text), `fallback`, `suggestion` (the persisted PENDING row), `error`. |
| `GET /products/{id}/price-history` | Accepted price changes, for the chart. |
| `GET /pricing-suggestions?status=&productId=&limit=` and `GET /reorder-suggestions?…` | All statuses unless filtered; newest first; limit 1–500. |
| `PATCH /pricing-suggestions/{id}` | `{"status":"ACCEPTED"\|"REJECTED","note":"…"}`. PENDING as input gives 400 `INVALID_DECISION`; deciding an already-decided suggestion gives 409. Returns `{suggestion, product}` written in one transaction. |
| `PATCH /reorder-suggestions/{id}` | Same; accept increments stock. |
| `GET / PUT /settings/strategy` | ADR-3. Includes `llmConfigured` and `llmModel` for the UI. |

CORS allows `http://localhost:5173` and `http://localhost:4200`.

**Why synchronous manual endpoints?** The merchandiser is explicitly waiting for an answer, and the timeout bounds the wait. The streaming endpoint is the better experience for long reasoning. 202-plus-polling was rejected as extra client complexity for a user-initiated call.

---

## ADR-11 Frontend: React 18

**Decision.** React 18 + Vite + TypeScript, TanStack Query (4 s polling, loading and error states, invalidation after every write) and plain CSS. The price chart is a hand-written SVG instead of a charting library. React was chosen over Angular for iteration speed and a smaller footprint.

- **Floor** ([`ReviewQueue`](frontend/src/components/ReviewQueue.tsx), [`CatalogBoard`](frontend/src/components/CatalogBoard.tsx)):
  - Review queue of PRICE_REVIEW_PENDING products and products with pending suggestions.
  - Pricing and reorder cards with tactic, % change, confidence bar, AI reasoning, flag notes and fallback reasons.
  - Trigger badges (*Inventory low · auto*, *Demand spike · auto*, *Initial · auto*, *Manual*) and source badges (*AI*, *Rule-based*, *Rule fallback*).
  - Accept/Reject for both suggestion types.
  - Product table with stock, price, velocity and status.
  - **Sell** (simulate order) and **Set stock** buttons.
  - Live polling indicator, toasts and an API-error banner.
- **Ceiling:**
  - Category and status filters (server-side query params).
  - Stock heatmap (out / below threshold / under 2× threshold / healthy).
  - Margin column (from `costPrice`).
  - Price history chart and a full suggestion log per product ([`HistoryPanel`](frontend/src/components/HistoryPanel.tsx)).
  - New-product form.
  - Strategy switch in the header.
- **SSE bonus.** [`StreamPanel`](frontend/src/components/StreamPanel.tsx) streams the reasoning token by token, then shows the queued suggestion. `EventSource` cannot POST, so [`api.ts`](frontend/src/api.ts) parses the SSE frames from a `fetch` body stream. On the server, [`ReasoningStreamSplitter`](backend/src/main/java/com/stockpulse/ai/ReasoningStreamSplitter.java) forwards prose until a `===JSON===` marker, holding back a marker-length tail so the marker never leaks, and the JSON after it goes through the same validator.

**Tradeoffs.** Polling instead of server push for suggestion arrival is simple, robust and matches the brief. No component library, so the UI is functional rather than polished, which matches the brief's priorities.

---

## ADR-12 Extensibility seams and deliberate exclusions

**Sprint 2 seams that already exist in code.**

| Roadmap item | Where it plugs in |
|---|---|
| `CompetitorAwareStrategy` | `class CompetitorAwareStrategy implements CommerceStrategy` + `@Component`. `StrategyRegistry` discovers it; it appears in `GET /settings/strategy` and the UI dropdown with **no other change**. Contract: [`CommerceStrategy`](backend/src/main/java/com/stockpulse/advisor/CommerceStrategy.java). |
| `costPrice` / `marginFloor` ("AI must not recommend below floor") | Already modelled: nullable [`Product.costPrice`, `marginFloorPct`](backend/src/main/java/com/stockpulse/product/Product.java), validated on `POST /products`, and **enforced today** by [`MarginFloorGuardrail`](backend/src/main/java/com/stockpulse/advisor/MarginFloorGuardrail.java) for every strategy. The prompts already show cost and floor when present. |
| Supplier catalog | `products.supplier_id` and `reorder_suggestions.supplier_id` exist (null today) and flow through `ReorderAdvice.supplierId`. |
| Category-level caps | Another `PriceGuardrail` bean with `@Order`; the chain is applied in `CommerceAdvisor`. |
| Price change cooldown | `SuggestionService.typesWithoutPending`, the single dedupe point for the loop. |
| AI uses a competitor API as a tool | A new `StreamingCommerceStrategy` implementation, or a tool-call loop inside `AiCommerceStrategy`; `LlmClient` is already an interface. |

**Deliberately excluded (priority decisions, not time excuses).**
- **Auto-apply of prices and automatic POs.** They would bypass the human checkpoint, which is the core safety property, before margin and category guardrails have production data behind them.
- **Authentication and users.** There is a single merchandising persona; the checkpoint is the accept action, not identity. Adding Spring Security later would not touch the domain.
- **Outbox or broker durability, retries, circuit breaker.** For one node, in-process events plus rule fallback bound the failure impact; the outbox is the first upgrade when scaling out.
- **Migrations (Flyway) and Postgres.** These are not needed for a single-file demo database and come with the production move.
- **Product edit/delete, storefront, cart, competitor scraping.** Out of scope per the brief.

---

## Walkthrough: order → low stock → suggestions → accept price

1. `POST /products/PRD-003/orders`, handled by [`ProductController.placeOrder`](backend/src/main/java/com/stockpulse/api/ProductController.java) and then [`ProductService.recordOrder`](backend/src/main/java/com/stockpulse/product/ProductService.java). In one transaction it sells (stock 8 → 7), inserts a `sale_order`, recomputes velocity (12 → 13) and publishes `InventoryChangedEvent(ORDER_PLACED)`. It returns **201 immediately**.
2. After commit, `RecommendationTriggerHandler` runs on an `agent-*` thread. `TriggerEvaluator` sees 7 < 15 and fires `INVENTORY_LOW`.
3. `RecommendationService.generateForTrigger` checks that no PENDING INVENTORY_LOW pair exists, loads the `ProductContext`, and calls `CommerceAdvisor` → `AiCommerceStrategy` with the `inventory-low` prompt and one LLM call. The output is validated per half and passed through the guardrails.
4. `SuggestionService.save` persists a `PricingSuggestion` and a `ReorderSuggestion` (`triggerReason=INVENTORY_LOW`, `generatedBy=AI`), and the product becomes `PRICE_REVIEW_PENDING`.
5. The console's next poll shows both cards with the **Inventory low · auto** badge and the AI reasoning.
6. Accept the price: `PATCH /pricing-suggestions/{id} {"status":"ACCEPTED"}` → `SuggestionService.decidePricing`. In one transaction it updates `Product.currentPrice` (24.99 → 27.49), writes `price_history`, supersedes the INITIAL suggestion and derives ACTIVE.
7. Optionally accept the reorder: stock increases by the recommended quantity.

The same path runs as an automated test: [`AgenticLoopIntegrationTest`](backend/src/test/java/com/stockpulse/AgenticLoopIntegrationTest.java).
