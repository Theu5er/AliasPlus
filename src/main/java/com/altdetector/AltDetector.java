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
import com.altdetector.net.NetworkIdDetector;
import com.altdetector.pack.PackDetector;
import com.altdetector.punish.PunishManager;
import com.altdetector.store.PlayerStore;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AltDetector —— 多维度小号关联检测插件。
 *
 * <p>整合四种独立检测维度，任一命中即可关联：
 * <ol>
 *   <li><b>登录标识符</b>：device id / selfSigned id / clientRandom id（同一设备换号必然相同）</li>
 *   <li><b>NetworkID</b>：NetherNet 传输层对端标识，客户端信令自报、跨会话大概率稳定
 *       （{@link NetworkIdDetector}）</li>
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
    private NetworkIdDetector networkIdDetector;
    private PackDetector packDetector;
    private BlobDetector blobDetector;
    private PunishManager punishManager;

    /* ================================================================== */
    /*  生命周期                                                           */
    /* ================================================================== */

    @Override
    public void onEnable() {
        this.config = new Config(new File(this.getDataFolder(), "config.yml"), Config.YAML);
        Config playersConfig = new Config(new File(this.getDataFolder(), "players.yml"), Config.YAML);

        if (!config.exists("packcache-enabled")) config.set("packcache-enabled", true);
        if (!config.exists("blobcache-enabled")) config.set("blobcache-enabled", true);
        if (!config.exists("networkid-enabled")) config.set("networkid-enabled", true);
        if (!config.exists("strictmode")) config.set("strictmode", true);
        if (!config.exists("punish-command")) config.set("punish-command", "ban %player% Alt account detected");
        if (!config.exists("punish-delay-min")) config.set("punish-delay-min", 5);
        if (!config.exists("punish-delay-max")) config.set("punish-delay-max", 30);
        config.save();

        if (!playersConfig.exists("players")) playersConfig.set("players", new LinkedHashMap<>());
        playersConfig.save();

        this.store = new PlayerStore(playersConfig);
        this.store.migrateLegacyStorage();
        this.store.normalizePlayers();

        this.blobDetector = new BlobDetector(this, store);
        this.blobDetector.loadData();
        this.blobDetector.registerPackets();

        this.networkIdDetector = new NetworkIdDetector(this, store);

        this.packDetector = new PackDetector(this, store);

        this.punishManager = new PunishManager(this, store);
        this.punishManager.start();

        this.getServer().getPluginManager().registerEvents(this, this);
        this.getServer().getPluginManager().registerEvents(this.packDetector, this);
        this.getServer().getPluginManager().registerEvents(this.blobDetector, this);
        this.getServer().getPluginManager().registerEvents(this.networkIdDetector, this);

        this.getServer().getCommandMap().register("alias", new AliasCommand(this, store));
    }

    @Override
    public void onDisable() {
        // 服务器即将关闭：同步执行所有等待中的惩罚（含未到期的延迟惩罚），不丢失
        if (this.punishManager != null) this.punishManager.flushAll();
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

    /**
     * NetworkID 维度开关：false 时登录不捕获 NetworkID（config.yml 的 networkid-enabled）。
     * 关闭后 /alias 的 NetworkID 行因无数据自然为空，历史数据保留不清除。
     */
    public boolean networkIdEnabled() {
        return this.config.getBoolean("networkid-enabled", true);
    }

    /**
     * 关联判定方向：true（默认）= 单向（只展示"谁缓存/命中了目标的指纹"），
     * false = 双向（额外展示"目标缓存/命中了谁的指纹"）。
     * 注意：双向模式下伪造的 claim 会同时出现在两边，punish 连坐可被用于定向误封。
     */
    public boolean strictMode() {
        return this.config.getBoolean("strictmode", true);
    }

    /* ================================================================== */
    /*  惩罚配置（供 PunishManager 查询）                                   */
    /* ================================================================== */

    /** punish-command 命令模板，支持 %player% / %uuid% 占位符。 */
    public String getPunishCommand() {
        String cmd = this.config.getString("punish-command", "ban %player% Alt account detected").trim();
        return cmd.isEmpty() ? "ban %player%" : cmd;
    }

    /** 延迟封禁最小间隔（秒）。 */
    public int getPunishDelayMin() {
        return this.config.getInt("punish-delay-min", 5);
    }

    /** 延迟封禁最大间隔（秒）。 */
    public int getPunishDelayMax() {
        return this.config.getInt("punish-delay-max", 30);
    }

    /* ================================================================== */
    /*  开发者 API                                                        */
    /* ================================================================== */

    /**
     * 查询目标玩家各维度的关联账号（单向结果；/alias 的 ResPackCache 与 BlobCache 两行
     * 展示的是方向相反的"主账号"，见 {@link #getPackMains} / {@link #getBlobMains}）。
     *
     * @param targetUuid 目标玩家 uuid（字符串形式；uuid 大小写不敏感，按存储键精确匹配）
     * @return 维度 → 关联账号 uuid 集合；维度固定为 device / selfSigned / clientRandom /
     *         networkid / pack / blob 六类，集合不含目标自身，目标无记录时各集合为空
     */
    public Map<String, Set<String>> getRelatedByDimension(String targetUuid) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> ids = store.getIdentifiers();
        // networkid 与前三者同为"账号自身携带的标识符"（而非可被定向伪造的 claim），
        // 同值即关联；RakNet 玩家无此维度，数据稀疏属正常
        for (String type : new String[]{"device", "selfSigned", "clientRandom", "networkid"}) {
            Set<String> matches = new LinkedHashSet<>();
            for (List<String> group : ids.getOrDefault(type, new LinkedHashMap<>()).values()) {
                if (group.contains(targetUuid)) {
                    for (String other : group) if (!other.equals(targetUuid)) matches.add(other);
                }
            }
            result.put(type, matches);
        }

        // pack：单向 = 谁缓存了 target 的指纹（claimed_by 以 target 为键）；
        // strictmode=false 时额外加上反方向（target 缓存了谁的指纹）
        Set<String> packMatches = new LinkedHashSet<>();
        for (String c : store.getPackClaims().getOrDefault(targetUuid, new ArrayList<>()))
            if (!c.equals(targetUuid)) packMatches.add(c);
        if (!strictMode()) {
            for (Map.Entry<String, List<String>> e : store.getPackClaims().entrySet()) {
                if (e.getValue().contains(targetUuid) && !e.getKey().equals(targetUuid))
                    packMatches.add(e.getKey());
            }
        }
        result.put("pack", packMatches);

        // blob：单向关联（谁命中了它的指纹），与 pack 一致
        result.put("blob", new LinkedHashSet<>(blobDetector.getBlobRelated(targetUuid)));
        return result;
    }

    /**
     * 开发者 API：返回 /alias 结果中全部关联小号的 uuid 并集。
     * 即 /alias 查询该玩家时会显示的所有关联账号（device / selfSigned / clientRandom /
     * 资源包指纹 / blob 指纹任一维度命中），不含目标自身。
     *
     * @param targetUuid 目标玩家 uuid（字符串形式）
     * @return 关联账号 uuid 集合，无任何关联时为空集合；目标 uuid 不存在时同样返回空集合
     */
    public Set<String> getRelatedAccounts(String targetUuid) {
        Set<String> all = new LinkedHashSet<>();
        if (targetUuid == null || targetUuid.isEmpty()) return all;
        for (Set<String> s : getRelatedByDimension(targetUuid).values()) all.addAll(s);
        return all;
    }

    /**
     * /alias 展示辅助：目标玩家的 Blob 维度"主账号"——目标设备的 blob 缓存命中过的
     * 账号（目标命中了谁的指纹）。与 {@link #getRelatedByDimension} 的 blob 维度方向
     * 相反，仅供 /alias 展示使用，不影响查询 API 的返回结果。
     */
    public Set<String> getBlobMains(String targetUuid) {
        return this.blobDetector == null ? new LinkedHashSet<>() : this.blobDetector.getBlobOwners(targetUuid);
    }

    /**
     * /alias 展示辅助：目标玩家的 Pack 维度"主账号"——目标客户端缓存命中过的账号
     * （目标缓存了谁的资源包指纹）。与 {@link #getRelatedByDimension} 的 pack 维度
     * 方向相反，仅供 /alias 展示使用，不影响查询 API 的返回结果。
     */
    public Set<String> getPackMains(String targetUuid) {
        Set<String> mains = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> e : store.getPackClaims().entrySet()) {
            if (e.getValue().contains(targetUuid) && !e.getKey().equals(targetUuid)) {
                mains.add(e.getKey());
            }
        }
        return mains;
    }

    /**
     * 开发者 API：惩罚目标玩家及其全部关联小号（关联结果与 /alias 完全一致）。
     *
     * <p>执行方式由 config.yml 决定：
     * <ul>
     *   <li>{@code punish-command} —— 惩罚命令模板，占位符 {@code %player%}（玩家名）、
     *       {@code %uuid%}（玩家 uuid），以控制台身份执行；</li>
     *   <li>{@code punish-delay-min} / {@code punish-delay-max} —— 延迟封禁的随机区间（秒）。</li>
     * </ul>
     *
     * <p>队列每 tick 只执行一条惩罚命令，直到全部处理完毕；服务器关闭时（onDisable）
     * 所有尚未执行的惩罚（含等待延迟的）会同步执行完毕，不丢失。同一账号重复调用不会
     * 重复入队。
     *
     * @param playerUuid 目标玩家 uuid（字符串形式）
     * @param delayed    true = 每个账号在配置区间内独立随机延迟后封禁；
     *                   false = 按入队顺序逐 tick 立即封禁
     * @return 本次实际加入惩罚队列的账号数（目标 + 关联小号，去重后）
     */
    public int punish(String playerUuid, boolean delayed) {
        return this.punishManager == null ? 0 : this.punishManager.punish(playerUuid, delayed);
    }

    /**
     * 开发者 API：该账号是否已被惩罚过（本插件执行过惩罚命令，或已在服务器封禁列表）。
     * 供"新账号命中已惩罚账号的指纹时自动惩罚"等扩展逻辑使用。
     */
    public boolean isPunished(String playerUuid) {
        return this.punishManager != null && this.punishManager.isPunished(playerUuid);
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
