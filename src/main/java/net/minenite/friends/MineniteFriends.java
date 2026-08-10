package net.minenite.friends;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Friends across a proxied network: a menu, friendly fire control, and travel.
 *
 * <p>The pieces this needs are split across two places. The menu and combat rules
 * are Bukkit, so they live here on the backend. Moving a player between servers
 * belongs to the proxy, and is asked for over the plugin messaging channel that
 * Velocity and BungeeCord both implement.
 */
public class MineniteFriends extends JavaPlugin implements Listener {

    /** The channel Velocity and BungeeCord both listen on for proxy requests. */
    private static final String PROXY_CHANNEL = "BungeeCord";

    private FriendStore store;
    private FriendsMenu menu;
    private String serverName;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // One shared directory for the whole network: a friends list that stopped
        // at the server boundary would defeat the point of having one.
        Path shared = Path.of(getConfig().getString("shared-directory",
                Path.of("").toAbsolutePath().getParent().resolve("shared").toString()));
        try {
            this.store = new FriendStore(shared);
        } catch (IOException failed) {
            getLogger().severe("Could not open the shared friends directory at " + shared
                    + " - the plugin cannot run without it: " + failed.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.menu = new FriendsMenu(this.store);
        this.serverName = getConfig().getString("server-name", getServer().getName());

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, PROXY_CHANNEL);

        getLogger().info("Friends loaded, sharing data at " + shared);
    }

    // --------------------------------------------------------------- presence

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.store.setPresence(player.getUniqueId(), player.getName(), this.serverName, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Recorded as offline on this server. If they are switching servers the
        // other side overwrites this a moment later, which is the correct order.
        this.store.setPresence(player.getUniqueId(), player.getName(), this.serverName, false);
    }

    // --------------------------------------------------------- friendly fire

    /**
     * Cancels damage between friends unless both of them allow it.
     *
     * <p>Both, deliberately: friendly fire is protection, and protection that one
     * person can switch off for the other is not protection. Each side's setting
     * only ever loosens their own guard.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (!this.store.areFriends(victim.getUniqueId(), attacker.getUniqueId())) {
            return;
        }
        if (allowsFire(victim.getUniqueId(), attacker.getUniqueId())
                && allowsFire(attacker.getUniqueId(), victim.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
    }

    /** A per-friend setting wins over the global one; the global is the default. */
    private boolean allowsFire(UUID owner, UUID other) {
        FriendStore.Friendship friendship = this.store.friendship(owner, other);
        if (friendship != null && friendship.friendlyFire()) {
            return true;
        }
        return friendship == null && this.store.globalFriendlyFire(owner);
    }

    /** Arrows and thrown items count as their shooter. */
    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    // --------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only a player can use this.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("join")) {
            if (args.length != 1) {
                player.sendMessage(ChatColor.GRAY + "Usage: /join <username>");
                return true;
            }
            travelTo(player, args[0]);
            return true;
        }

        // /friend
        if (args.length == 0) {
            player.openInventory(this.menu.openList(player));
            return true;
        }
        addFriend(player, args[0]);
        return true;
    }

    private void addFriend(Player player, String targetName) {
        if (targetName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(ChatColor.RED + "You cannot add yourself.");
            return;
        }

        UUID targetId = null;
        String resolvedName = null;

        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            targetId = online.getUniqueId();
            resolvedName = online.getName();
        } else {
            // Not on this server: fall back to anyone the network has seen, which
            // is what makes adding someone on another server possible at all.
            Map.Entry<UUID, FriendStore.Presence> seen = this.store.presenceByName(targetName);
            if (seen != null) {
                targetId = seen.getKey();
                resolvedName = seen.getValue().name();
            }
        }

        if (targetId == null) {
            player.sendMessage(ChatColor.RED + "No player called " + targetName + " has been seen on this network.");
            return;
        }

        if (this.store.addFriend(player.getUniqueId(), player.getName(), targetId, resolvedName)) {
            player.sendMessage(ChatColor.GREEN + "You are now friends with " + resolvedName + ".");
            Player friendOnline = Bukkit.getPlayer(targetId);
            if (friendOnline != null) {
                friendOnline.sendMessage(ChatColor.GREEN + player.getName() + " added you as a friend.");
            }
        } else {
            player.sendMessage(ChatColor.GRAY + "You are already friends with " + resolvedName + ".");
        }
    }

    /** Sends a player to whichever server another player is on. */
    private void travelTo(Player player, String targetName) {
        Map.Entry<UUID, FriendStore.Presence> seen = this.store.presenceByName(targetName);
        if (seen == null) {
            player.sendMessage(ChatColor.RED + "No player called " + targetName + " has been seen on this network.");
            return;
        }
        FriendStore.Presence presence = seen.getValue();
        if (!presence.online()) {
            player.sendMessage(ChatColor.RED + presence.name() + " is not online.");
            return;
        }
        if (presence.server().equals(this.serverName)) {
            player.sendMessage(ChatColor.GRAY + "You are already on the same server as " + presence.name() + ".");
            return;
        }
        player.sendMessage(ChatColor.GRAY + "Sending you to " + presence.server() + "...");
        connectTo(player, presence.server());
    }

    private void connectTo(Player player, String server) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException impossible) {
            // Writing to a byte array does not fail; if it somehow does, there is
            // nothing useful to tell the player.
            getLogger().warning("Could not build the proxy request: " + impossible.getMessage());
            return;
        }
        player.sendPluginMessage(this, PROXY_CHANNEL, bytes.toByteArray());
    }

    // ------------------------------------------------------------------ menu

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        boolean isList = title.equals(FriendsMenu.LIST_TITLE);
        boolean isDetail = title.startsWith(FriendsMenu.DETAIL_PREFIX);
        if (!isList && !isDetail) {
            return;
        }

        // These are display slots, not storage: nothing may be picked up.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }

        if (isList) {
            handleListClick(player, event.getRawSlot(), clicked);
        } else {
            handleDetailClick(player, title.substring(FriendsMenu.DETAIL_PREFIX.length()), event.getRawSlot());
        }
    }

    private void handleListClick(Player player, int slot, ItemStack clicked) {
        if (slot == FriendsMenu.friendlyFireSlot()) {
            boolean now = !this.store.globalFriendlyFire(player.getUniqueId());
            this.store.setGlobalFriendlyFire(player.getUniqueId(), now);
            player.openInventory(this.menu.openList(player));
            return;
        }

        String name = displayName(clicked);
        if (name == null) {
            return;
        }
        this.store.friendsOf(player.getUniqueId()).stream()
                .filter(friend -> friend.name().equals(name))
                .findFirst()
                .ifPresent(friend -> player.openInventory(this.menu.openDetail(player, friend)));
    }

    private void handleDetailClick(Player player, String friendName, int slot) {
        List<FriendStore.Friendship> friends = this.store.friendsOf(player.getUniqueId());
        FriendStore.Friendship friend = friends.stream()
                .filter(entry -> entry.name().equals(friendName))
                .findFirst()
                .orElse(null);
        if (friend == null) {
            player.closeInventory();
            return;
        }

        switch (slot) {
            case 11 -> {
                this.store.setFriendlyFire(player.getUniqueId(), friend.uuid(), !friend.friendlyFire());
                FriendStore.Friendship updated = this.store.friendship(player.getUniqueId(), friend.uuid());
                player.openInventory(this.menu.openDetail(player, updated));
            }
            case 13 -> {
                player.closeInventory();
                travelTo(player, friend.name());
            }
            case 15 -> {
                this.store.removeFriend(player.getUniqueId(), friend.uuid());
                player.sendMessage(ChatColor.GRAY + "Removed " + friend.name() + " from your friends.");
                player.openInventory(this.menu.openList(player));
            }
            case 22 -> player.openInventory(this.menu.openList(player));
            default -> {
                // The head itself and the empty slots do nothing.
            }
        }
    }

    private String displayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return null;
        }
        return ChatColor.stripColor(meta.getDisplayName());
    }
}
