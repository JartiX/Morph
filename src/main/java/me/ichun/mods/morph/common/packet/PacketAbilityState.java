package me.ichun.mods.morph.common.packet;

import io.netty.buffer.ByteBuf;
import me.ichun.mods.ichunutil.common.core.network.AbstractPacket;
import me.ichun.mods.morph.client.ability.AbilityClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class PacketAbilityState extends AbstractPacket
{
    private String name = "";
    private int remaining, total;
    public PacketAbilityState() {}
    public PacketAbilityState(String name, int remaining, int total)
    { this.name = name; this.remaining = remaining; this.total = total; }
    @Override public void writeTo(ByteBuf buffer)
    { ByteBufUtils.writeUTF8String(buffer, name); buffer.writeInt(remaining); buffer.writeInt(total); }
    @Override public void readFrom(ByteBuf buffer)
    { name = ByteBufUtils.readUTF8String(buffer); remaining = buffer.readInt(); total = buffer.readInt(); }
    @Override public Side receivingSide() { return Side.CLIENT; }
    @SideOnly(Side.CLIENT)
    @Override public void execute(Side side, EntityPlayer player)
    { if(side == Side.CLIENT) AbilityClient.receive(name, remaining, total); }
}
