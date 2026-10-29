package com.udyam.catalogue;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductDto> all() {
        return service.findAll();
    }

    @GetMapping("/{productId}")
    public ProductDto one(@PathVariable String productId) {
        return service.findById(productId);
    }
}
