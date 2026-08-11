package net.minenite.serverplugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * The network's core plugin, running on every backend.
 *
 * <p>Currently friends, friendly fire and travel between servers; the name is
 * deliberately about where it runs rather than what it does today, because more
 * belongs here than friendship.
 *
 * <p>Its counterpart is ProxyPlugin, on the proxy. The split is not arbitrary:
 * menus and combat rules are Bukkit and can only exist on a backend, while moving
 * a player between servers is something only the proxy can do.
 */
public class ServerPlugin extends JavaPlugin implements Listener {

    /**
     * The channel the proxy listens on for requests like "move this player".
     *
     * <p>The namespaced form, not the legacy "BungeeCord" alias. Paper rewrites
     * the alias to this before it reaches the wire; CardForge's messenger does
     * not, so a message sent under the old name left the server addressed to a
     * channel the proxy never listens on - the player was told they were being
     * sent somewhere and then simply was not.
     */
    private static final String PROXY_CHANNEL = "bungeecord:main";

    private FriendStore store;
    private FriendsMenu menu;
    private String serverName;
    private Path travelDirectory;

    /**
     * Whether this server stops friends hurting each other.
     *
     * <p>Per server, not per player, because it is a property of what the server
     * is for. A server built around friends fighting each other - an arena, a
     * minigame - wants the hits to land, and no combination of player settings
     * should be able to break its rules.
     */
    private boolean protectFriends;

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

        this.menu = new FriendsMenu(this.store, getConfig().getBoolean("friendly-fire-protection", true));
        this.serverName = getConfig().getString("server-name", getServer().getName());
        this.protectFriends = getConfig().getBoolean("friendly-fire-protection", true);

        this.travelDirectory = shared.resolve("travel");
        try {
            Files.createDirectories(this.travelDirectory);
        } catch (IOException failed) {
            getLogger().warning("Could not create " + this.travelDirectory + "; travel between servers will not work");
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, PROXY_CHANNEL);

        getLogger().info("Friends loaded, sharing data at " + shared
                + (this.protectFriends ? "" : " (friends can hurt each other on this server)"));
    }

    // --------------------------------------------------------------- presence

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.store.setPresence(player.getUniqueId(), player.getName(), this.serverName, true, isModded(player));

        // The client sends its brand on its own schedule, and it has usually not
        // arrived by the time this event fires - so the record above says "unknown",
        // which is read as modded. Written again once it has landed, otherwise a
        // vanilla player is permanently mistaken for a modded one and shown servers
        // they cannot reach.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                boolean modded = isModded(player);
                // Logged because this decides which servers they are shown and
                // allowed to reach; when it is wrong there is otherwise nothing to
                // look at.
                getLogger().info("Client brand for " + player.getName() + ": "
                        + player.getClientBrandName() + " -> " + (modded ? "modded" : "vanilla"));
                this.store.setPresence(player.getUniqueId(), player.getName(), this.serverName, true, modded);
            }
        }, 60L);
    }

    /**
     * Whether this client loads mods, from the brand it reports.
     *
     * <p>A vanilla client cannot join a server with content mods - negotiation
     * refuses it - so the proxy needs to know before sending anyone there, and
     * only a backend can see this.
     *
     * <p>Unknown counts as modded. Detection failing should not lock a player out
     * of servers they can actually use; the worst case in this direction is that
     * they try one and are refused by the server itself, which is what happened
     * before any of this existed.
     */
    private boolean isModded(Player player) {
        try {
            String brand = player.getClientBrandName();
            getLogger().fine(() -> "Client brand for " + player.getName() + ": " + brand);
            if (brand == null || brand.isBlank()) {
                return true;
            }
            return !brand.equalsIgnoreCase("vanilla");
        } catch (Throwable unsupported) {
            // getClientBrandName is Paper API and may not be implemented here.
            getLogger().fine("Client brand unavailable, assuming modded: " + unsupported);
            return true;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Recorded as offline on this server. If they are switching servers the
        // other side overwrites this a moment later, which is the correct order.
        this.store.setPresence(player.getUniqueId(), player.getName(), this.serverName, false, isModded(player));
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
        if (!this.protectFriends) {
            // This server's own rules win over anyone's friends list.
            return;
        }
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

        if (this.store.areFriends(player.getUniqueId(), targetId)) {
            player.sendMessage(ChatColor.GRAY + "You are already friends with " + resolvedName + ".");
            return;
        }

        // If they already asked you, treat this as the answer rather than sending a
        // second request back the other way and leaving both waiting.
        if (this.store.hasRequest(player.getUniqueId(), targetId)) {
            acceptRequest(player, targetId, resolvedName);
            return;
        }

        if (this.store.addRequest(targetId, player.getUniqueId(), player.getName())) {
            player.sendMessage(ChatColor.GREEN + "Friend request sent to " + resolvedName + ".");
            Player recipient = Bukkit.getPlayer(targetId);
            if (recipient != null) {
                recipient.sendMessage(ChatColor.GREEN + player.getName() + " wants to be your friend. "
                        + ChatColor.GRAY + "Open /friends to answer.");
            }
        } else {
            player.sendMessage(ChatColor.GRAY + "You have already asked " + resolvedName + ".");
        }
    }

    /** Turns a waiting request into a friendship on both sides. */
    private void acceptRequest(Player player, UUID requesterId, String requesterName) {
        this.store.removeRequest(player.getUniqueId(), requesterId);
        this.store.addFriend(player.getUniqueId(), player.getName(), requesterId, requesterName);
        player.sendMessage(ChatColor.GREEN + "You are now friends with " + requesterName + ".");
        Player online = Bukkit.getPlayer(requesterId);
        if (online != null) {
            online.sendMessage(ChatColor.GREEN + player.getName() + " accepted your friend request.");
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

    /**
     * Asks the proxy to move a player, by leaving the request where it will see it.
     *
     * <p>The normal way is a plugin message on the proxy channel, and that is what
     * this did at first. It does not work here: CardForge's
     * {@code CraftPlayer.sendPluginMessage} validates its arguments and then drops
     * the message - its send path is still a TODO - so the request left no trace
     * and the player was told they were travelling and then simply was not.
     *
     * <p>The proxy plugin watches this directory instead. Both sides share a disk,
     * which is what makes it possible; it is a workaround, and it goes away when
     * plugin messaging is implemented in CardForge.
     */
    private void connectTo(Player player, String server) {
        Path request = this.travelDirectory.resolve(player.getUniqueId() + ".travel");
        try {
            // Written elsewhere and moved into place, so the proxy cannot read a
            // half-written file on its next pass.
            Path staged = this.travelDirectory.resolve(player.getUniqueId() + ".staging");
            Files.writeString(staged, server, StandardCharsets.UTF_8);
            Files.move(staged, request, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failed) {
            player.sendMessage(ChatColor.RED + "Could not reach the proxy to move you.");
            getLogger().warning("Could not write a travel request: " + failed.getMessage());
        }
    }

    // ------------------------------------------------------------------ menu

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        boolean isList = title.equals(FriendsMenu.LIST_TITLE);
        boolean isDetail = title.startsWith(FriendsMenu.DETAIL_PREFIX);
        boolean isRequests = title.equals(FriendsMenu.REQUESTS_TITLE);
        if (!isList && !isDetail && !isRequests) {
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
        } else if (isRequests) {
            handleRequestClick(player, event.getRawSlot(), clicked, event.isRightClick());
        } else {
            handleDetailClick(player, title.substring(FriendsMenu.DETAIL_PREFIX.length()), event.getRawSlot());
        }
    }

    private void handleRequestClick(Player player, int slot, ItemStack clicked, boolean declined) {
        if (slot == 22) {
            player.openInventory(this.menu.openList(player));
            return;
        }
        String name = displayName(clicked);
        if (name == null) {
            return;
        }
        this.store.requestsFor(player.getUniqueId()).stream()
                .filter(request -> request.name().equals(name))
                .findFirst()
                .ifPresent(request -> {
                    if (declined) {
                        this.store.removeRequest(player.getUniqueId(), request.uuid());
                        player.sendMessage(ChatColor.GRAY + "Declined " + request.name() + "'s request.");
                    } else {
                        acceptRequest(player, request.uuid(), request.name());
                    }
                    player.openInventory(this.menu.openRequests(player));
                });
    }

    private void handleListClick(Player player, int slot, ItemStack clicked) {
        if (slot == FriendsMenu.requestsSlot()) {
            player.openInventory(this.menu.openRequests(player));
            return;
        }
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
