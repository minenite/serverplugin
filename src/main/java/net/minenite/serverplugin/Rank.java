package net.minenite.serverplugin;

import java.util.Locale;

import org.bukkit.ChatColor;

/**
 * The network's ranks, in order of authority.
 *
 * <p>Declaration order <em>is</em> the hierarchy - {@link #OWNER} first, so a
 * lower ordinal outranks a higher one. Everything that compares two ranks relies
 * on that, so inserting one in the middle changes who outranks whom, which is the
 * intended way to add a rank.
 *
 * @param label      shown before the name, blank for the rank everyone starts at
 * @param labelColour colour of the label
 * @param nameColour colour of the player's name in chat and the tab list
 */
public enum Rank {

    OWNER("OWNER", "&f", "&4"),
    ADMIN("ADMIN", "&f", "&c"),
    DEV("DEV", "&f", "&2"),
    SMOD("SMOD", "&f", "&6"),
    MOD("MOD", "&f", "&5"),
    TMOD("TMOD", "&f", "&d"),
    BUILDER("BUILDER", "&f", "&3"),
    ELITE("ELITE", "&2", "&7"),
    MVP("MVP", "&b", "&7"),
    VIP("VIP", "&a", "&7"),

    /** No rank: no label, and a name like anybody else's. */
    DEFAULT("", "", "&7");

    private final String label;
    private final String labelColour;
    private final String nameColour;

    Rank(String label, String labelColour, String nameColour) {
        this.label = label;
        this.labelColour = labelColour;
        this.nameColour = nameColour;
    }

    /** True when this rank is at least as high as {@code other}. */
    public boolean atLeast(Rank other) {
        return this.ordinal() <= other.ordinal();
    }

    /** The name as it should appear in chat and the tab list. */
    public String colouredName(String playerName) {
        return ChatColor.translateAlternateColorCodes('&', this.nameColour + playerName);
    }

    /** The label and name together, with a trailing space when there is a label. */
    public String prefixedName(String playerName) {
        String text = this.label.isEmpty()
                ? this.nameColour + playerName
                : this.labelColour + this.label + " " + this.nameColour + playerName;
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public String label() {
        return this.label;
    }

    /** Parses a name typed by a player; null when it is not a rank. */
    public static Rank parse(String name) {
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notARank) {
            return null;
        }
    }

    /** Every rank, for telling someone what they can choose from. */
    public static String listNames() {
        StringBuilder names = new StringBuilder();
        for (Rank rank : values()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(rank.name().toLowerCase(Locale.ROOT));
        }
        return names.toString();
    }
}
