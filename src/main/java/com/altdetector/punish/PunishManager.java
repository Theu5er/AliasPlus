package com.altdetector.punish;

import com.altdetector.AltDetector;
import com.altdetector.store.PlayerStore;

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

    private final AltDetector plugin;
    private final PlayerStore store;

    /** 待执行惩罚队列（主线程访问；tick 任务每次最多执行一条）。 */
    private final List<PendingPunish> pending = new ArrayList<>();
    /** 已在队列中的账号，防止重复惩罚。 */
    private final Set<String> pendingUuids = new HashSet<>();

    private static final class PendingPunish {
        final String uuid;
        final String name;

        PendingPunish(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
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
        if (playerUuid == null || playerUuid.isEmpty()) return 0;
        String target = playerUuid.toLowerCase();

        LinkedHashSet<String> targets = new LinkedHashSet<>();
        targets.add(target);
        targets.addAll(plugin.getRelatedAccounts(target));

        int queued = 0;
        for (String uuid : targets) {
            if (!pendingUuids.add(uuid)) continue;
            pending.add(new PendingPunish(uuid, store.displayName(uuid)));
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
            execute(p);
        }
    }

    private void tick() {
        if (pending.isEmpty()) return;
        PendingPunish p = pending.remove(0);
        pendingUuids.remove(p.uuid);
        execute(p);
    }

    private void execute(PendingPunish p) {
        String cmd = plugin.getPunishCommand()
                .replace("%player%", p.name)
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
    }

    /**
     * 该账号是否已被惩罚过：本插件执行过惩罚命令（Punished 标记），或
     * 服务器封禁列表里已有其当前玩家名（兼容管理员手动 /ban 的账号）。
     */
    public boolean isPunished(String playerUuid) {
        if (playerUuid == null || playerUuid.isEmpty()) return false;
        String uuid = playerUuid.toLowerCase();
        if (store.isPunishedFlagged(uuid)) return true;
        String name = store.displayName(uuid);
        if (name == null || name.isEmpty() || name.equals(uuid)) return false;
        try {
            return plugin.getServer().getNameBans().isBanned(name);
        } catch (Exception e) {
            return false;
        }
    }

}
