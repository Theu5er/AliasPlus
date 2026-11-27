package com.altdetector.checks;

import cn.nukkit.utils.LoginChainData;
import com.altdetector.AltDetector;
import com.altdetector.util.Hashes;

import java.util.Map;

/**
 * DeviceId 维度：采集设备标识并校验格式。
 *
 * <p>开关打开时（{@code checks.IdFormat}），格式必须合法且与上报的平台吻合，
 * 否则不落库并触发惩罚。
 */
public final class DeviceId {

    private DeviceId() {
    }

    /**
     * 采集并写入 {@code identifiers}。
     *
     * @return true 表示格式非法（调用方据此触发惩罚）
     */
    public static boolean collect(AltDetector plugin, LoginChainData data, Map<String, String> identifiers) {
        if (!plugin.deviceEnabled()) return false;

        String rawDevice = data.getDeviceId();
        if (rawDevice == null || rawDevice.isEmpty()) return false;

        // 开关打开时：格式必须合法，且要和上报的平台吻合
        IdFormat.Format format = IdFormat.parse(rawDevice);
        boolean accept = !plugin.idFormatEnabled()
                || (format != IdFormat.Format.INVALID
                    && IdFormat.matchesPlatform(format, data.getDeviceOS()));
        if (!accept) return true;

        identifiers.put("DeviceId", Hashes.sha256(rawDevice));
        return false;
    }
}
