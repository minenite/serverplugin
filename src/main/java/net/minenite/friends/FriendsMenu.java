package net.minenite.friends;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * The two screens of the friends menu.
 *
 * <p>Screens are identified by their title. Bukkit gives no way to attach data to
 * an open inventory, and the alternative - tracking who has what open in a map -
 * has to be kept correct across quits, kicks and server switches. The title is
 * already carried by the click event and cannot go stale.
 */
public final class FriendsMenu {

    public static final String LIST_TITLE = ChatColor.DARK_GRAY + "Friends";
    public static final String DETAIL_PREFIX = ChatColor.DARK_GRAY + "Friend: ";
    public static final String REQUESTS_TITLE = ChatColor.DARK_GRAY + "Friend requests";

    private static final int LIST_SIZE = 54;
    private static final int FRIENDLY_FIRE_SLOT = 49;
    private static final int REQUESTS_SLOT = 53;

    private final FriendStore store;
    private final boolean protectFriends;

    public FriendsMenu(FriendStore store, boolean protectFriends) {
        this.store = store;
        this.protectFriends = protectFriends;
    }

    /** The list of friends, one head each. */
    public Inventory openList(Player viewer) {
        List<FriendStore.Friendship> friends = this.store.friendsOf(viewer.getUniqueId());
        Inventory inventory = Bukkit.createInventory(null, LIST_SIZE, LIST_TITLE);

        int slot = 0;
        for (FriendStore.Friendship friend : friends) {
            if (slot >= 45) {
                // Beyond this the bottom row is reserved for controls. A player with
                // 45 friends can still see the rest by removing some; paging is not
                // worth the complexity until someone actually hits it.
                break;
            }
            inventory.setItem(slot++, head(friend));
        }

        if (friends.isEmpty()) {
            inventory.setItem(22, label(Material.PAPER, ChatColor.GRAY + "No friends yet",
                    List.of(ChatColor.DARK_GRAY + "Use /friend <username> to add one")));
        }

        boolean globalFire = this.store.globalFriendlyFire(viewer.getUniqueId());
        inventory.setItem(FRIENDLY_FIRE_SLOT, friendlyFireToggle(globalFire, null));

        // Always present, even at zero: a control that appears only when it has
        // something in it is a control players never learn is there.
        int pending = this.store.requestsFor(viewer.getUniqueId()).size();
        ItemStack requests = label(pending > 0 ? Material.WRITABLE_BOOK : Material.BOOK,
                (pending > 0 ? ChatColor.YELLOW : ChatColor.GRAY) + "Friend requests: " + pending,
                pending > 0
                        ? List.of(ChatColor.GRAY + "Click to answer them")
                        : List.of(ChatColor.DARK_GRAY + "Nobody is waiting"));
        if (pending > 0) {
            // The stack size puts the number on the icon itself, so it is legible
            // without reading the name.
            requests.setAmount(Math.min(pending, 64));
        }
        inventory.setItem(REQUESTS_SLOT, requests);
        return inventory;
    }

    /** One friend, with the details and actions for them. */
    public Inventory openDetail(Player viewer, FriendStore.Friendship friend) {
        Inventory inventory = Bukkit.createInventory(null, 27, DETAIL_PREFIX + friend.name());

        FriendStore.Presence presence = this.store.presence(friend.uuid());
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Friends for " + ChatColor.WHITE + describeDuration(
                Duration.ofMillis(System.currentTimeMillis() - friend.since())));
        if (presence != null && presence.online()) {
            lore.add(ChatColor.GREEN + "Online " + ChatColor.GRAY + "on " + ChatColor.WHITE + presence.server());
        } else if (presence != null) {
            lore.add(ChatColor.GRAY + "Last online " + ChatColor.WHITE + describeDuration(
                    Duration.ofMillis(System.currentTimeMillis() - presence.lastOnline())) + ChatColor.GRAY + " ago");
        } else {
            lore.add(ChatColor.GRAY + "Never seen on this network");
        }
        inventory.setItem(4, head(friend, lore));

        inventory.setItem(11, friendlyFireToggle(friend.friendlyFire(), friend.name()));

        boolean reachable = presence != null && presence.online();
        inventory.setItem(13, label(reachable ? Material.ENDER_PEARL : Material.BARRIER,
                reachable ? ChatColor.AQUA + "Join " + friend.name()
                        : ChatColor.DARK_GRAY + "Cannot join " + friend.name(),
                reachable ? List.of(ChatColor.GRAY + "Travel to " + presence.server())
                        : List.of(ChatColor.GRAY + "They are not online")));

        inventory.setItem(15, label(Material.REDSTONE_BLOCK, ChatColor.RED + "Remove friend",
                List.of(ChatColor.GRAY + "Removes them for both of you")));

        inventory.setItem(22, label(Material.ARROW, ChatColor.GRAY + "Back", List.of()));
        return inventory;
    }

    /** Incoming requests, one head each. */
    public Inventory openRequests(Player viewer) {
        List<FriendStore.Request> requests = this.store.requestsFor(viewer.getUniqueId());
        Inventory inventory = Bukkit.createInventory(null, 27, REQUESTS_TITLE);

        int slot = 0;
        for (FriendStore.Request request : requests) {
            if (slot >= 18) {
                break;
            }
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof SkullMeta skull) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(request.uuid()));
            }
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + request.name());
                meta.setLore(List.of(
                        ChatColor.GRAY + "Asked " + describeDuration(
                                Duration.ofMillis(System.currentTimeMillis() - request.sent())) + " ago",
                        ChatColor.GREEN + "Left click to accept",
                        ChatColor.RED + "Right click to decline"));
                item.setItemMeta(meta);
            }
            inventory.setItem(slot++, item);
        }

        if (requests.isEmpty()) {
            inventory.setItem(13, label(Material.PAPER, ChatColor.GRAY + "No requests waiting", List.of()));
        }
        inventory.setItem(22, label(Material.ARROW, ChatColor.GRAY + "Back", List.of()));
        return inventory;
    }

    // ------------------------------------------------------------------ items

    /**
     * A player head, textured from the friend's own profile.
     *
     * <p>The texture is resolved by the server from the stored profile, so a
     * friend the server has never seen may show as a default head until it is.
     */
    private ItemStack head(FriendStore.Friendship friend) {
        FriendStore.Presence presence = this.store.presence(friend.uuid());
        List<String> lore = new ArrayList<>();
        if (presence != null && presence.online()) {
            lore.add(ChatColor.GREEN + "Online " + ChatColor.GRAY + "on " + ChatColor.WHITE + presence.server());
        } else {
            lore.add(ChatColor.GRAY + "Offline");
        }
        lore.add(ChatColor.DARK_GRAY + "Click for details");
        return head(friend, lore);
    }

    private ItemStack head(FriendStore.Friendship friend, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(friend.uuid()));
        }
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + friend.name());
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** The friendly fire control, used for both the global and per-friend cases. */
    private ItemStack friendlyFireToggle(boolean allowed, String friendName) {
        String scope = friendName == null ? "all friends" : friendName;
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + (allowed
                ? "You can be hurt by " + scope
                : "You cannot be hurt by " + scope));
        if (!this.protectFriends) {
            // Otherwise the setting reads as broken here: the player turns
            // protection on, gets hit by a friend anyway, and has no way to know
            // the server overrules it.
            lore.add(ChatColor.YELLOW + "This server ignores it");
            lore.add(ChatColor.DARK_GRAY + "Friends can fight here");
        }
        lore.add(ChatColor.DARK_GRAY + "Click to change");
        return label(allowed ? Material.IRON_SWORD : Material.SHIELD,
                (allowed ? ChatColor.RED : ChatColor.GREEN) + "Friendly fire: "
                        + (allowed ? "on" : "off"),
                lore);
    }

    private ItemStack label(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static int friendlyFireSlot() {
        return FRIENDLY_FIRE_SLOT;
    }

    public static int requestsSlot() {
        return REQUESTS_SLOT;
    }

    /** "3 days", "5 hours" - one unit is enough for a menu. */
    public static String describeDuration(Duration duration) {
        long days = duration.toDays();
        if (days > 0) {
            return days + (days == 1 ? " day" : " days");
        }
        long hours = duration.toHours();
        if (hours > 0) {
            return hours + (hours == 1 ? " hour" : " hours");
        }
        long minutes = Math.max(1, duration.toMinutes());
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
