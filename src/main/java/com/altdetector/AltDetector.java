package com.altdetector;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerLoginEvent;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.LoginChainData;
import com.altdetector.blob.BlobDetector;
import com.altdetector.command.AliasCommand;
import com.altdetector.pack.PackDetector;
import com.altdetector.store.PlayerStore;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.LinkedHashMap;

/**
 * AltDetector —— 多维度小号关联检测插件。
 *
 * <p>整合三种独立检测维度，任一命中即可关联：
 * <ol>
 *   <li><b>登录标识符</b>：device id / selfSigned id / clientRandom id（同一设备换号必然相同）</li>
 *   <li><b>资源包指纹</b>：为每个账号分配唯一资源包 UUID，客户端缓存后换号登录可检测
 *       （{@link PackDetector}）</li>
 *   <li><b>Client Blob Cache</b>：基岩客户端内置持久 blob 缓存（内容寻址、跨账号共享），
 *       为每个账号生成唯一 biome 指纹 blob，同设备换号登录时命中即关联（{@link BlobDetector}）</li>
 * </ol>
 *
 * <p>所有数据统一存储在 {@code players.yml}（{@link PlayerStore}），
 * {@code /alias <玩家名>} 一键查询全部维度（{@link AliasCommand}）。
 *
 * <p>本类只负责装配：配置初始化、模块构造、监听器与命令注册、登录事件入库。
 */
public class AltDetector extends PluginBase implements Listener {

    private Config config;
    private PlayerStore store;
    private PackDetector packDetector;
    private BlobDetector blobDetector;

    /* ================================================================== */
    /*  生命周期                                                           */
    /* ================================================================== */

    @Override
    public void onEnable() {
        this.config = new Config(new File(this.getDataFolder(), "config.yml"), Config.YAML);
        Config playersConfig = new Config(new File(this.getDataFolder(), "players.yml"), Config.YAML);

        if (!config.exists("packcache-enabled")) config.set("packcache-enabled", true);
        if (!config.exists("blobcache-enabled")) config.set("blobcache-enabled", true);
        if (!config.exists("blobcache-debug")) config.set("blobcache-debug", false);
        config.save();

        if (!playersConfig.exists("players")) playersConfig.set("players", new LinkedHashMap<>());
        playersConfig.save();

        this.store = new PlayerStore(playersConfig);
        this.store.migrateLegacyStorage();
        this.store.normalizePlayers();

        this.blobDetector = new BlobDetector(this, store);
        this.blobDetector.loadData();
        this.blobDetector.registerPackets();

        this.packDetector = new PackDetector(this, store);

        this.getServer().getPluginManager().registerEvents(this, this);
        this.getServer().getPluginManager().registerEvents(this.packDetector, this);
        this.getServer().getPluginManager().registerEvents(this.blobDetector, this);

        this.getServer().getCommandMap().register("alias", new AliasCommand(this, store, blobDetector));
    }

    /* ================================================================== */
    /*  模块开关（供检测器查询）                                            */
    /* ================================================================== */

    public boolean packEnabled() {
        return this.config.getBoolean("packcache-enabled", true);
    }

    public boolean blobEnabled() {
        return this.config.getBoolean("blobcache-enabled", true);
    }

    /* ================================================================== */
    /*  登录事件：身份档案与登录标识符入库                                   */
    /* ================================================================== */

    @EventHandler(priority = cn.nukkit.event.EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        String playerUuid = player.getUniqueId().toString();

        LoginChainData lcd = player.getLoginChainData();
        String xuid = null;
        if (lcd != null) { try { xuid = lcd.getXUID(); } catch (Exception ignored) {} }

        store.storePlayerProfile(playerUuid, playerName, xuid);

        if (lcd != null) {
            store.storeIdentifier("device", lcd.getDeviceId(), playerUuid);
            store.storeIdentifier("clientRandom", String.valueOf(lcd.getClientId()), playerUuid);
            try {
                JsonObject rawData = lcd.getRawData();
                if (rawData != null && rawData.has("SelfSignedId") && !rawData.get("SelfSignedId").isJsonNull())
                    store.storeIdentifier("selfSigned", rawData.get("SelfSignedId").getAsString(), playerUuid);
            } catch (Exception ignored) {}
        }
    }
}
