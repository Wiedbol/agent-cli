package demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopRulesTest {
    @Test void shouldShipFreeAtThreshold() { assertEquals(0, ShopRules.shipping(10000)); }
    @Test void shouldChargeBelowThreshold() { assertEquals(800, ShopRules.shipping(9999)); }
    @Test void shouldShipFreeAboveThreshold() { assertEquals(0, ShopRules.shipping(10001)); }
    @Test void shouldRejectNegativeAmount() { assertThrows(IllegalArgumentException.class, () -> ShopRules.shipping(-1)); }
    @Test void shouldAllowCouponAtThreshold() { assertTrue(ShopRules.coupon(20000)); }
    @Test void shouldRejectCouponBelowThreshold() { assertFalse(ShopRules.coupon(19999)); }
    @Test void shouldAllowCouponAboveThreshold() { assertTrue(ShopRules.coupon(20001)); }
    @Test void shouldRejectNegativeCouponAmount() { assertThrows(IllegalArgumentException.class, () -> ShopRules.coupon(-1)); }
    @Test void shouldWarnAtStockThreshold() { assertTrue(ShopRules.lowStock(5)); }
    @Test void shouldWarnBelowStockThreshold() { assertTrue(ShopRules.lowStock(4)); }
    @Test void shouldNotWarnAboveStockThreshold() { assertFalse(ShopRules.lowStock(6)); }
    @Test void shouldRejectNegativeStock() { assertThrows(IllegalArgumentException.class, () -> ShopRules.lowStock(-1)); }
    @Test void shouldParsePositiveQuantity() { assertEquals(3, ShopRules.quantity("3")); }
    @Test void shouldRejectZeroQuantity() { assertThrows(IllegalArgumentException.class, () -> ShopRules.quantity("0")); }
    @Test void shouldRejectNegativeQuantity() { assertThrows(IllegalArgumentException.class, () -> ShopRules.quantity("-1")); }
    @Test void shouldRejectNonNumericQuantity() { assertThrows(NumberFormatException.class, () -> ShopRules.quantity("three")); }
}
