package com.capstone.bookstore.catalog;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.capstone.bookstore.config.BookstoreProperties;

/** Entity to API model. Computes the tentative delivery date: today + dispatch days + configured transit days. */
@Component
public class ProductMapper {

    private final Clock clock;
    private final int transitDays;

    public ProductMapper(Clock clock, BookstoreProperties properties) {
        this.clock = clock;
        this.transitDays = properties.delivery().transitDays();
    }

    /** Tentative delivery date of a product: today + dispatch days + configured transit days. */
    public LocalDate estimatedDelivery(Product product) {
        return LocalDate.now(clock).plusDays((long) product.getDispatchDays() + transitDays);
    }

    /** Category and brand must be loaded (fetch join) or the call must run inside a transaction. */
    public ProductResponse toResponse(Product product) {
        LocalDate delivery = estimatedDelivery(product);
        return new ProductResponse(
                product.getId(),
                product.getTitle(),
                product.getAuthor(),
                product.getIsbn(),
                product.getDescription(),
                product.getPrice(),
                product.getImageUrl(),
                CategoryResponse.from(product.getCategory()),
                BrandResponse.from(product.getBrand()),
                product.getStockQty(),
                product.getStockQty() > 0,
                delivery,
                product.getVersion());
    }
}
