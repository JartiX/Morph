package me.ichun.mods.morph.common.ability;

import me.ichun.mods.morph.common.Morph;
import me.ichun.mods.morph.common.morph.MorphInfo;
import me.ichun.mods.morph.common.packet.PacketAbilityState;
import me.ichun.mods.morph.api.ability.Ability;
import me.ichun.mods.morph.api.ability.type.AbilityPotionEffect;
import net.minecraft.entity.*;
import net.minecraft.entity.player.*;
import net.minecraft.entity.projectile.*;
import net.minecraft.init.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.potion.PotionUtils;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.living.EnderTeleportEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** All world mutations happen on the logical server. The client sends only an intent. */
public final class ActiveAbilityHandler
{
    private static final String DATA = "MorphActiveAbilities", SHOT = "MorphAbilityShot";
    private static final ThreadLocal<EntityPlayer> EXPLODING = new ThreadLocal<>();

    @SubscribeEvent public void naturalAttack(LivingAttackEvent event) { VanillaPassives.attack(event); }
    @SubscribeEvent public void naturalKnockback(LivingKnockBackEvent event) { VanillaPassives.knockback(event); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void naturalHurt(LivingHurtEvent event) { VanillaPassives.hurt(event); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void meleeEffect(LivingHurtEvent event)
    {
        if(event.getEntityLiving().world.isRemote || event.getAmount() <= 0 || Morph.config.abilities != 1
                || !(event.getSource().getTrueSource() instanceof EntityPlayer)
                || event.getSource().getImmediateSource() != event.getSource().getTrueSource()
                || !"player".equals(event.getSource().getDamageType())) return;
        EntityPlayer player = (EntityPlayer)event.getSource().getTrueSource();
        if(!mayHit(player, event.getEntityLiving())) return;
        MorphInfo info = Morph.eventHandlerServer.morphsActive.get(player.getName());
        if(info == null || info.isMorphing() || info.nextState.abilities == null) return;
        for(Ability ability : info.nextState.abilities)
            if(ability instanceof AbilityPotionEffect)
            {
                AbilityPotionEffect mapped = (AbilityPotionEffect)ability;
                Potion potion = Potion.getPotionById(mapped.potionId);
                if(potion != null && mapped.duration > 0)
                    event.getEntityLiving().addPotionEffect(new PotionEffect(potion, mapped.duration, mapped.amplifier, mapped.ambient, true));
            }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void explosion(ExplosionEvent.Detonate event)
    {
        EntityPlayer owner = EXPLODING.get();
        if(owner == null || event.getExplosion().getExplosivePlacedBy() != owner) return;
        event.getAffectedEntities().removeIf(entity -> !(entity instanceof EntityLivingBase)
                || !mayHit(owner, (EntityLivingBase)entity));
    }

    public static ActiveAbility current(EntityPlayer player)
    {
        if(Morph.config.abilities != 1 || Morph.eventHandlerServer == null) return null;
        MorphInfo info = Morph.eventHandlerServer.morphsActive.get(player.getName());
        if(info == null || info.isMorphing() || info.nextState == null) return null;
        ActiveAbility ability = ActiveAbility.get(info.nextState.currentVariant.entId);
        if(ability != null)
            for(String disabled : Morph.config.disabledAbilities)
                if("active".equalsIgnoreCase(disabled) || ability.cooldownKey().equalsIgnoreCase(disabled)
                        || ("active:" + ability.form).equalsIgnoreCase(disabled)) return null;
        return ability;
    }

    private static NBTTagCompound data(EntityPlayer player)
    {
        NBTTagCompound root = player.getEntityData();
        if(!root.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) root.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        NBTTagCompound persistent = root.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if(!persistent.hasKey(DATA)) persistent.setTag(DATA, new NBTTagCompound());
        return persistent.getCompoundTag(DATA);
    }

    // Overworld total time is saved, monotonic across /time set and common to every dimension.
    private static long now(EntityPlayer player) { return player.getServer().getWorld(0).getTotalWorldTime(); }
    public static int remaining(EntityPlayer player, ActiveAbility ability)
    {
        return (int)Math.min(ability.cooldown, Math.max(0L, data(player).getLong(ability.cooldownKey()) - now(player)));
    }

    public static void sync(EntityPlayer player)
    {
        ActiveAbility a = current(player);
        Morph.channel.sendTo(new PacketAbilityState(a == null ? "" : a.key(), a == null ? 0 : remaining(player, a), a == null ? 0 : a.cooldown), player);
    }

    public static void use(EntityPlayer player)
    {
        if(player.world.isRemote || !(player instanceof EntityPlayerMP) || player.isSpectator()
                || !player.isEntityAlive() || player.isPlayerSleeping()) return;
        long time = now(player);
        if(player.getEntityData().getLong("MorphAbilityRequestAfter") > time) return;
        player.getEntityData().setLong("MorphAbilityRequestAfter", time + 2);
        ActiveAbility ability = current(player);
        if(ability == null || remaining(player, ability) != 0)
        { AbilitySounds.unavailable(player); sync(player); return; }
        if(perform(player, ability))
        {
            data(player).setLong(ability.cooldownKey(), now(player) + ability.cooldown);
            player.addExhaustion(0.5F);
            particles(player, EnumParticleTypes.SPELL, 12);
            AbilitySounds.playSuccess(player, ability.action);
        }
        else if(AbilitySounds.unavailable(player))
            player.sendStatusMessage(new net.minecraft.util.text.TextComponentTranslation("morph.active.unavailable"), true);
        sync(player);
    }

    @SubscribeEvent public void clone(PlayerEvent.Clone event)
    {
        // Forge generally copies PlayerPersisted, but explicitly preserve cooldowns on death too.
        NBTTagCompound copy = data(event.getOriginal()).copy();
        data(event.getEntityPlayer());
        event.getEntityPlayer().getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG).setTag(DATA, copy);
    }

    @SubscribeEvent public void tick(TickEvent.PlayerTickEvent event)
    {
        if(event.phase != TickEvent.Phase.END || event.player.world.isRemote) return;
        if(event.player.ticksExisted % 10 == 0) sync(event.player);
        ExtraPassives.tick(event.player);
        VanillaPassives.tick(event.player);
    }

    @SubscribeEvent public void login(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event)
    { ExtraPassives.login(event.player); }

    public static boolean mayHit(EntityPlayer owner, EntityLivingBase victim)
    {
        if(victim == owner || !victim.isEntityAlive() || victim.isOnSameTeam(owner)) return false;
        if(victim instanceof EntityPlayer)
        {
            EntityPlayer other = (EntityPlayer)victim;
            if(other.isSpectator() || other.capabilities.disableDamage || !owner.canAttackPlayer(other)
                    || !owner.getServer().isPVPEnabled()) return false;
        }
        return true;
    }

    private static EntityLivingBase target(EntityPlayer player, double range)
    {
        Vec3d eye = player.getPositionEyes(1), end = eye.add(player.getLookVec().scale(range));
        RayTraceResult block = player.world.rayTraceBlocks(eye, end, false, true, false);
        double best = block == null ? range : eye.distanceTo(block.hitVec);
        EntityLivingBase selected = null;
        for(EntityLivingBase living : player.world.getEntitiesWithinAABB(EntityLivingBase.class,
                player.getEntityBoundingBox().expand(player.getLookVec().x * range, player.getLookVec().y * range, player.getLookVec().z * range).grow(1)))
        {
            if(!mayHit(player, living) || !player.canEntityBeSeen(living)) continue;
            RayTraceResult hit = living.getEntityBoundingBox().grow(0.2).calculateIntercept(eye, end);
            if(hit != null && eye.distanceTo(hit.hitVec) < best) { selected = living; best = eye.distanceTo(hit.hitVec); }
        }
        return selected;
    }

    private static boolean strike(EntityPlayer p, String action, double range, float damage, Potion potion, int seconds)
    {
        EntityLivingBase t = target(p, range);
        if(t == null) return false;
        if(!t.attackEntityFrom(DamageSource.causePlayerDamage(p), damage)) return false;
        if(potion != null) effect(t, potion, seconds, 0);
        AbilitySounds.playImpact(p.world, t.getPositionVector().add(0, t.height / 2D, 0), action);
        return true;
    }

    private static void pulse(EntityPlayer p, double radius, float damage, Potion potion, int seconds, boolean knock)
    {
        for(EntityLivingBase t : p.world.getEntitiesWithinAABB(EntityLivingBase.class, p.getEntityBoundingBox().grow(radius)))
            if(mayHit(p, t) && p.getDistanceSq(t) <= radius * radius && p.canEntityBeSeen(t))
            {
                if(damage == 0 || t.attackEntityFrom(DamageSource.causePlayerDamage(p), damage))
                {
                    if(potion != null) effect(t, potion, seconds, 0);
                    if(knock) t.knockBack(p, 0.7F, p.posX - t.posX, p.posZ - t.posZ);
                }
            }
    }

    private static void effect(EntityLivingBase p, Potion potion, int seconds, int amplifier)
    { p.addPotionEffect(new PotionEffect(potion, seconds * 20, amplifier)); }

    private static boolean shoot(EntityPlayer p, String action)
    {
        Vec3d direction = p.getLookVec();
        Entity projectile;
        switch(action)
        {
            case "arrow": case "stray_arrow":
                EntityTippedArrow arrow = new EntityTippedArrow(p.world, p);
                arrow.setDamage(2); arrow.pickupStatus = EntityArrow.PickupStatus.DISALLOWED;
                arrow.shoot(p, p.rotationPitch, p.rotationYaw, 0, 2.2F, 1);
                projectile = arrow; break;
            case "blaze": projectile = new EntitySmallFireball(p.world, p, direction.x, direction.y, direction.z); break;
            case "ghast": projectile = new EntityLargeFireball(p.world, p, direction.x, direction.y, direction.z); break;
            case "skull": projectile = new EntityWitherSkull(p.world, p, direction.x, direction.y, direction.z); break;
            case "breath": projectile = new EntityDragonFireball(p.world, p, direction.x, direction.y, direction.z); break;
            case "witch":
                EntityPotion potion = new EntityPotion(p.world, p,
                        PotionUtils.addPotionToItemStack(new ItemStack(Items.SPLASH_POTION), PotionTypes.POISON));
                potion.shoot(p, p.rotationPitch, p.rotationYaw, -10, 0.8F, 1); projectile = potion; break;
            case "levitate":
                EntityLivingBase victim = target(p, 16);
                if(victim == null) return false;
                projectile = new EntityShulkerBullet(p.world, p, victim, EnumFacing.Axis.Y); break;
            default:
                EntitySnowball ball = new EntitySnowball(p.world, p);
                ball.shoot(p, p.rotationPitch, p.rotationYaw, 0, 1.5F, 1); projectile = ball;
        }
        if(projectile instanceof EntityFireball)
            projectile.setPosition(p.posX, p.posY + p.getEyeHeight(), p.posZ);
        projectile.getEntityData().setString(SHOT, action);
        projectile.getEntityData().setUniqueId("MorphAbilityOwner", p.getUniqueID());
        return p.world.spawnEntity(projectile);
    }

    @SubscribeEvent public void impact(ProjectileImpactEvent event)
    {
        Entity shot = event.getEntity();
        String action = shot.getEntityData().getString(SHOT);
        if(action.isEmpty()) return;
        // Cancel vanilla fireball block damage/fire, skull healing and splash that bypasses PvP rules.
        event.setCanceled(true);
        if(shot.world.isRemote) return;
        EntityPlayer owner = shot.world.getPlayerEntityByUUID(shot.getEntityData().getUniqueId("MorphAbilityOwner"));
        if(shot instanceof EntityFireball && ((EntityFireball)shot).shootingEntity != null)
        {
            // Vanilla reflection replaces shootingEntity. Use its current owner and PvP permissions.
            EntityLivingBase shooter = ((EntityFireball)shot).shootingEntity;
            owner = shooter instanceof EntityPlayer ? (EntityPlayer)shooter : null;
        }
        RayTraceResult hit = event.getRayTraceResult();
        Vec3d center = hit.hitVec == null ? shot.getPositionVector() : hit.hitVec;
        DamageSource source = (action.equals("blaze") || action.equals("ghast")) && shot instanceof EntityFireball
                ? DamageSource.causeFireballDamage((EntityFireball)shot, owner)
                : action.equals("breath") ? DamageSource.causeIndirectMagicDamage(shot, owner)
                : DamageSource.causeThrownDamage(shot, owner);
        if(owner != null && owner.isEntityAlive() && hit.entityHit instanceof EntityLivingBase)
        {
            EntityLivingBase t = (EntityLivingBase)hit.entityHit;
            if(mayHit(owner, t))
            {
                float damage = action.equals("web") ? 0 : action.equals("snowball") ? 1 : action.equals("spit") ? 2 : 6;
                if(damage == 0 || t.attackEntityFrom(source, damage))
                {
                    if(action.equals("web")) effect(t, MobEffects.SLOWNESS, 5, 3);
                    if(action.equals("levitate")) effect(t, MobEffects.LEVITATION, 4, 0);
                    if(action.equals("stray_arrow")) effect(t, MobEffects.SLOWNESS, 6, 0);
                    if(action.equals("witch")) effect(t, MobEffects.POISON, 5, 0);
                    if(action.equals("skull")) effect(t, MobEffects.WITHER, 5, 0);
                    if(action.equals("blaze")) t.setFire(3);
                }
            }
        }
        if(owner != null && owner.isEntityAlive() && (action.equals("breath") || action.equals("ghast")))
            for(EntityLivingBase t : shot.world.getEntitiesWithinAABB(EntityLivingBase.class,
                    new AxisAlignedBB(center.x - 3, center.y - 3, center.z - 3, center.x + 3, center.y + 3, center.z + 3)))
                if(t != hit.entityHit && mayHit(owner, t) && t.getDistanceSq(center.x, center.y, center.z) <= 9
                        && shot.world.rayTraceBlocks(center, t.getPositionEyes(1), false, true, false) == null)
                    t.attackEntityFrom(source, 4);
        if(shot.world instanceof WorldServer)
            ((WorldServer)shot.world).spawnParticle(action.equals("web") ? EnumParticleTypes.SNOWBALL : EnumParticleTypes.SPELL,
                    center.x, center.y, center.z, 12, 0.25, 0.25, 0.25, 0.02);
        AbilitySounds.playImpact(shot.world, center, action);
        shot.setDead();
    }

    private static boolean blink(EntityPlayer p, int range)
    {
        return blink(p, range, p.getLookVec());
    }

    /** Defensive enderman escape uses the same collision/permission checks as the active blink. */
    public static void escapeProjectile(EntityPlayer p)
    {
        long time = now(p);
        if(p.getEntityData().getLong("MorphEscapeAfter") > time) return;
        p.getEntityData().setLong("MorphEscapeAfter", time + 10);
        for(int attempt = 0; attempt < 8; attempt++)
        {
            double angle = p.getRNG().nextDouble() * Math.PI * 2;
            if(blink(p, 8, new Vec3d(Math.cos(angle), 0, Math.sin(angle))))
            { AbilitySounds.playSuccess(p, "teleport"); break; }
        }
    }

    private static boolean blink(EntityPlayer p, int range, Vec3d direction)
    {
        Vec3d start = p.getPositionEyes(1), end = start.add(direction.scale(range));
        RayTraceResult hit = p.world.rayTraceBlocks(start, end, false, true, false);
        double distance = hit == null ? range : Math.max(0, start.distanceTo(hit.hitVec) - p.width - 0.5);
        for(double d = distance; d >= 1; d -= 0.5)
        {
            double x = p.posX + direction.x * d, y = p.posY + direction.y * d, z = p.posZ + direction.z * d;
            AxisAlignedBB box = p.getEntityBoundingBox().offset(x - p.posX, y - p.posY, z - p.posZ);
            BlockPos pos = new BlockPos(x, y, z);
            if(y < 1 || y + p.height >= p.world.getHeight() || !p.world.isBlockLoaded(pos)
                    || !p.world.getWorldBorder().contains(box) || !p.world.getCollisionBoxes(p, box).isEmpty()
                    || p.world.containsAnyLiquid(box)) continue;
            // Swept bounding box prevents squeezing through walls with only an unobstructed eye ray.
            boolean clear = true;
            for(double step = 0.25; step <= d; step += 0.25)
                if(!p.world.getCollisionBoxes(p, p.getEntityBoundingBox().offset(direction.x * step, direction.y * step, direction.z * step)).isEmpty()) { clear = false; break; }
            if(!clear) continue;
            EnderTeleportEvent teleport = new EnderTeleportEvent(p, x, y, z, 0);
            if(MinecraftForge.EVENT_BUS.post(teleport)) return false;
            x = teleport.getTargetX(); y = teleport.getTargetY(); z = teleport.getTargetZ();
            // Permission/event handlers may redirect a teleport; validate their final destination too.
            Vec3d delta = new Vec3d(x - p.posX, y - p.posY, z - p.posZ);
            double length = delta.length();
            AxisAlignedBB adjusted = p.getEntityBoundingBox().offset(delta);
            if(!Double.isFinite(length) || length > range + 1.0E-6 || y < 1 || y + p.height >= p.world.getHeight()
                    || !p.world.isBlockLoaded(new BlockPos(x, y, z)) || !p.world.getWorldBorder().contains(adjusted)
                    || p.world.containsAnyLiquid(adjusted)) return false;
            Vec3d stepDirection = length > 0 ? delta.scale(1 / length) : Vec3d.ZERO;
            for(double step = 0; step <= length; step += 0.25)
                if(!p.world.getCollisionBoxes(p, p.getEntityBoundingBox().offset(stepDirection.scale(step))).isEmpty()) return false;
            if(!p.world.getCollisionBoxes(p, adjusted).isEmpty()) return false;
            p.setPositionAndUpdate(x, y, z); p.fallDistance = 0; return true;
        }
        return false;
    }

    private static void particles(EntityPlayer p, EnumParticleTypes particle, int count)
    {
        ((WorldServer)p.world).spawnParticle(particle, p.posX, p.posY + p.height / 2, p.posZ, count, 0.4, 0.4, 0.4, 0.03);
    }

    private static boolean perform(EntityPlayer p, ActiveAbility a)
    {
        switch(a.action)
        {
            case "arrow": case "stray_arrow": case "blaze": case "ghast": case "skull": case "breath":
            case "web": case "spit": case "levitate": case "witch": case "snowball": return shoot(p, a.action);
            case "egg": give(p, new ItemStack(Items.EGG)); return true;
            case "teleport": return blink(p, 12);
            case "blink": return blink(p, 5);
            case "explode":
                if(p.getHealth() <= 1) return false;
                p.setHealth(Math.max(1, p.getHealth() - 4));
                MorphInfo info = Morph.eventHandlerServer.morphsActive.get(p.getName());
                EntityLivingBase form = info.nextState.getEntInstance(p.world);
                float power = form instanceof net.minecraft.entity.monster.EntityCreeper
                        && ((net.minecraft.entity.monster.EntityCreeper)form).getPowered() ? 6F : 3F;
                EXPLODING.set(p);
                try
                {
                    p.world.newExplosion(p, p.posX, p.posY, p.posZ, power, false,
                            ForgeEventFactory.getMobGriefingEvent(p.world, p));
                }
                finally { EXPLODING.remove(); }
                return true;
            case "beam": return strike(p, a.action, 16, 6, null, 0);
            case "elder_beam": return strike(p, a.action, 20, 8, MobEffects.MINING_FATIGUE, 6);
            case "fangs": return strike(p, a.action, 10, 6, null, 0);
            case "wither_strike": return strike(p, a.action, 3.5, 5, MobEffects.WITHER, 5);
            case "hunger": return strike(p, a.action, 3.5, 4, MobEffects.HUNGER, 8);
            case "lunge": case "maul": case "cleave":
                if(!strike(p, a.action, 4, a.action.equals("cleave") ? 7 : 5, null, 0)) return false;
                return true;
            case "stomp": case "slam": case "magma":
                pulse(p, a.action.equals("stomp") ? 5 : 3, a.action.equals("magma") ? 4 : 6, null, 0, true); return true;
            case "echo":
                for(EntityLivingBase t : p.world.getEntitiesWithinAABB(EntityLivingBase.class, p.getEntityBoundingBox().grow(12)))
                    if(mayHit(p, t) && p.canEntityBeSeen(t)) effect(t, MobEffects.GLOWING, 6, 0);
                return true;
            case "cleanse": p.clearActivePotions(); return true;
            case "soup":
                if(!consumeHeld(p, Items.BOWL)) return false;
                give(p, new ItemStack(Items.MUSHROOM_STEW)); return true;
            case "barter":
                if(!consumeHeld(p, Items.EMERALD)) return false;
                give(p, new ItemStack(Items.BREAD, 3)); return true;
            case "graze":
                if(p.world.getBlockState(p.getPosition().down()).getBlock() != Blocks.GRASS) return false;
                p.getFoodStats().addStats(2, 0.2F); return true;
            case "ink":
                effect(p, MobEffects.INVISIBILITY, 5, 0); pulse(p, 4, 0, MobEffects.BLINDNESS, 4, false);
                particles(p, EnumParticleTypes.SMOKE_LARGE, 30); return true;
            case "illusion": effect(p, MobEffects.INVISIBILITY, 6, 0); effect(p, MobEffects.SPEED, 6, 0); return true;
            case "screech":
                pulse(p, 6, 0, MobEffects.WEAKNESS, 5, false);
                return true;
            case "howl": case "rally": effect(p, MobEffects.STRENGTH, 7, 0); return true;
            case "stand": effect(p, MobEffects.RESISTANCE, 5, 1); effect(p, MobEffects.SLOWNESS, 5, 3); return true;
            case "jump": case "slime": case "pounce":
                if(!p.onGround) return false;
                p.motionY = a.action.equals("slime") ? 0.8 : 0.65;
                p.velocityChanged = true;
                effect(p, MobEffects.JUMP_BOOST, 3, 1); return true;
            default: // charge, sprint, scurry and vex dash use collision-respecting normal movement.
                effect(p, MobEffects.SPEED, 5, a.action.equals("charge") ? 2 : 1); return true;
        }
    }
    private static boolean consumeHeld(EntityPlayer p, net.minecraft.item.Item item)
    {
        for(EnumHand hand : EnumHand.values())
            if(p.getHeldItem(hand).getItem() == item) { p.getHeldItem(hand).shrink(1); return true; }
        return false;
    }
    private static void give(EntityPlayer p, ItemStack stack)
    { if(!p.inventory.addItemStackToInventory(stack)) p.dropItem(stack, false); }
}
