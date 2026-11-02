package me.ichun.mods.morph.common.ability;

import me.ichun.mods.morph.api.ability.Ability;
import me.ichun.mods.morph.api.ability.type.AbilityPotionEffect;
import me.ichun.mods.morph.common.Morph;
import me.ichun.mods.morph.common.morph.MorphInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import java.util.*;

/** Own effects are removed immediately; external potion applications relinquish ownership. */
final class ExtraPassives
{
    private static final String SAVED = "MorphOwnedPassives";
    static void login(EntityPlayer p)
    {
        NBTTagCompound saved = p.getEntityData().getCompoundTag(SAVED);
        for(String key : saved.getKeySet())
        {
            Potion potion = Potion.getPotionFromResourceLocation(key);
            if(potion != null) p.removePotionEffect(potion);
        }
        p.getEntityData().removeTag(SAVED);
    }
    static void tick(EntityPlayer p)
    {
        MorphInfo info = Morph.eventHandlerServer.morphsActive.get(p.getName());
        ActiveAbility form = info == null || info.isMorphing() || !p.isEntityAlive() || p.isSpectator()
                || Morph.config.abilities != 1 ? null : ActiveAbility.get(info.nextState.currentVariant.entId);
        Map<Potion, String> wanted = new HashMap<>();
        if(form != null)
            switch(form.form)
            {
                case "bat": case "spider": case "cave_spider": case "enderman":
                    wanted.put(MobEffects.NIGHT_VISION, "night_vision"); break;
                case "guardian": case "elder_guardian": case "squid":
                    if(!mapped(info, "swim", null)) wanted.put(MobEffects.WATER_BREATHING, "water_breathing"); break;
                case "villager_golem": case "shulker": wanted.put(MobEffects.RESISTANCE, "armor"); break;
                case "rabbit": case "slime": case "magma_cube": wanted.put(MobEffects.JUMP_BOOST, "jump"); break;
                case "horse": case "donkey": case "mule": case "skeleton_horse": case "zombie_horse": wanted.put(MobEffects.SPEED, "speed"); break;
                default: break;
            }
        wanted.entrySet().removeIf(entry -> disabled(entry.getValue()));
        for(PotionEffect active : new ArrayList<>(p.getActivePotionEffects()))
            if(active instanceof OwnedPotionEffect && !((OwnedPotionEffect)active).external && !wanted.containsKey(active.getPotion()))
            {
                p.removePotionEffect(active.getPotion()); unmark(p, active.getPotion());
            }
        for(Map.Entry<Potion,String> entry : wanted.entrySet())
        {
            Potion potion = entry.getKey();
            PotionEffect old = p.getActivePotionEffect(potion);
            int duration = potion == MobEffects.NIGHT_VISION ? 260 : 65;
            int refresh = potion == MobEffects.NIGHT_VISION ? 240 : 25;
            if(old == null)
            {
                OwnedPotionEffect owned = new OwnedPotionEffect(p, potion, duration);
                p.addPotionEffect(owned); mark(p, potion);
            }
            else if(old instanceof OwnedPotionEffect && !((OwnedPotionEffect)old).external && old.getDuration() < refresh)
                p.addPotionEffect(new OwnedPotionEffect(p, potion, duration));
        }
        NBTTagCompound saved = p.getEntityData().getCompoundTag(SAVED);
        for(String key : new HashSet<>(saved.getKeySet()))
        {
            Potion potion = Potion.getPotionFromResourceLocation(key);
            if(potion == null || !(p.getActivePotionEffect(potion) instanceof OwnedPotionEffect)) saved.removeTag(key);
        }
    }
    private static boolean disabled(String name)
    {
        for(String disabled : Morph.config.disabledAbilities)
            if(disabled.equalsIgnoreCase("passive:" + name) || disabled.equalsIgnoreCase("extraPassives")) return true;
        return false;
    }
    private static boolean mapped(MorphInfo info, String type, Potion potion)
    {
        if(info == null || info.nextState.abilities == null) return false;
        for(Ability ability : info.nextState.abilities)
            if(!type.isEmpty() && ability.getType().equals(type) || potion != null && ability instanceof AbilityPotionEffect
                    && ((AbilityPotionEffect)ability).potionId == Potion.getIdFromPotion(potion)) return true;
        return false;
    }
    private static void mark(EntityPlayer p, Potion potion)
    {
        if(!p.getEntityData().hasKey(SAVED)) p.getEntityData().setTag(SAVED, new NBTTagCompound());
        p.getEntityData().getCompoundTag(SAVED).setBoolean(potion.getRegistryName().toString(), true);
    }
    private static void unmark(EntityPlayer p, Potion potion)
    { p.getEntityData().getCompoundTag(SAVED).removeTag(potion.getRegistryName().toString()); }
    private static final class OwnedPotionEffect extends PotionEffect
    {
        private final EntityPlayer owner;
        private boolean external;
        OwnedPotionEffect(EntityPlayer owner, Potion potion, int ticks)
        { super(potion, ticks, 0, true, false); this.owner = owner; }
        @Override public void combine(PotionEffect effect)
        {
            if(!(effect instanceof OwnedPotionEffect)) { external = true; unmark(owner, getPotion()); }
            super.combine(effect);
        }
    }
}
