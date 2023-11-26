package com.github.glodblock.extendedae.common.me.wireless;

import appeng.api.features.Locatables;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.me.service.helpers.ConnectionWrapper;
import com.github.glodblock.extendedae.EAE;
import com.github.glodblock.extendedae.common.tileentities.TileWirelessConnector;
import com.github.glodblock.extendedae.config.EPPConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class WirelessConnect implements IActionHost {

    private static final Set<WirelessConnect> ACTIVE_CONNECTOR = new HashSet<>();
    private static final Locatables.Type<IActionHost> CONNECTORS = new Locatables.Type<>();
    private boolean isDestroyed = false;
    private boolean registered;
    private ConnectionWrapper connection;
    private long thisSide;
    private long otherSide;
    private boolean shutdown;
    private double dis;
    private TileWirelessConnector host;

    static {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ACTIVE_CONNECTOR.clear());
        ServerWorldEvents.UNLOAD.register(
                (server, world) -> ACTIVE_CONNECTOR.forEach(o -> o.onUnload(world))
        );
    }

    public WirelessConnect(TileWirelessConnector connector) {
        this.host = connector;
        this.registered = true;
        ACTIVE_CONNECTOR.add(this);
    }

    public void onUnload(final ServerLevel e) {
        if (this.host != null && this.host.getLevel() == e.getLevel()) {
            this.destroy();
        }
    }

    public void active() {
        ACTIVE_CONNECTOR.add(this);
        this.registered = true;
    }

    public void updateStatus() {
        final long f = this.host.getFrequency();
        if (this.thisSide != f && this.thisSide != -f) {
            if (f != 0) {
                if (this.thisSide != 0) {
                    CONNECTORS.unregister(host.getLevel(), this.thisSide);
                }
                if (this.canUseNode(-f)) {
                    this.otherSide = f;
                    this.thisSide = -f;
                } else if (this.canUseNode(f)) {
                    this.thisSide = f;
                    this.otherSide = -f;
                }
                CONNECTORS.register(host.getLevel(), getLocatableKey(), this);
            } else {
                CONNECTORS.unregister(host.getLevel(), getLocatableKey());
                this.otherSide = 0;
                this.thisSide = 0;
            }
        }

        var myOtherSide = this.otherSide == 0 ? null : CONNECTORS.get(host.getLevel(), this.otherSide);

        this.shutdown = false;
        this.dis = 0;

        if (myOtherSide instanceof WirelessConnect sideB) {
            var sideA = this;
            this.dis = Math.sqrt(sideA.host.getBlockPos().distSqr(sideB.host.getBlockPos()));
            if (sideA.isActive() && sideB.isActive()
                    && this.dis <= EPPConfig.INSTANCE.wirelessConnectorMaxRange
                    && (sideA.host.getLevel() == sideB.host.getLevel())) {
                if (this.connection != null && this.connection.getConnection() != null) {
                    final IGridNode a = this.connection.getConnection().a();
                    final IGridNode b = this.connection.getConnection().b();
                    final IGridNode sa = sideA.getNode();
                    final IGridNode sb = sideB.getNode();
                    if ((a == sa || b == sa) && (a == sb || b == sb)) {
                        return;
                    }
                }

                try {
                    if (sideA.connection != null && sideA.connection.getConnection() != null) {
                        sideA.connection.getConnection().destroy();
                        sideA.connection = new ConnectionWrapper(null);
                    }
                    if (sideB.connection != null && sideB.connection.getConnection() != null) {
                        sideB.connection.getConnection().destroy();
                        sideB.connection = new ConnectionWrapper(null);
                    }
                    if (sideA.getNode() != null && sideB.getNode() != null) {
                        sideA.connection = sideB.connection = new ConnectionWrapper(GridHelper.createConnection(sideA.getNode(), sideB.getNode()));
                    }
                } catch (IllegalStateException e) {
                    EAE.LOGGER.debug(e.getMessage());
                }
            } else {
                this.shutdown = true;
            }
        } else {
            this.shutdown = true;
        }
        if (this.shutdown && this.connection != null && this.connection.getConnection() != null) {
            this.connection.getConnection().destroy();
            this.connection.setConnection(null);
            this.connection = new ConnectionWrapper(null);
        }
    }

    public double getDistance() {
        return this.dis;
    }

    public boolean isConnected() {
        return !this.shutdown;
    }

    @SuppressWarnings("deprecation")
    private boolean canUseNode(long qe) {
        var locatable = CONNECTORS.get(host.getLevel(), qe);
        if (locatable instanceof WirelessConnect qc) {
            var world = qc.host.getLevel();
            if (!qc.isDestroyed && world != null) {
                if (world.hasChunkAt(qc.host.getBlockPos())) {
                    final var cur = Objects.requireNonNull(world.getServer()).getLevel(world.dimension());
                    final var te = world.getBlockEntity(qc.host.getBlockPos());
                    return te != qc.host || world != cur;
                } else {
                    EAE.LOGGER.warn(String.format("Found a registered Wireless Connector with serial %s whose chunk seems to be unloaded: %s", qe, qc));
                }
            }
        }
        return true;
    }

    @Nullable
    public BlockPos getOtherSide() {
        if (this.otherSide == 0) {
            return null;
        }
        var o = CONNECTORS.get(this.host.getLevel(), this.otherSide);
        return o instanceof WirelessConnect c ? c.host.getBlockPos() : null;
    }

    private boolean isActive() {
        if (this.isDestroyed || !this.registered) {
            return false;
        }
        return this.hasFreq();
    }

    private IGridNode getNode() {
        return this.host.getGridNode();
    }

    private boolean hasFreq() {
        return this.thisSide != 0;
    }

    public void destroy() {
        if (this.isDestroyed) {
            return;
        }
        this.isDestroyed = true;
        try {
            if (this.registered) {
                ACTIVE_CONNECTOR.remove(this);
                this.registered = false;
            }

            if (this.thisSide != 0) {
                this.updateStatus();
                CONNECTORS.unregister(host.getLevel(), getLocatableKey());
            }
            this.host = null;
        } catch (Exception ignore) {
        }
    }

    private long getLocatableKey() {
        return this.thisSide;
    }

    @Override
    public IGridNode getActionableNode() {
        return host.getMainNode().getNode();
    }

}
