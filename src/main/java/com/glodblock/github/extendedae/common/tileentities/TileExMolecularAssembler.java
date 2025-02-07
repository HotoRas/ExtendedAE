package com.glodblock.github.extendedae.common.tileentities;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.IPowerChannelState;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.ISegmentedInventory;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.util.AECableType;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.blockentity.grid.AENetworkedInvBlockEntity;
import appeng.client.render.crafting.AssemblerAnimationStatus;
import appeng.core.definitions.AEItems;
import appeng.core.localization.GuiText;
import appeng.core.localization.Tooltips;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.CombinedInternalInventory;
import com.glodblock.github.extendedae.common.EAESingletons;
import com.glodblock.github.extendedae.common.me.CraftingThread;
import com.glodblock.github.extendedae.network.EAENetworkHandler;
import com.glodblock.github.extendedae.network.packet.SAssemblerAnimation;
import com.glodblock.github.glodium.util.GlodUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static appeng.blockentity.crafting.MolecularAssemblerBlockEntity.INV_MAIN;

public class TileExMolecularAssembler extends AENetworkedInvBlockEntity implements IUpgradeableObject, IGridTickable, ICraftingMachine, IPowerChannelState {

    public static final int MAX_THREAD = 8;
    private final IUpgradeInventory upgrades;
    private boolean isPowered = false;
    private final CraftingThread[] threads = new CraftingThread[MAX_THREAD];
    private final InternalInventory internalInv;
    private final InternalInventory gridInvExt;
    @OnlyIn(Dist.CLIENT)
    private AssemblerAnimationStatus animationStatus;

    public TileExMolecularAssembler(BlockPos pos, BlockState blockState) {
        super(GlodUtil.getTileType(TileExMolecularAssembler.class, TileExMolecularAssembler::new, EAESingletons.EX_ASSEMBLER), pos, blockState);
        this.getMainNode().setIdlePowerUsage(0.0).addService(IGridTickable.class, this);
        this.upgrades = UpgradeInventories.forMachine(EAESingletons.EX_ASSEMBLER, this.getUpgradeSlots(), this::saveChanges);
        var invs = new ArrayList<InternalInventory>();
        var invs2 = new ArrayList<InternalInventory>();
        for (int x = 0; x < MAX_THREAD; x ++) {
            this.threads[x] = new CraftingThread(this);
            invs.add(this.threads[x].getInternalInventory());
            invs2.add(this.threads[x].getExposedInventoryForSide());
        }
        this.internalInv = new CombinedInternalInventory(invs.toArray(new InternalInventory[0]));
        this.gridInvExt = new CombinedInternalInventory(invs2.toArray(new InternalInventory[0]));
    }

    public int getUpgradeSlots() {
        return 5;
    }

    @Override
    public boolean isPowered() {
        return this.isPowered;
    }

    @Override
    public boolean isActive() {
        return this.isPowered;
    }

    @Override
    public PatternContainerGroup getCraftingMachineInfo() {
        Component name;
        if (this.hasCustomName()) {
            name = this.getCustomName();
        } else {
            name = EAESingletons.EX_ASSEMBLER.asItem().getDescription();
        }
        var icon = AEItemKey.of(EAESingletons.EX_ASSEMBLER);

        List<Component> tooltip;
        var accelerationCards = this.getInstalledUpgrades(AEItems.SPEED_CARD);
        if (accelerationCards == 0) {
            tooltip = List.of();
        } else {
            tooltip = List.of(
                    GuiText.CompatibleUpgrade.text(
                            Tooltips.of(AEItems.SPEED_CARD.asItem().getDescription()),
                            Tooltips.ofUnformattedNumber(accelerationCards)));
        }
        return new PatternContainerGroup(icon, name, tooltip);
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputs, Direction ejectionDirection) {
        for (var thread : this.threads) {
            if (thread.acceptJob(patternDetails, inputs, ejectionDirection)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean acceptsPlans() {
        return true;
    }

    @Override
    protected boolean readFromStream(RegistryFriendlyByteBuf data) {
        final boolean c = super.readFromStream(data);
        final boolean oldPower = this.isPowered;
        this.isPowered = data.readBoolean();
        return this.isPowered != oldPower || c;
    }

    @Override
    protected void writeToStream(RegistryFriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeBoolean(this.isPowered);
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        for (int x = 0; x < MAX_THREAD; x ++) {
            var tag = this.threads[x].writeNBT(registries);
            data.put("#ct" + x, tag);
        }
        this.upgrades.writeToNBT(data, "upgrades", registries);
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        this.upgrades.readFromNBT(data, "upgrades", registries);
        for (int x = 0; x < MAX_THREAD; x ++) {
            if (data.contains("#ct" + x)) {
                this.threads[x].readNBT(data.getCompound("#ct" + x), registries);
            }
        }
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.COVERED;
    }

    @Override
    public InternalInventory getSubInventory(ResourceLocation id) {
        if (id.equals(ISegmentedInventory.UPGRADES)) {
            return this.upgrades;
        } else if (id.equals(INV_MAIN)) {
            return this.internalInv;
        }
        return super.getSubInventory(id);
    }

    public InternalInventory getCraftInventory(int index) {
        return this.threads[index].getInternalInventory();
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        var isAwake = false;
        for (var t : this.threads) {
            t.recalculatePlan();
            t.updateSleepiness();
            isAwake |= t.isAwake();
        }
        if (isAwake) {
            for (var t : this.threads) {
                t.forceAwake();
            }
        }
        return new TickingRequest(1, 1, !isAwake);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        var rate = TickRateModulation.SLEEP;
        var firstJob = ItemStack.EMPTY;
        int cards = this.upgrades.getInstalledUpgrades(AEItems.SPEED_CARD);
        for (var t : this.threads) {
            if (t.isAwake()) {
                var tr = t.tick(cards, ticksSinceLastCall);
                if (tr.ordinal() > rate.ordinal()) {
                    rate = tr;
                }
                if (firstJob.isEmpty()) {
                    firstJob = t.getOutput();
                }
            }
        }
        var item = AEItemKey.of(firstJob);
        if (item != null && this.level instanceof ServerLevel) {
            EAENetworkHandler.INSTANCE.sendToAllAround(new SAssemblerAnimation(this.worldPosition, (byte) 50, item), (ServerLevel) this.level, this.worldPosition, 32, null);
        }
        return rate;
    }

    @Override
    public InternalInventory getInternalInventory() {
        return this.internalInv;
    }

    @Override
    protected InternalInventory getExposedInventoryForSide(Direction side) {
        return this.gridInvExt;
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        for (var t : this.threads) {
            if (inv == t.getInternalInventory()) {
                t.recalculatePlan();
                break;
            }
        }
    }

    public int getCraftingProgress(int index) {
        return this.threads[index].getCraftingProgress();
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        for (var upgrade : upgrades) {
            drops.add(upgrade);
        }
    }

    @Override
    public void clearContent() {
        super.clearContent();
        upgrades.clear();
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        if (reason != IGridNodeListener.State.GRID_BOOT) {
            boolean newState = false;
            var grid = getMainNode().getGrid();
            if (grid != null) {
                newState = this.getMainNode().isPowered() && grid.getEnergyService().extractAEPower(1, Actionable.SIMULATE, PowerMultiplier.CONFIG) > 0.0001;
            }
            if (newState != this.isPowered) {
                this.isPowered = newState;
                this.markForUpdate();
            }
        }
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return this.upgrades;
    }

    public IMolecularAssemblerSupportedPattern getCurrentPattern(int index) {
        return this.threads[index].getCurrentPattern();
    }

    @OnlyIn(Dist.CLIENT)
    public void setAnimationStatus(@Nullable AssemblerAnimationStatus status) {
        this.animationStatus = status;
    }

    @OnlyIn(Dist.CLIENT)
    @Nullable
    public AssemblerAnimationStatus getAnimationStatus() {
        return this.animationStatus;
    }

}
