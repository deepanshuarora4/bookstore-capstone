package com.capstone.bookstore.catalog;

import java.util.Arrays;
import java.util.Optional;

import org.springframework.data.domain.Sort;

/** The {@code sort} values of GET /products in openapi.yaml. The id tie-breaker keeps paging stable. */
public enum ProductSort {
    TITLE_ASC("title_asc", Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id"))),
    TITLE_DESC("title_desc", Sort.by(Sort.Order.desc("title"), Sort.Order.asc("id"))),
    PRICE_ASC("price_asc", Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id"))),
    PRICE_DESC("price_desc", Sort.by(Sort.Order.desc("price"), Sort.Order.asc("id"))),
    NEWEST("newest", Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

    private final String value;
    private final Sort sort;

    ProductSort(String value, Sort sort) {
        this.value = value;
        this.sort = sort;
    }

    public Sort sort() {
        return sort;
    }

    public static Optional<ProductSort> parse(String value) {
        return Arrays.stream(values()).filter(s -> s.value.equals(value)).findFirst();
    }
}
