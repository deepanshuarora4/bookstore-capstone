package com.capstone.bookstore.giftpoints;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.bookstore.common.ApiException;
import com.capstone.bookstore.common.ErrorCode;
import com.capstone.bookstore.common.PageResponse;
import com.capstone.bookstore.config.BookstoreProperties;
import com.capstone.bookstore.order.Order;
import com.capstone.bookstore.user.UserRepository;

/**
 * Gift point rules (IBM step 10a). Every balance change is written to the ledger, and the unique
 * (order, type) constraint allows at most one EARN / REDEEM / REFUND / REVERSE entry per order.
 * All methods that change points join the caller's transaction, so they commit or roll back with the order.
 */
@Service
public class GiftPointService {

    private final GiftPointAccountRepository accounts;
    private final GiftPointTransactionRepository transactions;
    private final UserRepository users;
    private final BookstoreProperties.Points rules;

    public GiftPointService(GiftPointAccountRepository accounts, GiftPointTransactionRepository transactions,
                            UserRepository users, BookstoreProperties properties) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.users = users;
        this.rules = properties.points();
    }

    /** Current balance of the user; 0 when the user has no account yet. */
    @Transactional(readOnly = true)
    public int balanceOf(Long userId) {
        return accounts.findByUserId(userId).map(GiftPointAccount::getBalance).orElse(0);
    }

    /** The percentage cap alone: floor(subtotal * max-redemption-percent / 100 / point-value). */
    public int redemptionCap(BigDecimal subtotal) {
        BigDecimal byPercent = subtotal.multiply(BigDecimal.valueOf(rules.maxRedemptionPercent()))
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.DOWN)
                .divide(rules.pointValue(), 0, RoundingMode.DOWN);
        return byPercent.min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
    }

    /** Most points usable for a subtotal: min(balance, redemption cap). */
    public int maxRedeemable(BigDecimal subtotal, int balance) {
        return Math.min(balance, redemptionCap(subtotal));
    }

    /** Money value of a number of points. */
    public BigDecimal discountFor(int points) {
        return rules.pointValue().multiply(BigDecimal.valueOf(points)).setScale(2, RoundingMode.HALF_UP);
    }

    /** Points earned by paying the given amount: earn-points-per-unit for every full spend unit. */
    public int pointsEarnedFor(BigDecimal amount) {
        BigDecimal units = amount.divideToIntegralValue(rules.earnSpendUnit());
        return units.multiply(BigDecimal.valueOf(rules.earnPointsPerUnit())).intValue();
    }

    /**
     * Debits points for an order. The caller has already checked the balance and the percentage limit;
     * the account row version still makes two concurrent redemptions fail instead of overdrawing.
     */
    @Transactional
    public void redeem(Long userId, Order order, int points) {
        if (points <= 0) {
            return;
        }
        GiftPointAccount account = accountOf(userId);
        if (points > account.getBalance()) {
            throw ApiException.badRequest(ErrorCode.INSUFFICIENT_GIFT_POINTS,
                    "Not enough gift points. Available: " + account.getBalance());
        }
        account.debit(points);
        accounts.saveAndFlush(account);
        transactions.save(new GiftPointTransaction(account, order, GiftPointTransactionType.REDEEM, -points));
    }

    /** Credits the points earned by a successful payment, once per order. Returns the points credited. */
    @Transactional
    public int earn(Long userId, Order order, BigDecimal paidAmount) {
        int points = pointsEarnedFor(paidAmount);
        if (points <= 0 || transactions.existsByOrderIdAndType(order.getId(), GiftPointTransactionType.EARN)) {
            return 0;
        }
        GiftPointAccount account = accountOf(userId);
        account.credit(points);
        accounts.saveAndFlush(account);
        transactions.save(new GiftPointTransaction(account, order, GiftPointTransactionType.EARN, points));
        return points;
    }

    /** Cancellation: returns the redeemed points (REFUND) and takes back the earned points (REVERSE), each once. */
    @Transactional
    public void settleCancellation(Long userId, Order order) {
        GiftPointAccount account = accountOf(userId);
        int redeemed = order.getPointsRedeemed();
        if (redeemed > 0 && !transactions.existsByOrderIdAndType(order.getId(), GiftPointTransactionType.REFUND)) {
            account.credit(redeemed);
            transactions.save(new GiftPointTransaction(account, order, GiftPointTransactionType.REFUND, redeemed));
        }
        int earned = order.getPointsEarned();
        if (earned > 0 && !transactions.existsByOrderIdAndType(order.getId(), GiftPointTransactionType.REVERSE)) {
            // Capped at the balance: the customer may already have spent the points, and the balance never goes negative.
            int reversal = Math.min(earned, account.getBalance());
            if (reversal > 0) {
                account.debit(reversal);
                transactions.save(new GiftPointTransaction(account, order, GiftPointTransactionType.REVERSE, -reversal));
            }
        }
        accounts.saveAndFlush(account);
    }

    @Transactional(readOnly = true)
    public GiftPointsResponse overview(Long userId, int page, int size) {
        int balance = balanceOf(userId);
        var rulesResponse = new GiftPointsResponse.Rules(rules.earnPointsPerUnit(), rules.earnSpendUnit(),
                rules.pointValue(), rules.maxRedemptionPercent());
        var ledger = transactions.findByAccountUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size));
        return new GiftPointsResponse(balance, rulesResponse, PageResponse.of(ledger, GiftPointsResponse.Entry::from));
    }

    /** Registration creates the account; users created any other way get an empty one on first use. */
    private GiftPointAccount accountOf(Long userId) {
        return accounts.findByUserId(userId)
                .orElseGet(() -> accounts.saveAndFlush(new GiftPointAccount(users.getReferenceById(userId))));
    }
}
