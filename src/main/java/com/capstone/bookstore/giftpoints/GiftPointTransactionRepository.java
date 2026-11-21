package com.capstone.bookstore.giftpoints;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GiftPointTransactionRepository extends JpaRepository<GiftPointTransaction, Long> {

    boolean existsByOrderIdAndType(Long orderId, GiftPointTransactionType type);

    /** The ledger of one user, newest first. */
    Page<GiftPointTransaction> findByAccountUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);
}
