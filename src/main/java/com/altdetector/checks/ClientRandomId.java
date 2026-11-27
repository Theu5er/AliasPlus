package com.altdetector.checks;

import cn.nukkit.utils.LoginChainData;
import com.altdetector.AltDetector;
import com.altdetector.util.Hashes;

import java.util.Map;

/**
 * ClientRandomId 维度：采集客户端随机标识。
 *
 * <p>取值 0 视为客户端未提供真实值（gophertunnel 在 dial.go 里同样把 0 当作未设置），
 * 开关打开时拒绝入库并触发惩罚。
 */
public final class ClientRandomId {

    private ClientRandomId() {
    }

    /**
     * 采集并写入 {@code identifiers}。
     *
     * @return true 表示取值无效（调用方据此触发惩罚）
     */
    public static boolean collect(AltDetector plugin, LoginChainData data, Map<String, String> identifiers) {
        if (!plugin.clientRandomEnabled()) return false;

        long clientId = data.getClientId();
        if (plugin.idFormatEnabled() && !IdFormat.isValidClientRandomId(clientId)) return true;

        identifiers.put("ClientRandomId", Hashes.sha256(String.valueOf(clientId)));
        return false;
    }
}
