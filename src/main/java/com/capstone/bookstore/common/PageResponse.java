package com.capstone.bookstore.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** PageMetadata + content, as in {@code ProductPage} in openapi.yaml. {@code page} is zero-based. */
public record PageResponse<T>(int page, int size, long totalElements, int totalPages, List<T> content) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                page.getContent().stream().map(mapper).toList());
    }
}
