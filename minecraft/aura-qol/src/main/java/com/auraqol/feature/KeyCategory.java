package com.auraqol.feature;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * The one "AuraQoL" section in Controls, shared by every feature with a key.
 *
 * <p>{@code KeyMapping.Category.register} THROWS on an id it has already seen,
 * so two features each registering "auraqol:auraqol" meant whichever started
 * second died - the HUD editor, every launch, with "Category 'auraqol:auraqol'
 * is already registered" in the log and no ] key.
 */
final class KeyCategory {
    private static KeyMapping.Category category;

    private KeyCategory() {
    }

    static synchronized KeyMapping.Category get() {
        if (category == null) {
            // Label resolves via Identifier.toLanguageKey("key.category"):
            // "key.category.auraqol.auraqol", which the lang file carries.
            category = KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath("auraqol", "auraqol"));
        }
        return category;
    }
}
