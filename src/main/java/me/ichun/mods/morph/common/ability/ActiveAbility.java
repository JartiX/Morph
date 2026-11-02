package me.ichun.mods.morph.common.ability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact registry IDs, rather than instanceof: subclasses must not inherit the wrong action. */
public final class ActiveAbility
{
    public final String form, action;
    public final int cooldown;
    private static final Map<String, ActiveAbility> FORMS = new LinkedHashMap<>();
    private ActiveAbility(String form, String action, int seconds)
    {
        this.form = form; this.action = action; this.cooldown = seconds * 20;
    }
    private static void add(String action, int seconds, String... forms)
    {
        for(String form : forms) FORMS.put("minecraft:" + form, new ActiveAbility(form, action, seconds));
    }
    static
    {
        add("echo", 12, "bat");
        add("blaze", 8, "blaze");
        add("web", 8, "spider", "cave_spider");
        add("egg", 15, "chicken");
        add("cleanse", 25, "cow");
        add("explode", 18, "creeper");
        add("charge", 8, "donkey", "horse", "mule", "skeleton_horse", "zombie_horse");
        add("breath", 16, "ender_dragon");
        add("teleport", 10, "enderman");
        add("blink", 6, "endermite");
        add("fangs", 10, "evocation_illager");
        add("ghast", 10, "ghast");
        add("stomp", 12, "giant");
        add("beam", 8, "guardian");
        add("elder_beam", 14, "elder_guardian");
        add("slam", 8, "villager_golem");
        add("spit", 6, "llama");
        add("magma", 7, "magma_cube");
        add("soup", 35, "mooshroom");
        add("pounce", 7, "ocelot");
        add("screech", 12, "parrot");
        add("sprint", 10, "pig");
        add("rally", 16, "zombie_pigman");
        add("maul", 8, "polar_bear");
        add("jump", 6, "rabbit");
        add("graze", 25, "sheep");
        add("levitate", 10, "shulker");
        add("scurry", 8, "silverfish");
        add("arrow", 3, "skeleton");
        add("slime", 6, "slime");
        add("snowball", 2, "snowman");
        add("ink", 14, "squid");
        add("stray_arrow", 5, "stray");
        add("dash", 7, "vex");
        add("barter", 30, "villager");
        add("cleave", 7, "vindication_illager");
        add("witch", 12, "witch");
        add("skull", 10, "wither");
        add("wither_strike", 8, "wither_skeleton");
        add("howl", 16, "wolf");
        add("hunger", 9, "husk");
        add("lunge", 7, "zombie", "zombie_villager");
        add("stand", 15, "armor_stand");
        add("illusion", 14, "illusion_illager");
    }
    public static ActiveAbility get(String id) { return FORMS.get(id); }
    public static Map<String, ActiveAbility> all() { return Collections.unmodifiableMap(FORMS); }
    public String key() { return "morph.active." + action; }
    public String cooldownKey() { return "active:" + action; }
}
