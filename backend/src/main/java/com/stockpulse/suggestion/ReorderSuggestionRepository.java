package com.stockpulse.suggestion;

import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReorderSuggestionRepository extends JpaRepository<ReorderSuggestion, Long> {

    boolean existsByProduct_IdAndTriggerReasonAndStatus(String productId, TriggerReason trigger, SuggestionStatus status);

    List<ReorderSuggestion> findByProduct_IdAndStatus(String productId, SuggestionStatus status);

    List<ReorderSuggestion> findByProduct_IdAndTriggerReasonAndStatus(String productId, TriggerReason trigger,
                                                                       SuggestionStatus status);

    @Query("""
            select s from ReorderSuggestion s join fetch s.product p
            where (:status is null or s.status = :status)
              and (:productId is null or p.id = :productId)
            order by s.id desc""")
    List<ReorderSuggestion> search(@Param("status") SuggestionStatus status,
                                   @Param("productId") String productId,
                                   Pageable page);

    @Query("select s.product.id, count(s) from ReorderSuggestion s where s.status = :status group by s.product.id")
    List<Object[]> countByProductWithStatus(@Param("status") SuggestionStatus status);
}
