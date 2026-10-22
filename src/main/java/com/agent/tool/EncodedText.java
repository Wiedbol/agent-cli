package com.agent.tool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/** 不猜测无 BOM 的旧编码，也不把无法表示的字符静默写成问号。 */
record EncodedText(String text, Charset charset, byte[] bom) {
    static EncodedText decode(byte[] bytes, String declared) throws IOException {
        Charset detected = null;
        int prefix = 0;
        if (starts(bytes, 0xff, 0xfe, 0x00, 0x00) || starts(bytes, 0x00, 0x00, 0xfe, 0xff)) {
            throw new IOException("暂不支持 UTF-32 编码");
        }
        if (starts(bytes, 0xef, 0xbb, 0xbf)) { detected = StandardCharsets.UTF_8; prefix = 3; }
        else if (starts(bytes, 0xff, 0xfe)) { detected = StandardCharsets.UTF_16LE; prefix = 2; }
        else if (starts(bytes, 0xfe, 0xff)) { detected = StandardCharsets.UTF_16BE; prefix = 2; }
        Charset explicit = declared == null ? null : Charset.forName(declared);
        if (explicit != null && !List.of("UTF-8", "UTF-16LE", "UTF-16BE", "GBK").contains(explicit.name())) {
            throw new IOException("仅支持 UTF-8、UTF-16LE、UTF-16BE 和显式 GBK");
        }
        if (detected != null && explicit != null && !detected.equals(explicit)) {
            throw new IOException("指定编码与文件 BOM 不一致");
        }
        Charset charset = detected != null ? detected : explicit != null ? explicit : StandardCharsets.UTF_8;
        String text = charset.newDecoder().decode(ByteBuffer.wrap(bytes, prefix, bytes.length - prefix)).toString();
        if (text.indexOf('\0') >= 0) { throw new IOException("文件包含 NUL，不能按文本安全修改"); }
        EncodedText result = new EncodedText(text, charset, Arrays.copyOf(bytes, prefix));
        if (!Arrays.equals(bytes, result.encode(text))) { throw new IOException("原文件编码不能无损往返"); }
        return result;
    }

    byte[] encode(String content) throws IOException {
        if (content.indexOf('\0') >= 0) { throw new IOException("替换文本不能包含 NUL"); }
        ByteBuffer body = charset.newEncoder().encode(CharBuffer.wrap(content));
        return ByteBuffer.allocate(bom.length + body.remaining()).put(bom).put(body).array();
    }

    String replacement(String requested) throws IOException {
        var endings = new LinkedHashSet<String>();
        var matcher = java.util.regex.Pattern.compile("\\r\\n|\\r|\\n").matcher(text);
        while (matcher.find()) { endings.add(matcher.group()); }
        if (endings.size() > 1 && (requested.contains("\n") || requested.contains("\r"))) {
            throw new IOException("文件含混合换行，拒绝自动选择多行替换的换行方式");
        }
        String newline = endings.isEmpty() ? "\n" : endings.iterator().next();
        return requested.replace("\r\n", "\n").replace('\r', '\n').replace("\n", newline);
    }

    private static boolean starts(byte[] bytes, int... values) {
        if (bytes.length < values.length) { return false; }
        for (int index = 0; index < values.length; index++) {
            if (Byte.toUnsignedInt(bytes[index]) != values[index]) { return false; }
        }
        return true;
    }
}
