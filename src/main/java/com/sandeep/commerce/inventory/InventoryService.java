package com.sandeep.commerce.inventory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRepository productRepository;

    @Transactional
    public void placeOrder(Long productId, int qty){
        Product product = productRepository.findById(productId)
                .orElseThrow();

        if(product.getStock() < qty) {
            throw new IllegalArgumentException("Out of stock");
        }

        product.updateStock(product.getStock() - qty);
    }

}
