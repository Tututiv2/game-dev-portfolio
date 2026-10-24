package com.skyclone.api.skill;

import org.bukkit.entity.Player;

/**
 * Feature service, provided by the "skills" module. Get it with {@code Sky.service(SkillService.class)}.
 *
 * <p>Rule: the module that performs the action grants the XP — mining grants Mining XP when a
 * block breaks, fishing grants Fishing XP per catch. The ONE exception is Combat: the skills
 * module grants it from SkyEvents.MobKill ({@code combatXp}) so no combat module has to.
 */
public interface SkillService {

    /** Adds XP after applying the skill's Wisdom stat. Fires SkillXpGain / SkillLevelUp. */
    void addXp(Player p, SkillType skill, double baseXp, String source);

    int level(Player p, SkillType skill);

    double xp(Player p, SkillType skill);

    /** XP into the current level and needed for the next. */
    double progress(Player p, SkillType skill);

    double needed(Player p, SkillType skill);

    /** Average of the non-cosmetic skills (Social/Runecrafting/Carpentry excluded). */
    double average(Player p);

    /** Max level this player may reach (Farming 50 -> 60 via a garden/contest unlock, etc.). */
    int cap(Player p, SkillType skill);

    void setCap(Player p, SkillType skill, int cap);

    void openMenu(Player p);
}
