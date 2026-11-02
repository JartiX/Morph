package me.ichun.mods.morph.validation;

import me.ichun.mods.morph.client.ability.AbilityClient;
import me.ichun.mods.morph.common.Morph;
import me.ichun.mods.morph.common.ability.ActiveAbility;
import me.ichun.mods.morph.common.ability.ActiveAbilityHandler;
import me.ichun.mods.morph.common.ability.AbilitySounds;
import me.ichun.mods.morph.common.handler.PlayerMorphHandler;
import me.ichun.mods.morph.common.morph.MorphInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.SoundEvents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.PlaySoundAtEntityEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

/** A short independent real-key/network/sound acceptance run, with no screenshot sweep. */
@Mod(modid = "morph_sound_validation", name = "Morph Sound Validation", version = "1", clientSideOnly = true,
        dependencies = "required-after:morph")
public final class SoundValidation
{
    private final Minecraft mc = Minecraft.getMinecraft();
    private final Map<ResourceLocation, Integer> heard = new HashMap<>();
    private File output;
    private String worldName;
    private boolean started;
    private volatile boolean ready, stopped;
    private volatile long serverTime;
    private volatile int publicFeedback;
    private int phase, elapsed, wait, requests;
    private long requestAt, validCooldownEnd;
    private Future<?> pending;
    private EntityCow target;
    private float targetAfterHit;
    private boolean feedbackValid = true;
    private final ActiveAbility ability = ActiveAbility.get("minecraft:zombie");

    @Mod.EventHandler
    public void init(FMLInitializationEvent event)
    {
        if(Boolean.getBoolean("morph.validation.soundsOnly")) MinecraftForge.EVENT_BUS.register(this);
    }

    private static void require(boolean condition, String message)
    {
        if(!condition) throw new IllegalStateException(message);
    }

    private void record(String message)
    {
        try
        {
            Files.write(new File(output, "report.txt").toPath(), (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("[MorphSoundValidation] " + message);
        }
        catch(Exception e) { throw new IllegalStateException("Cannot save sound evidence", e); }
    }

    private EntityPlayerMP player()
    {
        require(mc.getIntegratedServer().getWorld(0).getWorldInfo().getWorldName().equals(worldName), "Refusing non-fixture world");
        return mc.getIntegratedServer().getPlayerList().getPlayers().get(0);
    }

    private void freshTarget(boolean invulnerable)
    {
        EntityPlayerMP p = player();
        if(target != null) target.setDead();
        target = new EntityCow(p.world);
        target.setNoAI(true);
        target.setNoGravity(true);
        target.setEntityInvulnerable(invulnerable);
        target.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(100);
        target.setHealth(100);
        target.setPosition(p.posX, p.posY + p.getEyeHeight() - target.height / 2D, p.posZ + 2.5);
        require(p.world.spawnEntity(target), "Sound target spawn failed");
    }

    private void resetCase()
    {
        // Only case preparation resets timers; the repeated-request scenario never changes them.
        EntityPlayerMP p = player();
        NBTTagCompound root = p.getEntityData();
        NBTTagCompound persistent = root.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if(!persistent.hasKey("MorphActiveAbilities")) persistent.setTag("MorphActiveAbilities", new NBTTagCompound());
        persistent.getCompoundTag("MorphActiveAbilities").setLong(ability.cooldownKey(), 0);
        root.setTag(EntityPlayer.PERSISTED_NBT_TAG, persistent);
        root.setLong("MorphAbilityRequestAfter", 0);
        root.setLong("MorphAbilityFeedbackAfter", 0);
        ActiveAbilityHandler.sync(p);
    }

    @SubscribeEvent
    public void server(TickEvent.ServerTickEvent event)
    {
        if(!ready || stopped || event.phase != TickEvent.Phase.END) return;
        EntityPlayerMP p = player();
        p.motionX = p.motionY = p.motionZ = 0;
        p.rotationYaw = p.rotationPitch = 0;
        p.extinguish();
        serverTime = p.world.getTotalWorldTime();
    }

    @SubscribeEvent
    public void sound(PlaySoundEvent event)
    {
        if(!ready || stopped || event.getSound() == null) return;
        ResourceLocation sound = event.getSound().getSoundLocation();
        heard.put(sound, heard.getOrDefault(sound, 0) + 1);
        if(sound.equals(SoundEvents.UI_BUTTON_CLICK.getRegistryName()))
        {
            feedbackValid &= event.getSound().getCategory() == SoundCategory.PLAYERS
                    && event.getSound().getVolume() <= 0.2F && event.getSound().getPitch() < 0.8F;
        }
    }

    @SubscribeEvent
    public void publicSound(PlaySoundAtEntityEvent event)
    {
        if(ready && !stopped && net.minecraftforge.fml.common.FMLCommonHandler.instance().getEffectiveSide().isServer()
                && event.getSound() == SoundEvents.UI_BUTTON_CLICK) publicFeedback++;
    }

    private int count(net.minecraft.util.SoundEvent sound) { return heard.getOrDefault(sound.getRegistryName(), 0); }

    private void clearSounds()
    {
        heard.clear();
        publicFeedback = 0;
        feedbackValid = true;
    }

    private void press()
    {
        KeyBinding.onTick(AbilityClient.USE.getKeyCode());
        requestAt = serverTime;
    }

    private void rejected(String label)
    {
        require(count(AbilitySounds.success("lunge")) == 0, label + " emitted success sound");
        require(count(SoundEvents.ENTITY_COW_HURT) == 0, label + " emitted target hit sound");
        require(count(SoundEvents.UI_BUTTON_CLICK) == 1, label + " did not emit exactly one quiet feedback cue; sounds=" + heard);
        require(publicFeedback == 0, label + " broadcast private feedback through World.playSound");
        require(feedbackValid, label + " feedback is not private-category/quiet/low pitched");
    }

    @SubscribeEvent
    public void client(TickEvent.ClientTickEvent event)
    {
        if(event.phase != TickEvent.Phase.END) return;
        if(stopped)
        {
            if(Boolean.getBoolean("morph.validation.autoExit")) mc.shutdown();
            return;
        }
        try
        {
            if(!started)
            {
                if(!(mc.currentScreen instanceof GuiMainMenu)) return;
                started = true;
                worldName = "MorphSoundValidation-" + System.currentTimeMillis();
                output = new File(mc.gameDir, "sound-validation/" + worldName);
                require(output.mkdirs(), "Cannot create sound evidence directory");
                record("START real key ticks, network requests, server health/cooldown, counted client sound events");
                mc.gameSettings.pauseOnLostFocus = false;
                mc.gameSettings.setSoundLevel(SoundCategory.MASTER, 1);
                mc.gameSettings.setSoundLevel(SoundCategory.PLAYERS, 1);
                WorldSettings settings = new WorldSettings(123456789L, GameType.SURVIVAL, false, false, WorldType.FLAT);
                settings.enableCommands();
                mc.launchIntegratedServer(worldName, worldName, settings);
                return;
            }
            if(++elapsed > 500) throw new IllegalStateException("Sound scenario exceeded 25 seconds of client ticks");
            if(mc.player == null || mc.getIntegratedServer() == null) return;
            mc.player.rotationYaw = mc.player.prevRotationYaw = 0;
            mc.player.rotationPitch = mc.player.prevRotationPitch = 0;
            mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
            if(pending != null)
            {
                if(!pending.isDone()) return;
                pending.get();
                pending = null;
            }
            if(wait-- > 0) return;
            switch(phase)
            {
                case 0:
                    pending = mc.getIntegratedServer().addScheduledTask(() -> {
                        EntityPlayerMP p = player();
                        p.world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
                        p.world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
                        p.world.setWorldTime(18000);
                        p.setPositionAndUpdate(0.5, 8, 0.5);
                        p.setNoGravity(true);
                        p.capabilities.disableDamage = true;
                        p.capabilities.allowFlying = p.capabilities.isFlying = true;
                        p.sendPlayerAbilities();
                        EntityZombie source = new EntityZombie(p.world);
                        require(PlayerMorphHandler.getInstance().acquireMorph(p, source, false, false), "Zombie acquire failed");
                        require(PlayerMorphHandler.getInstance().forceMorph(p, source), "Zombie morph failed");
                        ready = true;
                    });
                    phase = 1;
                    wait = Morph.config.morphTime + 15;
                    break;
                case 1:
                    MorphInfo info = Morph.eventHandlerClient.morphsActive.get(mc.player.getName());
                    require(info != null && !info.isMorphing(), "Client zombie form not settled");
                    pending = mc.getIntegratedServer().addScheduledTask(() -> {
                        Morph.eventHandlerServer.morphsActive.get(player().getName()).setPlayerBoundingBox();
                        freshTarget(false);
                        resetCase();
                    });
                    phase = 2;
                    break;
                case 2:
                    clearSounds();
                    press();
                    phase = 3;
                    break;
                case 3:
                    if(serverTime - requestAt < 6) return;
                    require(count(AbilitySounds.success("lunge")) == 1, "Valid hit must emit exactly one success cue; sounds=" + heard);
                    require(count(SoundEvents.ENTITY_COW_HURT) == 1, "Valid hit did not produce exactly one real cow hurt cue");
                    require(count(SoundEvents.UI_BUTTON_CLICK) == 0, "Valid hit emitted failure cue");
                    pending = mc.getIntegratedServer().addScheduledTask(() -> {
                        EntityPlayerMP p = player();
                        require(target.getHealth() < 100, "Valid action did not actually damage target");
                        require(ActiveAbilityHandler.remaining(p, ability) > 0, "Valid action did not start cooldown");
                        targetAfterHit = target.getHealth();
                        validCooldownEnd = p.world.getTotalWorldTime() + ActiveAbilityHandler.remaining(p, ability);
                        record("PASS valid real key hit: targetHealth=" + targetAfterHit + "; one success + one actual hurt cue");
                    });
                    phase = 4;
                    break;
                case 4:
                    clearSounds();
                    requests = 0;
                    phase = 5;
                    break;
                case 5:
                    if(requests < 5 && serverTime - requestAt >= 2)
                    {
                        press();
                        requests++;
                    }
                    if(requests < 5 || serverTime - requestAt < 5) return;
                    rejected("Five cooldown requests spaced at least two server ticks apart");
                    pending = mc.getIntegratedServer().addScheduledTask(() -> {
                        EntityPlayerMP p = player();
                        require(target.getHealth() == targetAfterHit, "Cooldown spam damaged target again");
                        require(p.world.getTotalWorldTime() + ActiveAbilityHandler.remaining(p, ability) == validCooldownEnd, "Cooldown spam restarted timer");
                        record("PASS five cooldown requests: no success/no hit, one quiet private click, unchanged target and absolute cooldown");
                        target.setDead();
                        resetCase();
                    });
                    phase = 6;
                    break;
                case 6:
                    clearSounds();
                    press();
                    phase = 7;
                    break;
                case 7:
                    if(serverTime - requestAt < 6) return;
                    rejected("No target");
                    pending = mc.getIntegratedServer().addScheduledTask(() -> {
                        require(ActiveAbilityHandler.remaining(player(), ability) == 0, "No target consumed cooldown");
                        record("PASS no target: cooldown remains zero, no success/hit sound, private feedback only");
                        freshTarget(true);
                        resetCase();
                    });
                    phase = 8;
                    break;
                case 8:
                    clearSounds();
                    press();
                    phase = 9;
                    break;
                case 9:
                    if(serverTime - requestAt < 6) return;
                    rejected("Invulnerable cow");
                    pending = mc.getIntegratedServer().addScheduledTask(() -> {
                        require(target.getHealth() == 100, "Invulnerable target lost health");
                        require(ActiveAbilityHandler.remaining(player(), ability) == 0, "Rejected damage consumed cooldown");
                        record("PASS invulnerable target: health100, cooldown0, no success/hit sound, private feedback only");
                    });
                    phase = 10;
                    break;
                default:
                    record("COMPLETE sound boundaries passed; actual key/network/sound counts; no window interaction");
                    stopped = true;
            }
        }
        catch(Exception e)
        {
            stopped = true;
            if(output != null) record("FAIL " + e);
            e.printStackTrace();
        }
    }
}
