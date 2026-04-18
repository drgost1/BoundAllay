package dev.nafis.boundallay.commands;

import dev.nafis.boundallay.AllayManager;
import dev.nafis.boundallay.AllayStorage;
import dev.nafis.boundallay.AllayType;
import dev.nafis.boundallay.BoundAllayPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class AllayCommand implements CommandExecutor, TabCompleter {

    private static final List<String> USER_SUBS =
            List.of("bind", "summon", "store", "release", "rename", "type", "list", "summonall", "storeall", "info", "help");
    private static final List<String> ADMIN_SUBS = List.of("admin");

    private final BoundAllayPlugin plugin;
    private final AllayManager mgr;

    public AllayCommand(BoundAllayPlugin plugin, AllayManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            return handleAdmin(sender, args);
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only (except /allay admin).");
            return true;
        }

        if (args.length == 0) {
            mgr.openVault(p);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "bind" -> {
                if (!p.hasPermission("boundallay.bind")) { noPermission(p); return true; }
                if (args.length < 2) mgr.bind(p);
                else mgr.bind(p, joinArgs(args, 1));
            }
            case "summon" -> {
                if (!p.hasPermission("boundallay.summon")) { noPermission(p); return true; }
                if (args.length < 2) mgr.summon(p);
                else mgr.summon(p, joinArgs(args, 1));
            }
            case "store" -> {
                if (args.length < 2) mgr.store(p);
                else mgr.store(p, joinArgs(args, 1));
            }
            case "release" -> {
                if (!p.hasPermission("boundallay.release")) { noPermission(p); return true; }
                if (args.length < 2) send(p, "Usage: /allay release <name>", NamedTextColor.RED);
                else mgr.release(p, joinArgs(args, 1));
            }
            case "rename" -> {
                if (!p.hasPermission("boundallay.rename")) { noPermission(p); return true; }
                if (args.length < 3) send(p, "Usage: /allay rename <old-name> <new-name>", NamedTextColor.RED);
                else mgr.rename(p, args[1], joinArgs(args, 2));
            }
            case "type" -> {
                if (!p.hasPermission("boundallay.type")) { noPermission(p); return true; }
                if (args.length < 3) {
                    send(p, "Usage: /allay type <name> <FIGHTER|HEALER|GUARDIAN>", NamedTextColor.RED);
                } else {
                    AllayType type;
                    try {
                        type = AllayType.valueOf(args[2].toUpperCase());
                    } catch (IllegalArgumentException e) {
                        send(p, "Unknown type. Use FIGHTER, HEALER, or GUARDIAN.", NamedTextColor.RED);
                        return true;
                    }
                    mgr.setType(p, args[1], type);
                }
            }
            case "list" -> mgr.showInfo(p);
            case "info" -> {
                if (args.length < 2) mgr.showInfo(p);
                else mgr.showInfo(p, joinArgs(args, 1));
            }
            case "summonall" -> {
                if (!p.hasPermission("boundallay.summon")) { noPermission(p); return true; }
                mgr.summonAll(p);
            }
            case "storeall" -> mgr.storeAll(p);
            case "help" -> help(p);
            case "vault", "menu", "open" -> mgr.openVault(p);
            default -> help(p);
        }
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("boundallay.admin")) {
            if (sender instanceof Player p) noPermission(p);
            else sender.sendMessage("No permission.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Admin: reload, stats, prune <days>, rescan").color(NamedTextColor.AQUA));
            return true;
        }

        switch (args[1].toLowerCase()) {
            case "reload" -> {
                plugin.reloadBoundConfig();
                sender.sendMessage(Component.text("BoundAllay config reloaded.").color(NamedTextColor.GREEN));
                sender.sendMessage(Component.text("Note: tick intervals only change after a full restart.")
                        .color(NamedTextColor.GRAY));
            }
            case "stats" -> {
                AllayStorage storage = mgr.getStorage();
                sender.sendMessage(Component.text("--- BoundAllay Stats ---").color(NamedTextColor.AQUA));
                sender.sendMessage(Component.text("Players with allays: " + storage.playerCount()).color(NamedTextColor.WHITE));
                sender.sendMessage(Component.text("Total allay records: " + storage.size()).color(NamedTextColor.WHITE));
            }
            case "prune" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Usage: /allay admin prune <days>").color(NamedTextColor.RED));
                    return true;
                }
                try {
                    int days = Integer.parseInt(args[2]);
                    long ms = days * 86_400_000L;
                    int pruned = mgr.getStorage().pruneOlderThan(ms);
                    sender.sendMessage(Component.text("Pruned " + pruned + " allays older than " + days + " days.")
                            .color(NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Invalid number.").color(NamedTextColor.RED));
                }
            }
            case "rescan" -> {
                mgr.scanAndRestoreActive();
                sender.sendMessage(Component.text("Entity scan complete. Check server log.").color(NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Unknown. Use: reload, stats, prune, rescan").color(NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (String s : USER_SUBS) if (s.startsWith(prefix)) out.add(s);
            if (sender.hasPermission("boundallay.admin")) {
                for (String s : ADMIN_SUBS) if (s.startsWith(prefix)) out.add(s);
            }
            return out;
        }

        if (args.length == 2 && sender instanceof Player p) {
            String sub = args[0].toLowerCase();
            String prefix = args[1].toLowerCase();

            if (sub.equals("summon") || sub.equals("store") || sub.equals("release")
                    || sub.equals("rename") || sub.equals("info") || sub.equals("type")) {
                List<String> names = mgr.listAllayIds(p.getUniqueId());
                List<String> out = new ArrayList<>();
                for (String name : names) {
                    if (name.toLowerCase().startsWith(prefix)) out.add(name);
                }
                return out;
            }

            if (sub.equals("admin")) {
                List<String> adminSubs = List.of("reload", "stats", "prune", "rescan");
                List<String> out = new ArrayList<>();
                for (String s : adminSubs) if (s.startsWith(prefix)) out.add(s);
                return out;
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("type")) {
            String prefix = args[2].toLowerCase();
            List<String> out = new ArrayList<>();
            for (AllayType t : AllayType.values()) {
                if (t.name().toLowerCase().startsWith(prefix)) out.add(t.name());
            }
            return out;
        }

        return List.of();
    }

    private void help(Player p) {
        send(p, "--- BoundAllay v4 ---", NamedTextColor.AQUA);
        send(p, "/allay                - open vault menu", NamedTextColor.GRAY);
        send(p, "/allay bind <name>    - bind the nearest wild allay", NamedTextColor.GRAY);
        send(p, "/allay summon [name]  - spawn your allay", NamedTextColor.GRAY);
        send(p, "/allay store [name]   - store a summoned allay", NamedTextColor.GRAY);
        send(p, "/allay type <name> <FIGHTER|HEALER|GUARDIAN>", NamedTextColor.GRAY);
        send(p, "/allay release <name> - permanently unbind", NamedTextColor.GRAY);
        send(p, "/allay rename <old> <new>", NamedTextColor.GRAY);
        send(p, "/allay list           - list all your allays", NamedTextColor.GRAY);
        send(p, "/allay summonall / storeall", NamedTextColor.GRAY);
        if (p.hasPermission("boundallay.admin")) {
            send(p, "/allay admin reload|stats|prune|rescan", NamedTextColor.DARK_AQUA);
        }
    }

    private static void send(Player p, String s, NamedTextColor c) {
        p.sendMessage(Component.text(s).color(c));
    }

    private static void noPermission(Player p) {
        p.sendMessage(Component.text("You lack permission.").color(NamedTextColor.RED));
    }

    private static String joinArgs(String[] args, int startIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            if (i > startIndex) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
