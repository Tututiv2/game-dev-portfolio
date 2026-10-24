package com.skyclone.api;

/**
 * One feature of the game. Every module is ONE package, {@code com.skyclone.mod.<id>}, whose
 * entry point is {@code com.skyclone.mod.<id>.<Name>Module implements GameModule} with a public
 * no-arg constructor. Core finds it by name from {@code com.skyclone.core.ModuleList} — a module
 * never edits a shared file to be loaded.
 *
 * <p>Lifecycle, all on the main thread:
 * <ol>
 *   <li>{@link #enable()} for every module, in ModuleList order. Register items, mobs, recipes,
 *       commands, dials, listeners, generators, NPCs, stat providers, and {@code Sky.provide}
 *       your own service here.</li>
 *   <li>{@link #postEnable()} for every module, once ALL have enabled. Cross-module wiring that
 *       needs another module's service (or another module's items to exist) goes here.</li>
 *   <li>{@link #disable()} in reverse order at shutdown. Flush anything not yet saved.</li>
 * </ol>
 *
 * <p>A module that throws from enable() is logged and skipped; the rest of the game still boots.
 */
public interface GameModule {

    /** Lower-case id, equal to the package name segment: "skills", "slayer", "hub". */
    String id();

    void enable();

    default void postEnable() {}

    default void disable() {}
}
