package com.stockpulse.product;

import com.stockpulse.advisor.ProductContext;
import com.stockpulse.common.ApiException;
import com.stockpulse.config.TriggerProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProductContextLoader {

    private final ProductRepository products;
    private final TriggerProperties triggers;

    public ProductContextLoader(ProductRepository products, TriggerProperties triggers) {
        this.products = products;
        this.triggers = triggers;
    }

    @Transactional(readOnly = true)
    public ProductContext load(String productId) {
        Product p = products.findById(productId).orElseThrow(() -> ApiException.notFound("Product", productId));
        double average = ProductContext.flooredAverage(products.averageVelocityOfPeers(p.getCategory(), p.getId()));
        int peers = (int) products.countByCategoryAndIdNot(p.getCategory(), p.getId());
        return new ProductContext(p.getId(), p.getSku(), p.getName(), p.getCategory(), p.getCurrentPrice(),
                p.getStockLevel(), p.getReorderThreshold(), p.getDemandVelocity(), average, peers,
                triggers.spikeMultiplier(), p.getCostPrice(), p.getMarginFloorPct(), p.getSupplierId());
    }
}
