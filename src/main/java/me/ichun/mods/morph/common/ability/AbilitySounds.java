package me.ichun.mods.morph.common.ability;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.SoundEvents;
import net.minecraft.network.play.server.SPacketSoundEffect;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Pure native sound selection, shared by actual emission and runtime acceptance. */
public final class AbilitySounds
{
    private AbilitySounds() {}

    /** Every registered action has a deliberate cue; unknown actions have no generic success noise. */
    public static SoundEvent success(String action)
    {
        switch(action)
        {
            case "echo": return SoundEvents.ENTITY_BAT_AMBIENT;
            case "blaze": return SoundEvents.ENTITY_BLAZE_SHOOT;
            case "web": return SoundEvents.ENTITY_SNOWBALL_THROW;
            case "egg": return SoundEvents.ENTITY_CHICKEN_EGG;
            case "cleanse": return SoundEvents.ENTITY_GENERIC_DRINK;
            case "explode": return SoundEvents.ENTITY_GENERIC_EXPLODE;
            case "charge": return SoundEvents.ENTITY_HORSE_GALLOP;
            case "breath": return SoundEvents.ENTITY_ENDERDRAGON_SHOOT;
            case "teleport": case "blink": return SoundEvents.ENTITY_ENDERMEN_TELEPORT;
            case "fangs": return SoundEvents.EVOCATION_ILLAGER_CAST_SPELL;
            case "ghast": return SoundEvents.ENTITY_GHAST_SHOOT;
            case "stomp": return SoundEvents.ENTITY_GENERIC_BIG_FALL;
            case "beam": case "elder_beam": return SoundEvents.ENTITY_GUARDIAN_ATTACK;
            case "slam": return SoundEvents.ENTITY_IRONGOLEM_ATTACK;
            case "spit": return SoundEvents.ENTITY_LLAMA_SPIT;
            case "magma": return SoundEvents.ENTITY_MAGMACUBE_JUMP;
            case "soup": return SoundEvents.ENTITY_COW_MILK;
            case "pounce": return SoundEvents.ENTITY_CAT_HISS;
            case "screech": return SoundEvents.ENTITY_PARROT_AMBIENT;
            case "sprint": return SoundEvents.ENTITY_PIG_AMBIENT;
            case "rally": return SoundEvents.ENTITY_ZOMBIE_PIG_ANGRY;
            case "maul": return SoundEvents.ENTITY_POLAR_BEAR_WARNING;
            case "jump": return SoundEvents.ENTITY_RABBIT_JUMP;
            case "graze": return SoundEvents.ENTITY_GENERIC_EAT;
            case "levitate": return SoundEvents.ENTITY_SHULKER_SHOOT;
            case "scurry": return SoundEvents.ENTITY_SILVERFISH_AMBIENT;
            case "arrow": case "stray_arrow": return SoundEvents.ENTITY_SKELETON_SHOOT;
            case "slime": return SoundEvents.ENTITY_SLIME_JUMP;
            case "snowball": return SoundEvents.ENTITY_SNOWMAN_SHOOT;
            case "ink": return SoundEvents.ENTITY_GENERIC_SPLASH;
            case "dash": return SoundEvents.ENTITY_VEX_CHARGE;
            case "barter": return SoundEvents.ENTITY_VILLAGER_YES;
            case "cleave": return SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP;
            case "witch": return SoundEvents.ENTITY_WITCH_THROW;
            case "skull": return SoundEvents.ENTITY_WITHER_SHOOT;
            case "wither_strike": return SoundEvents.ENTITY_WITHER_SKELETON_AMBIENT;
            case "howl": return SoundEvents.ENTITY_WOLF_HOWL;
            case "hunger": return SoundEvents.ENTITY_HUSK_AMBIENT;
            case "lunge": return SoundEvents.ENTITY_PLAYER_ATTACK_STRONG;
            case "stand": return SoundEvents.ITEM_ARMOR_EQUIP_IRON;
            case "illusion": return SoundEvents.ENTITY_ILLAGER_MIRROR_MOVE;
            default: return null;
        }
    }

    /** Our impact handler cancels vanilla payloads, therefore it also owns their single impact cue. */
    public static SoundEvent impact(String action)
    {
        switch(action)
        {
            case "web": return SoundEvents.BLOCK_CLOTH_PLACE;
            case "spit": return SoundEvents.ENTITY_SLIME_SQUISH;
            case "arrow": case "stray_arrow": return SoundEvents.ENTITY_ARROW_HIT;
            case "blaze": return SoundEvents.BLOCK_FIRE_EXTINGUISH;
            case "ghast": case "skull": return SoundEvents.ENTITY_GENERIC_EXPLODE;
            case "breath": return SoundEvents.ENTITY_ENDERDRAGON_FIREBALL_EPLD;
            case "levitate": return SoundEvents.ENTITY_SHULKER_BULLET_HIT;
            case "witch": return SoundEvents.ENTITY_SPLASH_POTION_BREAK;
            case "snowball": return SoundEvents.BLOCK_SNOW_HIT;
            case "fangs": return SoundEvents.EVOCATION_FANGS_ATTACK;
            case "beam": case "elder_beam": return SoundEvents.ENCHANT_THORNS_HIT;
            default: return null;
        }
    }

    /** Explosion already emits through World.newExplosion; never layer a second identical sound. */
    public static boolean nativeSuccess(String action) { return "explode".equals(action); }

    public static void playSuccess(EntityPlayer player, String action)
    {
        if(player.world.isRemote || nativeSuccess(action)) return;
        float pitch = "elder_beam".equals(action) || "stomp".equals(action) ? 0.8F : 1F;
        play(player.world, player.getPositionVector(), success(action), 0.7F, pitch);
    }

    public static void playImpact(World world, Vec3d point, String action)
    { if(!world.isRemote) play(world, point, impact(action), 0.55F, 1F); }

    private static void play(World world, Vec3d point, SoundEvent sound, float volume, float pitch)
    {
        if(sound != null) world.playSound(null, point.x, point.y, point.z, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    /** The rejection is private, quiet and limited independently of input packet spam. */
    public static boolean unavailable(EntityPlayer player)
    {
        if(!(player instanceof EntityPlayerMP) || player.world.isRemote) return false;
        long now = player.getServer().getWorld(0).getTotalWorldTime();
        if(player.getEntityData().getLong("MorphAbilityFeedbackAfter") > now) return false;
        player.getEntityData().setLong("MorphAbilityFeedbackAfter", now + 20);
        ((EntityPlayerMP)player).connection.sendPacket(new SPacketSoundEffect(SoundEvents.UI_BUTTON_CLICK,
                SoundCategory.PLAYERS, player.posX, player.posY, player.posZ, 0.18F, 0.65F));
        return true;
    }
}
