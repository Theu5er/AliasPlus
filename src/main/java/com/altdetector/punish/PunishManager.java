package com.altdetector.punish;

import com.altdetector.AltDetector;
import com.altdetector.store.PlayerStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 惩罚管理器：punish API 的实现。
 *
 * <p>一次 {@link #punish(String, boolean)} 调用会收集目标玩家及其全部关联小号，
 * 逐个加入惩罚队列；后台任务每 tick 只执行一条到期的惩罚命令（"每次惩罚只处理
 * 一个账号，直到所有都处理完为止"）。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>立即封禁</b>（delayed=false）：账号按入队顺序逐 tick 执行；</li>
 *   <li><b>延迟封禁</b>（delayed=true）：每个账号独立随机延迟在配置区间
 *       （punish-delay-min ~ punish-delay-max 秒）内，到期后执行。</li>
 * </ul>
 *
 * <p>服务器关闭（onDisable）时，所有尚未执行的惩罚（含等待延迟的）同步执行完毕，
 * 不丢失。
 *
 * <p>命令模板（config.yml 的 punish-command）支持占位符：
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
    /** 已在队列中的账号（含待执行与等待延迟），防止重复惩罚。 */
    private final Set<String> pendingUuids = new HashSet<>();

    private static final class PendingPunish {
        final String uuid;
        final String name;
        final long runAtTick;

        PendingPunish(String uuid, String name, long runAtTick) {
            this.uuid = uuid;
            this.name = name;
            this.runAtTick = runAtTick;
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
     * @param delayed    true = 每个账号在配置区间内独立随机延迟后封禁；
     *                   false = 按入队顺序逐 tick 立即封禁
     * @return 本次实际加入惩罚队列的账号数（已在队列中的账号不会重复加入）
     */
    public synchronized int punish(String playerUuid, boolean delayed) {
        if (playerUuid == null || playerUuid.isEmpty()) return 0;
        String target = playerUuid.toLowerCase();

        LinkedHashSet<String> targets = new LinkedHashSet<>();
        targets.add(target);
        targets.addAll(plugin.getRelatedAccounts(target));

        long now = plugin.getServer().getTick();
        int queued = 0;
        for (String uuid : targets) {
            if (!pendingUuids.add(uuid)) continue;
            pending.add(new PendingPunish(uuid, store.displayName(uuid),
                    now + (delayed ? randomDelayTicks() : 0L)));
            queued++;
        }
        return queued;
    }

    /** 服务器即将关闭时调用：同步执行所有等待中的惩罚（含未到期的延迟惩罚），不丢失。 */
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
        long now = plugin.getServer().getTick();
        int idx = -1;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < pending.size(); i++) {
            PendingPunish p = pending.get(i);
            if (p.runAtTick <= now && p.runAtTick < best) {
                best = p.runAtTick;
                idx = i;
            }
        }
        if (idx < 0) return; // 尚无到期账号（延迟惩罚等待中）
        PendingPunish p = pending.remove(idx);
        pendingUuids.remove(p.uuid);
        execute(p);
    }

    private void execute(PendingPunish p) {
        String cmd = plugin.getPunishCommand()
                .replace("%player%", p.name)
                .replace("%uuid%", p.uuid);
        try {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
        } catch (Exception e) {
            plugin.getLogger().warning("执行惩罚命令失败 (" + p.uuid + "): " + e);
        }
    }

    /** 延迟区间随机，单位换算为 tick；配置非法（min>max / 负数）时自动修正。 */
    private long randomDelayTicks() {
        int minSec = Math.max(0, plugin.getPunishDelayMin());
        int maxSec = Math.max(minSec, plugin.getPunishDelayMax());
        int sec = minSec + ThreadLocalRandom.current().nextInt(maxSec - minSec + 1);
        return sec * 20L;
    }
}
