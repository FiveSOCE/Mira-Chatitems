package gg.mira.chatitems;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class MiraChatItemsPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private MiraCore core;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = MiraCoreProvider.require();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("chatitem");
        if (command == null) throw new IllegalStateException("chatitem command missing");
        command.setExecutor(this);
        getServer().getScheduler().runTaskTimer(this, this::cleanup, 20L * 60L, 20L * 60L);
        core.modules().register(this, "MiraChatItems");
    }

    @Override
    public void onDisable() {
        snapshots.clear();
        if (core != null) core.modules().unregister(this);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (!containsToken(raw)) return;

        Player player = event.getPlayer();
        Component transformed;

        // Inventory/item access must stay on the primary thread even though
        // Paper's chat event is normally asynchronous.
        if (Bukkit.isPrimaryThread()) {
            transformed = transformMessage(player, raw);
        } else {
            CompletableFuture<Component> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    future.complete(transformMessage(player, raw));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            try {
                transformed = future.join();
            } catch (RuntimeException exception) {
                getLogger().warning("Could not transform chat link safely: " + exception.getMessage());
                return;
            }
        }

        // Critical: do not replace/cancel/resend the chat line. Wrap whatever
        // renderer is already responsible for ranks, prefixes, nicknames,
        // suffixes and the normal ': message' format, and only substitute the
        // message component that renderer receives.
        ChatRenderer original = event.renderer();
        event.renderer((source, sourceDisplayName, ignoredMessage, viewer) ->
                original.render(source, sourceDisplayName, transformed, viewer));
    }

    private Component transformMessage(Player player, String raw) {
        Component message = Component.empty();

        int cursor = 0;
        while (cursor < raw.length()) {
            TokenMatch match = nextToken(raw, cursor);
            if (match == null) {
                message = message.append(Component.text(raw.substring(cursor)));
                break;
            }
            if (match.start() > cursor) message = message.append(Component.text(raw.substring(cursor, match.start())));

            if (match.type() == Type.ITEM) {
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getType().isAir()) {
                    core.messages().send(player, "&cYou are not holding an item to link.");
                    message = message.append(Component.text(match.raw()));
                } else {
                    message = message.append(itemLink(held));
                }
                cursor = match.end();
                continue;
            }

            Snapshot snapshot = createSnapshot(player, match.type());
            if (snapshot == null) {
                message = message.append(Component.text(match.raw()));
            } else {
                snapshots.put(snapshot.id(), snapshot);
                message = message.append(storageLink(player, match.type(), snapshot));
            }
            cursor = match.end();
        }
        return message;
    }

    private Component itemLink(ItemStack held) {
        Component name = itemName(held);
        Component rendered = Component.text("[", NamedTextColor.WHITE)
                .append(name)
                .append(Component.text("]", NamedTextColor.WHITE));
        return rendered.hoverEvent(held.asHoverEvent());
    }

    private Component storageLink(Player player, Type type, Snapshot snapshot) {
        Component owner = player.displayName().append(Component.text("'s "));
        Component label = switch (type) {
            case INVENTORY -> Component.text("Inventory", NamedTextColor.YELLOW);
            case ENDERCHEST -> Component.text("Enderchest", NamedTextColor.GOLD);
            case BACKPACK -> Component.text("Backpack", NamedTextColor.LIGHT_PURPLE);
            default -> Component.text(type.name());
        };

        Component link = Component.text("[", NamedTextColor.WHITE)
                .append(label)
                .append(Component.text("]", NamedTextColor.WHITE))
                .clickEvent(ClickEvent.runCommand("/chatitem view " + snapshot.id()))
                .hoverEvent(HoverEvent.showText(Component.text("Click to view")));

        return owner.append(link);
    }

    private Component itemName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName() && item.getItemMeta().displayName() != null) {
            return item.getItemMeta().displayName();
        }
        return Component.translatable(item.getType().translationKey());
    }

    private Snapshot createSnapshot(Player player, Type type) {
        ItemStack[] items;
        String title;
        switch (type) {
            case INVENTORY -> {
                items = new ItemStack[45];
                ItemStack[] storage = player.getInventory().getStorageContents();
                for (int i = 0; i < Math.min(36, storage.length); i++) items[i] = cloneItem(storage[i]);
                ItemStack[] armor = player.getInventory().getArmorContents();
                for (int i = 0; i < Math.min(4, armor.length); i++) items[36 + i] = cloneItem(armor[i]);
                items[40] = cloneItem(player.getInventory().getItemInOffHand());
                title = player.getName() + "'s Inventory";
            }
            case ENDERCHEST -> {
                items = cloneArray(player.getEnderChest().getContents());
                title = player.getName() + "'s Ender Chest";
            }
            case BACKPACK -> {
                items = backpackContents(player);
                if (items == null) {
                    core.messages().send(player, "&cWear a linked Backpack chestplate before using [backpack].");
                    return null;
                }
                title = player.getName() + "'s Backpack";
            }
            default -> { return null; }
        }
        return new Snapshot(randomId(), title, items, System.currentTimeMillis());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length != 2 || !args[0].equalsIgnoreCase("view")) {
            core.messages().send(player, "&7Use &f[item]&7, &f[inv]&7, &f[echest] &7or &f[backpack] &7in chat.");
            return true;
        }
        Snapshot snapshot = snapshots.get(args[1]);
        long ttl = Math.max(1L, getConfig().getLong("snapshot-minutes", 5L)) * 60_000L;
        if (snapshot == null || System.currentTimeMillis() - snapshot.createdAt() > ttl) {
            core.messages().send(player, "&cThat chat link has expired.");
            return true;
        }
        openSnapshot(player, snapshot);
        return true;
    }

    private void openSnapshot(Player player, Snapshot snapshot) {
        int size = Math.max(9, Math.min(54, ((Math.max(1, snapshot.items().length) + 8) / 9) * 9));
        Inventory inv = Bukkit.createInventory(new ViewHolder(), size, snapshot.title());
        for (int i = 0; i < Math.min(size, snapshot.items().length); i++) inv.setItem(i, cloneItem(snapshot.items()[i]));
        player.openInventory(inv);
    }

    @EventHandler
    public void onViewClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof ViewHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onViewDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ViewHolder) event.setCancelled(true);
    }

    private ItemStack[] backpackContents(Player player) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("MiraBackpacks");
        if (plugin == null || !plugin.isEnabled()) return null;
        try {
            for (var reg : Bukkit.getServicesManager().getRegistrations(plugin)) {
                Class<?> service = reg.getService();
                if (!service.getName().endsWith("$BackpacksApi")) continue;
                Object provider = reg.getProvider();
                Method active = service.getMethod("activeIdentity", ItemStack.class);
                Object optional = active.invoke(provider, player.getInventory().getChestplate());
                if (!(optional instanceof Optional<?> opt) || opt.isEmpty()) return null;
                Object identity = opt.get();
                UUID id = (UUID) identity.getClass().getMethod("id").invoke(identity);
                ItemStack[] contents = (ItemStack[]) service.getMethod("contents", UUID.class).invoke(provider, id);
                return cloneArray(contents);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            getLogger().warning("Backpack link failed: " + ex.getMessage());
        }
        return null;
    }

    private void cleanup() {
        long ttl = Math.max(1L, getConfig().getLong("snapshot-minutes", 5L)) * 60_000L;
        long now = System.currentTimeMillis();
        snapshots.entrySet().removeIf(e -> now - e.getValue().createdAt() > ttl);
    }

    private boolean containsToken(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("[item]") || lower.contains("[inv]") || lower.contains("[inventory]")
                || lower.contains("[echest]") || lower.contains("[enderchest]") || lower.contains("[backpack]");
    }

    private TokenMatch nextToken(String raw, int from) {
        String lower = raw.toLowerCase(Locale.ROOT);
        List<TokenMatch> matches = new ArrayList<>();
        add(matches, lower, raw, from, "[item]", Type.ITEM, "Item");
        add(matches, lower, raw, from, "[inv]", Type.INVENTORY, "Inventory");
        add(matches, lower, raw, from, "[inventory]", Type.INVENTORY, "Inventory");
        add(matches, lower, raw, from, "[echest]", Type.ENDERCHEST, "Ender Chest");
        add(matches, lower, raw, from, "[enderchest]", Type.ENDERCHEST, "Ender Chest");
        add(matches, lower, raw, from, "[backpack]", Type.BACKPACK, "Backpack");
        return matches.stream().min(Comparator.comparingInt(TokenMatch::start)).orElse(null);
    }

    private static void add(List<TokenMatch> out, String lower, String raw, int from, String token, Type type, String label) {
        int at = lower.indexOf(token, from);
        if (at >= 0) out.add(new TokenMatch(at, at + token.length(), raw.substring(at, at + token.length()), type, label));
    }

    private String randomId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }
    private static ItemStack cloneItem(ItemStack item) { return item == null ? null : item.clone(); }
    private static ItemStack[] cloneArray(ItemStack[] items) {
        if (items == null) return new ItemStack[0];
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = cloneItem(items[i]);
        return copy;
    }

    private enum Type { ITEM, INVENTORY, ENDERCHEST, BACKPACK }
    private record TokenMatch(int start, int end, String raw, Type type, String label) {}
    private record Snapshot(String id, String title, ItemStack[] items, long createdAt) {}
    private record ViewHolder() implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
}
