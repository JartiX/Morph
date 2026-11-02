package me.ichun.mods.morph.common.core;

import me.ichun.mods.morph.common.Morph;
import me.ichun.mods.morph.common.morph.MorphInfo;
import me.ichun.mods.morph.common.morph.MorphState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.entity.monster.EntitySpider;
import net.minecraft.entity.player.EntityPlayer;

/** Hooks called by the two small vanilla patches; safe before mod initialization. */
public final class MorphTraits
{
    private MorphTraits() {}

    private static EntityLivingBase form(EntityLivingBase entity)
    {
        if(!(entity instanceof EntityPlayer) || Morph.config == null || Morph.config.abilities != 1
                || entity.world == null || Morph.eventHandlerServer == null) return entity;
        for(String disabled : Morph.config.disabledAbilities)
            if("vanillaTraits".equalsIgnoreCase(disabled)) return entity;
        MorphInfo info = entity.world.isRemote
                ? clientInfo(entity.getName()) : Morph.eventHandlerServer.morphsActive.get(entity.getName());
        if(info == null) return entity;
        MorphState state = info.isMorphing() && info.prevState != null ? info.prevState : info.nextState;
        return state == null ? entity : state.getEntInstance(entity.world);
    }

    @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
    private static MorphInfo clientInfo(String name)
    {
        return Morph.eventHandlerClient == null ? null : Morph.eventHandlerClient.morphsActive.get(name);
    }

    public static EnumCreatureAttribute creatureAttribute(EntityLivingBase entity)
    {
        EntityLivingBase shape = form(entity);
        // The original EntityLivingBase implementation returns UNDEFINED. Subclasses
        // (zombies, skeletons, spiders...) keep their own overrides unchanged.
        return shape == entity || shape instanceof EntityPlayer
                ? EnumCreatureAttribute.UNDEFINED : shape.getCreatureAttribute();
    }

    public static boolean ignoresWeb(EntityPlayer player)
    {
        return form(player) instanceof EntitySpider;
    }
}
