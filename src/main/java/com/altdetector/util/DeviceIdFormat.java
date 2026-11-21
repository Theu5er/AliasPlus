package com.altdetector.util;

import java.util.Base64;
import java.util.Locale;

/**
 * DeviceId 格式判定，规则参照 gophertunnel 的 {@code DeviceID.Format()}。
 *
 * <p>合法形态只有四种：32 位十六进制（分大小写）、标准 UUID、Base64（解码后 32 字节）。
 * 判定顺序必须先 hex 后 UUID —— 32 位十六进制补上连字符也能被 UUID 解析器接受。
 */
public final class DeviceIdFormat {

    public enum Format {
        UPPER_HEX,
        LOWER_HEX,
        BASE64,
        UUID,
        INVALID
    }

    private DeviceIdFormat() {
    }

    public static Format parse(String id) {
        if (id == null || id.isEmpty()) return Format.INVALID;
        if (id.length() == 32 && isHex(id)) {
            return id.equals(id.toLowerCase(Locale.ROOT)) ? Format.LOWER_HEX : Format.UPPER_HEX;
        }
        if (id.length() == 36 && isUuid(id)) return Format.UUID;
        try {
            if (Base64.getDecoder().decode(id).length == 32) return Format.BASE64;
        } catch (IllegalArgumentException ignored) {
            // 不是合法 Base64
        }
        return Format.INVALID;
    }


    /**
     * 该格式是否符合对应平台的惯例。deviceOS 取 Bedrock 的 DeviceOS 枚举值。
     *
     * <p>对应关系来自 gophertunnel 对真实客户端的观察（其注释标注），不是协议规定，
     * 因此只对已知平台做判断，其余平台一律放行。
     */
    public static boolean matchesPlatform(Format format, int deviceOS) {
        if (format == Format.INVALID) return false;
        switch (deviceOS) {
            case 1:   // Android
            case 9:   // Dedicated
                return format == Format.LOWER_HEX;
            case 2:   // iOS
                return format == Format.UPPER_HEX;
            case 7:   // Windows 10
            case 8:   // Windows 32
                return format == Format.LOWER_HEX || format == Format.UUID;
            case 11:  // Orbis (PlayStation)
                return format == Format.UUID;
            case 13:  // Xbox
                return format == Format.BASE64;
            default:
                return true;
        }
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private static boolean isUuid(String s) {
        if (s.charAt(8) != '-' || s.charAt(13) != '-' || s.charAt(18) != '-' || s.charAt(23) != '-') return false;
        for (int i = 0; i < s.length(); i++) {
            if (i == 8 || i == 13 || i == 18 || i == 23) continue;
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }
}
