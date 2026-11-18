package me.jamino.wynnlodgrabber;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Tells whether the player is in-world on a Wynncraft character, without depending on Wynntils.
 *
 * <p>Same signal Wynntils uses ({@code CharacterModel#updateCharacterId}): once a character is
 * selected, hotbar slot 7 holds the character compass, and the first lore line of that item is the
 * character's 8-character ID. In the character-selection lobby (or anywhere else) that slot is
 * empty or holds something without such a line, so this goes false on its own when you leave.
 */
final class WynnCharacter {
    private static final int COMPASS_SLOT = 7;
    private static final Pattern CHARACTER_ID = Pattern.compile("^[a-z0-9]{8}$");

    private WynnCharacter() {}

    /** The selected character's ID, or {@code null} if no character is selected. Call on the client thread. */
    static String currentId(Minecraft client) {
        if (client.player == null) return null;

        ItemStack compass = client.player.getInventory().getItem(COMPASS_SLOT);
        if (compass.isEmpty()) return null;

        ItemLore lore = compass.get(DataComponents.LORE);
        if (lore == null) return null;

        List<Component> lines = lore.lines();
        if (lines.isEmpty()) return null;

        String first = ChatFormatting.stripFormatting(lines.getFirst().getString());
        if (first == null) return null;
        first = first.strip();
        return CHARACTER_ID.matcher(first).matches() ? first : null;
    }

    static boolean hasCharacter(Minecraft client) {
        return currentId(client) != null;
    }
}
