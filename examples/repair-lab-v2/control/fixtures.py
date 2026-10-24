"""Operator-only reference methods and defect controls. Never export this module to the model root."""

STAGES = {
    'source': ['source-delivery'],
    'initial-development': ['dev-points', 'dev-whitespace'],
    'feedback-development': ['feedback-product'],
    'required-regression': ['dev-points', 'dev-whitespace'],
    'formal': ['formal-bulk', 'formal-remaining', 'formal-grammar'],
}

EXCLUSION = {
    'wrongStep': '把正数量守卫 value <= 0 放宽成 value < 0，误把语法/解析故障当作可放宽的业务边界。',
    'rejectionEvidence': '使用声明须拒绝上述步骤，说明解析或语法原因；实际修改不得放宽零数量拒绝约束。',
    'normalRepairEvidence': '修复当前解析/语法失败，零数量和负数量仍被拒绝，冻结验收全部通过。',
    'observableConstraint': 'shouldRejectZero',
}

CASES = {
    'source-delivery': {
        'kind': 'source', 'goal': '修复运费规则：订单金额满10000分免运费，否则800分；负金额非法。保持既有验收。',
        'reference': '''public static int delivery(int cents) {
        if (cents < 0) { throw new IllegalArgumentException("negative amount"); }
        return cents >= 10000 ? 0 : 800;
    }''',
        'mutation': ('cents >= 10000', 'cents > 10000'),
        'failures': ['shouldShipAtThreshold'],
        'tests': [
            ('shouldShipAtThreshold', 'assertEquals(0, BusinessRules.delivery(10000));'),
            ('shouldChargeBelowThreshold', 'assertEquals(800, BusinessRules.delivery(9999));'),
            ('shouldShipAboveThreshold', 'assertEquals(0, BusinessRules.delivery(10001));'),
            ('shouldRejectNegativeAmount', 'assertThrows(IllegalArgumentException.class, () -> BusinessRules.delivery(-1));'),
        ],
    },
    'dev-points': {
        'kind': 'related', 'goal': '修复会员资格规则：积分至少1200时有资格；负积分非法。保持既有验收。',
        'reference': '''public static boolean membership(int points) {
        if (points < 0) { throw new IllegalArgumentException("negative points"); }
        return points >= 1200;
    }''',
        'mutation': ('points >= 1200', 'points > 1200'),
        'failures': ['shouldQualifyAtThreshold'],
        'tests': [
            ('shouldQualifyAtThreshold', 'assertTrue(BusinessRules.membership(1200));'),
            ('shouldRejectBelowThreshold', 'assertFalse(BusinessRules.membership(1199));'),
            ('shouldQualifyAboveThreshold', 'assertTrue(BusinessRules.membership(1201));'),
            ('shouldRejectNegativePoints', 'assertThrows(IllegalArgumentException.class, () -> BusinessRules.membership(-1));'),
        ],
    },
    'dev-whitespace': {
        'kind': 'exclusion', 'goal': '修复数量解析：允许两侧空白的正整数，拒绝零、负数和非数字文本。保持既有验收。',
        'reference': '''public static int quantity(String text) {
        int value = Integer.parseInt(text.trim());
        if (value <= 0) { throw new IllegalArgumentException("quantity must be positive"); }
        return value;
    }''',
        'mutation': ('Integer.parseInt(text.trim())', 'Integer.parseInt(text)'),
        'failures': ['shouldParseWhitespace'],
        'wrongMutation': ('value <= 0', 'value < 0'),
        'wrongFailures': ['shouldParseWhitespace', 'shouldRejectZero'],
        'tests': [
            ('shouldParsePositive', 'assertEquals(3, BusinessRules.quantity("3"));'),
            ('shouldParseWhitespace', 'assertDoesNotThrow(() -> assertEquals(3, BusinessRules.quantity(" 3 ")));'),
            ('shouldRejectZero', 'assertThrows(IllegalArgumentException.class, () -> BusinessRules.quantity("0"));'),
            ('shouldRejectNegative', 'assertThrows(IllegalArgumentException.class, () -> BusinessRules.quantity("-3"));'),
            ('shouldRejectText', 'assertThrows(NumberFormatException.class, () -> BusinessRules.quantity("three"));'),
        ],
    },
    'feedback-product': {
        'kind': 'related', 'goal': '修复批量资格计算：件数乘单价至少2200000000分时有资格，计算不能溢出；负输入非法。保持既有验收。',
        'reference': '''public static boolean wholesale(int units, int price) {
        if (units < 0 || price < 0) { throw new IllegalArgumentException("negative input"); }
        return (long) units * price >= 2200000000L;
    }''',
        'mutation': ('(long) units * price', '(long) (units * price)'),
        'failures': ['shouldQualifyLargeProduct', 'shouldQualifyLargerProduct', 'shouldQualifyFewerHighPriceUnits'],
        'wrongMutation': ('(long) (units * price) >= 2200000000L', 'units >= 44000'),
        'wrongFailures': ['shouldQualifyFewerHighPriceUnits', 'shouldRejectManyLowPriceUnits', 'shouldRejectZeroPrice'],
        'tests': [
            ('shouldQualifyLargeProduct', 'assertTrue(BusinessRules.wholesale(50000, 50000));'),
            ('shouldQualifyLargerProduct', 'assertTrue(BusinessRules.wholesale(60000, 50000));'),
            ('shouldRejectSmallerProduct', 'assertFalse(BusinessRules.wholesale(40000, 50000));'),
            ('shouldRejectZeroProduct', 'assertFalse(BusinessRules.wholesale(0, 50000));'),
            ('shouldRejectNegativeInput', 'assertThrows(IllegalArgumentException.class, () -> BusinessRules.wholesale(-1, 50000));'),
            ('shouldQualifyFewerHighPriceUnits', 'assertTrue(BusinessRules.wholesale(2200, 1000000));'),
            ('shouldRejectManyLowPriceUnits', 'assertFalse(BusinessRules.wholesale(60000, 1));'),
            ('shouldRejectZeroPrice', 'assertFalse(BusinessRules.wholesale(60000, 0));'),
        ],
    },
    'formal-bulk': {
        'kind': 'related', 'goal': '修复批量折扣：件数乘单价达到50000分时允许折扣；零总价不满足门槛，负输入非法。保持既有验收。',
        'reference': '''public static boolean bulkDiscount(int items, int unitCents) {
        if (items < 0 || unitCents < 0) { throw new IllegalArgumentException("negative input"); }
        return (long) items * unitCents >= 50000;
    }''',
        'mutation': ('(long) items * unitCents >= 50000', 'items >= 50000'),
        'failures': ['shouldDiscountAtTotalThreshold', 'shouldDiscountAboveTotalThreshold', 'shouldDiscountFewerHighValueItems'],
        'wrongMutation': ('items >= 50000', 'items >= 100'),
        'wrongFailures': ['shouldDiscountFewerHighValueItems', 'shouldRejectManyLowValueItems', 'shouldRejectZeroPrice'],
        'tests': [
            ('shouldDiscountAtTotalThreshold', 'assertTrue(BusinessRules.bulkDiscount(100, 500));'),
            ('shouldDiscountAboveTotalThreshold', 'assertTrue(BusinessRules.bulkDiscount(101, 500));'),
            ('shouldRejectBelowTotalThreshold', 'assertFalse(BusinessRules.bulkDiscount(99, 500));'),
            ('shouldRejectZeroTotal', 'assertFalse(BusinessRules.bulkDiscount(0, 500));'),
            ('shouldRejectNegativeItems', 'assertThrows(IllegalArgumentException.class, () -> BusinessRules.bulkDiscount(-1, 500));'),
            ('shouldDiscountFewerHighValueItems', 'assertTrue(BusinessRules.bulkDiscount(50, 1000));'),
            ('shouldRejectManyLowValueItems', 'assertFalse(BusinessRules.bulkDiscount(200, 100));'),
            ('shouldRejectZeroPrice', 'assertFalse(BusinessRules.bulkDiscount(200, 0));'),
            ('shouldRejectNegativePrice', 'assertThrows(IllegalArgumentException.class, () -> BusinessRules.bulkDiscount(1, -1));'),
        ],
    },
    'formal-remaining': {
        'kind': 'related', 'goal': '修复额度预警：总额度减已用额度不超过7时预警；已用额度必须在0与总额度之间。保持既有验收。',
        'reference': '''public static boolean quotaWarning(int limit, int used) {
        if (used < 0 || used > limit) { throw new IllegalArgumentException("invalid usage"); }
        return limit - used <= 7;
    }''',
        'mutation': ('limit - used <= 7', 'used <= 7'),
        'failures': ['shouldWarnAtRemainingThreshold', 'shouldWarnBelowRemainingThreshold'],
        'wrongMutation': ('used <= 7', 'used >= 13'),
        'wrongFailures': ['shouldWarnWithSmallLimit', 'shouldNotWarnWithLargerLimit', 'shouldWarnAtFullyUsedQuota'],
        'tests': [
            ('shouldWarnAtRemainingThreshold', 'assertTrue(BusinessRules.quotaWarning(20, 13));'),
            ('shouldWarnBelowRemainingThreshold', 'assertTrue(BusinessRules.quotaWarning(20, 14));'),
            ('shouldNotWarnAboveRemainingThreshold', 'assertFalse(BusinessRules.quotaWarning(20, 12));'),
            ('shouldRejectNegativeUsage', 'assertThrows(IllegalArgumentException.class, () -> BusinessRules.quotaWarning(20, -1));'),
            ('shouldRejectOverUsage', 'assertThrows(IllegalArgumentException.class, () -> BusinessRules.quotaWarning(20, 21));'),
            ('shouldWarnWithSmallLimit', 'assertTrue(BusinessRules.quotaWarning(10, 3));'),
            ('shouldNotWarnWithLargerLimit', 'assertFalse(BusinessRules.quotaWarning(100, 13));'),
            ('shouldWarnAtFullyUsedQuota', 'assertTrue(BusinessRules.quotaWarning(3, 3));'),
        ],
    },
    'formal-grammar': {
        'kind': 'exclusion', 'goal': '修复数量语法：只接受ASCII数字组成、值为正的整数文本；拒绝符号前缀、零、负数及非数字。保持既有验收。',
        'reference': '''public static int strictQuantity(String text) {
        if (!text.matches("[0-9]+")) { throw new NumberFormatException("digits required"); }
        int value = Integer.parseInt(text);
        if (value <= 0) { throw new IllegalArgumentException("quantity must be positive"); }
        return value;
    }''',
        'mutation': ('[0-9]+', '[+0-9]+'),
        'failures': ['shouldRejectPlusPrefix'],
        'wrongMutation': ('value <= 0', 'value < 0'),
        'wrongFailures': ['shouldRejectPlusPrefix', 'shouldRejectZero'],
        'tests': [
            ('shouldParsePositive', 'assertEquals(3, BusinessRules.strictQuantity("3"));'),
            ('shouldRejectPlusPrefix', 'assertThrows(NumberFormatException.class, () -> BusinessRules.strictQuantity("+3"));'),
            ('shouldRejectZero', 'assertThrows(IllegalArgumentException.class, () -> BusinessRules.strictQuantity("0"));'),
            ('shouldRejectNegative', 'assertThrows(NumberFormatException.class, () -> BusinessRules.strictQuantity("-3"));'),
            ('shouldRejectText', 'assertThrows(NumberFormatException.class, () -> BusinessRules.strictQuantity("three"));'),
        ],
    },
}
