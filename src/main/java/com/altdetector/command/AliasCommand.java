package com.altdetector.command;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.TextFormat;
import com.altdetector.AltDetector;
import com.altdetector.util.Uuids;
import com.altdetector.store.PlayerStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /alias（/al）命令：按玩家名 / uuid 查询全部维度的小号关联结果，
 * 直接展示已持久化的数据（BlobCache 由进服自动探测落盘，无需查询时触发）。
 */
public class AliasCommand extends Command {

    private final AltDetector plugin;
    private final PlayerStore store;

    public AliasCommand(AltDetector plugin, PlayerStore store) {
        super("alias", "Query a player's linked alt accounts",
                "/alias <player/uuid> | /alias -stats | /alias -reload", new String[]{"al"});
        this.setPermission("altdetector.alias"); // plugin.yml 中 default: op，op 默认拥有
        this.plugin = plugin;
        this.store = store;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!this.testPermission(sender)) return true;
        if (args.length < 1) {
            sender.sendMessage(TextFormat.YELLOW + "Usage:");
            sender.sendMessage(TextFormat.GRAY + "  /alias <player/uuid>");
            sender.sendMessage(TextFormat.GRAY + "  /alias -stats    ban statistics");
            sender.sendMessage(TextFormat.GRAY + "  /alias -reload   reload config.yml");
            return true;
        }

        // 子命令统一带 - 前缀：Minecraft 名字只允许字母数字下划线，不会以 - 开头，
        // 因此 /alias -stats 与名为 stats 的玩家可以共存，互不干扰
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        if ("-stats".equals(sub) || "-reload".equals(sub)) {
            if (!sender.hasPermission("altdetector.admin")) {
                sender.sendMessage(TextFormat.RED + "You don't have permission to do that.");
                return true;
            }
            if ("-stats".equals(sub)) {
                this.showStats(sender);
            } else {
                this.reloadConfiguration(sender);
            }
            return true;
        }

        String target = args[0];
        String wanted = Uuids.normalize(target);

        // 先刷新磁盘再解析：宁可多读一次盘，也不要读到旧副本

        List<String> targetKeys = new ArrayList<>();
        Map<String, Map<String, Object>> players = store.getPlayers();
        // 1) 参数就是存储键（玩家 uuid）
        if (players.containsKey(wanted)) targetKeys.add(wanted);
        // 2) 退化匹配：记录的 uuid 字段、历史名字——改名或老数据下仍能查到
        for (Map.Entry<String, Map<String, Object>> e : players.entrySet()) {
            String key = e.getKey();
            if (targetKeys.contains(key)) continue;
            Map<String, Object> info = e.getValue();
            if (wanted.equals(Uuids.normalize(stringOf(info.get("uuid"))))) {
                targetKeys.add(key);
                continue;
            }
            Object aliases = info.get("aliases");
            if (aliases instanceof List) {
                for (Object a : (List<?>) aliases) {
                    if (String.valueOf(a).equalsIgnoreCase(target)) {
                        targetKeys.add(key);
                        break;
                    }
                }
            }
        }

        if (targetKeys.isEmpty()) {
            sender.sendMessage(TextFormat.RED + "Player not found " + target);
            if ("stats".equalsIgnoreCase(target) || "reload".equalsIgnoreCase(target)) {
                sender.sendMessage(TextFormat.GRAY + "Looking for the statistics/reload command? Use /alias -"
                        + target.toLowerCase(java.util.Locale.ROOT));
            }
            return true;
        }

        boolean first = true;
        for (String key : targetKeys) {
            if (!first) sender.sendMessage("");
            first = false;
            showAliasResult(sender, key);
        }
        return true;
    }

    private static String stringOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private void showAliasResult(CommandSender sender, String targetUuid) {
        Map<String, Object> targetInfo = store.getPlayer(targetUuid);
        String targetName = targetInfo != null ? String.valueOf(targetInfo.get("name")) : targetUuid;
        String display = store.getDisplayIdentifier(targetUuid);

        // 与开发者 API（AltDetector#getRelatedAccounts）共用同一查询入口，保证 /alias 输出与 API 结果一致
        Map<String, Set<String>> related = plugin.getRelatedByDimension(targetUuid);
        Set<String> deviceMatches = related.get("DeviceId");
        Set<String> selfMatches = related.get("SelfSignedId");
        Set<String> clientMatches = related.get("ClientRandomId");
        Set<String> networkMatches = related.get("NetworkID");
        // ResourcePackCache 与 BlobCache 两行都展示"主账号"（目标命中了谁的指纹，即它挂在谁
        // 名下），与查询 API（getRelatedByDimension 的单向 pack/blob）方向不同，后者保持不变
        Set<String> packMatches = plugin.getPackMains(targetUuid);
        Set<String> blobRelated = plugin.getBlobMains(targetUuid);

        sender.sendMessage("§l--" + targetName + "'s accounts (" + display + ")--");

        boolean hasAny = !deviceMatches.isEmpty() || !selfMatches.isEmpty()
                || !clientMatches.isEmpty() || !networkMatches.isEmpty() || !packMatches.isEmpty()
                || !blobRelated.isEmpty();

        if (hasAny) {
            // 一次取全量快照给下面几行复用，避免每个关联账号各查一遍
            Map<String, Map<String, Object>> players = store.getPlayers();
            Map<String, Integer> nameDup = store.buildNameCollisionMap();
            if (!deviceMatches.isEmpty())
                sender.sendMessage("§l§cDeviceId: §r§7" + formatUuids(deviceMatches, nameDup, players));
            if (!selfMatches.isEmpty())
                sender.sendMessage("§l§cSelfSignedId: §r§7" + formatUuids(selfMatches, nameDup, players));
            if (!clientMatches.isEmpty())
                sender.sendMessage("§l§cClientRandomId: §r§7" + formatUuids(clientMatches, nameDup, players));
            if (!networkMatches.isEmpty())
                sender.sendMessage("§l§cNetworkID: §r§7" + formatUuids(networkMatches, nameDup, players));
            if (!packMatches.isEmpty())
                sender.sendMessage("§l§cResourcePackCache by §r§7" + formatUuids(packMatches, nameDup, players));
            if (!blobRelated.isEmpty())
                sender.sendMessage("§l§cBlobCache by §r§7" + formatUuids(blobRelated, nameDup, players));
        } else {
            sender.sendMessage("§l§cNothing found :(");
        }
    }

    /** 封禁统计：被连坐封禁的小号数量，以及这些小号各自带了哪些检测维度。 */
    private void showStats(CommandSender sender) {
        int alts = this.store.countRelatedBans();

        sender.sendMessage(TextFormat.GOLD + "" + TextFormat.BOLD + "===== Ban statistics =====");
        sender.sendMessage(TextFormat.YELLOW + "Banned alts: " + TextFormat.WHITE + alts);
        sender.sendMessage(TextFormat.YELLOW + "Banned by dimension:");
        // 顺序由存储层负责（与 config.yml 的 checks 段一致），这里不再维护第二份列表
        for (Map.Entry<String, Integer> entry : this.store.countAltDimensions().entrySet()) {
            sender.sendMessage(TextFormat.GRAY + "  " + pad(entry.getKey(), 20) + entry.getValue());
        }
    }

    /** 配置重载：包名/图标/天数/开关立即生效。 */
    private void reloadConfiguration(CommandSender sender) {
        if (this.plugin.reloadConfiguration()) {
            sender.sendMessage(TextFormat.GREEN + "Config reloaded.");
        } else {
            sender.sendMessage(TextFormat.RED + "Reload failed, see console for details.");
        }
    }

    /** 简易左对齐，避免 tty 里数字错位。 */
    private static String pad(String text, int width) {
        if (text.length() >= width) return text + " ";
        StringBuilder builder = new StringBuilder(text);
        while (builder.length() < width) builder.append(' ');
        return builder.toString();
    }

    private String formatUuids(Set<String> uuids, Map<String, Integer> nameDup,
                               Map<String, Map<String, Object>> players) {
        List<String> list = new ArrayList<>();
        for (String uuid : uuids) {
            Map<String, Object> info = players.get(uuid);
            String name = info != null ? String.valueOf(info.get("name")) : uuid;
            // 无重名：只显示名字提升可读性；全服同名（忽略大小写）账号 >1 时附带 uuid 消歧
            if (info != null && nameDup.getOrDefault(name.toLowerCase(), 0) > 1)
                list.add(name + " (" + store.getDisplayIdentifier(uuid) + ")");
            else
                list.add(name);
        }
        return String.join(", ", list);
    }
}
