package com.capstone.bookstore.catalog;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.bookstore.common.ApiException;

/** Categories (IBM step 5): public listing and ADMIN creation. */
@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return categoryRepository.findAllByOrderByNameAsc().stream().map(CategoryResponse::from).toList();
    }

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        if (categoryRepository.existsByNameIgnoreCase(request.name())) {
            throw ApiException.duplicate("A category with this name already exists");
        }
        try {
            return CategoryResponse.from(
                    categoryRepository.saveAndFlush(new Category(request.name(), request.description())));
        } catch (DataIntegrityViolationException ex) {
            // Concurrent create of the same name: the unique constraint is the final guard.
            throw ApiException.duplicate("A category with this name already exists");
        }
    }
}
