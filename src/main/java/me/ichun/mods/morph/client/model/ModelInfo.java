package me.ichun.mods.morph.client.model;

import me.ichun.mods.ichunutil.client.model.util.ModelHelper;
import me.ichun.mods.morph.common.Morph;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.world.GameType;

import java.util.ArrayList;

public class ModelInfo
{
    public final Class entClass;
    public final Render entRenderer;
    public final ModelBase modelParent;
    public final ArrayList<ModelRenderer> modelList;
    public final ModelRenderer[] modelArms;

    public ModelInfo(Class clz, Render rend, ModelBase model)
    {
        entClass = clz;
        entRenderer = rend;
        modelParent = model;
        modelList = ModelHelper.getModelCubes(model);
        modelArms = ModelHelper.getPotentialArms(modelParent);
        // The legacy helper knows legs/wings, but omits vanilla tentacles entirely.
        if(modelParent instanceof net.minecraft.client.model.ModelGhast)
            tentacleArms(modelParent, net.minecraft.client.model.ModelGhast.class, "tentacles", "field_78127_b");
        else if(modelParent instanceof net.minecraft.client.model.ModelSquid)
            tentacleArms(modelParent, net.minecraft.client.model.ModelSquid.class, "squidTentacles", "field_78201_b");
    }

    private void tentacleArms(ModelBase model, Class<?> owner, String mcp, String srg)
    {
        try
        {
            java.lang.reflect.Field field = net.minecraftforge.fml.relauncher.ReflectionHelper.findField(owner, mcp, srg);
            ModelRenderer[] tentacles = (ModelRenderer[])field.get(model);
            modelArms[0] = tentacles[0];
            modelArms[1] = tentacles[1];
        }
        catch(ReflectiveOperationException ex)
        {
            throw new IllegalStateException("Cannot obtain vanilla tentacles for POV", ex);
        }
    }

    public void forceRender(Entity ent, double d, double d1, double d2, float f, float f1)
    {
        if(Minecraft.getMinecraft().getRenderManager().renderEngine != null && Minecraft.getMinecraft().getRenderManager().renderViewEntity != null)
        {
            try
            {
                GameType oriGameType = null;
                NetworkPlayerInfo npi = null;
                if(ent instanceof EntityOtherPlayerMP)
                {
                    npi = Minecraft.getMinecraft().getConnection().getPlayerInfo(((EntityOtherPlayerMP)ent).getGameProfile().getId());
                    if(npi != null)
                    {
                        oriGameType = npi.getGameType();
                        npi.setGameType(GameType.ADVENTURE);
                    }
                }
                entRenderer.doRender(ent, d, d1, d2, f, f1);
                if(npi != null)
                {
                    npi.setGameType(oriGameType);
                }
            }
            catch(Exception e)
            {
                Morph.LOGGER.warn("A morph/model is causing an exception when Morph tries to render it! You might want to report this to the author of the Morphed mob (Not to Morph!): " + entRenderer.getClass().getName());
                e.printStackTrace();
            }
        }
    }
}
