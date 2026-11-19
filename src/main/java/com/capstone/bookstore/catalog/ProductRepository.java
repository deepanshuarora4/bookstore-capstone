package com.capstone.bookstore.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    boolean existsByIsbn(String isbn);

    boolean existsByIsbnAndIdNot(String isbn, Long id);

    /** One query with category and brand, so building a response never triggers a lazy load per product. */
    @EntityGraph(attributePaths = {"category", "brand"})
    @Override
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "brand"})
    Optional<Product> findByIdAndActiveTrue(Long id);

    /**
     * Atomic stock decrement for checkout: succeeds (returns 1) only while enough stock is left, so two concurrent
     * checkouts can never oversell. The version is bumped so a stale admin edit of the product is refused.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stockQty = p.stockQty - :qty, p.version = p.version + 1 "
            + "where p.id = :id and p.stockQty >= :qty")
    int decrementStock(@Param("id") Long id, @Param("qty") int qty);

    /** Gives stock back when an order is cancelled. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stockQty = p.stockQty + :qty, p.version = p.version + 1 where p.id = :id")
    int incrementStock(@Param("id") Long id, @Param("qty") int qty);

    /** Products with category and brand in one query (used to build responses for a list of ids). */
    @EntityGraph(attributePaths = {"category", "brand"})
    List<Product> findWithCategoryAndBrandByIdIn(Collection<Long> ids);

    /**
     * Active, in-stock products sharing the category or the brand of the given product, excluding the product
     * itself. Products matching both come first. The limit is applied by the database through the Pageable.
     */
    @Query("""
            select p from Product p
              join fetch p.category c
              join fetch p.brand b
            where p.active = true
              and p.stockQty > 0
              and p.id <> :productId
              and (c.id = :categoryId or b.id = :brandId)
            order by case when c.id = :categoryId and b.id = :brandId then 0 else 1 end, p.title, p.id
            """)
    List<Product> findRelated(@Param("productId") Long productId, @Param("categoryId") Long categoryId,
                              @Param("brandId") Long brandId, Pageable limit);

    /**
     * Recommendations: active, in-stock products in one of the given categories or brands that are not in
     * {@code excludedIds} (already bought). Matching both category and brand ranks first, then newest.
     * {@code excludedIds}, {@code categoryIds} and {@code brandIds} must not be empty.
     */
    @Query("""
            select p from Product p
              join fetch p.category c
              join fetch p.brand b
            where p.active = true
              and p.stockQty > 0
              and p.id not in :excludedIds
              and (c.id in :categoryIds or b.id in :brandIds)
            order by (case when c.id in :categoryIds then 1 else 0 end)
                   + (case when b.id in :brandIds then 1 else 0 end) desc,
                     p.createdAt desc, p.id desc
            """)
    List<Product> findRecommended(@Param("excludedIds") Collection<Long> excludedIds,
                                  @Param("categoryIds") Collection<Long> categoryIds,
                                  @Param("brandIds") Collection<Long> brandIds, Pageable limit);

    /** Recommendations for a user without purchases: the newest active, in-stock products. */
    @Query("""
            select p from Product p
              join fetch p.category
              join fetch p.brand
            where p.active = true and p.stockQty > 0
            order by p.createdAt desc, p.id desc
            """)
    List<Product> findNewestAvailable(Pageable limit);
}
