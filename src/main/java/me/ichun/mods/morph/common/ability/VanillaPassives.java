package me.ichun.mods.morph.common.ability;

import me.ichun.mods.morph.common.Morph;
import me.ichun.mods.morph.common.morph.MorphInfo;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.MobEffects;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSourceIndirect;
import net.minecraftforge.event.entity.living.*;
import java.util.UUID;

/** Vanilla traits which are not supplied by the original JSON ability implementations. */
final class VanillaPassives
{
    private static final UUID SHELL_ARMOR_ID = UUID.fromString("77f64267-3278-414b-a811-f091c25f88b0");
    private static final AttributeModifier SHELL_ARMOR = new AttributeModifier(
            SHELL_ARMOR_ID, "Morph closed shulker shell", 20D, 0).setSaved(false);

    private static MorphInfo state(EntityPlayer p)
    {
        if(p.world.isRemote || Morph.config.abilities != 1 || p.isSpectator() || !p.isEntityAlive()) return null;
        for(String disabled : Morph.config.disabledAbilities) if("vanillaTraits".equalsIgnoreCase(disabled)) return null;
        MorphInfo info = Morph.eventHandlerServer.morphsActive.get(p.getName());
        return info == null || info.isMorphing() ? null : info;
    }
    private static boolean noDrown(MorphInfo info)
    {
        EntityLivingBase form = info.nextState.getEntInstance(info.getPlayer().world);
        String id = info.nextState.currentVariant.entId;
        return form != null && (form.canBreatheUnderwater() || form.isEntityUndead())
                || "minecraft:villager_golem".equals(id) || "minecraft:snowman".equals(id);
    }
    static void tick(EntityPlayer p)
    {
        MorphInfo info = state(p);
        // Check removal before returning: leaving the form, spectator and disabled traits must clean up.
        boolean closedShell = info != null && p.isSneaking()
                && "minecraft:shulker".equals(info.nextState.currentVariant.entId);
        IAttributeInstance armor = p.getEntityAttribute(SharedMonsterAttributes.ARMOR);
        if(closedShell && armor.getModifier(SHELL_ARMOR_ID) == null) armor.applyModifier(SHELL_ARMOR);
        else if(!closedShell && armor.getModifier(SHELL_ARMOR_ID) != null) armor.removeModifier(SHELL_ARMOR_ID);
        if(info == null) return;
        if("minecraft:wither".equals(info.nextState.currentVariant.entId) && p.ticksExisted % 20 == 0
                && p.getHealth() < p.getMaxHealth()) p.heal(1F);
        if(noDrown(info)) p.setAir(300);
        if(info.nextState.getEntInstance(info.getPlayer().world) != null && info.nextState.getEntInstance(info.getPlayer().world).isEntityUndead())
        {
            p.removePotionEffect(MobEffects.POISON);
            p.removePotionEffect(MobEffects.REGENERATION);
        }
        if("minecraft:snowman".equals(info.nextState.currentVariant.entId)
                && p.world.getBiome(p.getPosition()).getTemperature(p.getPosition()) > 1F)
            p.attackEntityFrom(DamageSource.ON_FIRE, 1F);
    }
    static void attack(LivingAttackEvent event)
    {
        if(!(event.getEntityLiving() instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer)event.getEntityLiving();
        MorphInfo info = state(player);
        if(info == null) return;
        if(event.getSource() == DamageSource.DROWN && noDrown(info)) event.setCanceled(true);
        String id = info.nextState.currentVariant.entId;
        if(event.getSource().getImmediateSource() instanceof EntityArrow
                && (id.equals("minecraft:shulker") && player.isSneaking()
                || id.equals("minecraft:wither") && player.getHealth() <= player.getMaxHealth() / 2F))
            event.setCanceled(true);
        if(id.equals("minecraft:enderman") && event.getSource() instanceof EntityDamageSourceIndirect)
        {
            event.setCanceled(true);
            ActiveAbilityHandler.escapeProjectile(player);
        }
    }
    static void knockback(LivingKnockBackEvent event)
    {
        if(!(event.getEntityLiving() instanceof EntityPlayer)) return;
        MorphInfo info = state((EntityPlayer)event.getEntityLiving());
        if(info != null && "minecraft:villager_golem".equals(info.nextState.currentVariant.entId)) event.setCanceled(true);
    }
    static void hurt(LivingHurtEvent event)
    {
        if(event.getAmount() <= 0 || event.getEntityLiving().world.isRemote) return;
        if(event.getEntityLiving() instanceof EntityPlayer && event.getSource().isMagicDamage())
        {
            MorphInfo victim = state((EntityPlayer)event.getEntityLiving());
            if(victim != null && "minecraft:witch".equals(victim.nextState.currentVariant.entId))
                event.setAmount(event.getAmount() * 0.15F);
        }
        if(event.getSource().getTrueSource() instanceof EntityPlayer)
        {
            EntityPlayer owner = (EntityPlayer)event.getSource().getTrueSource();
            MorphInfo info = state(owner);
            if(info != null && ActiveAbilityHandler.mayHit(owner, event.getEntityLiving()))
            {
                String id = info.nextState.currentVariant.entId;
                if(event.getSource().getImmediateSource() instanceof EntityArrow && id.equals("minecraft:stray"))
                    event.getEntityLiving().addPotionEffect(new PotionEffect(MobEffects.SLOWNESS, 600, 0));
                if("player".equals(event.getSource().getDamageType()) && event.getSource().getImmediateSource() == owner)
                {
                    if(id.equals("minecraft:husk")) event.getEntityLiving().addPotionEffect(new PotionEffect(MobEffects.HUNGER, 140, 0));
                    if((id.equals("minecraft:zombie") || id.equals("minecraft:zombie_villager") || id.equals("minecraft:husk"))
                            && owner.isBurning() && owner.getHeldItemMainhand().isEmpty()) event.getEntityLiving().setFire(4);
                }
            }
        }
        if(event.getEntityLiving() instanceof EntityPlayer && !event.getSource().isMagicDamage()
                && event.getSource().getImmediateSource() instanceof EntityLivingBase)
        {
            EntityPlayer player = (EntityPlayer)event.getEntityLiving();
            MorphInfo info = state(player);
            if(info == null) return;
            String id = info.nextState.currentVariant.entId;
            EntityLivingBase attacker = (EntityLivingBase)event.getSource().getImmediateSource();
            if((id.equals("minecraft:guardian") || id.equals("minecraft:elder_guardian"))
                    && player.motionX * player.motionX + player.motionZ * player.motionZ < 0.0025
                    && ActiveAbilityHandler.mayHit(player, attacker))
                attacker.attackEntityFrom(DamageSource.causeThornsDamage(player), 2F);
        }
    }
}
