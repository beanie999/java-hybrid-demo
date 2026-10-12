package com.example.pricing;

import java.util.List;

/** An order to be priced. {@code currency} is the ISO code the quote should be returned in. */
public record Order(String id, String currency, List<LineItem> items) {}
