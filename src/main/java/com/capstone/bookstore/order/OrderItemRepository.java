package com.capstone.bookstore.order;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** Lines of several orders in one query (used to build the order history page without one query per order). */
    List<OrderItem> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);

    /**
     * Buy Again: per product {@code [productId, number of orders, last ordered at]} over the user's non-cancelled
     * orders, restricted to products that are still active and in stock, most recently ordered first.
     */
    @Query("""
            select p.id, count(oi.id), max(o.placedAt)
            from OrderItem oi join oi.order o join oi.product p
            where o.user.id = :userId and o.status <> com.capstone.bookstore.order.OrderStatus.CANCELLED
              and p.active = true and p.stockQty > 0
            group by p.id
            order by max(o.placedAt) desc, p.id
            """)
    List<Object[]> findBuyAgain(@Param("userId") Long userId, Pageable limit);

    /**
     * Recommendations: the distinct products (with category and brand) of the user's non-cancelled orders,
     * whatever their current stock or active state. Same notion of "purchased" as Buy Again.
     */
    @Query("""
            select distinct p
            from OrderItem oi join oi.order o join oi.product p join fetch p.category join fetch p.brand
            where o.user.id = :userId and o.status <> com.capstone.bookstore.order.OrderStatus.CANCELLED
            """)
    List<com.capstone.bookstore.catalog.Product> findPurchasedProducts(@Param("userId") Long userId);
}
