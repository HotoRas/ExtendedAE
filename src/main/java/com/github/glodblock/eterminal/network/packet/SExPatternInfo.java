package com.github.glodblock.eterminal.network.packet;

import com.github.glodblock.eterminal.client.gui.GuiExPatternTerminal;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public class SExPatternInfo implements IMessage<SExPatternInfo> {

    private long id;
    private BlockPos pos;
    private ResourceKey<Level> dim;

    public SExPatternInfo() {
        // NO-OP
    }

    public SExPatternInfo(long id, BlockPos pos, ResourceKey<Level> dim) {
        this.id = id;
        this.pos = pos;
        this.dim = dim;
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeVarLong(this.id);
        buf.writeVarLong(this.pos.asLong());
        buf.writeResourceKey(this.dim);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        this.id = buf.readVarLong();
        this.pos = BlockPos.of(buf.readVarLong());
        this.dim = buf.readResourceKey(Registry.DIMENSION_REGISTRY);
    }

    @Override
    public void onMessage(Player player) {
        if (Minecraft.getInstance().screen instanceof GuiExPatternTerminal gui) {
            gui.postTileInfo(this.id, this.pos, this.dim);
        }
    }

    @Override
    public Class<SExPatternInfo> getPacketClass() {
        return SExPatternInfo.class;
    }

    @Override
    public boolean isClient() {
        return true;
    }
}
