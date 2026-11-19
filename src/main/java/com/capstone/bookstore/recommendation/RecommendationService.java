package com.capstone.bookstore.recommendation;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.bookstore.catalog.Product;
import com.capstone.bookstore.catalog.ProductMapper;
import com.capstone.bookstore.catalog.ProductRepository;
import com.capstone.bookstore.order.OrderItemRepository;

/**
 * GET /recommendations (IBM step 2b). Rule-based, from existing data only: active, in-stock products in the
 * categories and brands of the user's purchased books (non-cancelled orders) that the user has not bought yet,
 * best match (category and brand) first, then newest. Without purchases: the newest available products.
 */
@Service
public class RecommendationService {

    private final OrderItemRepository orderItems;
    private final ProductRepository products;
    private final ProductMapper productMapper;

    public RecommendationService(OrderItemRepository orderItems, ProductRepository products,
                                 ProductMapper productMapper) {
        this.orderItems = orderItems;
        this.products = products;
        this.productMapper = productMapper;
    }

    @Transactional(readOnly = true)
    public List<RecommendedProductResponse> recommend(Long userId, int limit) {
        List<Product> purchased = orderItems.findPurchasedProducts(userId);
        PageRequest page = PageRequest.of(0, limit);
        if (purchased.isEmpty()) {
            return products.findNewestAvailable(page).stream()
                    .map(p -> new RecommendedProductResponse(productMapper.toResponse(p), "New arrival"))
                    .toList();
        }
        Set<Long> productIds = purchased.stream().map(Product::getId).collect(Collectors.toSet());
        Set<Long> categoryIds = purchased.stream().map(p -> p.getCategory().getId()).collect(Collectors.toSet());
        Set<Long> brandIds = purchased.stream().map(p -> p.getBrand().getId()).collect(Collectors.toSet());
        return products.findRecommended(productIds, categoryIds, brandIds, page).stream()
                .map(p -> new RecommendedProductResponse(productMapper.toResponse(p),
                        reason(p, categoryIds, brandIds)))
                .toList();
    }

    private static String reason(Product p, Set<Long> categoryIds, Set<Long> brandIds) {
        String category = p.getCategory().getName();
        String brand = p.getBrand().getName();
        boolean sameCategory = categoryIds.contains(p.getCategory().getId());
        boolean sameBrand = brandIds.contains(p.getBrand().getId());
        if (sameCategory && sameBrand) {
            return "Because you bought books in " + category + " from " + brand;
        }
        return sameCategory ? "Because you bought books in " + category : "Because you bought books from " + brand;
    }
}
