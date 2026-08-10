package net.minenite.friends;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Friendships and presence, shared by every server on the network.
 *
 * <p>A friends list is worthless per server - the point of it is knowing where
 * someone is - so the data lives in one file the backends share, rather than in
 * each server's own folder.
 *
 * <p>Ten servers writing one file need arbitration, and every write here takes an
 * exclusive {@link FileLock} and re-reads before modifying. That is heavy for a
 * frequent operation and irrelevant for this one: friendships change when a
 * player types a command, not on a tick.
 *
 * <p>The obvious alternative is a database. It is not worth a driver dependency
 * and a daemon for something this small, and the file is readable with an editor
 * when something looks wrong.
 */
public final class FriendStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path friendsFile;
    private final Path presenceFile;

    public FriendStore(Path sharedDirectory) throws IOException {
        Files.createDirectories(sharedDirectory);
        this.friendsFile = sharedDirectory.resolve("friends.json");
        this.presenceFile = sharedDirectory.resolve("presence.json");
    }

    /** One person's friendship with another. */
    public record Friendship(UUID uuid, String name, long since, boolean friendlyFire) {
    }

    /** Where someone is, and when they were last seen. */
    public record Presence(String name, String server, long lastOnline, boolean online) {
    }

    // ---------------------------------------------------------------- friends

    public List<Friendship> friendsOf(UUID player) {
        JsonObject root = read(this.friendsFile);
        JsonObject entry = root.getAsJsonObject(player.toString());
        List<Friendship> friends = new ArrayList<>();
        if (entry == null) {
            return friends;
        }
        JsonObject list = entry.getAsJsonObject("friends");
        if (list == null) {
            return friends;
        }
        for (String key : list.keySet()) {
            JsonObject friend = list.getAsJsonObject(key);
            friends.add(new Friendship(
                    UUID.fromString(key),
                    friend.get("name").getAsString(),
                    friend.get("since").getAsLong(),
                    !friend.has("friendlyFire") || friend.get("friendlyFire").getAsBoolean()));
        }
        friends.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return friends;
    }

    public boolean areFriends(UUID a, UUID b) {
        return friendsOf(a).stream().anyMatch(friend -> friend.uuid().equals(b));
    }

    public Friendship friendship(UUID owner, UUID friend) {
        return friendsOf(owner).stream()
                .filter(entry -> entry.uuid().equals(friend))
                .findFirst()
                .orElse(null);
    }

    /**
     * Records a friendship on both sides.
     *
     * <p>Stored twice rather than as one shared record: each side owns its own
     * per-friend settings, so A can allow friendly fire with B while B does not.
     *
     * @return false if they were already friends
     */
    public boolean addFriend(UUID a, String nameA, UUID b, String nameB) {
        if (areFriends(a, b)) {
            return false;
        }
        long now = System.currentTimeMillis();
        modify(this.friendsFile, root -> {
            link(root, a, nameA, b, nameB, now);
            link(root, b, nameB, a, nameA, now);
        });
        return true;
    }

    public void removeFriend(UUID a, UUID b) {
        modify(this.friendsFile, root -> {
            unlink(root, a, b);
            unlink(root, b, a);
        });
    }

    /** Sets whether {@code owner} will take damage from {@code friend}. */
    public void setFriendlyFire(UUID owner, UUID friend, boolean allowed) {
        modify(this.friendsFile, root -> {
            JsonObject entry = root.getAsJsonObject(owner.toString());
            if (entry == null) {
                return;
            }
            JsonObject list = entry.getAsJsonObject("friends");
            if (list == null || !list.has(friend.toString())) {
                return;
            }
            list.getAsJsonObject(friend.toString()).addProperty("friendlyFire", allowed);
        });
    }

    /** The default applied to friends with no setting of their own. */
    public boolean globalFriendlyFire(UUID player) {
        JsonObject entry = read(this.friendsFile).getAsJsonObject(player.toString());
        return entry != null && entry.has("friendlyFire") && entry.get("friendlyFire").getAsBoolean();
    }

    public void setGlobalFriendlyFire(UUID player, boolean allowed) {
        modify(this.friendsFile, root -> {
            JsonObject entry = root.getAsJsonObject(player.toString());
            if (entry == null) {
                entry = new JsonObject();
                entry.add("friends", new JsonObject());
                root.add(player.toString(), entry);
            }
            entry.addProperty("friendlyFire", allowed);
        });
    }

    private static void link(JsonObject root, UUID owner, String ownerName, UUID friend, String friendName, long now) {
        JsonObject entry = root.getAsJsonObject(owner.toString());
        if (entry == null) {
            entry = new JsonObject();
            entry.add("friends", new JsonObject());
            root.add(owner.toString(), entry);
        }
        entry.addProperty("name", ownerName);
        JsonObject list = entry.getAsJsonObject("friends");
        if (list == null) {
            list = new JsonObject();
            entry.add("friends", list);
        }
        JsonObject record = new JsonObject();
        record.addProperty("name", friendName);
        record.addProperty("since", now);
        record.addProperty("friendlyFire", false);
        list.add(friend.toString(), record);
    }

    private static void unlink(JsonObject root, UUID owner, UUID friend) {
        JsonObject entry = root.getAsJsonObject(owner.toString());
        if (entry == null) {
            return;
        }
        JsonObject list = entry.getAsJsonObject("friends");
        if (list != null) {
            list.remove(friend.toString());
        }
    }

    // --------------------------------------------------------------- requests

    /** Someone who has asked to be your friend. */
    public record Request(UUID uuid, String name, long sent) {
    }

    /**
     * Records that {@code from} wants to be friends with {@code to}.
     *
     * <p>Stored on the recipient, because that is who has to act on it and who
     * needs to see it listed.
     *
     * @return false if the same request is already waiting
     */
    public boolean addRequest(UUID to, UUID from, String fromName) {
        if (hasRequest(to, from)) {
            return false;
        }
        modify(this.friendsFile, root -> {
            JsonObject entry = root.getAsJsonObject(to.toString());
            if (entry == null) {
                entry = new JsonObject();
                entry.add("friends", new JsonObject());
                root.add(to.toString(), entry);
            }
            JsonObject requests = entry.getAsJsonObject("requests");
            if (requests == null) {
                requests = new JsonObject();
                entry.add("requests", requests);
            }
            JsonObject record = new JsonObject();
            record.addProperty("name", fromName);
            record.addProperty("sent", System.currentTimeMillis());
            requests.add(from.toString(), record);
        });
        return true;
    }

    public boolean hasRequest(UUID to, UUID from) {
        return requestsFor(to).stream().anyMatch(request -> request.uuid().equals(from));
    }

    public List<Request> requestsFor(UUID player) {
        JsonObject entry = read(this.friendsFile).getAsJsonObject(player.toString());
        List<Request> requests = new ArrayList<>();
        if (entry == null) {
            return requests;
        }
        JsonObject list = entry.getAsJsonObject("requests");
        if (list == null) {
            return requests;
        }
        for (String key : list.keySet()) {
            JsonObject record = list.getAsJsonObject(key);
            requests.add(new Request(UUID.fromString(key), record.get("name").getAsString(),
                    record.get("sent").getAsLong()));
        }
        requests.sort((a, b) -> Long.compare(b.sent(), a.sent()));
        return requests;
    }

    public void removeRequest(UUID to, UUID from) {
        modify(this.friendsFile, root -> {
            JsonObject entry = root.getAsJsonObject(to.toString());
            if (entry == null) {
                return;
            }
            JsonObject requests = entry.getAsJsonObject("requests");
            if (requests != null) {
                requests.remove(from.toString());
            }
        });
    }

    // --------------------------------------------------------------- presence

    /** Records where someone is, so other servers can find and reach them. */
    public void setPresence(UUID player, String name, String server, boolean online) {
        modify(this.presenceFile, root -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name);
            entry.addProperty("server", server);
            entry.addProperty("lastOnline", System.currentTimeMillis());
            entry.addProperty("online", online);
            root.add(player.toString(), entry);
        });
    }

    public Presence presence(UUID player) {
        JsonObject entry = read(this.presenceFile).getAsJsonObject(player.toString());
        if (entry == null) {
            return null;
        }
        return new Presence(
                entry.get("name").getAsString(),
                entry.get("server").getAsString(),
                entry.get("lastOnline").getAsLong(),
                entry.get("online").getAsBoolean());
    }

    /** Finds someone by name, for commands where a player types a username. */
    public Map.Entry<UUID, Presence> presenceByName(String name) {
        JsonObject root = read(this.presenceFile);
        for (String key : root.keySet()) {
            JsonObject entry = root.getAsJsonObject(key);
            if (entry.get("name").getAsString().equalsIgnoreCase(name)) {
                return Map.entry(UUID.fromString(key), new Presence(
                        entry.get("name").getAsString(),
                        entry.get("server").getAsString(),
                        entry.get("lastOnline").getAsLong(),
                        entry.get("online").getAsBoolean()));
            }
        }
        return null;
    }

    /** Every player this network has seen, for name lookups when adding a friend. */
    public Map<UUID, String> knownPlayers() {
        JsonObject root = read(this.presenceFile);
        Map<UUID, String> players = new LinkedHashMap<>();
        for (String key : root.keySet()) {
            players.put(UUID.fromString(key), root.getAsJsonObject(key).get("name").getAsString());
        }
        return players;
    }

    // ------------------------------------------------------------------- i/o

    private JsonObject read(Path file) {
        try {
            if (!Files.exists(file)) {
                return new JsonObject();
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return new JsonObject();
            }
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception unreadable) {
            // A corrupt file must not take the server down with it. Returning empty
            // loses data, so the original is kept for inspection rather than
            // overwritten silently.
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis()));
            } catch (IOException ignored) {
                // Nothing more to do; an empty view is still better than a crash.
            }
            return new JsonObject();
        }
    }

    /**
     * Read, modify and write back while holding an exclusive lock.
     *
     * <p>The lock spans the read as well as the write. Locking only the write
     * would let two servers each read the same state, apply their own change and
     * write, and the second would erase the first.
     */
    private synchronized void modify(Path file, Consumer<JsonObject> change) {
        try {
            if (!Files.exists(file)) {
                Files.writeString(file, "{}", StandardCharsets.UTF_8);
            }
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                JsonObject root = read(file);
                change.accept(root);
                byte[] out = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
                channel.truncate(0);
                channel.position(0);
                channel.write(java.nio.ByteBuffer.wrap(out));
                lock.isValid();
            }
        } catch (IOException failed) {
            throw new IllegalStateException("Could not update " + file, failed);
        }
    }
}
