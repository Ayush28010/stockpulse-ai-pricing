package com.stockpulse.product;

import com.stockpulse.domain.Category;
import com.stockpulse.domain.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, String> {

    boolean existsBySku(String sku);

    @Query("""
            select p from Product p
            where (:status is null or p.status = :status)
              and (:category is null or p.category = :category)
            order by p.id""")
    List<Product> search(@Param("status") ProductStatus status, @Param("category") Category category);

    @Query("select avg(p.demandVelocity) from Product p where p.category = :category and p.id <> :excludeId")
    Double averageVelocityOfPeers(@Param("category") Category category, @Param("excludeId") String excludeId);

    long countByCategoryAndIdNot(Category category, String id);

    @Query("select p.id from Product p")
    List<String> findAllIds();
}
