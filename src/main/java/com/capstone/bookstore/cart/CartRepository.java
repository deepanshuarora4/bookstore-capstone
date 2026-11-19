package com.capstone.bookstore.cart;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByUserId(Long userId);

    /** The basket with its lines, products, categories and brands in one query (no lazy loading per line). */
    @Query("""
            select distinct c from Cart c
              left join fetch c.items i
              left join fetch i.product p
              left join fetch p.category
              left join fetch p.brand
            where c.user.id = :userId
            """)
    Optional<Cart> findDetailedByUserId(@Param("userId") Long userId);
}
