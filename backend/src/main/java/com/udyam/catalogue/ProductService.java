package com.udyam.catalogue;

import com.udyam.common.errors.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    public List<ProductDto> findAll() {
        return repository.findAllByOrderByProductIdAsc().stream().map(ProductDto::from).toList();
    }

    public ProductDto findById(String productId) {
        return repository.findById(productId)
                .map(ProductDto::from)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productId));
    }
}
