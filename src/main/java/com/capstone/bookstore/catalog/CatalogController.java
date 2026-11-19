package com.capstone.bookstore.catalog;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.bookstore.common.PageResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Public catalogue endpoints (security: [] in openapi.yaml): categories, products, related products, brands.
 * Parameter limits mirror the Page / Size / Limit parameters in the spec; violations are 400 VALIDATION_FAILED.
 */
@RestController
public class CatalogController {

    private final CategoryService categoryService;
    private final BrandService brandService;
    private final ProductService productService;

    public CatalogController(CategoryService categoryService, BrandService brandService, ProductService productService) {
        this.categoryService = categoryService;
        this.brandService = brandService;
        this.productService = productService;
    }

    @GetMapping("/categories")
    public List<CategoryResponse> listCategories() {
        return categoryService.list();
    }

    @GetMapping("/products")
    public PageResponse<ProductResponse> listProducts(
            @RequestParam(required = false) @Min(value = 1, message = "must be at least 1") Long categoryId,
            @RequestParam(required = false) @Min(value = 1, message = "must be at least 1") Long brandId,
            @RequestParam(required = false) @Size(min = 1, max = 100, message = "must be between 1 and 100 characters") String q,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "must not be negative") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "must be between 1 and 100")
                    @Max(value = 100, message = "must be between 1 and 100") int size,
            @RequestParam(defaultValue = "title_asc") String sort) {
        return productService.search(categoryId, brandId, q, page, size, sort);
    }

    @GetMapping("/products/{productId}")
    public ProductResponse getProduct(@PathVariable @Min(value = 1, message = "must be at least 1") Long productId) {
        return productService.get(productId);
    }

    @GetMapping("/products/{productId}/related")
    public List<ProductResponse> getRelatedProducts(
            @PathVariable @Min(value = 1, message = "must be at least 1") Long productId,
            @RequestParam(defaultValue = "8") @Min(value = 1, message = "must be between 1 and 50")
                    @Max(value = 50, message = "must be between 1 and 50") int limit) {
        return productService.related(productId, limit);
    }

    @GetMapping("/brands")
    public List<BrandResponse> listBrands() {
        return brandService.list();
    }

    @GetMapping("/brands/{brandId}/products")
    public PageResponse<ProductResponse> listProductsByBrand(
            @PathVariable @Min(value = 1, message = "must be at least 1") Long brandId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "must not be negative") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "must be between 1 and 100")
                    @Max(value = 100, message = "must be between 1 and 100") int size) {
        return productService.listByBrand(brandId, page, size);
    }
}
