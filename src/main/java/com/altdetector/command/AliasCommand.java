package com.altdetector.command;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.TextFormat;
import com.altdetector.AltDetector;
import com.altdetector.store.PlayerStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /alias（/al）命令：按玩家名 / uuid / xuid 查询全部维度的小号关联结果，
 * 直接展示已持久化的数据（BlobCache 由进服自动探测落盘，无需查询时触发）。
 */
public class AliasCommand extends Command {

    private final AltDetector plugin;
    private final PlayerStore store;

    public AliasCommand(AltDetector plugin, PlayerStore store) {
        super("alias", "Query a player's linked alt accounts", "/alias <player/uuid/xuid>", new String[]{"al"});
        this.setPermission("altdetector.alias"); // plugin.yml 中 default: op，op 默认拥有
        this.plugin = plugin;
        this.store = store;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!this.testPermission(sender)) return true;
        if (args.length < 1) {
            sender.sendMessage(TextFormat.YELLOW + "用法:");
            sender.sendMessage(TextFormat.GRAY + "  /alias <玩家名/uuid/xuid>");
            return true;
        }

        String target = args[0];

        List<String> targetUuids = new ArrayList<>();
        if (target.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            targetUuids.add(target.toLowerCase());
        } else {
            for (Map.Entry<String, Map<String, Object>> e : store.getPlayers().entrySet()) {
                String uuid = e.getKey();
                if (targetUuids.contains(uuid)) continue;
                Map<String, Object> info = e.getValue();
                Object xuidObj = info.get("xuid");
                if (xuidObj != null && String.valueOf(xuidObj).equalsIgnoreCase(target)) {
                    targetUuids.add(uuid);
                    continue;
                }
                Object aliases = info.get("aliases");
                if (aliases instanceof List) {
                    for (Object a : (List<?>) aliases) {
                        if (String.valueOf(a).equalsIgnoreCase(target)) {
                            targetUuids.add(uuid);
                            break;
                        }
                    }
                }
            }
        }

        if (targetUuids.isEmpty()) {
            sender.sendMessage(TextFormat.RED + "Player not found " + target);
            return true;
        }

        store.reload();

        boolean first = true;
        for (String uuid : targetUuids) {
            if (!first) sender.sendMessage("");
            first = false;
            showAliasResult(sender, uuid);
        }
        return true;
    }

    private void showAliasResult(CommandSender sender, String targetUuid) {
        Map<String, Map<String, Object>> players = store.getPlayers();
        Map<String, Object> targetInfo = players.get(targetUuid);
        String targetName = targetInfo != null ? String.valueOf(targetInfo.get("name")) : targetUuid;
        String display = store.getDisplayIdentifier(targetUuid);
        boolean spoof = store.isSpoofFlagged(targetUuid);

        // 与开发者 API（AltDetector#getRelatedAccounts）共用同一查询入口，保证 /alias 输出与 API 结果一致
        Map<String, Set<String>> related = plugin.getRelatedByDimension(targetUuid);
        Set<String> deviceMatches = related.get("device");
        Set<String> selfMatches = related.get("selfSigned");
        Set<String> clientMatches = related.get("clientRandom");
        Set<String> networkMatches = related.get("networkid");
        // ResPackCache 与 BlobCache 两行展示"主账号"（目标命中了谁的指纹，即它挂在谁名下），
        // 与查询 API（getRelatedByDimension 的单向 pack/blob）方向不同，后者保持不变
        Set<String> packMatches = plugin.getPackMains(targetUuid);
        Set<String> blobRelated = plugin.getBlobMains(targetUuid);

        sender.sendMessage("§l--" + targetName + "'s accounts (" + display + ")--");

        boolean hasAny = !deviceMatches.isEmpty() || !selfMatches.isEmpty()
                || !clientMatches.isEmpty() || !networkMatches.isEmpty() || !packMatches.isEmpty()
                || !blobRelated.isEmpty() || spoof;

        if (hasAny) {
            if (!deviceMatches.isEmpty())
                sender.sendMessage("§l§cDeviceId: §r§7" + formatUuids(deviceMatches));
            if (!selfMatches.isEmpty())
                sender.sendMessage("§l§cSelfSignedId: §r§7" + formatUuids(selfMatches));
            if (!clientMatches.isEmpty())
                sender.sendMessage("§l§cClientRandomId: §r§7" + formatUuids(clientMatches));
            if (!networkMatches.isEmpty())
                sender.sendMessage("§l§cNetworkID: §r§7" + formatUuids(networkMatches));
            if (!packMatches.isEmpty())
                sender.sendMessage("§l§cResPackCache: §r§7" + formatUuids(packMatches));
            if (!blobRelated.isEmpty())
                sender.sendMessage("§l§cBlobCache: §r§7" + formatUuids(blobRelated));
            if (spoof)
                sender.sendMessage("§l§cResourcePackSpoof detected");
        } else {
            sender.sendMessage("§l§cNothing found :(");
        }
    }

    private String formatUuids(Set<String> uuids) {
        Map<String, Integer> nameDup = store.buildNameCollisionMap();
        List<String> list = new ArrayList<>();
        for (String uuid : uuids) {
            Map<String, Object> info = store.getPlayers().get(uuid);
            String name = info != null ? String.valueOf(info.get("name")) : uuid;
            // 无重名：只显示名字提升可读性；全服同名（忽略大小写）账号 >1 时附带 xuid/uuid 消歧
            if (info != null && nameDup.getOrDefault(name.toLowerCase(), 0) > 1)
                list.add(name + " (" + store.getDisplayIdentifier(uuid) + ")");
            else
                list.add(name);
        }
        return String.join(", ", list);
    }
}
