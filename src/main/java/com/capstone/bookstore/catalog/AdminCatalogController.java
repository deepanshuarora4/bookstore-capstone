package com.capstone.bookstore.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

/**
 * ADMIN-only catalogue management. Enforced twice: {@code /admin/**} requires ROLE_ADMIN in SecurityConfig, and
 * {@code @PreAuthorize} here protects the methods even if the URL rules were ever changed.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCatalogController {

    private final CategoryService categoryService;
    private final BrandService brandService;
    private final ProductService productService;

    public AdminCatalogController(CategoryService categoryService, BrandService brandService,
                                  ProductService productService) {
        this.categoryService = categoryService;
        this.brandService = brandService;
        this.productService = productService;
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(@Valid @RequestBody CategoryRequest request) {
        return categoryService.create(request);
    }

    @PostMapping("/brands")
    @ResponseStatus(HttpStatus.CREATED)
    public BrandResponse createBrand(@Valid @RequestBody BrandRequest request) {
        return brandService.create(request);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(@Valid @RequestBody ProductRequest request) {
        return productService.create(request);
    }

    @PutMapping("/products/{productId}")
    public ProductResponse updateProduct(@PathVariable @Min(value = 1, message = "must be at least 1") Long productId,
                                         @Valid @RequestBody ProductRequest request) {
        return productService.update(productId, request);
    }
}
