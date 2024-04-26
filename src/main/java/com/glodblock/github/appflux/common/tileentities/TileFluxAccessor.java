package com.glodblock.github.appflux.common.tileentities;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.blockentity.grid.AENetworkBlockEntity;
import com.glodblock.github.appflux.common.AFItemAndBlock;
import com.glodblock.github.appflux.common.caps.NetworkFEPower;
import com.glodblock.github.appflux.common.me.energy.EnergyDistributor;
import com.glodblock.github.appflux.common.me.key.FluxKey;
import com.glodblock.github.appflux.common.me.key.type.EnergyType;
import com.glodblock.github.appflux.config.AFConfig;
import com.glodblock.github.appflux.util.AFUtil;
import com.glodblock.github.glodium.util.GlodUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;

public class TileFluxAccessor extends AENetworkBlockEntity implements IGridTickable {

    public TileFluxAccessor(BlockPos pos, BlockState blockState) {
        super(GlodUtil.getTileType(TileFluxAccessor.class, TileFluxAccessor::new, AFItemAndBlock.FLUX_ACCESSOR), pos, blockState);
        this.getMainNode().setFlags(GridFlags.REQUIRE_CHANNEL);
        this.getMainNode().setIdlePowerUsage(1.0).addService(IGridTickable.class, this);
    }

    public IEnergyStorage getEnergyStorage() {
        if (this.getStorage() != null) {
            return new NetworkFEPower(this.getStorage(), this.getSource());
        } else {
            return new EnergyStorage(0);
        }
    }

    private IStorageService getStorage() {
        if (this.getGridNode() != null) {
            return this.getGridNode().getGrid().getStorageService();
        }
        return null;
    }

    private IActionSource getSource() {
        return IActionSource.ofMachine(this);
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 1, false);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        var storage = this.getStorage();
        var gird = AFUtil.getGrid(this, null);
        if (storage != null && this.level != null) {
            for (var d : Direction.values()) {
                var te = this.level.getBlockEntity(this.worldPosition.offset(d.getNormal()));
                var thatGrid = AFUtil.getGrid(te, d.getOpposite());
                if (te != null && thatGrid != gird && !AFUtil.isBlackListTE(te, d.getOpposite())) {
                    var accepter = AFUtil.findCapability(te, Capabilities.EnergyStorage.BLOCK, d.getOpposite());
                    if (accepter != null) {
                        var toAdd = accepter.receiveEnergy(AFUtil.clampLong(AFConfig.getFluxAccessorIO()), true);
                        if (toAdd > 0) {
                            var drained = storage.getInventory().extract(FluxKey.of(EnergyType.FE), toAdd, Actionable.MODULATE, this.getSource());
                            if (drained > 0) {
                                var actuallyDrained = accepter.receiveEnergy((int) drained, false);
                                var differ = drained - actuallyDrained;
                                if (differ > 0) {
                                    storage.getInventory().insert(FluxKey.of(EnergyType.FE), differ, Actionable.MODULATE, this.getSource());
                                }
                            }
                        }
                    }
                }
            }
            if (AFConfig.selfCharge() && gird != null) {
                EnergyDistributor.chargeNetwork(gird.getService(IEnergyService.class), storage, this.getSource());
            }
        }
        return TickRateModulation.SAME;
    }

}