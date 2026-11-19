package com.capstone.bookstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.capstone.bookstore.config.BookstoreProperties;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Skeleton guard: starts the application on H2 (tests only), applies every Flyway migration, and lets
 * Hibernate validate (ddl-auto=validate) that all entities match the migrated schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaAndContextTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BookstoreProperties properties;

    @PersistenceContext
    private EntityManager em;

    @Test
    void contextLoadsAndAllMigrationsApply() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL",
                Integer.class);
        assertThat(applied).isEqualTo(5);
    }

    @Test
    void configuredBusinessRulesMatchOpenApiDefaults() {
        assertThat(properties.points().earnPointsPerUnit()).isEqualTo(1);
        assertThat(properties.points().earnSpendUnit()).isEqualByComparingTo("10.00");
        assertThat(properties.points().pointValue()).isEqualByComparingTo("1.00");
        assertThat(properties.points().maxRedemptionPercent()).isEqualTo(50);
        assertThat(properties.order().cancellationWindowHours()).isEqualTo(48);
        assertThat(properties.delivery().transitDays()).isEqualTo(3);
        assertThat(properties.cart().maxQuantityPerItem()).isEqualTo(10);
        assertThat(properties.payment().declineCardSuffix()).isEqualTo("0002");
        assertThat(properties.security().jwt().expirySeconds()).isEqualTo(3600);
    }

    @Test
    void paymentsTableStoresOnlyLastFourDigitsOfTheCard() {
        var columns = jdbc.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_name) = 'payments'",
                String.class);
        assertThat(columns).contains("card_last4");
        assertThat(columns).noneMatch(c -> c.contains("cvv") || c.equals("card_number") || c.equals("pan"));
    }

    @Test
    void databaseRejectsNegativeStockAndNegativeGiftPointBalance() {
        jdbc.update("INSERT INTO users (full_name, email, password_hash, role, created_at) VALUES (?,?,?,?,?)",
                "Test User", "constraints@example.com", "$2a$10$hash", "CUSTOMER", java.sql.Timestamp.from(Instant.now()));
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'constraints@example.com'", Long.class);

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                jdbc.update("INSERT INTO gift_point_accounts (user_id, balance) VALUES (?, ?)", userId, -1));

        jdbc.update("INSERT INTO categories (name) VALUES ('Constraint Cat')");
        jdbc.update("INSERT INTO brands (name) VALUES ('Constraint Brand')");
        Long catId = jdbc.queryForObject("SELECT id FROM categories WHERE name = 'Constraint Cat'", Long.class);
        Long brandId = jdbc.queryForObject("SELECT id FROM brands WHERE name = 'Constraint Brand'", Long.class);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                jdbc.update("INSERT INTO products (title, author, isbn, price, stock_qty, dispatch_days, active,"
                                + " category_id, brand_id, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        "Book", "Author", "1234567890", new BigDecimal("199.00"), -1, 0, true,
                        catId, brandId, java.sql.Timestamp.from(Instant.now())));
    }

    @Test
    void entitiesAreRegisteredWithHibernate() {
        var names = em.getMetamodel().getEntities().stream().map(e -> e.getName()).toList();
        assertThat(names).containsExactlyInAnyOrder(
                "User", "Address", "Category", "Brand", "Product", "Cart", "CartItem",
                "Order", "OrderItem", "Payment", "GiftPointAccount", "GiftPointTransaction");
        assertThat(LocalDate.now()).isNotNull();
    }
}
