package me.ichun.mods.morph.validation;

import me.ichun.mods.morph.common.Morph;
import me.ichun.mods.morph.common.ability.ActiveAbility;
import me.ichun.mods.morph.common.ability.ActiveAbilityHandler;
import me.ichun.mods.morph.common.ability.AbilitySounds;
import me.ichun.mods.morph.client.ability.AbilityClient;
import me.ichun.mods.morph.common.handler.PlayerMorphHandler;
import me.ichun.mods.morph.common.mob.MobRelations;
import me.ichun.mods.morph.common.morph.MorphInfo;
import me.ichun.mods.morph.client.model.ModelHandler;
import me.ichun.mods.morph.client.model.ModelInfo;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelBox;
import net.minecraft.entity.projectile.EntityTippedArrow;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.resources.Language;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.MobEffects;
import net.minecraft.potion.PotionEffect;
import net.minecraft.init.Items;
import net.minecraft.item.ItemMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.DamageSource;
import net.minecraft.util.SoundCategory;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Future;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.nbt.NBTTagCompound;

/** Development-only real-client acceptance fixture. Never included in normal builds. */
@Mod(modid = "morph_validation", name = "Morph Runtime Validation", version = "1", clientSideOnly = true,
        dependencies = "required-after:morph")
public final class MorphValidation
{
    private static final String[] FORMS = {"zombie", "skeleton", "cow", "wolf", "spider", "chicken",
            "creeper", "enderman", "villager_golem", "ghast", "player"};
    private static final String[] SCENES = {"idle", "swing", "item", "map"};
    private final Minecraft mc = Minecraft.getMinecraft();
    private File output;
    private String worldName;
    private boolean started, capture;
    private volatile boolean stopped;
    private int form, scene, wait, elapsed, screenshots;
    private Future<?> pending;
    private ItemStack map = ItemStack.EMPTY;
    private volatile boolean fixtureReady;
    private ActiveAbility cooldownProbe;
    private long cooldownProbeAt;
    private volatile boolean screenshotsDone;
    private int suitePhase, suiteWait;
    private ActiveAbility preservedAbility;
    private long preservedUntil;
    private EntityWolf wolf;
    private EntitySkeleton skeleton;
    private double fleeStart;
    private int blocksBefore;
    private int swingFrame, extraScreenshots, abilityIndex, abilityPhase;
    private List<Map.Entry<String, ActiveAbility>> abilityCases;
    private final boolean visualOnly = Boolean.getBoolean("morph.validation.visualOnly");
    private final Map<ModelRenderer, String> modelBaseline = new IdentityHashMap<>();
    private boolean allFormsDone;
    private int traitPhase, traitWait;
    private float traitHealth;
    private final boolean hudOnly = Boolean.getBoolean("morph.validation.hudOnly");
    private final boolean autoExit = Boolean.getBoolean("morph.validation.autoExit");
    private static final String[] HUD_FORMS = {"zombie", "creeper", "spider", "chicken", "shulker"};
    private final Set<ResourceLocation> heardSounds = new HashSet<>();
    private Future<?> hudPending;
    private int hudForm, hudPhase, hudWait, hudLayout, hudState, hudCaptures;
    private String hudCapture;
    private boolean hudExpectVisible = true, hudInitialized;
    private volatile boolean hudServerReady;
    private volatile int hudFreezeCooldown = -1;
    private EntityCow hudTarget;
    private Language hudOriginalLanguage;
    private boolean hudOriginalUnicode, hudLanguageOverridden, hudPresentation;
    private int presentationForm;
    private static final String[] PRESENTATION_FORMS = {"creeper", "spider", "chicken", "shulker"};

    @Mod.EventHandler
    public void init(FMLInitializationEvent event)
    {
        if(Boolean.getBoolean("morph.validation.soundsOnly")) return;
        MinecraftForge.EVENT_BUS.register(this);
    }

    private synchronized void record(String line)
    {
        try
        {
            Files.write(new File(output, "report.txt").toPath(), (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("[MorphValidation] " + line);
        }
        catch(Exception e) { throw new IllegalStateException("Cannot write validation evidence", e); }
    }

    private static void require(boolean condition, String message)
    {
        if(!condition) throw new IllegalStateException(message);
    }

    @SubscribeEvent
    public void client(TickEvent.ClientTickEvent event)
    {
        if(event.phase != TickEvent.Phase.END) return;
        if(stopped)
        {
            if(autoExit) mc.shutdown();
            return;
        }
        try
        {
            if(!started)
            {
                if(!(mc.currentScreen instanceof GuiMainMenu)) return;
                started = true;
                worldName = "MorphValidation-" + System.currentTimeMillis();
                output = new File(mc.gameDir, "morph-validation/" + worldName);
                require(output.mkdirs(), "Cannot create fresh evidence directory");
                record("START world=" + worldName + " seed=123456789; framebuffer captures, no synthetic images");
                snapshotModels();
                mc.gameSettings.pauseOnLostFocus = false;
                mc.gameSettings.thirdPersonView = 0;
                mc.gameSettings.viewBobbing = false;
                mc.gameSettings.fovSetting = 70;
                mc.gameSettings.limitFramerate = 60;
                mc.gameSettings.renderDistanceChunks = 4;
                WorldSettings settings = new WorldSettings(123456789L, GameType.SURVIVAL, false, false, WorldType.FLAT);
                settings.enableCommands();
                mc.launchIntegratedServer(worldName, worldName, settings);
                return;
            }
            if(++elapsed > 24000) throw new IllegalStateException("Validation timeout");
            if(mc.player == null || mc.world == null || mc.getIntegratedServer() == null) return;
            if(hudOnly) { hud(); return; }
            if(screenshotsDone) return;
            mc.gameSettings.thirdPersonView = 0;
            mc.player.rotationYaw = mc.player.prevRotationYaw = 0;
            mc.player.rotationPitch = mc.player.prevRotationPitch = 15;
            mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
            if(pending != null)
            {
                if(!pending.isDone()) return;
                pending.get();
                pending = null;
                wait = Morph.config.morphTime + 50;
                return;
            }
            if(!fixtureReady)
            {
                pending = mc.getIntegratedServer().addScheduledTask(() -> {
                    EntityPlayerMP p = player();
                    p.world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
                    p.world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
                    p.world.getGameRules().setOrCreateGameRule("doWeatherCycle", "false");
                    p.world.getGameRules().setOrCreateGameRule("mobGriefing", "false");
                    p.world.setWorldTime(6000);
                    p.world.getWorldInfo().setRaining(false);
                    map = ItemMap.setupNewMap(p.world, 0, 0, (byte)0, true, false);
                    for(String id : FORMS)
                    {
                        if("player".equals(id)) continue;
                        Entity entity = EntityList.createEntityByIDFromName(new ResourceLocation("minecraft", id), p.world);
                        require(entity instanceof EntityLivingBase, "Missing entity " + id);
                        require(PlayerMorphHandler.getInstance().acquireMorph(p, (EntityLivingBase)entity, false, false), "Cannot acquire " + id);
                        require(Morph.eventHandlerServer.getPlayerMorphs(p).stream().anyMatch(v -> v.entId.equals("minecraft:" + id)), "Acquisition missing " + id);
                        record("PASS acquired minecraft:" + id);
                    }
                    fixtureReady = true;
                    beginForm();
                });
                return;
            }
            if(wait-- > 0) return;
            String expected = FORMS[form];
            MorphInfo info = Morph.eventHandlerClient.morphsActive.get(mc.player.getName());
            if("player".equals(expected)) require(info == null || info.nextState.currentVariant.entId.startsWith("player:"), "Client did not demorph");
            else require(info != null && !info.isMorphing() && info.nextState.currentVariant.entId.equals("minecraft:" + expected), "Client form mismatch: " + expected);
            if(scene == 0)
            {
                pending = mc.getIntegratedServer().addScheduledTask(this::checkForm);
                scene = 1;
                return;
            }
            if(!capture)
            {
                int index = scene - 1;
                mc.gameSettings.mainHand = index < 4 ? EnumHandSide.RIGHT : EnumHandSide.LEFT;
                mc.gameSettings.sendSettingsToServer();
                final int kind = index % 4;
                mc.getIntegratedServer().addScheduledTask(() -> {
                    EntityPlayerMP p = player();
                    p.inventory.currentItem = 0;
                    p.inventory.setInventorySlotContents(0, kind == 2 ? new ItemStack(Items.DIAMOND_SWORD) : kind == 3 ? map.copy() : ItemStack.EMPTY);
                    p.inventoryContainer.detectAndSendChanges();
                });
                capture = true;
                wait = 25;
            }
        }
        catch(Exception e) { fail(e); }
    }

    private EntityPlayerMP player()
    {
        require(mc.getIntegratedServer().getWorld(0).getWorldInfo().getWorldName().equals(worldName), "Refusing non-fixture world");
        return mc.getIntegratedServer().getPlayerList().getPlayers().get(0);
    }

    private void beginForm()
    {
        EntityPlayerMP p = player();
        p.setHealth(p.getMaxHealth());
        p.clearActivePotions();
        p.extinguish();
        p.setNoGravity(true);
        p.capabilities.disableDamage = true;
        p.capabilities.allowFlying = true;
        p.capabilities.isFlying = true;
        p.sendPlayerAbilities();
        p.setPositionAndUpdate(0.5, 8, 0.5);
        p.rotationYaw = 0;
        p.rotationPitch = 0;
        if("player".equals(FORMS[form])) require(PlayerMorphHandler.getInstance().forceDemorph(p), "Demorph rejected");
        else
        {
            EntityLivingBase entity = (EntityLivingBase)EntityList.createEntityByIDFromName(new ResourceLocation("minecraft", FORMS[form]), p.world);
            require(PlayerMorphHandler.getInstance().forceMorph(p, entity), "Morph rejected " + FORMS[form]);
        }
    }

    @SubscribeEvent
    public void server(TickEvent.ServerTickEvent event)
    {
        if(hudOnly)
        {
            if(hudServerReady && !stopped && event.phase == TickEvent.Phase.END)
            {
                try
                {
                    EntityPlayerMP p = player();
                    p.motionX = p.motionY = p.motionZ = 0;
                    p.setAir(120);
                    p.extinguish();
                    p.getFoodStats().setFoodLevel(20);
                    if(hudFreezeCooldown >= 0)
                    {
                        ActiveAbility ability = ActiveAbilityHandler.current(p);
                        if(ability != null)
                        {
                            setTestCooldown(p, ability, hudFreezeCooldown);
                            ActiveAbilityHandler.sync(p);
                        }
                    }
                }
                catch(Exception e) { fail(e); }
            }
            return;
        }
        if(!fixtureReady || stopped || event.phase != TickEvent.Phase.END) return;
        // Keep physics, sunburn and ability impulses out of the comparison scene.
        EntityPlayerMP p = player();
        if(screenshotsDone)
        {
            try { suite(p); }
            catch(Exception e) { fail(e); }
            return;
        }
        p.setPosition(0.5, 8, 0.5);
        p.motionX = p.motionY = p.motionZ = 0;
        p.extinguish();
        p.getFoodStats().setFoodLevel(20);
        if(cooldownProbe != null && p.world.getTotalWorldTime() >= cooldownProbeAt)
        {
            try
            {
                int before = ActiveAbilityHandler.remaining(p, cooldownProbe);
                require(before > 0, "Cooldown expired before delayed probe");
                ActiveAbilityHandler.use(p);
                require(ActiveAbilityHandler.remaining(p, cooldownProbe) == before, "Delayed repeat restarted cooldown");
                record("PASS cooldown blocks repeat after request throttle: " + cooldownProbe.action);
                cooldownProbe = null;
            }
            catch(Exception e) { fail(e); }
        }
    }

    @SubscribeEvent
    public void sound(PlaySoundEvent event)
    {
        if(hudOnly && event.getSound() != null) heardSounds.add(event.getSound().getSoundLocation());
    }

    private static void setTestCooldown(EntityPlayerMP p, ActiveAbility ability, int ticks)
    {
        NBTTagCompound persistent = p.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if(!persistent.hasKey("MorphActiveAbilities")) persistent.setTag("MorphActiveAbilities", new NBTTagCompound());
        persistent.getCompoundTag("MorphActiveAbilities").setLong(ability.cooldownKey(), p.world.getTotalWorldTime() + ticks);
        p.getEntityData().setLong("MorphAbilityRequestAfter", 0);
    }

    private void hudMorph()
    {
        EntityPlayerMP p = player();
        if(!hudInitialized)
        {
            p.world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
            p.world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
            p.world.getGameRules().setOrCreateGameRule("mobGriefing", "false");
            p.world.getGameRules().setOrCreateGameRule("naturalRegeneration", "false");
            p.world.setWorldTime(6000);
            for(ActiveAbility ability : ActiveAbility.all().values())
            {
                require(AbilitySounds.success(ability.action) != null, "No sound mapping " + ability.action);
            }
            record("PASS sound mapping covers all " + ActiveAbility.all().size() + " forms");
            hudInitialized = true;
        }
        clearEntities(p);
        p.setPositionAndUpdate(0.5, 8, 0.5);
        p.setNoGravity(true);
        p.capabilities.allowFlying = p.capabilities.isFlying = true;
        p.capabilities.disableDamage = true;
        p.sendPlayerAbilities();
        p.setHealth(p.getMaxHealth());
        p.clearActivePotions();
        p.inventory.clear();
        net.minecraft.item.Item[] hotbar = {Items.IRON_SWORD, Items.IRON_PICKAXE, Items.BREAD, Items.FLINT_AND_STEEL,
                Items.CLOCK, Items.APPLE, Items.BOW, Items.COMPASS, Items.COOKED_BEEF};
        for(int i = 0; i < 9; i++) p.inventory.setInventorySlotContents(i, new ItemStack(hotbar[i], i == 2 || i == 5 ? 32 : 1));
        p.inventory.currentItem = 0;
        p.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        p.setItemStackToSlot(EntityEquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        p.setItemStackToSlot(EntityEquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        p.setItemStackToSlot(EntityEquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
        p.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
        // A stationary water column makes vanilla's real air bar visible; no HUD is painted by this fixture.
        for(int y = 8; y <= 12; y++)
            for(int x = -1; x <= 1; x++) for(int z = -1; z <= 1; z++)
                p.world.setBlockState(new BlockPos(x, y, z), Blocks.WATER.getDefaultState(), 2);
        EntityLivingBase source = (EntityLivingBase)EntityList.createEntityByIDFromName(new ResourceLocation("minecraft", HUD_FORMS[hudForm]), p.world);
        if(!Morph.eventHandlerServer.getPlayerMorphs(p).stream().anyMatch(v -> v.entId.equals("minecraft:" + HUD_FORMS[hudForm])))
            require(PlayerMorphHandler.getInstance().acquireMorph(p, source, false, false), "HUD acquire rejected " + HUD_FORMS[hudForm]);
        require(PlayerMorphHandler.getInstance().forceMorph(p, source), "HUD morph rejected " + HUD_FORMS[hudForm]);
        hudServerReady = true;
    }

    private void hudPrepareAction()
    {
        EntityPlayerMP p = player();
        MorphInfo info = Morph.eventHandlerServer.morphsActive.get(p.getName());
        require(info != null && !info.isMorphing(), "HUD server morph unfinished");
        info.setPlayerBoundingBox();
        p.rotationYaw = p.rotationPitch = 0;
        if(hudTarget != null) hudTarget.setDead();
        hudTarget = new EntityCow(p.world);
        hudTarget.setNoAI(true);
        hudTarget.setNoGravity(true);
        hudTarget.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(100);
        hudTarget.setHealth(100);
        hudTarget.setPosition(p.posX, p.posY + p.getEyeHeight() - hudTarget.height / 2, p.posZ + 2.5);
        require(p.world.spawnEntity(hudTarget), "HUD action target spawn rejected");
        setTestCooldown(p, ActiveAbilityHandler.current(p), 0);
        ActiveAbilityHandler.sync(p);
        p.inventoryContainer.detectAndSendChanges();
    }

    private void hudLayout()
    {
        if(hudLayout >= 12 && !hudLanguageOverridden)
        {
            hudOriginalLanguage = mc.getLanguageManager().getCurrentLanguage();
            hudOriginalUnicode = mc.gameSettings.forceUnicodeFont;
            hudLanguageOverridden = true;
            language(mc.getLanguageManager().getLanguage("en_us"), false);
        }
        int resolution = hudLayout / 6;
        int scale = hudLayout >= 12 ? 3 : (hudLayout / 2) % 3 + 1;
        mc.resize(resolution == 0 ? 1024 : resolution == 1 ? 854 : 1280,
                resolution == 0 ? 576 : resolution == 1 ? 480 : 720);
        mc.gameSettings.guiScale = scale;
        mc.gameSettings.mainHand = hudLayout % 2 == 0 ? EnumHandSide.RIGHT : EnumHandSide.LEFT;
        mc.gameSettings.sendSettingsToServer();
        mc.gameSettings.thirdPersonView = 0;
        mc.gameSettings.hideGUI = false;
    }

    private void language(Language language, boolean unicode)
    {
        require(language != null, "Validation locale missing");
        mc.getLanguageManager().setCurrentLanguage(language);
        mc.gameSettings.language = language.getLanguageCode();
        mc.gameSettings.forceUnicodeFont = unicode;
        mc.getLanguageManager().onResourceManagerReload(mc.getResourceManager());
        mc.fontRenderer.setUnicodeFlag(mc.isUnicode());
        mc.fontRenderer.setBidiFlag(mc.getLanguageManager().isCurrentLanguageBidirectional());
    }

    private void restoreHudLanguage()
    {
        if(hudLanguageOverridden)
        {
            language(hudOriginalLanguage, hudOriginalUnicode);
            hudLanguageOverridden = false;
            record("PASS restored HUD locale " + hudOriginalLanguage.getLanguageCode());
        }
    }

    private void presentationMorph()
    {
        EntityPlayerMP p = player();
        clearEntities(p);
        p.world.setWorldTime(6000);
        p.setPositionAndUpdate(32.5, 4, 0.5);
        p.setHealth(p.getMaxHealth());
        p.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
        p.inventory.currentItem = 0;
        p.inventory.setInventorySlotContents(0, ItemStack.EMPTY);
        p.inventoryContainer.detectAndSendChanges();
        EntityLivingBase source = (EntityLivingBase)EntityList.createEntityByIDFromName(
                new ResourceLocation("minecraft", PRESENTATION_FORMS[presentationForm]), p.world);
        require(PlayerMorphHandler.getInstance().forceMorph(p, source), "Presenter morph rejected");
    }

    private String hudName(String state)
    {
        return "hud_" + HUD_FORMS[hudForm] + "_" + mc.displayWidth + "x" + mc.displayHeight
                + "_gui" + mc.gameSettings.guiScale + "_" + mc.gameSettings.mainHand.toString().toLowerCase(java.util.Locale.ROOT) + "_" + state + ".png";
    }

    private void hud()
    {
        mc.player.rotationYaw = mc.player.prevRotationYaw = 0;
        mc.player.rotationPitch = mc.player.prevRotationPitch = 12;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
        if(hudPending != null)
        {
            if(!hudPending.isDone()) return;
            try { hudPending.get(); }
            catch(Exception e) { throw new IllegalStateException("HUD server step failed", e); }
            hudPending = null;
        }
        if(hudWait-- > 0 || hudCapture != null) return;
        switch(hudPhase)
        {
            case 0:
                mc.gameSettings.setSoundLevel(SoundCategory.MASTER, 1);
                mc.gameSettings.setSoundLevel(SoundCategory.PLAYERS, 1);
                hudFreezeCooldown = -1;
                hudPending = mc.getIntegratedServer().addScheduledTask(this::hudMorph);
                hudWait = Morph.config.morphTime + 20;
                hudPhase = 1;
                break;
            case 1:
                require(Morph.eventHandlerClient.morphsActive.containsKey(mc.player.getName())
                        && !Morph.eventHandlerClient.morphsActive.get(mc.player.getName()).isMorphing(), "HUD client morph unfinished");
                for(ActiveAbility ability : ActiveAbility.all().values())
                    require(!AbilityClient.iconFor(ability.action).isEmpty(), "Missing native item icon " + ability.action);
                hudLayout = hudState = 0;
                hudLayout();
                hudPending = mc.getIntegratedServer().addScheduledTask(this::hudPrepareAction);
                hudWait = 15;
                hudPhase = 2;
                break;
            case 2:
                require(AbilityClient.remainingTicks() == 0, "HUD ready state not synchronized");
                hudCapture = hudName("ready_keytest");
                hudPhase = 3;
                break;
            case 3:
                heardSounds.clear();
                hudFreezeCooldown = -1;
                KeyBinding.onTick(AbilityClient.USE.getKeyCode());
                hudWait = 12;
                hudPhase = 4;
                break;
            case 4:
                require(AbilityClient.remainingTicks() > 0, "Real key tick did not synchronize ability cooldown");
                ActiveAbility expected = ActiveAbility.get("minecraft:" + HUD_FORMS[hudForm]);
                require(heardSounds.contains(AbilitySounds.success(expected.action).getRegistryName()),
                        "Client did not hear action sound " + AbilitySounds.success(expected.action).getRegistryName() + "; heard=" + heardSounds);
                hudPending = mc.getIntegratedServer().addScheduledTask(() -> {
                    EntityPlayerMP p = player();
                    require(ActiveAbilityHandler.remaining(p, ActiveAbilityHandler.current(p)) > 0, "Key tick did not reach logical server");
                    record("PASS actual key tick -> PacketAbilityUse -> server cooldown -> client sound " + expected.action);
                });
                hudCapture = hudName("activated_sound");
                hudPhase = 5;
                break;
            case 5:
                if(hudLayout == (hudForm == HUD_FORMS.length - 1 ? 14 : 12))
                {
                    verifyModels();
                    record("PASS HUD model signatures unchanged after " + HUD_FORMS[hudForm]);
                    hudForm++;
                    if(hudForm < HUD_FORMS.length) hudPhase = 0;
                    else { hudForm--; hudPhase = 20; }
                    return;
                }
                hudLayout();
                int state = hudState;
                hudFreezeCooldown = state == 0 ? 0 : state == 1
                        ? ActiveAbility.get("minecraft:" + HUD_FORMS[hudForm]).cooldown / 2 : 12;
                hudWait = 3;
                hudPhase = 6;
                break;
            case 6:
                int actual = AbilityClient.remainingTicks();
                require(hudState == 0 ? actual == 0 : hudState == 1 ? actual > 20 : actual > 0 && actual <= 20,
                        "HUD cooldown display state mismatch state=" + hudState + " actual=" + actual);
                hudCapture = hudName(new String[]{"ready", "mid_cooldown", "last_second"}[hudState]);
                if(++hudState == 3) { hudState = 0; hudLayout++; }
                hudPhase = 5;
                break;
            case 7:
                hudFreezeCooldown = 0;
                mc.gameSettings.hideGUI = true;
                hudExpectVisible = false;
                hudCapture = "hud_hidden_gui.png";
                hudPhase = 8;
                break;
            case 8:
                mc.gameSettings.hideGUI = false;
                mc.gameSettings.thirdPersonView = 1;
                hudExpectVisible = true;
                hudCapture = "hud_third_person_visible.png";
                hudPhase = 9;
                break;
            case 9:
                mc.gameSettings.thirdPersonView = 0;
                mc.displayGuiScreen(new GuiInventory(mc.player));
                hudExpectVisible = false;
                hudCapture = "hud_inventory_hidden.png";
                hudPhase = 10;
                break;
            case 20:
                restoreHudLanguage();
                hudPresentation = true;
                mc.resize(1024, 576);
                mc.gameSettings.guiScale = 2;
                mc.gameSettings.mainHand = EnumHandSide.RIGHT;
                mc.gameSettings.sendSettingsToServer();
                hudFreezeCooldown = 0;
                hudPending = mc.getIntegratedServer().addScheduledTask(this::presentationMorph);
                hudWait = Morph.config.morphTime + 8;
                hudPhase = 21;
                break;
            case 21:
                require(AbilityClient.shouldShow() && mc.player.getHeldItemMainhand().isEmpty(), "Presenter label fixture not ready");
                int labelTicks = ReflectionHelper.getPrivateValue(AbilityClient.class, null, "nameTicks");
                require(labelTicks > 0, "Presenter form-change label already expired");
                hudCapture = "presentation_" + PRESENTATION_FORMS[presentationForm] + "_brief_label.png";
                hudPhase = 22;
                break;
            case 22:
                hudPending = mc.getIntegratedServer().addScheduledTask(() -> {
                    EntityPlayerMP p = player();
                    p.inventory.setInventorySlotContents(0, new ItemStack(Items.APPLE));
                    p.inventoryContainer.detectAndSendChanges();
                });
                hudWait = 3;
                hudPhase = 23;
                break;
            case 23:
                require(mc.player.getHeldItemMainhand().getItem() == Items.APPLE && mc.player.getHeldItemOffhand().isEmpty(), "Presenter hand items not synchronized");
                hudCapture = "presentation_" + PRESENTATION_FORMS[presentationForm] + "_ready.png";
                hudPhase = presentationForm == 0 ? 24 : 26;
                break;
            case 24:
                hudFreezeCooldown = ActiveAbility.get("minecraft:creeper").cooldown / 2;
                hudWait = 3;
                hudPhase = 25;
                break;
            case 25:
                require(AbilityClient.remainingTicks() > 20, "Presenter creeper midpoint not synchronized");
                hudCapture = "presentation_creeper_mid_cooldown.png";
                hudPhase = 26;
                break;
            case 26:
                if(++presentationForm < PRESENTATION_FORMS.length) hudPhase = 20;
                else hudPhase = 7;
                break;
            default:
                mc.displayGuiScreen(null);
                GameType gameType = mc.playerController.getCurrentGameType();
                mc.playerController.setGameType(GameType.SPECTATOR);
                require(!AbilityClient.shouldShow(), "HUD visible for spectator");
                mc.playerController.setGameType(gameType);
                float health = mc.player.getHealth();
                mc.player.setHealth(0);
                require(!AbilityClient.shouldShow(), "HUD visible while dead");
                mc.player.setHealth(health);
                record("PASS HUD hidden for F1/inventory/spectator/dead; remains visible in third person");
                record("COMPLETE hudOnly forms=5 layouts=12+2effectiveScale3 states=3 keyPacketAndSound=5 screenshots=" + hudCaptures);
                stopped = true;
        }
    }

    private void renderHud()
    {
        if(hudCapture == null) return;
        require(AbilityClient.shouldShow() == hudExpectVisible, "HUD visibility mismatch for " + hudCapture);
        if(hudExpectVisible)
        {
            ScaledResolution resolution = new ScaledResolution(mc);
            if(mc.displayWidth == 1280) require(resolution.getScaleFactor() == 3, "Requested GUI3 did not use actual scale3 at1280x720");
            int[] bounds = AbilityClient.hudBounds();
            int center = resolution.getScaledWidth() / 2;
            require(bounds != null && bounds.length == 4 && bounds[2] == 22 && bounds[3] == 22, "HUD slot is not native 22px");
            require(bounds[0] >= 0 && bounds[1] >= 0 && bounds[0] + bounds[2] <= resolution.getScaledWidth()
                    && bounds[1] + bounds[3] <= resolution.getScaledHeight(), "HUD slot clipped by screen");
            require(mc.player.getPrimaryHand() == EnumHandSide.RIGHT ? bounds[0] >= center + 91
                    : bounds[0] + bounds[2] <= center - 91, "HUD overlaps hotbar or uses shield side");
            if(!hudPresentation) require(mc.player.getHeldItemOffhand().getItem() == Items.SHIELD, "HUD fixture missing real offhand shield");
            require(mc.player.getPrimaryHand() == mc.gameSettings.mainHand, "HUD primary hand not synchronized");
            if(!hudPresentation && mc.gameSettings.thirdPersonView == 0)
                require(mc.player.isInsideOfMaterial(Material.WATER), "HUD air fixture is not actually underwater");
        }
        saveCapture(hudCapture);
        hudCaptures++;
        hudCapture = null;
    }

    private void saveCapture(String name)
    {
        ScreenShotHelper.saveScreenshot(output, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        File file = new File(output, "screenshots/" + name);
        require(file.isFile() && file.length() > 1000, "Missing framebuffer capture " + name);
        record("CAPTURE " + name + " bytes=" + file.length());
    }

    private void suiteMorph(EntityPlayerMP p, String id)
    {
        clearEntities(p);
        p.clearActivePotions();
        p.extinguish();
        p.inventory.clear();
        p.capabilities.disableDamage = false;
        p.setPositionAndUpdate(0.5, 4, 0.5);
        p.rotationYaw = 0;
        p.rotationPitch = 0;
        EntityLivingBase entity = (EntityLivingBase)EntityList.createEntityByIDFromName(new ResourceLocation("minecraft", id), p.world);
        require(PlayerMorphHandler.getInstance().forceMorph(p, entity), "Suite morph rejected " + id);
        suiteWait = Morph.config.morphTime + 10;
    }

    private static void clearEntities(EntityPlayerMP p)
    {
        for(Entity entity : p.world.loadedEntityList)
            if(!(entity instanceof EntityPlayer)) entity.setDead();
    }

    private int testBlocks(EntityPlayerMP p, boolean build)
    {
        int count = 0;
        for(int x = 15; x <= 17; x++)
            for(int z = 15; z <= 17; z++)
                if(x != 16 || z != 16)
                {
                    BlockPos pos = new BlockPos(x, 4, z);
                    if(build) p.world.setBlockState(pos, Blocks.DIRT.getDefaultState());
                    if(p.world.getBlockState(pos).getBlock() == Blocks.DIRT) count++;
                }
        return count;
    }

    /** Actual world ticks, Forge damage/target/clone events and blocks, after the visual series. */
    private void suite(EntityPlayerMP p)
    {
        if(visualOnly || allFormsDone)
        {
            traits(p);
            return;
        }
        p.setHealth(p.getMaxHealth());
        p.getFoodStats().setFoodLevel(20);
        if(suiteWait-- > 0) return;
        switch(suitePhase++)
        {
            case 0:
                record("BEGIN phased server integration checks");
                p.world.setWorldTime(18000); // Keep actual test mobs from burning while their AI runs.
                suiteMorph(p, "cow");
                break;
            case 1:
                preservedAbility = ActiveAbilityHandler.current(p);
                require(preservedAbility != null, "Cow has no active ability");
                ActiveAbilityHandler.use(p);
                require(ActiveAbilityHandler.remaining(p, preservedAbility) > 0, "Cow cooldown absent");
                NBTTagCompound persisted = p.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG).copy();
                preservedUntil = p.world.getTotalWorldTime() + ActiveAbilityHandler.remaining(p, preservedAbility);
                EntityPlayerMP cloned = new EntityPlayerMP(p.getServer(), p.getServerWorld(),
                        new GameProfile(UUID.randomUUID(), "MorphCloneFixture"), new PlayerInteractionManager(p.getServerWorld()));
                MinecraftForge.EVENT_BUS.post(new PlayerEvent.Clone(cloned, p, true));
                require(cloned.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
                        .getCompoundTag("MorphActiveAbilities").equals(persisted.getCompoundTag("MorphActiveAbilities")), "Clone lost ability cooldown NBT");
                require(ActiveAbilityHandler.remaining(cloned, preservedAbility) == ActiveAbilityHandler.remaining(p, preservedAbility), "Clone cooldown differs");
                record("PASS death Clone event preserves cooldown NBT and remaining time");
                suiteMorph(p, "skeleton");
                break;
            case 2:
                suiteMorph(p, "cow");
                break;
            case 3:
                int remaining = ActiveAbilityHandler.remaining(p, preservedAbility);
                require(remaining > 0 && p.world.getTotalWorldTime() + remaining == preservedUntil, "Shape switch reset cooldown");
                ActiveAbilityHandler.use(p);
                require(ActiveAbilityHandler.remaining(p, preservedAbility) == remaining, "Returning shape bypassed cooldown");
                record("PASS cow -> skeleton -> cow preserves absolute cooldown and blocks action");
                suiteMorph(p, "sheep");
                break;
            case 4:
                wolf = new EntityWolf(p.world);
                wolf.setPosition(4.5, 4, 0.5);
                p.world.spawnEntity(wolf);
                suiteWait = 20;
                break;
            case 5:
                require(wolf.getAttackTarget() == p, "Wild wolf did not hunt sheep form");
                record("PASS actual wild wolf AI acquires sheep player");
                suiteMorph(p, "wolf");
                break;
            case 6:
                skeleton = new EntitySkeleton(p.world);
                skeleton.getRNG().setSeed(1234);
                skeleton.setPosition(0.5, 4, 4.5);
                p.world.spawnEntity(skeleton);
                fleeStart = skeleton.getDistanceSq(p);
                suiteWait = 40;
                break;
            case 7:
                require(skeleton.getAttackTarget() == null, "Skeleton attacked wolf form instead of fleeing");
                require(skeleton.getDistanceSq(p) > fleeStart + 1, "Skeleton AI did not move away from wolf form");
                record("PASS actual skeleton navigation flees wolf player: distanceSq=" + fleeStart + " -> " + skeleton.getDistanceSq(p));
                clearEntities(p);
                wolf = new EntityWolf(p.world);
                wolf.setPosition(2.5, 4, 0.5);
                p.world.spawnEntity(wolf);
                EntityCow cow = new EntityCow(p.world);
                cow.setNoAI(true);
                cow.setPosition(0.5, 4, 2.5);
                p.world.spawnEntity(cow);
                require(cow.attackEntityFrom(DamageSource.causePlayerDamage(p), 1), "Non-player damage fixture rejected");
                require(wolf.getAttackTarget() == cow, "Wolf form did not recruit nearby wolf against cow");
                record("PASS wolf form recruits wild wolf after real damage to non-player");
                wolf.setAttackTarget(null);
                EntityPlayer victim = new EntityPlayer(p.world, new GameProfile(UUID.randomUUID(), "MorphPvPFixture"))
                {
                    @Override public boolean isSpectator() { return false; }
                    @Override public boolean isCreative() { return false; }
                };
                victim.setPosition(0.5, 4, 2.5);
                victim.capabilities.disableDamage = false;
                victim.setHealth(victim.getMaxHealth());
                require(victim.attackEntityFrom(DamageSource.causePlayerDamage(p), 1), "Player damage fixture rejected");
                require(wolf.getAttackTarget() == null, "Wolf pack recruited against player");
                record("PASS wolf form does not recruit pack against real damage to synthetic EntityPlayer victim");
                suiteMorph(p, "creeper");
                break;
            case 8:
                // Previous screenshot use is safely beyond this form's cooldown by now.
                p.setPositionAndUpdate(16.5, 4, 16.5);
                p.world.getGameRules().setOrCreateGameRule("mobGriefing", "false");
                blocksBefore = testBlocks(p, true);
                require(ActiveAbilityHandler.remaining(p, ActiveAbilityHandler.current(p)) == 0, "Creeper cooldown not yet expired");
                ActiveAbilityHandler.use(p);
                require(ActiveAbilityHandler.remaining(p, ActiveAbilityHandler.current(p)) > 0, "Creeper false fixture did not explode");
                suiteWait = 3;
                break;
            case 9:
                require(testBlocks(p, false) == blocksBefore, "mobGriefing=false destroyed blocks");
                record("PASS creeper ability mobGriefing=false preserves " + blocksBefore + " dirt blocks");
                suiteWait = ActiveAbilityHandler.remaining(p, ActiveAbilityHandler.current(p)) + 3;
                break;
            case 10:
                p.setPositionAndUpdate(16.5, 4, 16.5);
                p.world.getGameRules().setOrCreateGameRule("mobGriefing", "true");
                blocksBefore = testBlocks(p, true);
                ActiveAbilityHandler.use(p);
                require(ActiveAbilityHandler.remaining(p, ActiveAbilityHandler.current(p)) > 0, "Creeper true fixture did not explode");
                suiteWait = 3;
                break;
            case 11:
                require(testBlocks(p, false) < blocksBefore, "mobGriefing=true did not destroy exposed dirt");
                record("PASS creeper ability mobGriefing=true destroys real blocks: " + blocksBefore + " -> " + testBlocks(p, false));
                p.world.getGameRules().setOrCreateGameRule("mobGriefing", "false");
                require(PlayerMorphHandler.getInstance().forceDemorph(p), "Final demorph rejected");
                suiteWait = Morph.config.morphTime + 10;
                break;
            default:
                if(abilityCases == null) require(ActiveAbilityHandler.current(p) == null, "Final demorph retained active ability");
                suitePhase--; // Remaining cases have their own three-phase state machine.
                allForms(p);
        }
    }

    private static int projectiles(EntityPlayerMP p)
    {
        int count = 0;
        for(Entity entity : p.world.loadedEntityList)
            if(!entity.isDead && (entity instanceof net.minecraft.entity.projectile.EntityArrow
                    || entity instanceof net.minecraft.entity.projectile.EntityFireball
                    || entity instanceof net.minecraft.entity.projectile.EntityThrowable
                    || entity instanceof net.minecraft.entity.projectile.EntityShulkerBullet)) count++;
        return count;
    }

    /** Every registered action gets a real acquired form, prepared prerequisites and world mutation. */
    private void allForms(EntityPlayerMP p)
    {
        if(abilityCases == null)
        {
            abilityCases = new ArrayList<>(ActiveAbility.all().entrySet());
            require(abilityCases.size() == 50, "Expected all 50 vanilla active forms, got " + abilityCases.size());
            record("BEGIN all-form actions count=" + abilityCases.size());
        }
        if(abilityIndex == abilityCases.size())
        {
            if(abilityPhase != 3)
            {
                require(PlayerMorphHandler.getInstance().forceDemorph(p), "All-form final demorph rejected");
                suiteWait = Morph.config.morphTime + 10;
                abilityPhase = 3;
                return;
            }
            require(ActiveAbilityHandler.current(p) == null, "All-form final demorph retained ability");
            require(extraScreenshots == 66, "Swing phase screenshot series incomplete");
            allFormsDone = true;
            return;
        }
        Map.Entry<String, ActiveAbility> test = abilityCases.get(abilityIndex);
        ActiveAbility action = test.getValue();
        if(abilityPhase == 0)
        {
            clearEntities(p);
            EntityLivingBase source = (EntityLivingBase)EntityList.createEntityByIDFromName(new ResourceLocation(test.getKey()), p.world);
            require(source != null, "Cannot create registered action form " + test.getKey());
            boolean owned = Morph.eventHandlerServer.getPlayerMorphs(p).stream().anyMatch(v -> v.entId.equals(test.getKey()));
            if(!owned) require(PlayerMorphHandler.getInstance().acquireMorph(p, source, false, false), "Cannot acquire action form " + test.getKey());
            require(PlayerMorphHandler.getInstance().forceMorph(p, source), "Cannot morph action form " + test.getKey());
            suiteWait = Morph.config.morphTime + 10;
            abilityPhase = 1;
            return;
        }
        require(ActiveAbilityHandler.current(p) == action, "Wrong active action for " + test.getKey());
        if(abilityPhase == 1)
        {
            clearEntities(p);
            p.clearActivePotions();
            p.extinguish();
            p.setHealth(p.getMaxHealth());
            p.inventory.clear();
            p.inventory.currentItem = 0;
            if("soup".equals(action.action)) p.inventory.setInventorySlotContents(0, new ItemStack(Items.BOWL));
            if("barter".equals(action.action)) p.inventory.setInventorySlotContents(0, new ItemStack(Items.EMERALD));
            p.world.setBlockState(new BlockPos(0, 3, 0), Blocks.GRASS.getDefaultState());
            p.setPositionAndUpdate(0.5, 4, 0.5);
            Morph.eventHandlerServer.morphsActive.get(p.getName()).setPlayerBoundingBox();
            p.rotationYaw = 0;
            p.rotationPitch = 0;
            p.onGround = true; // The fixture stands on the grass block placed immediately above.
            p.motionX = p.motionY = p.motionZ = 0;
            // Isolate this action from earlier dedicated cooldown tests; those never alter timers.
            p.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
                    .getCompoundTag("MorphActiveAbilities").setLong(action.cooldownKey(), 0);
            p.getEntityData().setLong("MorphAbilityRequestAfter", 0);
            EntityCow target = new EntityCow(p.world);
            target.setNoAI(true);
            target.setNoGravity(true);
            target.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MAX_HEALTH).setBaseValue(100);
            target.setHealth(100);
            // Keep the target outside even boss-sized player bodies and align it with the real eye ray.
            double distance = Math.max(2.5, p.getEntityBoundingBox().maxZ - p.posZ + target.width / 2.0 + 0.5);
            target.setPosition(p.posX, p.posY + p.getEyeHeight() - target.height / 2.0, p.posZ + distance);
            require(!target.getEntityBoundingBox().intersects(p.getEntityBoundingBox()), "Action target overlaps player " + test.getKey());
            require(p.world.spawnEntity(target), "Cannot spawn action target");
            int shots = projectiles(p);
            ActiveAbilityHandler.use(p);
            require(ActiveAbilityHandler.remaining(p, action) == action.cooldown, "Action failed to enter full cooldown: " + test.getKey() + "/" + action.action);
            if(Arrays.asList("arrow", "stray_arrow", "blaze", "ghast", "skull", "breath", "web", "spit", "levitate", "witch", "snowball").contains(action.action))
                require(projectiles(p) > shots, "Action did not spawn actual projectile: " + test.getKey());
            if("egg".equals(action.action)) require(p.inventory.hasItemStack(new ItemStack(Items.EGG)), "Chicken action did not give egg");
            if("soup".equals(action.action)) require(p.inventory.hasItemStack(new ItemStack(Items.MUSHROOM_STEW)), "Mooshroom action did not give soup");
            if("barter".equals(action.action)) require(p.inventory.hasItemStack(new ItemStack(Items.BREAD)), "Villager action did not give bread");
            suiteWait = 3;
            abilityPhase = 2;
            return;
        }
        int before = ActiveAbilityHandler.remaining(p, action);
        require(before > 0 && before < action.cooldown, "Action cooldown did not tick: " + test.getKey());
        ActiveAbilityHandler.use(p);
        require(ActiveAbilityHandler.remaining(p, action) == before, "Action allowed repeat during cooldown: " + test.getKey());
        record("PASS all-form " + test.getKey() + " action=" + action.action + " cooldown=" + before);
        abilityIndex++;
        abilityPhase = 0;
    }

    private static String signature(ModelRenderer part)
    {
        StringBuilder value = new StringBuilder();
        value.append(part.cubeList.size()).append(':');
        for(ModelBox box : part.cubeList)
            value.append(System.identityHashCode(box)).append('/').append(box.posX1).append('/').append(box.posY1)
                    .append('/').append(box.posZ1).append('/').append(box.posX2).append('/').append(box.posY2)
                    .append('/').append(box.posZ2).append(';');
        value.append(" children:");
        if(part.childModels == null) value.append("null");
        else for(ModelRenderer child : part.childModels) value.append(System.identityHashCode(child)).append(';');
        return value.toString();
    }

    private void snapshotPart(ModelRenderer part)
    {
        if(part == null || modelBaseline.containsKey(part)) return;
        modelBaseline.put(part, signature(part));
        if(part.childModels != null) for(ModelRenderer child : part.childModels) snapshotPart(child);
    }

    private void snapshotModels()
    {
        for(String id : FORMS)
        {
            Class type = "player".equals(id) ? EntityPlayer.class : EntityList.getClass(new ResourceLocation("minecraft", id));
            require(type != null, "Cannot locate baseline model class " + id);
            ModelInfo info = ModelHandler.getEntityModelInfo(type);
            require(info != null && info.modelParent != null, "Missing baseline model " + id);
            for(ModelRenderer part : info.modelParent.boxList) snapshotPart(part);
            for(ModelRenderer arm : info.modelArms) snapshotPart(arm);
        }
        // Include both cached player arm/sleeve graphs, even when the local skin is only one type.
        for(net.minecraft.client.renderer.entity.RenderPlayer renderer : mc.getRenderManager().skinMap.values())
            for(ModelRenderer part : renderer.getMainModel().boxList) snapshotPart(part);
        require(!modelBaseline.isEmpty(), "Empty cached vanilla model baseline");
        record("BASELINE cached vanilla cubeList/childModels graphs parts=" + modelBaseline.size());
    }

    private void verifyModels()
    {
        for(Map.Entry<ModelRenderer, String> entry : modelBaseline.entrySet())
            require(entry.getValue().equals(signature(entry.getKey())), "Cached vanilla model geometry was mutated: " + entry.getKey().boxName);
    }

    private void traitMorph(EntityPlayerMP p, String id)
    {
        p.setSneaking(false);
        suiteMorph(p, id);
        traitWait = Morph.config.morphTime + 10;
    }

    private static DamageSource arrowDamage(EntityPlayerMP p)
    {
        return DamageSource.causeArrowDamage(new EntityTippedArrow(p.world), new EntitySkeleton(p.world));
    }

    private static void damageReady(EntityPlayerMP p)
    {
        p.capabilities.disableDamage = false;
        p.hurtResistantTime = 0;
        p.setAbsorptionAmount(0);
        p.clearActivePotions();
        p.motionX = p.motionY = p.motionZ = 0;
    }

    /** Damage events and timed traits use separate phases; no per-tick health reset masks healing. */
    private void traits(EntityPlayerMP p)
    {
        p.getFoodStats().setFoodLevel(20);
        if(traitWait-- > 0) return;
        switch(traitPhase++)
        {
            case 0:
                require(extraScreenshots == 66 && screenshots == 88, "Trait suite started without complete visual evidence");
                record("BEGIN new vanilla trait checks; visualOnly=" + visualOnly);
                p.world.getGameRules().setOrCreateGameRule("naturalRegeneration", "false");
                p.world.setWorldTime(18000);
                traitMorph(p, "witch");
                break;
            case 1:
                damageReady(p);
                p.setHealth(p.getMaxHealth());
                traitHealth = p.getHealth();
                require(p.attackEntityFrom(DamageSource.MAGIC, 10), "Witch magic damage fixture rejected");
                require(Math.abs(traitHealth - p.getHealth() - 1.5F) < 0.02F, "Witch did not reduce magic damage by 85%: " + (traitHealth - p.getHealth()));
                record("PASS witch actual MAGIC damage 10 -> " + (traitHealth - p.getHealth()));
                traitMorph(p, "wither");
                break;
            case 2:
                damageReady(p);
                p.setHealth(p.getMaxHealth());
                traitHealth = p.getHealth();
                require(p.attackEntityFrom(arrowDamage(p), 2), "Wither above-half arrow fixture rejected");
                require(p.getHealth() < traitHealth, "Wither above half was incorrectly arrow immune");
                damageReady(p);
                p.setHealth(Math.max(2, p.getMaxHealth() / 2F - 2));
                traitHealth = p.getHealth();
                p.attackEntityFrom(arrowDamage(p), 8);
                require(p.getHealth() == traitHealth, "Wither below half took arrow damage");
                traitWait = 19; // Exactly 20 subsequent server ticks: one ticksExisted%20 healing boundary.
                break;
            case 3:
                require(Math.abs(p.getHealth() - traitHealth - 1F) < 0.02F, "Wither did not heal exactly one HP in 20 ticks: " + (p.getHealth() - traitHealth));
                record("PASS wither arrow immunity only below half and heals 1 HP per 20 ticks");
                traitMorph(p, "shulker");
                break;
            case 4:
                damageReady(p);
                p.setHealth(p.getMaxHealth());
                require(p.getEntityAttribute(SharedMonsterAttributes.ARMOR).getAttributeValue() == 0, "Open shulker has unexpected armor");
                p.setSneaking(true);
                traitWait = 1;
                break;
            case 5:
                require(p.isSneaking(), "Shulker closed-shell fixture lost sneak state");
                require(p.getEntityAttribute(SharedMonsterAttributes.ARMOR).getAttributeValue() == 20, "Closed shulker does not have +20 armor");
                traitHealth = p.getHealth();
                p.attackEntityFrom(arrowDamage(p), 8);
                require(p.getHealth() == traitHealth, "Closed shulker took arrow damage");
                p.setSneaking(false);
                traitWait = 1;
                break;
            case 6:
                require(p.getEntityAttribute(SharedMonsterAttributes.ARMOR).getAttributeValue() == 0, "Shulker retained armor after opening shell");
                damageReady(p);
                traitHealth = p.getHealth();
                require(p.attackEntityFrom(arrowDamage(p), 2), "Open shulker arrow fixture rejected");
                require(p.getHealth() < traitHealth, "Open shulker stayed arrow immune");
                p.setSneaking(true);
                traitWait = 1;
                break;
            case 7:
                require(p.getEntityAttribute(SharedMonsterAttributes.ARMOR).getAttributeValue() == 20, "Second shulker shell did not close");
                // Remain sneaking across the transition to prove form exit removes the modifier.
                suiteMorph(p, "cow");
                p.setSneaking(true);
                traitWait = Morph.config.morphTime + 10;
                break;
            case 8:
                require(p.getEntityAttribute(SharedMonsterAttributes.ARMOR).getAttributeValue() == 0, "Shulker armor persisted after leaving form");
                record("PASS shulker closed shell +20 armor/arrow immunity, opening and leaving remove traits");
                traitMorph(p, "enderman");
                break;
            case 9:
                damageReady(p);
                p.setHealth(p.getMaxHealth());
                p.getEntityData().setLong("MorphEscapeAfter", 0);
                Vec3d before = p.getPositionVector();
                traitHealth = p.getHealth();
                p.attackEntityFrom(arrowDamage(p), 8);
                double escaped = p.getPositionVector().distanceTo(before);
                require(p.getHealth() == traitHealth, "Enderman took indirect projectile damage");
                require(escaped > 0.5 && escaped <= 8.001, "Enderman did not escape safely within 8 blocks: " + escaped);
                require(p.world.getCollisionBoxes(p, p.getEntityBoundingBox()).isEmpty()
                        && !p.world.containsAnyLiquid(p.getEntityBoundingBox())
                        && p.world.getWorldBorder().contains(p.getEntityBoundingBox()), "Enderman escaped into invalid space");
                damageReady(p);
                require(p.attackEntityFrom(DamageSource.GENERIC, 2), "Enderman direct damage fixture rejected");
                require(p.getHealth() < traitHealth, "Enderman incorrectly immune to direct generic damage");
                record("PASS enderman indirect immunity + safe escape distance=" + escaped + "; direct generic damage still applies");
                require(PlayerMorphHandler.getInstance().forceDemorph(p), "Traits final demorph rejected");
                traitWait = Morph.config.morphTime + 10;
                break;
            default:
                require(ActiveAbilityHandler.current(p) == null, "Traits final demorph retained active action");
                record("COMPLETE assertions passed; screenshots=" + screenshots + "; extraSwingScreenshots="
                        + extraScreenshots + "; activeForms=" + (visualOnly ? "skipped(previous verified run)" : abilityIndex)
                        + "; newTraits=witch,wither,shulker,enderman; visual appearance requires image review");
                stopped = true;
        }
    }

    private void checkForm()
    {
        EntityPlayerMP p = player();
        MorphInfo info = Morph.eventHandlerServer.morphsActive.get(p.getName());
        if("player".equals(FORMS[form]))
        {
            require(info == null || !info.isMorphing(), "Demorph still transitioning");
            require(ActiveAbilityHandler.current(p) == null, "Player retained active ability");
            record("PASS demorph and no active ability");
            return;
        }
        require(info != null && !info.isMorphing(), "Server transition unfinished");
        require(MobRelations.shape(p) == info.nextState.getEntInstance(p.world), "Relations shape mismatch");
        if("zombie".equals(FORMS[form]))
        {
            require(p.getCreatureAttribute() == EnumCreatureAttribute.UNDEAD, "Zombie player does not have vanilla undead attribute");
            p.capabilities.disableDamage = false;
            p.hurtResistantTime = 0;
            p.setHealth(p.getMaxHealth() / 2);
            float before = p.getHealth();
            MobEffects.INSTANT_HEALTH.performEffect(p, 0);
            require(p.getHealth() < before, "Instant health did not damage undead player");
            before = p.getHealth();
            MobEffects.INSTANT_DAMAGE.performEffect(p, 0);
            require(p.getHealth() > before, "Instant damage did not heal undead player");
            require(!p.isPotionApplicable(new PotionEffect(MobEffects.POISON, 100)), "Undead player accepts poison");
            require(!p.isPotionApplicable(new PotionEffect(MobEffects.REGENERATION, 100)), "Undead player accepts regeneration");
            p.capabilities.disableDamage = true;
            p.setHealth(p.getMaxHealth());
            record("PASS vanilla undead attribute, inverse instant potions, poison/regeneration immunity");
        }
        if("spider".equals(FORMS[form]))
        {
            try
            {
                java.lang.reflect.Field web = ReflectionHelper.findField(Entity.class, "isInWeb", "field_70134_J");
                web.setBoolean(p, false);
                p.setInWeb();
                require(!web.getBoolean(p), "Spider player was slowed by vanilla setInWeb");
                record("PASS spider vanilla web immunity through Entity.setInWeb");
            }
            catch(ReflectiveOperationException e) { throw new IllegalStateException("Cannot inspect web flag", e); }
        }
        ActiveAbility a = ActiveAbilityHandler.current(p);
        require(a != null && a == ActiveAbility.get("minecraft:" + FORMS[form]), "Active ability mismatch");
        EntityCow target = new EntityCow(p.world);
        target.setNoAI(true);
        target.setPosition(p.posX, p.posY, p.posZ + 2);
        p.world.spawnEntity(target);
        ActiveAbilityHandler.use(p);
        int remaining = ActiveAbilityHandler.remaining(p, a);
        require(remaining > 0 && remaining <= a.cooldown, "Ability did not enter cooldown " + a.action);
        ActiveAbilityHandler.use(p);
        require(ActiveAbilityHandler.remaining(p, a) == remaining, "Repeated ability changed cooldown");
        cooldownProbe = a;
        cooldownProbeAt = p.world.getTotalWorldTime() + 3;
        target.setDead();
        if("zombie".equals(FORMS[form]))
        {
            EntityZombie mob = new EntityZombie(p.world);
            mob.setPosition(p.posX + 2, p.posY, p.posZ);
            mob.setAttackTarget(p);
            require(mob.getAttackTarget() == null, "Zombie camouflage failed");
            mob.setRevengeTarget(p);
            mob.setAttackTarget(p);
            require(mob.getAttackTarget() == p, "Provocation did not override camouflage");
            record("PASS zombie camouflage and provocation through Forge target events");
        }
        record("PASS server form=" + FORMS[form] + " passiveCount=" + info.nextState.abilities.size()
                + " active=" + a.action + " cooldown=" + remaining);
        // Restore controlled viewpoint after teleport/jump and remove visual ability effects.
        p.clearActivePotions();
        p.setPositionAndUpdate(0.5, 8, 0.5);
        for(Entity entity : p.world.loadedEntityList)
            if(entity != p && !(entity instanceof net.minecraft.entity.player.EntityPlayer)) entity.setDead();
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event)
    {
        if(hudOnly)
        {
            if(!stopped && event.phase == TickEvent.Phase.END)
            {
                try { renderHud(); }
                catch(Exception e) { fail(e); }
            }
            return;
        }
        if(stopped || !capture || wait > 0 || mc.player == null || mc.currentScreen != null) return;
        try
        {
            int index = scene - 1;
            if(event.phase == TickEvent.Phase.START)
            {
                // Freeze the actual vanilla swing input at its visible midpoint for repeatable comparison.
                mc.player.isSwingInProgress = false;
                mc.player.swingProgress = mc.player.prevSwingProgress = index % 4 == 1
                        ? new float[]{0.5F, 0.15F, 0.35F, 0.65F}[swingFrame] : 0;
                return;
            }
            require(mc.player.getPrimaryHand() == mc.gameSettings.mainHand, "Client primary hand did not synchronize");
            ItemStack held = mc.player.getHeldItemMainhand();
            require(index % 4 == 2 ? held.getItem() == Items.DIAMOND_SWORD : index % 4 == 3
                    ? held.getItem() == Items.FILLED_MAP : held.isEmpty(), "Client held item did not synchronize");
            String name = String.format("%02d_%s_%s_%s.png", form, FORMS[form], index < 4 ? "right" : "left", SCENES[index % 4]);
            if(index % 4 == 1 && swingFrame > 0)
                name = name.replace("_swing.png", "_swing_" + new String[]{"050", "015", "035", "065"}[swingFrame] + ".png");
            ScreenShotHelper.saveScreenshot(output, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            File image = new File(output, "screenshots/" + name);
            require(image.isFile() && image.length() > 1000, "Framebuffer screenshot missing " + name);
            record("CAPTURE " + name + " bytes=" + image.length());
            if(swingFrame == 0) screenshots++;
            else extraScreenshots++;
            if(index % 4 == 1 && swingFrame++ < 3) return;
            swingFrame = 0;
            capture = false;
            scene++;
            if(scene > 8)
            {
                verifyModels();
                record("PASS cached vanilla model signatures unchanged after " + FORMS[form]);
                form++;
                scene = 0;
                if(form == FORMS.length)
                {
                    require(screenshots == 88, "Screenshot series incomplete");
                    record("VISUAL_SERIES_COMPLETE screenshots=" + screenshots + "; beginning server integration checks");
                    screenshotsDone = true;
                }
                else pending = mc.getIntegratedServer().addScheduledTask(this::beginForm);
            }
        }
        catch(Exception e) { fail(e); }
    }

    private void fail(Exception e)
    {
        stopped = true;
        if(output != null) record("FAIL " + e);
        e.printStackTrace();
    }
}
