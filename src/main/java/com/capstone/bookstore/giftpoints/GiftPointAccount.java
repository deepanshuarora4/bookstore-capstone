package com.capstone.bookstore.giftpoints;

import com.capstone.bookstore.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A user's gift point balance. The balance can never be negative: {@link #debit(int)} refuses to overdraw,
 * the database has {@code CHECK (balance >= 0)}, and {@code @Version} rejects concurrent lost updates.
 */
@Entity
@Table(name = "gift_point_accounts")
public class GiftPointAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false, unique = true)
    private User user;

    @Column(nullable = false)
    private int balance;

    @Version
    private long version;

    protected GiftPointAccount() {
    }

    public GiftPointAccount(User user) {
        this.user = user;
    }

    public void credit(int points) {
        if (points <= 0) {
            throw new IllegalArgumentException("points to credit must be positive");
        }
        balance = Math.addExact(balance, points);
    }

    public void debit(int points) {
        if (points <= 0) {
            throw new IllegalArgumentException("points to debit must be positive");
        }
        if (points > balance) {
            throw new IllegalStateException("insufficient gift points");
        }
        balance -= points;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public int getBalance() { return balance; }
    public long getVersion() { return version; }
}
