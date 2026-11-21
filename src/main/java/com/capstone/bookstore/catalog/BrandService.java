package com.capstone.bookstore.catalog;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.bookstore.common.ApiException;

/** Brands / publishers (IBM step 6): public listing and ADMIN creation. Products of a brand: see ProductService. */
@Service
public class BrandService {

    private final BrandRepository brandRepository;

    public BrandService(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    @Transactional(readOnly = true)
    public List<BrandResponse> list() {
        return brandRepository.findAllByOrderByNameAsc().stream().map(BrandResponse::from).toList();
    }

    @Transactional
    public BrandResponse create(BrandRequest request) {
        if (brandRepository.existsByNameIgnoreCase(request.name())) {
            throw ApiException.duplicate("A brand with this name already exists");
        }
        try {
            return BrandResponse.from(brandRepository.saveAndFlush(new Brand(request.name(), request.description())));
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.duplicate("A brand with this name already exists");
        }
    }
}
