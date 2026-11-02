package com.bettercontent.electrochemistry.mixin;

import com.bettercontent.electrochemistry.basin.BasinBatchPort;
import com.bettercontent.electrochemistry.basin.BasinFluidRecovery;
import com.bettercontent.electrochemistry.power.BasinPowerPort;
import com.bettercontent.electrochemistry.recipe.BasinBatchRecipe;
import com.bettercontent.electrochemistry.recipe.ElectrochemistryRecipes;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.item.SmartInventory;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fluids.FluidStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** V1 one-item fixture and separately typed V2 two-item + physically reserved bath.
 * PowerGrid supplies ALL work; no internal energy store or gas byproduct exists.
 */
@Mixin(value = BasinBlockEntity.class, remap = false)
public abstract class BasinBatchStateMixin extends SmartBlockEntity implements BasinBatchPort {
    @Unique private static final String ELECTROCHEMISTRY_BATCH_KEY = "better_electrochemistry:Batch";
    @Unique private static final int ELECTROCHEMISTRY_SETTLE_TICKS = 40;
    @Unique private static final Logger ELECTROCHEMISTRY_LOG = LoggerFactory.getLogger("better_electrochemistry");
    // Preserve unreadable escrow NBT verbatim; never silently discard potentially reserved items.
    @Unique private CompoundTag electrochemistry$quarantinedBatch;
    @Unique private ItemStack electrochemistry$escrow = ItemStack.EMPTY;
    @Unique private boolean electrochemistry$compound;
    @Unique private boolean electrochemistry$releasing;
    @Unique private ItemStack electrochemistry$anodeEscrow = ItemStack.EMPTY;
    @Unique private ItemStack electrochemistry$seedEscrow = ItemStack.EMPTY;
    @Unique private FluidStack electrochemistry$bathEscrow = FluidStack.EMPTY;
    // Actual amount already returned to native input. Held + credited stays 250mB.
    @Unique private int electrochemistry$bathCreditedMb;
    @Unique private ResourceLocation electrochemistry$recipeId;
    @Unique private String electrochemistry$fingerprint = "";
    @Unique private double electrochemistry$workJoules;
    @Unique private int electrochemistry$emptySampleTicks;
    @Unique private boolean electrochemistry$probePending;
    @Unique private boolean electrochemistry$probeInFlight;
    @Unique private float electrochemistry$failedLoadedVoltage;
    @Unique private float electrochemistry$probeOpenVoltage;
    @Unique private boolean electrochemistry$hadBothCables;
    @Unique private double electrochemistry$retryAboveVoltage;
    @Unique private boolean electrochemistry$needsScan = true;
    @Unique private List<BasinBatchRecipe> electrochemistry$cachedCandidates = List.of();
    @Unique private long electrochemistry$lastScanTick = Long.MIN_VALUE;

    protected BasinBatchStateMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Unique private BasinBlockEntity electrochemistry$basin() {
        return (BasinBlockEntity) (Object) this;
    }

    @Override public boolean electrochemistry$hasBatch() {
        return electrochemistry$compound || !electrochemistry$escrow.isEmpty();
    }
    @Override public double electrochemistry$completedWorkJoules() { return electrochemistry$workJoules; }

    @Override public boolean electrochemistry$ownsProcessing() {
        return electrochemistry$quarantinedBatch != null || electrochemistry$hasBatch()
                || electrochemistry$selectCandidate() != null;
    }

    /** Cache matching inputs only; output, voltage and current recipe identity remain live checks. */
    @Unique private BasinBatchRecipe electrochemistry$selectCandidate() {
        if (level == null || level.isClientSide || electrochemistry$operatorRunning()) return null;
        // Inventory mutation forces immediate arbitration. The periodic scan also notices
        // datapack reloads without an idle per-tick walk over every loaded recipe.
        if (electrochemistry$needsScan || level.getGameTime() - electrochemistry$lastScanTick >= 20) {
            electrochemistry$cachedCandidates = electrochemistry$findCandidates();
            electrochemistry$needsScan = false;
            electrochemistry$lastScanTick = level.getGameTime();
        }
        if (electrochemistry$cachedCandidates.isEmpty()) return null;
        double voltage = ((BasinPowerPort) (Object) this).electrochemistry$availableVoltage();
        for (BasinBatchRecipe candidate : electrochemistry$cachedCandidates) {
            var currentlyLoaded = level.getRecipeManager().byKey(candidate.getId()).orElse(null);
            if (!(currentlyLoaded instanceof BasinBatchRecipe live)
                    || live.getType() != ElectrochemistryRecipes.BASIN_BATCH.get()
                    || !live.fingerprint().equals(candidate.fingerprint())) {
                electrochemistry$needsScan = true;
                continue;
            }
            if (electrochemistry$inputMatches(live) && electrochemistry$canFit(live.output())
                    && (!live.isElectroforming() || electrochemistry$compoundReady(live))
                    && voltage >= live.minVoltage()) return live;
        }
        return null;
    }

    /** A running ordinary Mixer/Press owns its complete cycle, including retract animation. */
    @Unique private boolean electrochemistry$operatorRunning() {
        if (level == null) return false;
        var operator = level.getBlockEntity(worldPosition.above(2));
        if (operator instanceof MechanicalMixerBlockEntity mixer) return mixer.running;
        if (operator instanceof MechanicalPressBlockEntity press)
            return press.getPressingBehaviour() != null && press.getPressingBehaviour().running;
        return false;
    }

    @Unique private List<BasinBatchRecipe> electrochemistry$findCandidates() {
        if (level == null || !ElectrochemistryRecipes.BASIN_BATCH.isPresent()) return List.of();
        return level.getRecipeManager().getAllRecipesFor(ElectrochemistryRecipes.BASIN_BATCH.get()).stream()
                .filter(this::electrochemistry$inputMatches)
                .sorted(Comparator.comparing(recipe -> recipe.getId().toString())).toList();
    }

    @Unique private boolean electrochemistry$inputMatches(BasinBatchRecipe recipe) {
        var basin = electrochemistry$basin();
        if (basin.inputTank == null) return false;
        if (recipe.isElectroforming() && !BasinFluidRecovery.exactBath(basin, recipe)) return false;
        if (!recipe.isElectroforming() && !basin.inputTank.isEmpty()) return false;
        ItemStack only = ItemStack.EMPTY;
        int anode = -1, seed = -1;
        var inventory = basin.getInputInventory();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            if (stack.hasTag() || stack.isDamageableItem()) return false;
            if (recipe.isElectroforming()) {
                if (stack.is(recipe.anodeItem()) && anode < 0 && stack.getCount() >= recipe.anodeCount())
                    anode = i;
                else if (stack.is(recipe.cathodeSeedItem()) && seed < 0
                        && stack.getCount() >= recipe.cathodeSeedCount()) seed = i;
                else return false; // No third item or unreviewed catalyst in a compound transaction.
            } else {
                if (!only.isEmpty()) return false;
                only = stack;
            }
        }
        return recipe.isElectroforming() ? anode >= 0 && seed >= 0 : recipe.matchesInput(only);
    }

    @Unique private boolean electrochemistry$compoundReady(BasinBatchRecipe recipe) {
        if (!BasinBatchRecipe.electroformingRuntimeEnabled()) return false;
        var basin = electrochemistry$basin();
        return BasinFluidRecovery.canReserve(basin, recipe)
                && !BasinFluidRecovery.filledCanister(
                        new FluidStack(recipe.bathFluid(), recipe.bathAmountMb())).isEmpty();
    }

    @Unique private BasinBatchRecipe electrochemistry$loadedRecipe() {
        if (level == null || electrochemistry$recipeId == null) return null;
        RecipeManager manager = level.getRecipeManager();
        var recipe = manager.byKey(electrochemistry$recipeId).orElse(null);
        if (!(recipe instanceof BasinBatchRecipe batch)
                || batch.getType() != ElectrochemistryRecipes.BASIN_BATCH.get()
                || !batch.fingerprint().equals(electrochemistry$fingerprint)) return null;
        if (batch.isElectroforming()) {
            int held = electrochemistry$bathEscrow.isEmpty() ? 0 : electrochemistry$bathEscrow.getAmount();
            if (!electrochemistry$compound || !electrochemistry$escrow.isEmpty()
                    || !electrochemistry$validCompoundItems(batch)
                    || electrochemistry$bathCreditedMb < 0
                    || held + electrochemistry$bathCreditedMb != batch.bathAmountMb()
                    || (!electrochemistry$bathEscrow.isEmpty()
                        && (electrochemistry$bathEscrow.getFluid() != batch.bathFluid()
                            || electrochemistry$bathEscrow.hasTag()))) return null;
            return batch;
        }
        if (electrochemistry$compound || electrochemistry$escrow.isEmpty()
                || !electrochemistry$escrow.is(batch.inputItem())
                || electrochemistry$escrow.getCount() != batch.inputCount()
                || electrochemistry$escrow.hasTag() || electrochemistry$escrow.isDamageableItem()) {
            ELECTROCHEMISTRY_LOG.error("Mismatched v1 Basin escrow at {} for recipe {}; refunding actual stack",
                    worldPosition, batch.getId());
            return null;
        }
        return batch;
    }

    @Unique private boolean electrochemistry$validCompoundItems(BasinBatchRecipe recipe) {
        return !electrochemistry$anodeEscrow.isEmpty() && !electrochemistry$seedEscrow.isEmpty()
                && electrochemistry$anodeEscrow.is(recipe.anodeItem())
                && electrochemistry$anodeEscrow.getCount() == recipe.anodeCount()
                && electrochemistry$seedEscrow.is(recipe.cathodeSeedItem())
                && electrochemistry$seedEscrow.getCount() == recipe.cathodeSeedCount()
                && !electrochemistry$anodeEscrow.hasTag() && !electrochemistry$seedEscrow.hasTag()
                && !electrochemistry$anodeEscrow.isDamageableItem()
                && !electrochemistry$seedEscrow.isDamageableItem();
    }

    @Unique private boolean electrochemistry$canFit(ItemStack result) {
        var output = electrochemistry$basin().getOutputInventory();
        if (output == null || result.isEmpty()) return false;
        for (int i = 0; i < output.getSlots(); i++) {
            var existing = output.getStackInSlot(i);
            int capacity = Math.min(output.getSlotLimit(i), result.getMaxStackSize());
            if (existing.isEmpty() && capacity >= result.getCount()) return true;
            if (ItemStack.isSameItemSameTags(existing, result)
                    && capacity - existing.getCount() >= result.getCount()) return true;
        }
        return false;
    }

    @Unique private boolean electrochemistry$insertOutput(ItemStack result) {
        var output = electrochemistry$basin().getOutputInventory();
        if (!electrochemistry$canFit(result)) return false;
        for (int i = 0; i < output.getSlots(); i++) {
            var existing = output.getStackInSlot(i);
            int capacity = Math.min(output.getSlotLimit(i), result.getMaxStackSize());
            if (existing.isEmpty() && capacity >= result.getCount()) {
                output.setStackInSlot(i, result.copy());
                return true;
            }
            if (ItemStack.isSameItemSameTags(existing, result)
                    && capacity - existing.getCount() >= result.getCount()) {
                var merged = existing.copy();
                merged.grow(result.getCount());
                output.setStackInSlot(i, merged);
                return true;
            }
        }
        return false;
    }

    @Unique private boolean electrochemistry$reserve(BasinBatchRecipe recipe) {
        if (level == null || level.isClientSide || electrochemistry$quarantinedBatch != null
                || electrochemistry$hasBatch() || electrochemistry$operatorRunning()
                || !ElectrochemistryRecipes.BASIN_BATCH.isPresent()) return false;
        var loaded = level.getRecipeManager().byKey(recipe.getId()).orElse(null);
        if (!(loaded instanceof BasinBatchRecipe live)
                || live.getType() != ElectrochemistryRecipes.BASIN_BATCH.get()
                || !live.fingerprint().equals(recipe.fingerprint())
                || !electrochemistry$inputMatches(live) || !electrochemistry$canFit(live.output())
                || ((BasinPowerPort) (Object) this).electrochemistry$availableVoltage() < live.minVoltage())
            return false;
        if (live.isElectroforming()) return electrochemistry$reserveCompound(live);
        SmartInventory inventory = electrochemistry$basin().getInputInventory();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!recipe.matchesInput(stack)) continue;
            ItemStack reserved = stack.copy();
            reserved.setCount(recipe.inputCount());
            ItemStack remaining = stack.copy();
            remaining.shrink(recipe.inputCount());
            inventory.setStackInSlot(i, remaining);
            electrochemistry$escrow = reserved;
            electrochemistry$recipeId = recipe.getId();
            electrochemistry$fingerprint = recipe.fingerprint();
            electrochemistry$workJoules = 0;
            setChanged();
            return true;
        }
        return false;
    }

    /** Persist actual extracted assets; an incomplete EXECUTE is rolled back. */
    @Unique private boolean electrochemistry$reserveCompound(BasinBatchRecipe recipe) {
        if (!BasinBatchRecipe.electroformingRuntimeEnabled() || !electrochemistry$compoundReady(recipe))
            return false;
        SmartInventory inv = electrochemistry$basin().getInputInventory();
        int anodeSlot = -1, seedSlot = -1;
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i).is(recipe.anodeItem())) anodeSlot = i;
            if (inv.getStackInSlot(i).is(recipe.cathodeSeedItem())) seedSlot = i;
        }
        if (anodeSlot < 0 || seedSlot < 0 || anodeSlot == seedSlot) return false;
        ItemStack anodePreview = inv.extractItem(anodeSlot, recipe.anodeCount(), true);
        ItemStack seedPreview = inv.extractItem(seedSlot, recipe.cathodeSeedCount(), true);
        if (!anodePreview.is(recipe.anodeItem()) || anodePreview.getCount() != recipe.anodeCount()
                || !seedPreview.is(recipe.cathodeSeedItem()) || seedPreview.getCount() != recipe.cathodeSeedCount()
                || !BasinFluidRecovery.canReserve(electrochemistry$basin(), recipe)) return false;
        electrochemistry$compound = true; // claim ordinary arbitration before inventory callbacks
        electrochemistry$recipeId = recipe.getId();
        electrochemistry$fingerprint = recipe.fingerprint();
        electrochemistry$workJoules = 0;
        setChanged();
        electrochemistry$anodeEscrow = inv.extractItem(anodeSlot, recipe.anodeCount(), false);
        setChanged();
        if (!electrochemistry$anodeEscrow.is(recipe.anodeItem())
                || electrochemistry$anodeEscrow.getCount() != recipe.anodeCount()) {
            electrochemistry$refundCompound(false);
            return false;
        }
        electrochemistry$seedEscrow = inv.extractItem(seedSlot, recipe.cathodeSeedCount(), false);
        setChanged();
        if (!electrochemistry$seedEscrow.is(recipe.cathodeSeedItem())
                || electrochemistry$seedEscrow.getCount() != recipe.cathodeSeedCount()) {
            electrochemistry$refundCompound(false);
            return false;
        }
        electrochemistry$bathEscrow = BasinFluidRecovery.reserve(electrochemistry$basin(), recipe);
        setChanged();
        if (electrochemistry$bathEscrow.getFluid() != recipe.bathFluid()
                || electrochemistry$bathEscrow.hasTag()
                || electrochemistry$bathEscrow.getAmount() != recipe.bathAmountMb()) {
            ELECTROCHEMISTRY_LOG.error("Partial native bath reservation at {}; refunding actual extracted assets",
                    worldPosition);
            electrochemistry$refundCompound(false);
            return false;
        }
        return true;
    }

    /** Native fills are credited by ACTUAL amount. No item output before full return. */
    @Unique private boolean electrochemistry$returnBathAndOutput(BasinBatchRecipe recipe) {
        var basin = electrochemistry$basin();
        if (!electrochemistry$canFit(recipe.output())) return false;
        if (!electrochemistry$bathEscrow.isEmpty()) {
            boolean space = electrochemistry$bathCreditedMb == 0
                    ? BasinFluidRecovery.canReturn(basin, electrochemistry$bathEscrow)
                    : BasinFluidRecovery.canRestore(basin, electrochemistry$bathEscrow,
                            recipe.bathAmountMb());
            if (!space) return false;
            int filled = BasinFluidRecovery.returnToInput(basin, electrochemistry$bathEscrow);
            if (filled < 0 || filled > electrochemistry$bathEscrow.getAmount()) {
                ELECTROCHEMISTRY_LOG.error("Invalid bath return amount {} at {}; quarantining compound escrow",
                        filled, worldPosition);
                electrochemistry$quarantineCompound();
                return false;
            }
            if (filled == 0) return false;
            var remaining = electrochemistry$bathEscrow.copy();
            remaining.setAmount(remaining.getAmount() - filled);
            electrochemistry$bathEscrow = remaining.isEmpty() ? FluidStack.EMPTY : remaining;
            electrochemistry$bathCreditedMb += filled;
            setChanged();
        }
        if (electrochemistry$bathCreditedMb != recipe.bathAmountMb()
                || !BasinFluidRecovery.exactBath(basin, recipe)) return false;
        if (!electrochemistry$insertOutput(recipe.output())) return false;
        electrochemistry$clear();
        return true;
    }

    @Unique private void electrochemistry$quarantineCompound() {
        var raw = new CompoundTag();
        electrochemistry$writeCompoundFields(raw);
        electrochemistry$quarantinedBatch = raw;
        ((BasinPowerPort) (Object) this).electrochemistry$setLoadEnabled(false);
        setChanged();
    }

    @Unique private void electrochemistry$clear() {
        electrochemistry$escrow = ItemStack.EMPTY;
        electrochemistry$compound = false;
        electrochemistry$releasing = false;
        electrochemistry$anodeEscrow = ItemStack.EMPTY;
        electrochemistry$seedEscrow = ItemStack.EMPTY;
        electrochemistry$bathEscrow = FluidStack.EMPTY;
        electrochemistry$bathCreditedMb = 0;
        electrochemistry$recipeId = null;
        electrochemistry$fingerprint = "";
        electrochemistry$workJoules = 0;
        electrochemistry$emptySampleTicks = 0;
        electrochemistry$probePending = false;
        electrochemistry$probeInFlight = false;
        electrochemistry$failedLoadedVoltage = 0;
        electrochemistry$probeOpenVoltage = 0;
        electrochemistry$hadBothCables = false;
        electrochemistry$retryAboveVoltage = 0;
        electrochemistry$needsScan = true;
        electrochemistry$cachedCandidates = List.of();
        setChanged();
        // This sets a Basin dirty flag only. Its next ordinary tick schedules the
        // operator's deferral check; no synchronous update or recursive reservation.
        if (level != null && !level.isClientSide) electrochemistry$basin().notifyChangeOfContents();
    }

    @Unique private void electrochemistry$refund(boolean breaking) {
        if (electrochemistry$compound) {
            if (breaking) electrochemistry$releaseCompoundOnBreak();
            else electrochemistry$refundCompound(false);
            return;
        }
        if (electrochemistry$escrow.isEmpty()) return;
        ItemStack remainder = electrochemistry$escrow.copy();
        electrochemistry$clear(); // V1 clear-first: no second refund during physical destroy.
        if (!breaking) {
            SmartInventory inventory = electrochemistry$basin().getInputInventory();
            for (int i = 0; i < inventory.getSlots() && !remainder.isEmpty(); i++) {
                ItemStack current = inventory.getStackInSlot(i);
                int capacity = Math.min(inventory.getSlotLimit(i), remainder.getMaxStackSize());
                if (current.isEmpty() && capacity > 0) {
                    int moved = Math.min(capacity, remainder.getCount());
                    var inserted = remainder.copy(); inserted.setCount(moved);
                    inventory.setStackInSlot(i, inserted);
                    remainder.shrink(moved);
                } else if (ItemStack.isSameItemSameTags(current, remainder) && capacity > current.getCount()) {
                    int moved = Math.min(capacity - current.getCount(), remainder.getCount());
                    var merged = current.copy(); merged.grow(moved);
                    inventory.setStackInSlot(i, merged);
                    remainder.shrink(moved);
                }
            }
        }
        if (!remainder.isEmpty() && level != null && !level.isClientSide)
            Containers.dropItemStack(level, worldPosition.getX() + .5, worldPosition.getY() + .5,
                    worldPosition.getZ() + .5, remainder);
    }

    /** Non-break refund returns the ACTUAL uninserted remainder to persisted escrow.
     * Never drop an item entity as a fallback while this Basin still exists.
     */
    @Unique private ItemStack electrochemistry$refundItem(ItemStack held, boolean breaking) {
        if (held.isEmpty()) return ItemStack.EMPTY;
        ItemStack remainder = held.copy();
        if (!breaking) {
            SmartInventory inv = electrochemistry$basin().getInputInventory();
            for (int i = 0; i < inv.getSlots() && !remainder.isEmpty(); i++)
                remainder = inv.insertItem(i, remainder, false);
            return remainder; // retry later if native input has no compatible room
        }
        if (level != null && !level.isClientSide)
            Containers.dropItemStack(level, worldPosition.getX() + .5, worldPosition.getY() + .5,
                    worldPosition.getZ() + .5, remainder);
        return ItemStack.EMPTY; // physical break: one vanilla best-effort attempt
    }

    /** Do not clear either item until the bath has returned or a real fluid-item entity exists. */
    @Unique private boolean electrochemistry$refundCompound(boolean breaking) {
        if (!electrochemistry$compound || electrochemistry$releasing) return false;
        electrochemistry$releasing = true;
        try {
            boolean bathAlreadyReturned = false;
            if (!breaking && electrochemistry$bathCreditedMb > 0
                    && !electrochemistry$bathEscrow.isEmpty()
                    && BasinFluidRecovery.canRestore(electrochemistry$basin(), electrochemistry$bathEscrow, 250)) {
                // A short EXECUTE may be deterministic: keep already returned
                // volume in native input instead of reclaiming/retrying 250 forever.
                int filled = BasinFluidRecovery.returnToInput(electrochemistry$basin(), electrochemistry$bathEscrow);
                if (filled < 0 || filled > electrochemistry$bathEscrow.getAmount()) return false;
                if (filled > 0) {
                    var remainder = electrochemistry$bathEscrow.copy();
                    remainder.setAmount(remainder.getAmount() - filled);
                    electrochemistry$bathEscrow = remainder.isEmpty() ? FluidStack.EMPTY : remainder;
                    electrochemistry$bathCreditedMb += filled;
                    setChanged();
                }
                if (!electrochemistry$bathEscrow.isEmpty()) return false;
            }
            if (!breaking && electrochemistry$bathCreditedMb == 250
                    && electrochemistry$bathEscrow.isEmpty()) {
                var reference = new FluidStack(
                        com.bettercontent.electrochemistry.fluid.ElectrochemistryFluids.SOURCE.get(), 250);
                if (!BasinFluidRecovery.exactAmount(electrochemistry$basin(), reference, 250)) {
                    ELECTROCHEMISTRY_LOG.error("Returned bath missing or obstructed during item refund at {}; "
                            + "holding item escrow until actual 250mB INPUT return", worldPosition);
                    return false;
                }
                bathAlreadyReturned = true; // leave credited250 until BOTH items are restored
            }
            if (electrochemistry$bathCreditedMb > 0 && !bathAlreadyReturned) {
                FluidStack template = electrochemistry$bathEscrow.isEmpty()
                        ? new FluidStack(com.bettercontent.electrochemistry.fluid.ElectrochemistryFluids.SOURCE.get(),
                                electrochemistry$bathCreditedMb)
                        : electrochemistry$bathEscrow;
                FluidStack reclaimed = BasinFluidRecovery.reclaim(electrochemistry$basin(), template,
                        electrochemistry$bathCreditedMb);
                if (!reclaimed.isEmpty() && reclaimed.getFluid() == template.getFluid()
                        && !reclaimed.hasTag()
                        && reclaimed.getAmount() <= electrochemistry$bathCreditedMb) {
                    var combined = reclaimed.copy();
                    combined.setAmount(reclaimed.getAmount() + electrochemistry$bathEscrow.getAmount());
                    electrochemistry$bathEscrow = combined;
                    electrochemistry$bathCreditedMb -= reclaimed.getAmount();
                    setChanged(); // keep real short drain in persisted escrow before returning
                }
                if (electrochemistry$bathCreditedMb > 0) {
                    ELECTROCHEMISTRY_LOG.error("Cannot fully reclaim returned native bath at {}; retaining compound escrow",
                            worldPosition);
                    return false;
                }
            }
            if (!electrochemistry$bathEscrow.isEmpty()) {
                boolean toTank = !breaking && BasinFluidRecovery.canRestore(electrochemistry$basin(),
                        electrochemistry$bathEscrow, 250);
                if (toTank) {
                    int filled = BasinFluidRecovery.returnToInput(electrochemistry$basin(), electrochemistry$bathEscrow);
                    if (filled < 0 || filled > electrochemistry$bathEscrow.getAmount()) return false;
                    if (filled > 0) {
                        var remaining = electrochemistry$bathEscrow.copy();
                        remaining.setAmount(remaining.getAmount() - filled);
                        electrochemistry$bathEscrow = remaining.isEmpty() ? FluidStack.EMPTY : remaining;
                        electrochemistry$bathCreditedMb += filled; // reclaim on break before any next refund
                        setChanged();
                    }
                    if (!electrochemistry$bathEscrow.isEmpty()) return false;
                    // Keep credited250 until BOTH item escrows are actually returned;
                    // break meanwhile can reclaim the still-owned native bath once.
                } else {
                    ItemStack canister = BasinFluidRecovery.filledCanister(electrochemistry$bathEscrow);
                    if (canister.isEmpty() || !BasinFluidRecovery.dropCanister(level, worldPosition, canister)) {
                        ELECTROCHEMISTRY_LOG.error("No usable bath recovery canister at {}; holding compound escrow",
                                worldPosition);
                        return false;
                    }
                    electrochemistry$bathEscrow = FluidStack.EMPTY;
                    setChanged();
                }
            }
            // Before releasing items, verify a full bath actually returned during
            // this call as well as on any later retry with blocked native input.
            if (electrochemistry$bathCreditedMb == 250 && electrochemistry$bathEscrow.isEmpty()) {
                var reference = new FluidStack(
                        com.bettercontent.electrochemistry.fluid.ElectrochemistryFluids.SOURCE.get(), 250);
                if (!BasinFluidRecovery.exactAmount(electrochemistry$basin(), reference, 250)) return false;
            }
            // Keep EACH item in its NBT escrow until native input actually accepts
            // it; a full/changed inventory must pause, never trigger an unchecked
            // item-entity spawn in the ordinary refund path.
            electrochemistry$anodeEscrow = electrochemistry$refundItem(electrochemistry$anodeEscrow, false);
            setChanged();
            electrochemistry$seedEscrow = electrochemistry$refundItem(electrochemistry$seedEscrow, false);
            setChanged();
            if (!electrochemistry$anodeEscrow.isEmpty() || !electrochemistry$seedEscrow.isEmpty())
                return false;
            electrochemistry$quarantinedBatch = null;
            electrochemistry$clear();
            return true;
        } finally {
            electrochemistry$releasing = false;
        }
    }

    @Inject(method = "notifyChangeOfContents", at = @At("TAIL"))
    private void electrochemistry$inventoryChanged(CallbackInfo ci) {
        electrochemistry$needsScan = true;
    }

    // Create Basin.tick returns early whenever contentsChanged is false. TAIL
    // misses that stable path; RETURN executes once at either native exit.
    @Inject(method = "tick", at = @At("RETURN"))
    private void electrochemistry$tickBatch(CallbackInfo ci) {
        if (level == null || level.isClientSide) return;
        BasinPowerPort power = (BasinPowerPort) (Object) this;
        if (electrochemistry$quarantinedBatch != null) {
            power.electrochemistry$setLoadEnabled(false);
            return; // Opaque escrow remains on this BE until manually recovered.
        }
        if (electrochemistry$hasBatch()) {
            BasinBatchRecipe recipe = electrochemistry$loadedRecipe();
            if (recipe == null) {
                power.electrochemistry$setLoadEnabled(false);
                electrochemistry$refund(false);
                return;
            }
            if (!electrochemistry$canFit(recipe.output())) {
                power.electrochemistry$setLoadEnabled(false);
                return;
            }
            if (electrochemistry$workJoules >= recipe.workJoules()) {
                power.electrochemistry$setLoadEnabled(false);
                if (recipe.isElectroforming()) electrochemistry$returnBathAndOutput(recipe);
                else if (electrochemistry$insertOutput(recipe.output())) electrochemistry$clear();
                return;
            }
            if (recipe.isElectroforming() && (electrochemistry$bathCreditedMb != 0
                    || !BasinFluidRecovery.canReturn(electrochemistry$basin(), electrochemistry$bathEscrow))) {
                power.electrochemistry$setLoadEnabled(false);
                return; // No paid work when returned bath cannot fit in native INPUT.
            }
            boolean cables = power.electrochemistry$hasBothCables();
            if (!cables) {
                power.electrochemistry$setLoadEnabled(false);
                if (electrochemistry$hadBothCables) {
                    // A physical native wire loss, not a solver-zero voltage, resets
                    // the brownout latch so reconnection may probe the same source.
                    electrochemistry$probePending = false;
                    electrochemistry$probeInFlight = false;
                    electrochemistry$retryAboveVoltage = 0;
                    electrochemistry$hadBothCables = false;
                }
                return;
            }
            electrochemistry$hadBothCables = true;
            if (!power.electrochemistry$isLoadEnabled()) {
                // While paused there is no resistor draw. Native open-circuit voltage
                // must recover with both live cables before closing the load again.
                float available = power.electrochemistry$availableVoltage();
                if (available < recipe.minVoltage() || available < electrochemistry$retryAboveVoltage) return;
                if (electrochemistry$probePending) {
                    electrochemistry$probePending = false;
                    if ((double) available < (double) recipe.minVoltage() + .25
                            || (double) available < (double) electrochemistry$failedLoadedVoltage * 1.5) {
                        // A steady near-threshold supply cannot earn a second pulse.
                        electrochemistry$retryAboveVoltage = Math.max(
                                (double) recipe.minVoltage() + .25, (double) available * 1.05);
                        return;
                    }
                    // A real jump after a transient 1V outage earns ONE loaded probe.
                    electrochemistry$probeInFlight = true;
                } else if (electrochemistry$retryAboveVoltage > 0) {
                    // A real rise above the prior retry floor earns another probe.
                    electrochemistry$probeInFlight = true;
                }
                electrochemistry$probeOpenVoltage = available;
                electrochemistry$emptySampleTicks = 0;
                power.electrochemistry$setLoadEnabled(true);
                return; // Let the native circuit settle before accounting any work.
            }
            var sample = power.electrochemistry$powerSample();
            if (sample.voltage() > 0 && sample.current() > 0 && sample.power() > 0) {
                electrochemistry$emptySampleTicks = 0;
                if (sample.voltage() < recipe.minVoltage()) {
                    if (electrochemistry$probeInFlight) {
                        // Two measured failures (initial + one recovery probe) at
                        // a steady 10V/min10V source cannot pulse indefinitely.
                        electrochemistry$retryAboveVoltage = Math.max(
                                (double) recipe.minVoltage() + .25,
                                (double) electrochemistry$probeOpenVoltage * 1.05);
                        electrochemistry$probeInFlight = false;
                    } else {
                        electrochemistry$failedLoadedVoltage = sample.voltage();
                        electrochemistry$probePending = true;
                    }
                    power.electrochemistry$setLoadEnabled(false);
                    return;
                }
                electrochemistry$probePending = false;
                electrochemistry$probeInFlight = false;
                electrochemistry$retryAboveVoltage = 0;
                electrochemistry$workJoules = Math.min(recipe.workJoules(),
                        electrochemistry$workJoules + sample.power() / 20.0);
                setChanged();
            } else if (++electrochemistry$emptySampleTicks >= ELECTROCHEMISTRY_SETTLE_TICKS) {
                // A disconnected/unsettled circuit must not remain switched on forever.
                electrochemistry$emptySampleTicks = 0;
                power.electrochemistry$setLoadEnabled(false);
            }
            return;
        }
        power.electrochemistry$setLoadEnabled(false);
        BasinBatchRecipe recipe = electrochemistry$selectCandidate();
        if (recipe != null) electrochemistry$reserve(recipe);
    }

    @Unique private void electrochemistry$writeCompoundFields(CompoundTag batch) {
        batch.putInt("Version", 2);
        if (electrochemistry$recipeId != null) batch.putString("Recipe", electrochemistry$recipeId.toString());
        batch.putString("Fingerprint", electrochemistry$fingerprint);
        if (!electrochemistry$anodeEscrow.isEmpty())
            batch.put("Anode", electrochemistry$anodeEscrow.save(new CompoundTag()));
        if (!electrochemistry$seedEscrow.isEmpty())
            batch.put("Seed", electrochemistry$seedEscrow.save(new CompoundTag()));
        if (!electrochemistry$bathEscrow.isEmpty())
            batch.put("Bath", electrochemistry$bathEscrow.writeToNBT(new CompoundTag()));
        batch.putInt("BathReturnedMb", electrochemistry$bathCreditedMb);
        batch.putDouble("WorkJoules", electrochemistry$workJoules);
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void electrochemistry$writeBatch(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) return;
        if (electrochemistry$quarantinedBatch != null) {
            tag.put(ELECTROCHEMISTRY_BATCH_KEY, electrochemistry$quarantinedBatch.copy());
            return;
        }
        if (!electrochemistry$hasBatch()) return;
        var batch = new CompoundTag();
        if (electrochemistry$compound) electrochemistry$writeCompoundFields(batch);
        else {
            batch.putString("Recipe", electrochemistry$recipeId.toString());
            batch.putString("Fingerprint", electrochemistry$fingerprint);
            batch.put("Escrow", electrochemistry$escrow.save(new CompoundTag()));
            batch.putDouble("WorkJoules", electrochemistry$workJoules);
        }
        tag.put(ELECTROCHEMISTRY_BATCH_KEY, batch);
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void electrochemistry$readBatch(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) return;
        electrochemistry$clear();
        electrochemistry$quarantinedBatch = null;
        if (!tag.contains(ELECTROCHEMISTRY_BATCH_KEY)) return;
        if (!tag.contains(ELECTROCHEMISTRY_BATCH_KEY, 10)) {
            electrochemistry$quarantinedBatch = new CompoundTag();
            electrochemistry$quarantinedBatch.put("Opaque", tag.get(ELECTROCHEMISTRY_BATCH_KEY).copy());
            ELECTROCHEMISTRY_LOG.error("Invalid Basin escrow tag type at {}; preserving opaque NBT", worldPosition);
            return;
        }
        var batch = tag.getCompound(ELECTROCHEMISTRY_BATCH_KEY);
        try {
            int version = batch.contains("Version") ? batch.getInt("Version") : 1;
            if (version == 2) {
                if ((batch.contains("Anode") && !batch.contains("Anode", 10))
                        || (batch.contains("Seed") && !batch.contains("Seed", 10))
                        || (batch.contains("Bath") && !batch.contains("Bath", 10)))
                    throw new IllegalArgumentException("Malformed compound escrow asset type");
                electrochemistry$compound = true;
                if (batch.contains("Anode", 10)) {
                    electrochemistry$anodeEscrow = ItemStack.of(batch.getCompound("Anode"));
                    if (electrochemistry$anodeEscrow.isEmpty() || electrochemistry$anodeEscrow.hasTag())
                        throw new IllegalArgumentException("Unreadable compound anode");
                }
                if (batch.contains("Seed", 10)) {
                    electrochemistry$seedEscrow = ItemStack.of(batch.getCompound("Seed"));
                    if (electrochemistry$seedEscrow.isEmpty() || electrochemistry$seedEscrow.hasTag())
                        throw new IllegalArgumentException("Unreadable compound seed");
                }
                if (batch.contains("Bath", 10)) {
                    electrochemistry$bathEscrow = FluidStack.loadFluidStackFromNBT(batch.getCompound("Bath"));
                    if (electrochemistry$bathEscrow.isEmpty() || electrochemistry$bathEscrow.hasTag()
                            || electrochemistry$bathEscrow.getAmount() > 250)
                        throw new IllegalArgumentException("Unreadable compound bath");
                }
                electrochemistry$bathCreditedMb = batch.getInt("BathReturnedMb");
                if (electrochemistry$bathCreditedMb < 0 || electrochemistry$bathCreditedMb > 250)
                    throw new IllegalArgumentException("Invalid returned bath ledger");
                if (electrochemistry$anodeEscrow.isEmpty() && electrochemistry$seedEscrow.isEmpty()
                        && electrochemistry$bathEscrow.isEmpty() && electrochemistry$bathCreditedMb == 0)
                    throw new IllegalArgumentException("Empty compound escrow record");
            } else if (version == 1) {
                if (!batch.contains("Escrow", 10)) throw new IllegalArgumentException("Missing escrow stack");
                var stack = ItemStack.of(batch.getCompound("Escrow"));
                if (stack.isEmpty() || stack.hasTag() || stack.isDamageableItem())
                    throw new IllegalArgumentException("Unreadable or unsupported escrow stack");
                electrochemistry$escrow = stack;
            } else throw new IllegalArgumentException("Unknown Basin escrow version " + version);
            try { electrochemistry$recipeId = new ResourceLocation(batch.getString("Recipe")); }
            catch (RuntimeException badId) { electrochemistry$recipeId = null; }
            electrochemistry$fingerprint = batch.getString("Fingerprint");
            double work = batch.getDouble("WorkJoules");
            electrochemistry$workJoules = Double.isFinite(work) && work >= 0 ? work : 0;
            // Missing/changed recipe ID or fingerprint refunds parseable escrow next tick.
        } catch (RuntimeException invalid) {
            electrochemistry$escrow = ItemStack.EMPTY;
            electrochemistry$compound = false;
            electrochemistry$anodeEscrow = ItemStack.EMPTY;
            electrochemistry$seedEscrow = ItemStack.EMPTY;
            electrochemistry$bathEscrow = FluidStack.EMPTY;
            electrochemistry$bathCreditedMb = 0;
            electrochemistry$quarantinedBatch = batch.copy();
            ELECTROCHEMISTRY_LOG.error("Unreadable Basin escrow at {}; preserving raw NBT for recovery", worldPosition, invalid);
        }
    }

    /** Physical break is deliberately best-effort, never the retrying normal refund path.
     * The user accepts lost bath if native reclaim or the sole canister spawn fails.
     * No separate entity/chunk save is atomic with this BE's NBT.
     */
    @Unique private void electrochemistry$releaseCompoundOnBreak() {
        if (!electrochemistry$compound || electrochemistry$releasing) return;
        electrochemistry$releasing = true;
        FluidStack held = electrochemistry$bathEscrow.copy();
        int credited = electrochemistry$bathCreditedMb;
        ItemStack anode = electrochemistry$anodeEscrow.copy();
        ItemStack seed = electrochemistry$seedEscrow.copy();
        if (credited > 0 && credited <= 250) {
            try {
                FluidStack reference = held.isEmpty()
                        ? new FluidStack(com.bettercontent.electrochemistry.fluid.ElectrochemistryFluids.SOURCE.get(),
                                credited)
                        : held;
                FluidStack reclaimed = BasinFluidRecovery.reclaim(electrochemistry$basin(), reference, credited);
                if (!reclaimed.isEmpty() && !reclaimed.hasTag()
                        && reclaimed.getFluid() == reference.getFluid()
                        && reclaimed.getAmount() <= credited
                        && (held.isEmpty() || reclaimed.getFluid() == held.getFluid())
                        && held.getAmount() + reclaimed.getAmount() <= 250) {
                    if (held.isEmpty()) held = reclaimed.copy();
                    else held.setAmount(held.getAmount() + reclaimed.getAmount());
                    if (reclaimed.getAmount() < credited)
                        ELECTROCHEMISTRY_LOG.warn("Short physical-break Basin bath reclaim at {}: {} of {} mB; "
                                + "remaining native bath may be lost", worldPosition, reclaimed.getAmount(), credited);
                } else {
                    ELECTROCHEMISTRY_LOG.warn("No valid credited bath reclaimed on physical break at {}; "
                            + "up to {} mB may be lost", worldPosition, credited);
                }
            } catch (RuntimeException reclaimFailure) {
                ELECTROCHEMISTRY_LOG.error("Physical-break bath reclaim failed at {}; accepted bath loss",
                        worldPosition, reclaimFailure);
            }
        }
        // Clear ownership BEFORE a single fluid-entity spawn and the item drops.
        // A second destroy() invocation cannot issue a second refund, even when
        // addFreshEntity fails. Never turn an unsuccessful break into a retrying
        // escrow on a BE that is already being removed from the world.
        electrochemistry$quarantinedBatch = null;
        electrochemistry$clear();
        try {
            if (!held.isEmpty()) {
                ItemStack canister = BasinFluidRecovery.filledCanister(held);
                if (canister.isEmpty() || !BasinFluidRecovery.dropCanister(level, worldPosition, canister))
                    ELECTROCHEMISTRY_LOG.error("Physical-break Basin bath recovery failed at {}; "
                            + "{} mB lost under accepted best-effort policy", worldPosition, held.getAmount());
            } else if (credited > 0) {
                ELECTROCHEMISTRY_LOG.error("Physical-break Basin bath recovery found no valid bath at {}; "
                        + "credited native bath lost under accepted best-effort policy", worldPosition);
            }
        } catch (RuntimeException spawnFailure) {
            ELECTROCHEMISTRY_LOG.error("Physical-break Basin canister spawn threw at {}; "
                    + "bath lost without retry", worldPosition, spawnFailure);
        }
        // Items are independent vanilla drops. Each is attempted once; a failed
        // spawn is not reissued from a disappearing BE (no false exactly-once claim).
        try { electrochemistry$refundItem(anode, true); }
        catch (RuntimeException dropFailure) {
            ELECTROCHEMISTRY_LOG.error("Physical-break Basin anode drop failed at {}", worldPosition, dropFailure);
        }
        try { electrochemistry$refundItem(seed, true); }
        catch (RuntimeException dropFailure) {
            ELECTROCHEMISTRY_LOG.error("Physical-break Basin seed drop failed at {}", worldPosition, dropFailure);
        }
    }

    @Unique private void electrochemistry$releaseOnBreak() {
        if (electrochemistry$compound) {
            electrochemistry$releaseCompoundOnBreak();
            return; // No raw-paper fallback for a normal compound break.
        }
        electrochemistry$refund(true);
        if (electrochemistry$quarantinedBatch == null || level == null || level.isClientSide) return;
        // Preserve corrupt/unknown item data on a visible recovery token rather than erasing
        // it when the Basin is physically broken. This is not a craftable ingredient.
        CompoundTag raw = electrochemistry$quarantinedBatch;
        electrochemistry$quarantinedBatch = null;
        var token = new ItemStack(Items.PAPER);
        token.getOrCreateTag().put("better_electrochemistry:EscrowRecovery", raw.copy());
        token.setHoverName(net.minecraft.network.chat.Component.literal("Electrochemistry escrow recovery"));
        ELECTROCHEMISTRY_LOG.error("Breaking quarantined Basin at {}; dropping tagged recovery token", worldPosition);
        Containers.dropItemStack(level, worldPosition.getX() + .5, worldPosition.getY() + .5,
                worldPosition.getZ() + .5, token);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void electrochemistry$dropEscrowOnBreak(CallbackInfo ci) {
        electrochemistry$releaseOnBreak();
    }

    // No remove() refund: Create calls remove on ordinary chunk unload. Physical
    // block replacement calls IBE.onRemove -> virtual BasinBlockEntity.destroy().
}
