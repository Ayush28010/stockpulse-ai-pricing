package com.stockpulse.domain;

public enum TriggerReason {
    /** Product entered the catalog (seed or POST /products) already low or spiking. */
    INITIAL,
    /** Stock dropped below reorder threshold after an order, stock update or inbound shipment. */
    INVENTORY_LOW,
    /** An order pushed demand velocity above spike-multiplier x category average. */
    DEMAND_SPIKE,
    /** Merchandiser asked for a suggestion explicitly. */
    MANUAL
}
