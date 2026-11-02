package com.bettercontent.electrochemistry.basin;

import com.bettercontent.electrochemistry.fluid.ElectrochemistryFluids;
import com.bettercontent.electrochemistry.recipe.BasinBatchRecipe;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;

/** Only the Basin's INPUT behaviour; never drain Create's combined input/output capability. */
public final class BasinFluidRecovery {
    private BasinFluidRecovery() {}

    private static IFluidHandler input(BasinBlockEntity basin) {
        return basin.inputTank == null ? null : basin.inputTank.getCapability().orElse(null);
    }

    /** Require exactly the recipe's untagged 250 mB across both native 1000 mB segments. */
    public static boolean exactBath(BasinBlockEntity basin, BasinBatchRecipe recipe) {
        IFluidHandler tank = input(basin);
        if (tank == null || !recipe.isElectroforming()) return false;
        int total = 0;
        for (int i = 0; i < tank.getTanks(); i++) {
            FluidStack found = tank.getFluidInTank(i);
            if (found.isEmpty()) continue;
            if (found.hasTag() || found.getFluid() != recipe.bathFluid() || found.getAmount() < 1)
                return false;
            total += found.getAmount();
            if (total > recipe.bathAmountMb()) return false;
        }
        return total == recipe.bathAmountMb();
    }

    /** Require an exact amount of this one untagged fluid, including zero. */
    public static boolean exactAmount(BasinBlockEntity basin, FluidStack held, int expected) {
        IFluidHandler tank = input(basin);
        if (tank == null || held == null || held.isEmpty() || expected < 0 || expected > 250) return false;
        int found = 0;
        for (int i = 0; i < tank.getTanks(); i++) {
            FluidStack part = tank.getFluidInTank(i);
            if (part.isEmpty()) continue;
            if (part.hasTag() || part.getFluid() != held.getFluid()) return false;
            found += part.getAmount();
        }
        return found == expected;
    }

    public static boolean emptyInput(BasinBlockEntity basin) {
        IFluidHandler tank = input(basin);
        if (tank == null) return false;
        for (int i = 0; i < tank.getTanks(); i++)
            if (!tank.getFluidInTank(i).isEmpty()) return false;
        return true;
    }

    /** All 250 mB must be available BEFORE removing either consumed item. */
    public static boolean canReserve(BasinBlockEntity basin, BasinBatchRecipe recipe) {
        if (!exactBath(basin, recipe)) return false;
        IFluidHandler tank = input(basin);
        var requested = new FluidStack(recipe.bathFluid(), recipe.bathAmountMb());
        FluidStack simulated = tank.drain(requested, FluidAction.SIMULATE);
        return simulated.getFluid() == recipe.bathFluid()
                && !simulated.hasTag() && simulated.getAmount() == recipe.bathAmountMb();
    }

    /** EXECUTE result is the *actual* quantity; caller must persist it, not a guessed recipe amount. */
    public static FluidStack reserve(BasinBlockEntity basin, BasinBatchRecipe recipe) {
        IFluidHandler tank = input(basin);
        return tank == null ? FluidStack.EMPTY : tank.drain(
                new FluidStack(recipe.bathFluid(), recipe.bathAmountMb()), FluidAction.EXECUTE);
    }

    /** Only an empty input may receive the escrowed bath on successful production. */
    public static boolean canReturn(BasinBlockEntity basin, FluidStack held) {
        IFluidHandler tank = input(basin);
        return tank != null && held != null && !held.isEmpty() && !held.hasTag()
                && emptyInput(basin) && tank.fill(held.copy(), FluidAction.SIMULATE) == held.getAmount();
    }

    /** Roll back an incomplete reservation if part of the bath remained in the input. */
    public static boolean canRestore(BasinBlockEntity basin, FluidStack held, int exactTotal) {
        IFluidHandler tank = input(basin);
        if (tank == null || held == null || held.isEmpty() || held.hasTag()) return false;
        int present = 0;
        for (int i = 0; i < tank.getTanks(); i++) {
            FluidStack found = tank.getFluidInTank(i);
            if (found.isEmpty()) continue;
            if (found.hasTag() || found.getFluid() != held.getFluid()) return false;
            present += found.getAmount();
        }
        return present + held.getAmount() == exactTotal
                && tank.fill(held.copy(), FluidAction.SIMULATE) == held.getAmount();
    }

    /** Reclaim a partly returned bath before refund/break, never count a simulation as real. */
    public static FluidStack reclaim(BasinBlockEntity basin, FluidStack sameFluid, int amount) {
        IFluidHandler tank = input(basin);
        if (tank == null || amount < 1 || !exactAmount(basin, sameFluid, amount)) return FluidStack.EMPTY;
        var requested = new FluidStack(sameFluid.getFluid(), amount);
        FluidStack simulated = tank.drain(requested, FluidAction.SIMULATE);
        if (simulated.getFluid() != sameFluid.getFluid() || simulated.getAmount() != amount
                || simulated.hasTag()) return FluidStack.EMPTY;
        return tank.drain(requested, FluidAction.EXECUTE);
    }

    /** Return the ACTUAL filled amount; never clear escrow on short fill. */
    public static int returnToInput(BasinBlockEntity basin, FluidStack held) {
        IFluidHandler tank = input(basin);
        return tank == null ? 0 : tank.fill(held.copy(), FluidAction.EXECUTE);
    }

    /** A real registered, usable Forge fluid item, not an unreadable paper NBT token. */
    public static ItemStack filledCanister(FluidStack held) {
        if (held == null || held.isEmpty() || held.hasTag()
                || held.getAmount() < 1 || held.getAmount() > 250) return ItemStack.EMPTY;
        try {
            if (held.getFluid() != ElectrochemistryFluids.SOURCE.get()) return ItemStack.EMPTY;
        } catch (RuntimeException notRegistered) { return ItemStack.EMPTY; }
        try {
            ItemStack canister = ElectrochemistryFluids.filledRecoveryCanister(held.getAmount());
            if (canister.isEmpty() || !canister.is(ElectrochemistryFluids.RECOVERY_CANISTER.get()))
                return ItemStack.EMPTY;
            var handler = canister.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).orElse(null);
            if (handler == null || handler.getTanks() != 1) return ItemStack.EMPTY;
            FluidStack contained = handler.getFluidInTank(0);
            return contained.getFluid() == held.getFluid() && contained.getAmount() == held.getAmount()
                    && !contained.hasTag() ? canister : ItemStack.EMPTY;
        } catch (RuntimeException notRegisteredOrBroken) {
            return ItemStack.EMPTY;
        }
    }

    /** Check entity creation. Normal refunds hold escrow on failure; physical
     * Basin breaks instead clear first and accept a one-shot lost-fluid result.
     */
    public static boolean dropCanister(Level level, BlockPos pos, ItemStack canister) {
        if (level == null || level.isClientSide || canister.isEmpty() || !level.isLoaded(pos)) return false;
        var drop = new ItemEntity(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, canister.copy());
        drop.setDefaultPickUpDelay();
        return level.addFreshEntity(drop);
    }
}
