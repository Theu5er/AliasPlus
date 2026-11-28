package com.altdetector.punish;

import com.altdetector.AltDetector;
import com.altdetector.store.PlayerStore;
import com.altdetector.util.Uuids;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 惩罚管理器：punish API 的实现。
 *
 * <p>一次 {@link #punish(String)} 调用会收集目标玩家及其全部关联小号，
 * 逐个加入惩罚队列；后台任务每 tick 只执行一条惩罚命令（"每次惩罚只处理
 * 一个账号，直到所有都处理完为止"）。
 *
 * <p>服务器关闭（onDisable）时，所有尚未执行的惩罚同步执行完毕，不丢失。
 *
 * <p>命令模板（config.yml 的 punish.command）支持占位符：
 * <ul>
 *   <li>{@code %player%} —— 玩家名（以控制台身份执行）</li>
 *   <li>{@code %uuid%} —— 玩家 uuid</li>
 * </ul>
 */
public class PunishManager {

    /** 封禁来源：自动检测项 + 开发者手动调用。 */
    public static final String SOURCE_ID_FORMAT = "IdFormat";
    public static final String SOURCE_RESOURCE_PACK_SPOOF = "ResourcePackSpoof";
    public static final String SOURCE_BLOB_CACHE = "BlobCache";
    public static final String SOURCE_MANUAL = "Manual";

    private final AltDetector plugin;
    private final PlayerStore store;

    /** 待执行惩罚队列（主线程访问；tick 任务每次最多执行一条）。 */
    private final List<PendingPunish> pending = new ArrayList<>();
    /** 已在队列中的账号，防止重复惩罚。 */
    private final Set<String> pendingUuids = new HashSet<>();

    private static final class PendingPunish {
        final String uuid;
        final String name;
        /** 触发来源，统计用；仅在实际封禁成功后落库。 */
        final String source;
        /** 株连时指向被抓的那个人；直接封禁为 null。 */
        final String trigger;

        PendingPunish(String uuid, String name, String source, String trigger) {
            this.uuid = uuid;
            this.name = name;
            this.source = source;
            this.trigger = trigger;
        }
    }

    public PunishManager(AltDetector plugin, PlayerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /** 启动后台消费任务：每 tick 检查一次，每次最多执行一条到期惩罚。 */
    public void start() {
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::tick, 1);
    }

    /**
     * 惩罚目标玩家及其全部关联小号（/alias 同款关联结果）。
     *
     * @param playerUuid 目标玩家 uuid（字符串形式）
     * @return 本次实际加入惩罚队列的账号数（已在队列中的账号不会重复加入）
     */
    public synchronized int punish(String playerUuid) {
        return punish(playerUuid, SOURCE_MANUAL);
    }

    /**
     * 同上，并带上这次封禁的来源维度。
     *
     * <p>来源只在封禁命令真正执行成功后才落库（见 {@link #execute}），
     * 因此命令失败或该账号已在队列中而跳过时，不会产生统计记录。
     * 一个账号只归到第一个封掉它的来源，所以统计各行相加等于被封账号数。
     *
     * @param source 触发来源，取本类 SOURCE_* 常量
     */
    public synchronized int punish(String playerUuid, String source) {
        if (playerUuid == null || playerUuid.isEmpty()) return 0;
        String target = Uuids.normalize(playerUuid);
        if (target.isEmpty()) return 0;
        String dim = source == null || source.isEmpty() ? SOURCE_MANUAL : source;

        LinkedHashSet<String> targets = new LinkedHashSet<>();
        targets.add(target);
        targets.addAll(plugin.getRelatedAccounts(target));

        int queued = 0;
        for (String uuid : targets) {
            String name = store.displayName(uuid);
            // 统计记录推迟到命令真正执行成功之后，避免"命令没成功却计入封禁数"
            String trigger = uuid.equals(target) ? null : target;
            if (!pendingUuids.add(uuid)) continue;
            pending.add(new PendingPunish(uuid, name, dim, trigger));
            queued++;
        }
        return queued;
    }

    /** 服务器即将关闭时调用：同步执行所有仍在队列中的惩罚，不丢失。 */
    public synchronized void flushAll() {
        List<PendingPunish> all = new ArrayList<>(pending);
        pending.clear();
        for (PendingPunish p : all) {
            pendingUuids.remove(p.uuid);
            try {
                execute(p);
            } catch (RuntimeException e) {
                // 关服兜底：一条失败不能连累后面还没执行的
                plugin.getLogger().warning("Punish flush failed for " + p.uuid + ": " + e.getMessage());
            }
        }
    }

    private synchronized void tick() {
        if (pending.isEmpty()) return;
        PendingPunish p = pending.remove(0);
        pendingUuids.remove(p.uuid);
        execute(p);
    }

    private void execute(PendingPunish p) {
        String safeName = safePlayerName(p.name);
        if (safeName == null) {
            plugin.getLogger().warning("Punish skipped for " + p.uuid
                    + ": player name contains characters command parsers split on: " + p.name);
            return;
        }
        String cmd = plugin.getPunishCommand()
                .replace("%player%", safeName)
                .replace("%uuid%", store.uuidOf(p.uuid));
        boolean ok;
        try {
            ok = plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
        } catch (Exception e) {
            plugin.getLogger().warning("Punish command failed (" + p.uuid + "): " + e);
            return;
        }
        if (!ok) {
            plugin.getLogger().warning("Punish command not found: " + cmd);
            return;
        }
        // 命令真的执行了才打标记：之后该账号的指纹被新账号命中时，可自动惩罚新账号
        store.markPunished(p.uuid);
        try {
            store.recordBan(p.uuid, p.name, p.source, p.trigger);
        } catch (RuntimeException e) {
            // 统计写失败不能连累已经完成的封禁
            plugin.getLogger().warning("Failed to record ban for " + p.uuid + ": " + e.getMessage());
        }
    }

    /**
     * 该账号是否已被惩罚过：本插件执行过惩罚命令（Punished 标记），或
     * 服务器封禁列表里已有其当前玩家名（兼容管理员手动 /ban 的账号）。
     */
    public boolean isPunished(String playerUuid) {
        if (playerUuid == null || playerUuid.isEmpty()) return false;
        String uuid = Uuids.normalize(playerUuid);
        if (store.isPunishedFlagged(uuid)) return true;
        String name = store.displayName(uuid);
        if (name == null || name.isEmpty() || name.equals(uuid)) return false;
        try {
            return plugin.getServer().getNameBans().isBanned(name);
        } catch (Exception e) {
            return false;
        }
    }


    /** 只接受玩家名校验允许的字符；含空格等分词字符时返回 null（跳过本次惩罚，避免封错人）。 */
    private static String safePlayerName(String name) {
        if (name == null || name.isEmpty()) return null;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return null;
        }
        return name;
    }
}
