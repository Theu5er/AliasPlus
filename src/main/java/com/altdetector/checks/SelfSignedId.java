package com.altdetector.checks;

import cn.nukkit.utils.LoginChainData;
import com.altdetector.AltDetector;
import com.altdetector.util.Hashes;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * SelfSignedId 维度：采集客户端自签标识。
 *
 * <p>照搬 gophertunnel 的 {@code ClientData.Validate()}：字段缺失或为空视为未提供；
 * 非空时必须能解析为 UUID，否则不落库并触发惩罚。
 */
public final class SelfSignedId {

    private static final String RAW_KEY = "SelfSignedId";

    private SelfSignedId() {
    }

    /**
     * 采集并写入 {@code identifiers}。
     *
     * @return true 表示取值无效（调用方据此触发惩罚）
     */
    public static boolean collect(AltDetector plugin, LoginChainData data, Map<String, String> identifiers) {
        if (!plugin.selfSignedEnabled()) return false;

        JsonObject rawData = data.getRawData();
        try {
            if (rawData == null || !rawData.has(RAW_KEY) || rawData.get(RAW_KEY).isJsonNull()) return false;

            String selfSigned = rawData.get(RAW_KEY).getAsString();
            if (plugin.idFormatEnabled() && !IdFormat.isValidSelfSignedId(selfSigned)) return true;

            identifiers.put(RAW_KEY, Hashes.sha256(selfSigned));
        } catch (Exception ignored) {
            // 与拆分前的行为一致：原始登录数据结构异常时不处理该维度
        }
        return false;
    }
}
