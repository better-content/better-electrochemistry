package com.bettercontent.electrochemistry.client.emi;

import com.bettercontent.electrochemistry.fluid.ElectrochemistryFluids;
import com.bettercontent.electrochemistry.recipe.BasinBatchRecipe;
import com.bettercontent.electrochemistry.recipe.ElectrochemistryRecipes;
import com.mojang.logging.LogUtils;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;
import java.util.ArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Client-only EMI service. The common mod entrypoint must never reference this class. */
@EmiEntrypoint
public final class ElectrochemistryEmiPlugin implements EmiPlugin {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = ElectrochemistryRecipes.MOD_ID;
    private static final ResourceLocation BASIN_ID = new ResourceLocation("create", "basin");
    private static final ResourceLocation BATH_ID = new ResourceLocation(MOD_ID, "aqueous_copper_ii_sulfate");
    // See chemistry-execution/.../contracts/production-ids.md: one short ID; no long alias.
    private static final ResourceLocation RECOVERY_ID = new ResourceLocation(MOD_ID, "bath_recovery_canister");
    public static final EmiRecipeCategory BASIN_BATCH = new EmiRecipeCategory(
            new ResourceLocation(MOD_ID, "basin_batch"), EmiStack.of(Items.COPPER_INGOT));

    @Override public void register(EmiRegistry registry) {
        // The loaded v2 JSON can parse before the Basin can safely start it.
        // Share the engine's explicit activation gate: no unprocessable card/category.
        if (!BasinBatchRecipe.electroformingRuntimeEnabled()) return;
        // Fail closed until the native fluid AND real capacity-250 recovery item are registered.
        // A v1 synthetic batch may still exist in a native fixture; it is never a production card.
        if (!ForgeRegistries.FLUIDS.containsKey(BATH_ID) || !ForgeRegistries.ITEMS.containsKey(RECOVERY_ID)) return;
        Fluid bath = ForgeRegistries.FLUIDS.getValue(BATH_ID);
        Item recovery = ForgeRegistries.ITEMS.getValue(RECOVERY_ID);
        if (bath == null || bath == Fluids.EMPTY || recovery == null || recovery == Items.AIR) return;
        var displays = new ArrayList<ElectroformingEmiRecipe>();
        for (var recipe : registry.getRecipeManager().getAllRecipesFor(ElectrochemistryRecipes.BASIN_BATCH.get())) {
            if (!recipe.isElectroforming() || recipe.bathFluid() != bath || recipe.bathAmountMb() != 250) continue;
            try {
                // A real Forge fluid-item capability carries the exceptional bath refund.
                // If it cannot be created, do not advertise a recoverable recipe card.
                var canister = ElectrochemistryFluids.filledRecoveryCanister(recipe.bathAmountMb());
                if (!canister.is(recovery)) continue;
                displays.add(new ElectroformingEmiRecipe(recipe, canister));
            } catch (RuntimeException notReady) {
                LOGGER.warn("Skipping electroforming EMI recipe {}: recovery item is not ready", recipe.getId(), notReady);
                // Native registration or recovery handler is not ready; fail closed.
            }
        }
        if (displays.isEmpty()) return; // Never advertise an empty production category.
        registry.addCategory(BASIN_BATCH);
        Item basin = ForgeRegistries.ITEMS.getValue(BASIN_ID);
        if (basin != null && basin != Items.AIR) registry.addWorkstation(BASIN_BATCH, EmiStack.of(basin));
        displays.forEach(registry::addRecipe);
        // EMI re-registers the plugin against its current RecipeManager on reload. There are
        // intentionally no static recipe caches, transfer handler or automatic recipe tree.
    }
}
