package com.udyam.dataload;

/** One "P01x2" entry of a lead's bundle_products: product P01, quantity 2. */
public record BundleItem(String productId, int quantity) {
}
