package com.stockpulse.suggestion;

import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PricingSuggestionRepository extends JpaRepository<PricingSuggestion, Long> {

    boolean existsByProduct_IdAndTriggerReasonAndStatus(String productId, TriggerReason trigger, SuggestionStatus status);

    boolean existsByProduct_IdAndStatus(String productId, SuggestionStatus status);

    List<PricingSuggestion> findByProduct_IdAndStatus(String productId, SuggestionStatus status);

    List<PricingSuggestion> findByProduct_IdAndTriggerReasonAndStatus(String productId, TriggerReason trigger,
                                                                       SuggestionStatus status);

    @Query("""
            select s from PricingSuggestion s join fetch s.product p
            where (:status is null or s.status = :status)
              and (:productId is null or p.id = :productId)
            order by s.id desc""")
    List<PricingSuggestion> search(@Param("status") SuggestionStatus status,
                                   @Param("productId") String productId,
                                   Pageable page);

    @Query("select s.product.id, count(s) from PricingSuggestion s where s.status = :status group by s.product.id")
    List<Object[]> countByProductWithStatus(@Param("status") SuggestionStatus status);
}
