package com.altdetector.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 标识符哈希。
 *
 * <p>设备号这类东西只需要判断相不相等，原样落盘没有必要，统一存 SHA-256 十六进制串。
 */
public final class Hashes {

    private Hashes() {
    }

    /** 返回 64 位小写十六进制；null 或空串返回 null。 */
    public static String sha256(String input) {
        if (input == null || input.isEmpty()) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
