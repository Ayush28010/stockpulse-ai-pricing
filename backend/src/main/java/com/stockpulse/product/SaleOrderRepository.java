package com.stockpulse.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface SaleOrderRepository extends JpaRepository<SaleOrder, Long> {

    long countByProductIdAndCreatedAtAfter(String productId, Instant since);
}
