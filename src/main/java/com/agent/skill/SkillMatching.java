package com.agent.skill;

import java.util.*;
import java.util.regex.Pattern;

/** 显式文件条件严格检查，文本相关性只决定目录排序，不等同于适用性证明。 */
final class SkillMatching {
    private static final Pattern REQUIRED = Pattern.compile("(?im)(?:requires|必须存在)\\s*[:：]\\s*([^\\r\\n;；]+)");
    private static final Pattern VERSION = Pattern.compile("(?i)(?<![a-f0-9])[a-f0-9]{64}(?![a-f0-9])");
    static String conditionFailure(String conditions, Map<String, String> files) {
        var required = REQUIRED.matcher(conditions);
        while (required.find()) { if (!files.containsKey(required.group(1).trim())) { return "文件前提不满足"; } }
        var versions = VERSION.matcher(conditions);
        while (versions.find()) { if (!files.containsValue(versions.group().toLowerCase(Locale.ROOT))) { return "版本前提不满足"; } }
        return null;
    }
    static Set<String> terms(String text) {
        var words = new TreeSet<String>();
        var matcher = Pattern.compile("[a-z][a-z0-9_]*|[\\p{IsHan}]+").matcher(text.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = matcher.group();
            if (term.matches("[\\p{IsHan}]+")) { for (int i = 0; i + 1 < term.length(); i++) { words.add(term.substring(i, i + 2)); } }
            else if (term.length() >= 3) { words.add(term); }
        }
        words.removeAll(Set.of("修复", "测试", "失败", "通过", "验收", "问题", "运行", "适用", "test", "tests", "should", "failed", "expected", "actual", "error", "exception", "java", "true", "false", "null"));
        return words;
    }
    static Set<String> matches(SkillCandidate.Guide guide, String context) {
        String natural = VERSION.matcher(REQUIRED.matcher(guide.conditions()).replaceAll("")).replaceAll("");
        var terms = terms(guide.title() + " " + natural + " " + guide.diagnosis());
        terms.retainAll(terms(context));
        return terms;
    }
}
