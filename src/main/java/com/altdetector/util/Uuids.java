package com.altdetector.util;

import cn.nukkit.Player;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 存储键就是玩家 UUID，这里只放几个文本规整与派生的小工具。 */
public final class Uuids {

    private Uuids() {
    }

    /** 玩家的存储键。 */
    public static String keyOf(Player player) {
        return player == null ? "" : normalize(player.getUniqueId().toString());
    }

    /** 去空白 + 小写。 */
    public static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /** 是否标准 UUID 串。 */
    public static boolean isUuid(String s) {
        if (s == null || s.length() != 36) return false;
        try {
            UUID.fromString(s.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 键 → 稳定 128 位身份，供指纹种子用；脏数据兜底成 nameUUIDFromBytes，避免抛异常。 */
    public static UUID blobIdentity(String key) {
        String k = normalize(key);
        if (isUuid(k)) return UUID.fromString(k);
        return UUID.nameUUIDFromBytes(k.getBytes(StandardCharsets.UTF_8));
    }
}
