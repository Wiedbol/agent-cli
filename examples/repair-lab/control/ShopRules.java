package demo;

/** 规则使用整数分，避免演示中的浮点误差掩盖业务边界。 */
public final class ShopRules {
    public static int shipping(int cents) {
        if (cents < 0) { throw new IllegalArgumentException("negative amount"); }
        return cents >= 10000 ? 0 : 800;
    }
    public static boolean coupon(int cents) {
        if (cents < 0) { throw new IllegalArgumentException("negative amount"); }
        return cents >= 20000;
    }
    public static boolean lowStock(int count) {
        if (count < 0) { throw new IllegalArgumentException("negative stock"); }
        return count <= 5;
    }
    public static int quantity(String text) {
        int value = Integer.parseInt(text);
        if (value <= 0) { throw new IllegalArgumentException("quantity must be positive"); }
        return value;
    }
}
