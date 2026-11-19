package com.capstone.bookstore.address;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, Long> {

    /** Ownership is part of every lookup: another user's (or a deleted) address simply does not exist. */
    Optional<Address> findByIdAndUserIdAndDeletedFalse(Long id, Long userId);

    List<Address> findByUserIdAndDeletedFalseOrderByDefaultAddressDescIdAsc(Long userId);

    List<Address> findByUserIdAndDeletedFalseAndDefaultAddressTrue(Long userId);

    boolean existsByUserIdAndDeletedFalse(Long userId);
}
