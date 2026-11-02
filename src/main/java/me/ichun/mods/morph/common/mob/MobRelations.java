package me.ichun.mods.morph.common.mob;

import me.ichun.mods.morph.api.ability.Ability;
import me.ichun.mods.morph.common.Morph;
import me.ichun.mods.morph.common.morph.MorphInfo;
import me.ichun.mods.morph.common.morph.MorphState;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.EntityAIAvoidEntity;
import net.minecraft.entity.ai.EntityAITarget;
import net.minecraft.entity.monster.*;
import net.minecraft.entity.passive.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.*;

/** Server-only additions to vanilla AI. One instance must be registered on EVENT_BUS. */
public final class MobRelations
{
    private static final long GRUDGE_TICKS = 1200L;
    private static final double SEARCH_RADIUS = 16.0D;
    // World keys are removed at unload. Grudges contain UUIDs only and expire even
    // while a player is offline, so reconnecting cannot erase provocation.
    private final Map<World, Map<UUID, Map<UUID, Long>>> grudges = new IdentityHashMap<>();
    private final Map<World, Map<UUID, UUID>> acquiredTargets = new IdentityHashMap<>();
    private final Set<EntityLiving> installing = Collections.newSetFromMap(new WeakHashMap<EntityLiving, Boolean>());

    /** Use the old shape until the transition completes; changing shape never changes grudges. */
    public static EntityLivingBase shape(EntityPlayer player)
    {
        if(Morph.config.abilities != 1) return player;
        MorphInfo info = Morph.eventHandlerServer.morphsActive.get(player.getName());
        if(info == null) return player;
        MorphState state = info.isMorphing() && info.prevState != null ? info.prevState : info.nextState;
        return state == null ? player : state.getEntInstance(player.world);
    }

    private static boolean activePlayer(EntityPlayer player)
    {
        return player.isEntityAlive() && !player.isSpectator() && !player.capabilities.isCreativeMode;
    }

    private static boolean hostileShape(EntityPlayer player)
    {
        EntityLivingBase form = shape(player);
        if(form instanceof IMob) return true;
        MorphInfo info = Morph.eventHandlerServer.morphsActive.get(player.getName());
        if(info == null) return false;
        MorphState state = info.isMorphing() && info.prevState != null ? info.prevState : info.nextState;
        if(state != null && state.abilities != null)
            for(Ability ability : state.abilities)
                if("hostile".equals(ability.getType())) return true;
        return false;
    }

    private boolean angryAt(EntityLiving mob, EntityPlayer player)
    {
        Map<UUID, Map<UUID, Long>> world = grudges.get(mob.world);
        Map<UUID, Long> targets = world == null ? null : world.get(mob.getUniqueID());
        Long until = targets == null ? null : targets.get(player.getUniqueID());
        return until != null && until > mob.world.getTotalWorldTime();
    }

    private void remember(EntityLiving mob, EntityPlayer player)
    {
        grudges.computeIfAbsent(mob.world, w -> new HashMap<>())
            .computeIfAbsent(mob.getUniqueID(), id -> new HashMap<>())
            .put(player.getUniqueID(), mob.world.getTotalWorldTime() + GRUDGE_TICKS);
    }

    private boolean suitable(EntityLiving mob, EntityPlayer player)
    {
        return activePlayer(player) && mob.world.getDifficulty() != EnumDifficulty.PEACEFUL
            && !mob.isOnSameTeam(player)
            && EntityAITarget.isSuitableTarget(mob, player, false, true)
            && (!(mob instanceof EntityTameable) || !((EntityTameable)mob).isOwner(player));
    }

    private static boolean ownerCanAttack(EntityLiving mob, EntityPlayer player)
    {
        if(!(mob instanceof EntityTameable) || !((EntityTameable)mob).isTamed()) return true;
        EntityLivingBase owner = ((EntityTameable)mob).getOwner();
        // No speculative PvP on behalf of an absent owner.
        return owner instanceof EntityPlayer && mob.getServer() != null && mob.getServer().isPVPEnabled()
            && owner != player && ((EntityPlayer)owner).canAttackPlayer(player);
    }

    /** Exceptions to hostile camouflage: predator/prey relations have priority. */
    private boolean hunts(EntityLiving mob, EntityPlayer player)
    {
        EntityLivingBase form = shape(player);
        if(form == player) return false;
        if(mob instanceof EntityWolf)
        {
            EntityWolf wolf = (EntityWolf)mob;
            if(wolf.isSitting() || wolf.isOwner(player)) return false;
            if(wolf.isTamed()) return form instanceof AbstractSkeleton && ownerCanAttack(wolf, player);
            return form instanceof EntitySheep || form instanceof EntityRabbit || form instanceof AbstractSkeleton;
        }
        if(mob instanceof EntityOcelot)
            return !((EntityOcelot)mob).isTamed() && form instanceof EntityChicken;
        if(mob instanceof EntityZombie)
            return form instanceof EntityVillager || form instanceof EntityIronGolem;
        if(mob instanceof EntityIronGolem)
            return form instanceof IMob && !(form instanceof EntityCreeper);
        if(mob instanceof EntitySnowman) return form instanceof IMob;
        if(mob instanceof EntityGuardian)
            return form instanceof EntitySquid && mob.getDistanceSq(player) > 9.0D;
        return false;
    }

    private boolean shouldIgnore(EntityLiving mob, EntityPlayer player)
    {
        if(!activePlayer(player) || mob.isOnSameTeam(player)) return true;
        if(!ownerCanAttack(mob, player)) return true;
        if(mob instanceof EntityTameable && ((EntityTameable)mob).isOwner(player)) return true;
        if(mob instanceof EntityCreature && fears((EntityCreature)mob, player)) return true;
        return mob instanceof IMob && hostileShape(player) && !hunts(mob, player) && !angryAt(mob, player);
    }

    @SubscribeEvent
    public void onTarget(LivingSetAttackTargetEvent event)
    {
        if(event.getEntityLiving().world.isRemote || !(event.getEntityLiving() instanceof EntityLiving)
            || !(event.getTarget() instanceof EntityPlayer)) return;
        EntityLiving mob = (EntityLiving)event.getEntityLiving();
        EntityPlayer player = (EntityPlayer)event.getTarget();
        // The target task may run several ticks after native revenge was assigned.
        // This also preserves pig-zombie group anger while the player is disguised.
        if(mob.getRevengeTarget() == player && mob.ticksExisted - mob.getRevengeTimer() <= 100)
            remember(mob, player);
        if(shouldIgnore(mob, player)) mob.setAttackTarget(null);
    }

    @SubscribeEvent
    public void onLivingTick(LivingEvent.LivingUpdateEvent event)
    {
        if(event.getEntityLiving().world.isRemote || !(event.getEntityLiving() instanceof EntityLiving)) return;
        EntityLiving mob = (EntityLiving)event.getEntityLiving();
        if(mob.ticksExisted % 10 != 0) return;
        EntityLivingBase target = mob.getAttackTarget();
        Map<UUID, UUID> acquired = acquiredTargets.get(mob.world);
        UUID assigned = acquired == null ? null : acquired.get(mob.getUniqueID());
        if(target instanceof EntityPlayer)
        {
            EntityPlayer player = (EntityPlayer)target;
            if(shouldIgnore(mob, player) || (player.getUniqueID().equals(assigned)
                && !angryAt(mob, player) && (!hunts(mob, player) || !suitable(mob, player)
                    || mob.getDistanceSq(player) > SEARCH_RADIUS * SEARCH_RADIUS)))
                mob.setAttackTarget(null);
        }
        if(acquired != null && (mob.getAttackTarget() == null
            || !mob.getAttackTarget().getUniqueID().equals(assigned))) acquired.remove(mob.getUniqueID());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDamage(LivingDamageEvent event)
    {
        if(event.getEntityLiving().world.isRemote || event.getAmount() <= 0
            || !(event.getSource().getTrueSource() instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer)event.getSource().getTrueSource();
        EntityLivingBase victim = event.getEntityLiving();
        if(victim instanceof EntityLiving) remember((EntityLiving)victim, player);
        if(!activePlayer(player) || victim instanceof EntityPlayer || !(shape(player) instanceof EntityWolf)
            || !victim.isEntityAlive() || victim.isOnSameTeam(player)
            || player.world.getDifficulty() == EnumDifficulty.PEACEFUL) return;
        for(EntityWolf wolf : player.world.getEntitiesWithinAABB(EntityWolf.class,
            player.getEntityBoundingBox().grow(16.0D, 10.0D, 16.0D)))
        {
            if(!wolf.isEntityAlive() || wolf.isSitting() || (wolf.isTamed() && !wolf.isOwner(player))
                || wolf.getAttackTarget() != null || wolf.isOnSameTeam(victim)
                || !wolf.shouldAttackEntity(victim, player)
                || !EntityAITarget.isSuitableTarget(wolf, victim, false, true)) continue;
            wolf.setAttackTarget(victim);
        }
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event)
    {
        if(event.side.isClient() || event.phase != TickEvent.Phase.END || event.world.getTotalWorldTime() % 10 != 0) return;
        World world = event.world;
        if(world.getTotalWorldTime() % 200 == 0) prune(world);
        Map<EntityLiving, EntityPlayer> nearest = new IdentityHashMap<>();
        for(EntityPlayer player : world.playerEntities)
        {
            if(!activePlayer(player) || shape(player) == player) continue;
            for(EntityLiving mob : world.getEntitiesWithinAABB(EntityLiving.class,
                player.getEntityBoundingBox().grow(SEARCH_RADIUS)))
            {
                if(!mob.isEntityAlive() || mob.isAIDisabled() || mob.getAttackTarget() != null
                    || mob.getDistanceSq(player) > SEARCH_RADIUS * SEARCH_RADIUS
                    || !hunts(mob, player) || !suitable(mob, player)) continue;
                EntityPlayer previous = nearest.get(mob);
                if(previous == null || mob.getDistanceSq(player) < mob.getDistanceSq(previous)) nearest.put(mob, player);
            }
        }
        nearest.forEach((mob, player) -> {
            mob.setAttackTarget(player);
            if(mob.getAttackTarget() == player)
                acquiredTargets.computeIfAbsent(world, w -> new HashMap<>()).put(mob.getUniqueID(), player.getUniqueID());
        });
    }

    /** Used by legacy AbilityFear to avoid competing with the installed vanilla avoidance task. */
    public static boolean hasBuiltInFear(EntityLivingBase form, EntityCreature creature)
    {
        return (form instanceof EntityOcelot && creature instanceof EntityCreeper)
            || (form instanceof EntityWolf && (creature instanceof AbstractSkeleton || creature instanceof EntityRabbit))
            || (form instanceof EntityZombie && creature instanceof EntityVillager)
            || (form instanceof AbstractIllager && creature instanceof EntityVillager);
    }

    private boolean fears(EntityCreature mob, EntityPlayer player)
    {
        return activePlayer(player) && !mob.isOnSameTeam(player) && !angryAt(mob, player)
            && hasBuiltInFear(shape(player), mob);
    }

    @SubscribeEvent
    public void onJoin(EntityJoinWorldEvent event)
    {
        if(event.getWorld().isRemote || !(event.getEntity() instanceof EntityCreature)) return;
        EntityCreature mob = (EntityCreature)event.getEntity();
        if(!(mob instanceof EntityCreeper || mob instanceof AbstractSkeleton
            || mob instanceof EntityRabbit || mob instanceof EntityVillager) || !installing.add(mob)) return;
        float distance = mob instanceof EntityVillager ? 12.0F : mob instanceof EntityCreeper ? 6.0F : mob instanceof EntityRabbit ? 8.0F : 6.0F;
        mob.tasks.addTask(2, new EntityAIAvoidEntity<EntityPlayer>(mob, EntityPlayer.class,
            player -> player != null && fears(mob, player), distance, 1.0D, 1.2D)
        {
            private EntityPlayer threat;

            @Override
            public boolean shouldExecute()
            {
                if(!super.shouldExecute()) return false;
                threat = this.closestLivingEntity;
                return true;
            }

            @Override
            public boolean shouldContinueExecuting()
            {
                return threat != null && fears(mob, threat) && super.shouldContinueExecuting();
            }
        });
    }

    private void prune(World world)
    {
        Map<UUID, Map<UUID, Long>> data = grudges.get(world);
        if(data != null)
        {
            long now = world.getTotalWorldTime();
            data.values().forEach(targets -> targets.values().removeIf(until -> until <= now));
            data.values().removeIf(Map::isEmpty);
        }
        Map<UUID, UUID> acquired = acquiredTargets.get(world);
        if(acquired != null)
        {
            Set<UUID> alive = new HashSet<>();
            for(Entity entity : world.loadedEntityList) if(entity.isEntityAlive()) alive.add(entity.getUniqueID());
            acquired.keySet().retainAll(alive);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        UUID player = event.player.getUniqueID();
        for(Map<UUID, UUID> targets : acquiredTargets.values()) targets.values().removeIf(player::equals);
    }

    @SubscribeEvent
    public void onUnload(WorldEvent.Unload event)
    {
        grudges.remove(event.getWorld());
        acquiredTargets.remove(event.getWorld());
    }
}
