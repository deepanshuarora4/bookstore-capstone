package com.capstone.bookstore.catalog;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.bookstore.common.ApiException;
import com.capstone.bookstore.common.PageResponse;

/**
 * Product catalogue (IBM steps 3, 6, 7, 7a): public browsing, search, details and related products,
 * plus ADMIN create / update. All filtering, sorting, paging and the related-products ranking are done by the
 * database; the catalogue is never loaded into memory.
 */
@Service
public class ProductService {

    private static final String SORT_VALUES = "title_asc, title_desc, price_asc, price_desc, newest";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final ProductMapper mapper;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository,
                          BrandRepository brandRepository, ProductMapper mapper) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ public

    /** GET /products: active products, optional category / brand / text filters. */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> search(Long categoryId, Long brandId, String q, int page, int size, String sort) {
        ProductSort order = ProductSort.parse(sort == null ? "title_asc" : sort)
                .orElseThrow(() -> ApiException.validation("sort", "must be one of: " + SORT_VALUES));

        Specification<Product> filter = ProductSpecifications.active();
        if (categoryId != null) {
            filter = filter.and(ProductSpecifications.inCategory(categoryId));
        }
        if (brandId != null) {
            filter = filter.and(ProductSpecifications.ofBrand(brandId));
        }
        if (q != null) {
            String text = q.trim();
            if (text.isEmpty()) {
                throw ApiException.validation("q", "must not be blank");
            }
            filter = filter.and(ProductSpecifications.matches(text));
        }
        Page<Product> result = productRepository.findAll(filter, PageRequest.of(page, size, order.sort()));
        return PageResponse.of(result, mapper::toResponse);
    }

    /** GET /brands/{brandId}/products: 404 when the brand does not exist. */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> listByBrand(Long brandId, int page, int size) {
        if (!brandRepository.existsById(brandId)) {
            throw ApiException.notFound("Brand " + brandId + " not found");
        }
        return search(null, brandId, null, page, size, null);
    }

    /** GET /products/{id}: inactive products are not visible to customers. */
    @Transactional(readOnly = true)
    public ProductResponse get(Long productId) {
        return mapper.toResponse(productRepository.findByIdAndActiveTrue(productId)
                .orElseThrow(() -> productNotFound(productId)));
    }

    /** GET /products/{id}/related: same category or same brand, active and in stock, best match first. */
    @Transactional(readOnly = true)
    public List<ProductResponse> related(Long productId, int limit) {
        Product product = productRepository.findByIdAndActiveTrue(productId)
                .orElseThrow(() -> productNotFound(productId));
        return productRepository.findRelated(productId, product.getCategory().getId(), product.getBrand().getId(),
                        PageRequest.of(0, limit))
                .stream().map(mapper::toResponse).toList();
    }

    // ------------------------------------------------------------------- admin

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Category category = findCategory(request.categoryId());
        Brand brand = findBrand(request.brandId());
        if (productRepository.existsByIsbn(request.isbn())) {
            throw duplicateIsbn();
        }
        Product product = new Product(request.title(), request.author(), request.isbn(), request.description(),
                request.price(), request.imageUrl(), request.stockQty(), request.dispatchDays(),
                request.activeOrDefault(), category, brand);
        return mapper.toResponse(save(product));
    }

    /**
     * Full update (PUT). Optimistic locking, two layers:
     * <ol>
     *   <li>if the client sends the {@code version} it read and it is no longer current, the update is refused;</li>
     *   <li>the JPA {@code @Version} check at flush time refuses the update if the row changed after it was loaded
     *       here (for example a stock change from an order), so no concurrent change is silently overwritten.</li>
     * </ol>
     * Both end as 409 CONCURRENT_MODIFICATION.
     */
    @Transactional
    public ProductResponse update(Long productId, ProductRequest request) {
        Product product = productRepository.findById(productId).orElseThrow(() -> productNotFound(productId));
        if (request.version() != null && request.version() != product.getVersion()) {
            throw ApiException.concurrentModification();
        }
        Category category = findCategory(request.categoryId());
        Brand brand = findBrand(request.brandId());
        if (productRepository.existsByIsbnAndIdNot(request.isbn(), productId)) {
            throw duplicateIsbn();
        }
        product.setTitle(request.title());
        product.setAuthor(request.author());
        product.setIsbn(request.isbn());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setImageUrl(request.imageUrl());
        product.setStockQty(request.stockQty());
        product.setDispatchDays(request.dispatchDays());
        product.setActive(request.activeOrDefault());
        product.setCategory(category);
        product.setBrand(brand);
        return mapper.toResponse(save(product));
    }

    // ----------------------------------------------------------------- helpers

    private Product save(Product product) {
        try {
            return productRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent insert of the same ISBN: the unique constraint is the final guard.
            throw duplicateIsbn();
        }
    }

    private Category findCategory(Long id) {
        return categoryRepository.findById(id).orElseThrow(() -> ApiException.notFound("Category " + id + " not found"));
    }

    private Brand findBrand(Long id) {
        return brandRepository.findById(id).orElseThrow(() -> ApiException.notFound("Brand " + id + " not found"));
    }

    private static ApiException productNotFound(Long id) {
        return ApiException.notFound("Product " + id + " not found");
    }

    private static ApiException duplicateIsbn() {
        return ApiException.duplicate("A product with this ISBN already exists");
    }
}
