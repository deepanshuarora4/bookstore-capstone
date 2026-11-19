package com.capstone.bookstore.payment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** The most recent attempt of an order. */
    Optional<Payment> findFirstByOrderIdOrderByCreatedAtDescIdDesc(Long orderId);

    List<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatus status);
}
