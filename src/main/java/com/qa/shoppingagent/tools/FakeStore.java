package com.qa.shoppingagent.tools;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic in-memory "backend" (catalog + orders). Seeded identically on startup and on
 * POST /test/reset so test runs are repeatable. Data mirrors datasets/shopping_assistant goldens.
 */
@Component
public class FakeStore {

    public record Variant(String size, String color, int quantity) {}

    public record Product(String sku, String name, String category, double price, List<Variant> variants) {}

    public enum OrderStatus { PLACED, PROCESSING_FOR_SHIPPING, SHIPPED, DELIVERED, CANCELLED }

    public record Order(String id, OrderStatus status, int minutesSincePlaced, String items, double total) {
        Order withStatus(OrderStatus s) { return new Order(id, s, minutesSincePlaced, items, total); }
    }

    private final List<Product> products = new ArrayList<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public FakeStore() {
        reset();
    }

    public synchronized void reset() {
        products.clear();
        products.add(new Product("NK-AM90", "Nike Air Max 90", "footwear", 129.99, shoeVariants(7, 13, Map.of(12, 0), 4)));
        products.add(new Product("AD-UB22", "Adidas Ultraboost 22", "footwear", 179.99, shoeVariants(8, 12, Map.of(9, 0), 4)));
        products.add(new Product("SONY-XM5", "Sony WH-1000XM5 Headphones", "electronics", 349.99,
                List.of(new Variant("one-size", "black", 7), new Variant("one-size", "silver", 0))));
        products.add(new Product("APL-APP2", "Apple AirPods Pro (2nd generation)", "electronics", 249.00,
                List.of(new Variant("one-size", "white", 0))));

        orders.clear();
        put(new Order("ORD-4521", OrderStatus.PLACED, 35, "1x Nike Air Max 90 (size 10, black)", 129.99));
        put(new Order("ORD-4522", OrderStatus.PLACED, 180, "1x Adidas Ultraboost 22 (size 10, grey)", 179.99));
        put(new Order("ORD-6010", OrderStatus.PROCESSING_FOR_SHIPPING, 50, "1x Apple AirPods Pro (2nd generation)", 249.00));
        put(new Order("ORD-7788", OrderStatus.SHIPPED, 2880, "1x Sony WH-1000XM5 Headphones (black)", 349.99));
        put(new Order("ORD-1001", OrderStatus.DELIVERED, 14400, "2x Nike Air Max 90 (size 9, white)", 259.98));
    }

    /** Sizes from..to in black and white; {@code soldOut} sizes have 0 stock in every colour. */
    private static List<Variant> shoeVariants(int from, int to, Map<Integer, Integer> soldOut, int qty) {
        List<Variant> v = new ArrayList<>();
        for (int size = from; size <= to; size++) {
            int q = soldOut.getOrDefault(size, qty);
            v.add(new Variant(String.valueOf(size), "black", q));
            v.add(new Variant(String.valueOf(size), "white", size == 10 ? Math.max(q - 1, 0) : q));
        }
        return v;
    }

    private void put(Order o) {
        orders.put(o.id(), o);
    }

    public synchronized List<Product> search(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        return products.stream()
                .filter(p -> p.name().toLowerCase(Locale.ROOT).contains(q)
                        || q.contains(p.name().toLowerCase(Locale.ROOT))
                        || p.category().equalsIgnoreCase(q)
                        || tokensMatch(p.name(), q))
                .toList();
    }

    /** Lenient match: every word of the query appears in the product name ("air max" -> "Nike Air Max 90"). */
    private static boolean tokensMatch(String name, String q) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] words = q.split("\\s+");
        for (String w : words) {
            if (!n.contains(w)) {
                return false;
            }
        }
        return words.length > 0;
    }

    public Optional<Order> order(String orderId) {
        return Optional.ofNullable(orders.get(normalize(orderId)));
    }

    public Order updateStatus(String orderId, OrderStatus status) {
        return orders.computeIfPresent(normalize(orderId), (id, o) -> o.withStatus(status));
    }

    public static String normalize(String orderId) {
        return orderId == null ? "" : orderId.trim().replace("#", "").toUpperCase(Locale.ROOT);
    }
}
