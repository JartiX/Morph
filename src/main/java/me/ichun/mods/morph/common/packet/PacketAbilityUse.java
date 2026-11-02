package me.ichun.mods.morph.common.packet;

import io.netty.buffer.ByteBuf;
import me.ichun.mods.ichunutil.common.core.network.AbstractPacket;
import me.ichun.mods.morph.common.ability.ActiveAbilityHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;

/** No client supplied form, target, damage, time or cooldown. */
public class PacketAbilityUse extends AbstractPacket
{
    public PacketAbilityUse() {}
    @Override public void writeTo(ByteBuf buffer) {}
    @Override public void readFrom(ByteBuf buffer) {}
    @Override public Side receivingSide() { return Side.SERVER; }
    @Override public void execute(Side side, EntityPlayer player)
    {
        if(side == Side.SERVER && player.getServer() != null)
            player.getServer().addScheduledTask(() -> ActiveAbilityHandler.use(player));
    }
}
