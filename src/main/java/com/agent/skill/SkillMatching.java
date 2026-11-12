package com.agent.skill;

import java.util.*;
import java.util.regex.Pattern;

/** 文件和版本前提严格检查；自然语言适用性由 Agent 结合目录与当前证据判断。 */
final class SkillMatching {
    private static final Pattern REQUIRED = Pattern.compile("(?im)(?:requires|必须存在)\\s*[:：]\\s*([^\\r\\n;；]+)");
    private static final Pattern VERSION = Pattern.compile("(?i)(?<![a-f0-9])[a-f0-9]{64}(?![a-f0-9])");

    static boolean hasFilePreconditions(String conditions) {
        return REQUIRED.matcher(conditions).find() || VERSION.matcher(conditions).find();
    }

    static String conditionFailure(String conditions, Map<String, String> files) {
        var required = REQUIRED.matcher(conditions);
        while (required.find()) { if (!files.containsKey(required.group(1).trim())) { return "文件前提不满足"; } }
        var versions = VERSION.matcher(conditions);
        while (versions.find()) { if (!files.containsValue(versions.group().toLowerCase(Locale.ROOT))) { return "版本前提不满足"; } }
        return null;
    }
}
