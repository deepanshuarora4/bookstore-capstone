package com.capstone.bookstore.giftpoints;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GiftPointAccountRepository extends JpaRepository<GiftPointAccount, Long> {

    Optional<GiftPointAccount> findByUserId(Long userId);
}
