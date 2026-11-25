package com.stockpulse.product;

import com.stockpulse.common.InstantMillisConverter;
import com.stockpulse.common.MoneyConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "sale_orders")
public class SaleOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String productId;

    @Column(nullable = false)
    private int quantity;

    @Convert(converter = MoneyConverter.class)
    @Column(nullable = false)
    private BigDecimal unitPrice;

    @Convert(converter = InstantMillisConverter.class)
    @Column(nullable = false)
    private Instant createdAt;

    protected SaleOrder() {
    }

    public SaleOrder(String productId, int quantity, BigDecimal unitPrice, Instant createdAt) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public Instant getCreatedAt() { return createdAt; }
}
