package me.ichun.mods.morph.client.render;

import me.ichun.mods.ichunutil.client.model.util.ModelHelper;
import me.ichun.mods.ichunutil.common.core.util.ObfHelper;
import me.ichun.mods.morph.client.model.ModelInfo;
import me.ichun.mods.morph.client.model.ModelMorph;
import me.ichun.mods.morph.client.morph.MorphInfoClient;
import me.ichun.mods.morph.common.Morph;
import me.ichun.mods.morph.common.handler.PlayerMorphHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

public class RenderPlayerHand extends RenderPlayer
{
    public RenderPlayer parent;

    public MorphInfoClient clientInfo;

    public float renderTick;

    public ModelMorph rightHandInterim;
    public ModelMorph leftHandInterim;

    public RenderPlayerHand()
    {
        super(Minecraft.getMinecraft().getRenderManager());

        rightHandInterim = new ModelMorph();
        leftHandInterim = new ModelMorph();
    }

    public void reset(World world, MorphInfoClient info)
    {
        rightHandInterim.clean();
        leftHandInterim.clean();
        if(info != null && world != null)
        {
            rightHandInterim.prevModels.clear();
            rightHandInterim.nextModels.clear();

            leftHandInterim.prevModels.clear();
            leftHandInterim.nextModels.clear();

            ModelInfo prevModel = info.getPrevStateModel(world);
            ModelInfo nextModel = info.getNextStateModel(world);

            if(prevModel != null && prevModel.modelArms[0] != null)
            {
                rightHandInterim.prevModels.add(prevModel.modelArms[0]);
            }
            if(nextModel.modelArms[0] != null)
            {
                rightHandInterim.nextModels.add(nextModel.modelArms[0]);
            }

            if(prevModel != null && prevModel.modelArms[1] != null)
            {
                leftHandInterim.prevModels.add(prevModel.modelArms[1]);
            }
            if(nextModel.modelArms[1] != null)
            {
                leftHandInterim.nextModels.add(nextModel.modelArms[1]);
            }

            // prepareReferences pads cube/child lists; never give it cached vanilla limbs.
            copyReferences(rightHandInterim.prevModels, rightHandInterim);
            copyReferences(rightHandInterim.nextModels, rightHandInterim);
            copyReferences(leftHandInterim.prevModels, leftHandInterim);
            copyReferences(leftHandInterim.nextModels, leftHandInterim);
            rightHandInterim.prepareReferences();
            leftHandInterim.prepareReferences();

            rightHandInterim.modelList.clear();
            leftHandInterim.modelList.clear();

            if(prevModel != null)
            {
                rightHandInterim.modelList.addAll(ModelHelper.getModelCubesCopy(rightHandInterim.prevModels, rightHandInterim, null));
                leftHandInterim.modelList.addAll(ModelHelper.getModelCubesCopy(leftHandInterim.prevModels, leftHandInterim, null));
            }
        }
    }

    private static void copyReferences(java.util.ArrayList<ModelRenderer> models, ModelMorph owner)
    {
        java.util.ArrayList<ModelRenderer> copies = ModelHelper.getModelCubesCopy(models, owner, null);
        models.clear();
        models.addAll(copies);
    }

    @Override
    public void renderRightArm(AbstractClientPlayer player)
    {
        if(clientInfo == null) super.renderRightArm(player);
        else renderLimb(player, 0);
    }

    @Override
    public void renderLeftArm(AbstractClientPlayer player)
    {
        if(clientInfo == null) super.renderLeftArm(player);
        else renderLimb(player, 1);
    }

    /** ItemRenderer supplies vanilla equip/swing transforms for BOTH hands. */
    private void renderLimb(AbstractClientPlayer player, int side)
    {
        float transition = clientInfo.getMorphTransitionProgress(renderTick);
        ModelRenderer limb;
        ResourceLocation texture;
        boolean playerArm = false;
        ModelRenderer sleeve = null;
        if(clientInfo.isMorphing() && clientInfo.morphTime >= 10
                && clientInfo.morphTime <= Morph.config.morphTime - 10)
        {
            ModelMorph interim = side == 0 ? rightHandInterim : leftHandInterim;
            if(interim.modelList.isEmpty()) return;
            interim.updateModelList(transition, interim.modelList, interim.prevModels, interim.nextModels, 0);
            limb = interim.modelList.get(0);
            texture = PlayerMorphHandler.morphSkin;
        }
        else
        {
            boolean previous = clientInfo.isMorphing() && clientInfo.morphTime < 10 && clientInfo.prevState != null;
            ModelInfo model = previous ? clientInfo.getPrevStateModel(player.world) : clientInfo.getNextStateModel(player.world);
            net.minecraft.entity.EntityLivingBase entity = previous
                    ? clientInfo.prevState.getEntInstance(player.world) : clientInfo.nextState.getEntInstance(player.world);
            if(model == null) return;
            limb = model.modelArms[side];
            if(limb == null) return; // A form without a limb must not borrow a human arm.
            texture = ObfHelper.getEntityTexture(model.entRenderer, model.entRenderer.getClass(), entity);
            playerArm = entity instanceof AbstractClientPlayer;
            if(playerArm && model.modelParent instanceof ModelPlayer
                    && ((AbstractClientPlayer)entity).isWearing(side == 0
                    ? net.minecraft.entity.player.EnumPlayerModelParts.RIGHT_SLEEVE
                    : net.minecraft.entity.player.EnumPlayerModelParts.LEFT_SLEEVE))
            {
                ModelPlayer skinModel = (ModelPlayer)model.modelParent;
                sleeve = side == 0 ? skinModel.bipedRightArmwear : skinModel.bipedLeftArmwear;
            }
        }
        if(texture == null) return;
        bindTexture(texture);
        renderAnchored(limb, side, playerArm);
        if(sleeve != null) renderAnchored(sleeve, side, true);
        float alpha = clientInfo.getMorphSkinAlpha(renderTick);
        if(alpha > 0.0F)
        {
            bindTexture(PlayerMorphHandler.morphSkin);
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.color(1F, 1F, 1F, alpha);
            try
            {
                renderAnchored(limb, side, playerArm);
                if(sleeve != null) renderAnchored(sleeve, side, true);
            }
            finally { GlStateManager.color(1F, 1F, 1F, 1F); GlStateManager.disableBlend(); }
        }
    }

    /**
     * Anchor the shoulder at the vanilla shoulder, rather than aligning a short
     * paw's tip to a human wrist (which left a floating piece in the old renderer).
     * Native texture/cubes are retained; long horizontal spider legs are oriented
     * down the forearm before normalizing their reach into the camera edge.
     */
    private void renderAnchored(ModelRenderer limb, int side, boolean playerArm)
    {
        if(limb.cubeList.isEmpty()) return;
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for(net.minecraft.client.model.ModelBox box : limb.cubeList)
        {
            minX = Math.min(minX, box.posX1); maxX = Math.max(maxX, box.posX2);
            minY = Math.min(minY, box.posY1); maxY = Math.max(maxY, box.posY2);
            minZ = Math.min(minZ, box.posZ1); maxZ = Math.max(maxZ, box.posZ2);
        }
        float turnZ = 0, turnX = 0;
        float width = maxX-minX, height = maxY-minY, depth = maxZ-minZ;
        if(!playerArm && width > height && width > 6)
        {
            turnZ = side == 0 ? -90 : 90;
            float a = minX, b = maxX;
            minX = turnZ < 0 ? minY : -maxY; maxX = turnZ < 0 ? maxY : -minY;
            minY = turnZ < 0 ? -b : a; maxY = turnZ < 0 ? -a : b;
        }
        else if(!playerArm && depth > height && depth > 6)
        {
            turnX = -90;
            float a = minY, b = maxY;
            minY = minZ; maxY = maxZ; minZ = -b; maxZ = -a;
        }
        float sx = Math.min(1F, 6F / Math.max(1F, maxX-minX));
        float sy = playerArm ? 1F : 12F / Math.max(1F, maxY-minY);
        float sz = Math.min(1F, 6F / Math.max(1F, maxZ-minZ));
        float px = limb.rotationPointX, py = limb.rotationPointY, pz = limb.rotationPointZ;
        float ax = limb.rotateAngleX, ay = limb.rotateAngleY, az = limb.rotateAngleZ;
        float ox = limb.offsetX, oy = limb.offsetY, oz = limb.offsetZ;
        boolean hidden = limb.isHidden, shown = limb.showModel;
        GlStateManager.pushMatrix();
        try
        {
            GlStateManager.translate(side == 0 ? -5F/16F : 5F/16F, 2F/16F, 0F);
            GlStateManager.rotate(side == 0 ? 5.7F : -5.7F, 0, 0, 1);
            // Preserve the asymmetric native human arm placement, center other limbs.
            float center = playerArm ? 0F : -(minX+maxX)*0.5F*sx;
            GlStateManager.translate(center/16F, (-2F-minY*sy)/16F,
                    playerArm ? 0F : -(minZ+maxZ)*0.5F*sz/16F);
            GlStateManager.scale(sx, sy, sz);
            if(turnZ != 0) GlStateManager.rotate(turnZ, 0, 0, 1);
            if(turnX != 0) GlStateManager.rotate(turnX, 1, 0, 0);
            limb.rotationPointX = limb.rotationPointY = limb.rotationPointZ = 0;
            limb.rotateAngleX = limb.rotateAngleY = limb.rotateAngleZ = 0;
            limb.offsetX = limb.offsetY = limb.offsetZ = 0;
            limb.isHidden = false; limb.showModel = true;
            limb.render(1F/16F);
        }
        finally
        {
            limb.rotationPointX=px; limb.rotationPointY=py; limb.rotationPointZ=pz;
            limb.rotateAngleX=ax; limb.rotateAngleY=ay; limb.rotateAngleZ=az;
            limb.offsetX=ox; limb.offsetY=oy; limb.offsetZ=oz;
            limb.isHidden=hidden; limb.showModel=shown;
            GlStateManager.popMatrix();
        }
    }
}
