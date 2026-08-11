package net.minenite.serverplugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Everyone's rank, shared by the whole network.
 *
 * <p>A rank set on one server has to be a rank everywhere - being ADMIN on the
 * lobby and nobody on warz1 would be worse than having no ranks at all - so this
 * lives in the shared directory beside friends and presence.
 *
 * <p>Held in memory and refreshed on a timer rather than read per lookup: chat
 * asks for a rank on every message, and touching the disk that often for a file
 * that changes when somebody types a command would be absurd. The cost is that a
 * rank set on another server takes a few seconds to appear here; the server that
 * set it updates immediately.
 */
public final class RankStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    /** What each player is, and what they are choosing to show. */
    private final Map<UUID, Rank> ranks = new ConcurrentHashMap<>();
    private final Map<UUID, Rank> tags = new ConcurrentHashMap<>();

    /** Players arriving and leaving without announcement. */
    private final java.util.Set<UUID> silent =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public RankStore(Path sharedDirectory) throws IOException {
        Files.createDirectories(sharedDirectory);
        this.file = sharedDirectory.resolve("ranks.json");
        reload();
    }

    /** The rank someone actually holds. */
    public Rank rankOf(UUID player) {
        return this.ranks.getOrDefault(player, Rank.DEFAULT);
    }

    /** Whether this player comes and goes unannounced. */
    public boolean isSilent(UUID player) {
        return this.silent.contains(player);
    }

    /** @return the state after toggling */
    public boolean toggleSilent(UUID player) throws IOException {
        boolean now;
        if (this.silent.contains(player)) {
            this.silent.remove(player);
            now = false;
        } else {
            this.silent.add(player);
            now = true;
        }
        save();
        return now;
    }

    /**
     * The rank someone is displaying, which may be lower than the one they hold.
     *
     * <p>Falls back to the real rank and never exceeds it: a tag set before a
     * demotion must not keep showing the old one.
     */
    public Rank displayedRankOf(UUID player) {
        // Coming and going unnoticed while wearing a staff rank would rather
        // defeat the point of it.
        if (this.silent.contains(player)) {
            return Rank.DEFAULT;
        }
        Rank real = rankOf(player);
        Rank tag = this.tags.get(player);
        if (tag == null || real.atLeast(tag)) {
            return tag == null ? real : tag;
        }
        return real;
    }

    public void setRank(UUID player, Rank rank) throws IOException {
        this.ranks.put(player, rank);
        // A tag below the old rank may be above the new one; drop it rather than
        // leave someone displaying authority they no longer have.
        this.tags.remove(player);
        save();
    }

    public void setTag(UUID player, Rank tag) throws IOException {
        if (tag == null || tag == rankOf(player)) {
            this.tags.remove(player);
        } else {
            this.tags.put(player, tag);
        }
        save();
    }

    /** Re-reads the file, picking up ranks set on other servers. */
    public void reload() {
        if (!Files.isReadable(this.file)) {
            return;
        }
        try {
            String text = Files.readString(this.file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return;
            }
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            this.ranks.clear();
            this.tags.clear();
            this.silent.clear();
            for (String key : root.keySet()) {
                JsonObject entry = root.getAsJsonObject(key);
                UUID id = UUID.fromString(key);
                Rank rank = Rank.parse(entry.has("rank") ? entry.get("rank").getAsString() : null);
                if (rank != null) {
                    this.ranks.put(id, rank);
                }
                Rank tag = Rank.parse(entry.has("tag") ? entry.get("tag").getAsString() : null);
                if (tag != null) {
                    this.tags.put(id, tag);
                }
                if (entry.has("silent") && entry.get("silent").getAsBoolean()) {
                    this.silent.add(id);
                }
            }
        } catch (Exception unreadable) {
            // Keep what is already in memory: an unreadable file must not silently
            // strip everyone's rank.
        }
    }

    private synchronized void save() throws IOException {
        JsonObject root = new JsonObject();
        java.util.Set<UUID> everyone = new java.util.LinkedHashSet<>(this.ranks.keySet());
        everyone.addAll(this.silent);
        everyone.addAll(this.tags.keySet());
        for (UUID id : everyone) {
            JsonObject record = new JsonObject();
            record.addProperty("rank", this.ranks.getOrDefault(id, Rank.DEFAULT).name());
            Rank tag = this.tags.get(id);
            if (tag != null) {
                record.addProperty("tag", tag.name());
            }
            if (this.silent.contains(id)) {
                record.addProperty("silent", true);
            }
            root.add(id.toString(), record);
        }
        Path staged = this.file.resolveSibling("ranks.json.staging");
        Files.writeString(staged, GSON.toJson(root), StandardCharsets.UTF_8);
        Files.move(staged, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
