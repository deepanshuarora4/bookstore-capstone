package com.capstone.bookstore.catalog;

import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

/** Dynamic filters for the product list. Everything is evaluated by the database. */
final class ProductSpecifications {

    private ProductSpecifications() {
    }

    static Specification<Product> active() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    static Specification<Product> inCategory(Long categoryId) {
        return (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    static Specification<Product> ofBrand(Long brandId) {
        return (root, query, cb) -> cb.equal(root.get("brand").get("id"), brandId);
    }

    /** Case-insensitive "contains" on title, author or ISBN. LIKE wildcards in the input are matched literally. */
    static Specification<Product> matches(String text) {
        String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, '\\'),
                cb.like(cb.lower(root.get("author")), pattern, '\\'),
                cb.like(cb.lower(root.get("isbn")), pattern, '\\'));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
