package me.ichun.mods.morph.client.ability;

import me.ichun.mods.morph.common.Morph;
import me.ichun.mods.morph.common.morph.MorphInfo;
import me.ichun.mods.morph.common.packet.PacketAbilityUse;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.PotionTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionUtils;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import java.util.HashMap;
import java.util.Map;

/** A single vanilla-sized ability slot, beside the hotbar on the free-hand side. */
@SideOnly(Side.CLIENT)
public final class AbilityClient
{
    public static final KeyBinding USE = new KeyBinding("key.morph.active", Keyboard.KEY_R, "key.categories.morph");
    private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");
    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static String name = "";
    private static int remaining, total, nameTicks;

    public static void init()
    {
        ClientRegistry.registerKeyBinding(USE);
        MinecraftForge.EVENT_BUS.register(new AbilityClient());
        icon("echo", new ItemStack(Items.ENDER_EYE));
        icon("blaze", new ItemStack(Items.BLAZE_POWDER));
        icon("web", new ItemStack(Blocks.WEB));
        icon("egg", new ItemStack(Items.EGG));
        icon("cleanse", new ItemStack(Items.MILK_BUCKET));
        icon("explode", new ItemStack(Blocks.TNT));
        icon("charge", new ItemStack(Items.SADDLE));
        icon("breath", new ItemStack(Items.DRAGON_BREATH));
        icon("teleport", new ItemStack(Items.ENDER_PEARL));
        icon("blink", new ItemStack(Items.CHORUS_FRUIT));
        icon("fangs", new ItemStack(Items.QUARTZ));
        icon("ghast", new ItemStack(Items.FIRE_CHARGE));
        icon("stomp", new ItemStack(Items.IRON_BOOTS));
        icon("beam", new ItemStack(Items.PRISMARINE_SHARD));
        icon("elder_beam", new ItemStack(Items.PRISMARINE_CRYSTALS));
        icon("slam", new ItemStack(Blocks.IRON_BLOCK));
        icon("spit", new ItemStack(Items.SLIME_BALL));
        icon("magma", new ItemStack(Items.MAGMA_CREAM));
        icon("soup", new ItemStack(Items.MUSHROOM_STEW));
        icon("pounce", new ItemStack(Items.RABBIT_HIDE));
        icon("screech", new ItemStack(Items.FEATHER));
        icon("sprint", new ItemStack(Items.CARROT_ON_A_STICK));
        icon("rally", new ItemStack(Items.GOLDEN_SWORD));
        icon("maul", new ItemStack(Items.FISH));
        icon("jump", new ItemStack(Items.RABBIT_FOOT));
        icon("graze", new ItemStack(Items.WHEAT));
        icon("levitate", new ItemStack(Items.SHULKER_SHELL));
        icon("scurry", new ItemStack(Items.FLINT));
        icon("arrow", new ItemStack(Items.ARROW));
        icon("slime", new ItemStack(Blocks.SLIME_BLOCK));
        icon("snowball", new ItemStack(Items.SNOWBALL));
        icon("ink", new ItemStack(Items.DYE, 1, 0));
        icon("stray_arrow", PotionUtils.addPotionToItemStack(new ItemStack(Items.TIPPED_ARROW), PotionTypes.SLOWNESS));
        icon("dash", new ItemStack(Items.SUGAR));
        icon("barter", new ItemStack(Items.EMERALD));
        icon("cleave", new ItemStack(Items.IRON_AXE));
        icon("witch", PotionUtils.addPotionToItemStack(new ItemStack(Items.SPLASH_POTION), PotionTypes.POISON));
        icon("skull", new ItemStack(Items.SKULL, 1, 1));
        icon("wither_strike", new ItemStack(Items.STONE_SWORD));
        icon("howl", new ItemStack(Items.BONE));
        icon("hunger", new ItemStack(Items.ROTTEN_FLESH));
        icon("lunge", new ItemStack(Items.IRON_SWORD));
        icon("stand", new ItemStack(Items.ARMOR_STAND));
        icon("illusion", new ItemStack(Items.GOLDEN_CARROT));
    }
    private static void icon(String action, ItemStack stack) { ICONS.put(action, stack); }
    public static ItemStack iconFor(String action)
    {
        ItemStack icon = ICONS.get(action);
        return icon == null ? ItemStack.EMPTY : icon.copy();
    }
    public static int remainingTicks() { return remaining; }
    public static void receive(String key, int ticks, int duration)
    {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if(!name.equals(key)) nameTicks = key.isEmpty() ? 0 : 40;
            name = key;
            total = Math.max(1, duration);
            remaining = Math.min(total, Math.max(0, ticks));
        });
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event)
    {
        if(event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if(mc.world == null || mc.player == null) { name = ""; remaining = nameTicks = 0; return; }
        if(!mc.isGamePaused())
        {
            if(remaining > 0) remaining--;
            if(nameTicks > 0) nameTicks--;
        }
        while(USE.isPressed())
            if(mc.currentScreen == null && !mc.player.isSpectator() && !mc.playerController.isSpectator() && mc.player.isEntityAlive())
                Morph.channel.sendToServer(new PacketAbilityUse());
    }
    public static boolean shouldShow()
    {
        Minecraft mc = Minecraft.getMinecraft();
        if(name.isEmpty() || mc.player == null || mc.world == null || mc.currentScreen != null
                || mc.gameSettings.hideGUI || mc.player.isSpectator() || mc.playerController == null
                || mc.playerController.isSpectator() || !mc.player.isEntityAlive()
                || mc.getRenderViewEntity() != mc.player || Morph.eventHandlerClient == null) return false;
        MorphInfo info = Morph.eventHandlerClient.morphsActive.get(mc.player.getName());
        return info != null && !info.isMorphing();
    }
    public static int[] hudBounds()
    {
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc);
        // Vanilla's hotbar attack meter lives on this same side: reserve its 22px too.
        int gap = mc.gameSettings.attackIndicator == 2 ? 27 : 5;
        boolean right = mc.player == null || mc.player.getPrimaryHand() == EnumHandSide.RIGHT;
        int x = right ? sr.getScaledWidth()/2 + 91 + gap : sr.getScaledWidth()/2 - 91 - gap - 22;
        return new int[]{x, sr.getScaledHeight()-22, 22, 22};
    }
    @SubscribeEvent public void render(RenderGameOverlayEvent.Post event)
    {
        if(event.getType() != RenderGameOverlayEvent.ElementType.ALL || !shouldShow()) return;
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack icon = iconFor(name.substring("morph.active.".length()));
        if(icon.isEmpty()) return;
        int[] bounds = hudBounds();
        int x = bounds[0], y = bounds[1];
        GlStateManager.pushMatrix();
        try
        {
            GlStateManager.color(1, 1, 1, 1);
            mc.getTextureManager().bindTexture(WIDGETS);
            Gui.drawModalRectWithCustomSizedTexture(x, y, 0, 0, 22, 22, 256, 256);
            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.enableRescaleNormal();
            mc.getRenderItem().renderItemAndEffectIntoGUI(icon, x+3, y+3);
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.disableDepth();
            GlStateManager.enableAlpha();
            if(remaining > 0)
            {
                float fraction = Math.max(0, (remaining-event.getPartialTicks())/(float)total);
                int top = (int)Math.floor(16*(1-fraction));
                Gui.drawRect(x+3, y+3+top, x+19, y+19, 0x7FFFFFFF);
                String seconds = Integer.toString((int)Math.ceil(remaining/20F));
                mc.fontRenderer.drawStringWithShadow(seconds, x+20-mc.fontRenderer.getStringWidth(seconds), y+13, 0xFFFFFF);
            }
            // Keep the key legible at GUI scale 1; use whole vanilla font pixels.
            String key = USE.getDisplayName();
            if(key.length() <= 3)
            {
                mc.fontRenderer.drawStringWithShadow(key, x+(22-mc.fontRenderer.getStringWidth(key))/2, y-9, 0xB0B0B0);
            }
            // Match vanilla's brief selected-item title; never retain a wide panel on screen.
            if(nameTicks > 0 && mc.player.getHeldItemMainhand().isEmpty())
            {
                String title = I18n.format(name) + " [" + key + "]";
                int alpha = Math.min(255, nameTicks*32);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                        GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
                mc.fontRenderer.drawStringWithShadow(title,
                        (event.getResolution().getScaledWidth()-mc.fontRenderer.getStringWidth(title))/2,
                        event.getResolution().getScaledHeight()-59, (alpha<<24)|0xFFFFFF);
            }
        }
        finally
        {
            GlStateManager.enableDepth();
            GlStateManager.enableAlpha();
            GlStateManager.disableBlend();
            GlStateManager.color(1, 1, 1, 1);
            GlStateManager.popMatrix();
        }
    }
}
