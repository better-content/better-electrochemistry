package com.bettercontent.electrochemistry.client.emi;

import com.bettercontent.electrochemistry.recipe.BasinBatchRecipe;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

/** One card per actual synchronized v2 Basin recipe; v1 fixture batches never appear. */
public final class ElectroformingEmiRecipe implements EmiRecipe {
    private final BasinBatchRecipe recipe;
    private final List<EmiIngredient> consumed;
    private final List<EmiIngredient> retained;
    private final List<EmiStack> products;
    private final EmiStack recoveryCanister;

    ElectroformingEmiRecipe(BasinBatchRecipe recipe, ItemStack filledRecoveryCanister) {
        if (!recipe.isElectroforming()) throw new IllegalArgumentException("Not a production electroforming batch");
        this.recipe = recipe;
        this.consumed = List.of(
                EmiStack.of(new ItemStack(recipe.anodeItem(), recipe.anodeCount())),
                EmiStack.of(new ItemStack(recipe.cathodeSeedItem(), recipe.cathodeSeedCount())));
        // EMI's catalyst list, not the consumed list, supplies fluid usage lookup.
        // The engine reserves and returns exactly this bath amount; it does not produce fluid.
        this.retained = List.of(EmiStack.of(recipe.bathFluid(), recipe.bathAmountMb()));
        this.products = List.of(EmiStack.of(recipe.output()));
        this.recoveryCanister = EmiStack.of(filledRecoveryCanister.copy());
    }

    @Override public EmiRecipeCategory getCategory() { return ElectrochemistryEmiPlugin.BASIN_BATCH; }
    @Override public ResourceLocation getId() { return recipe.getId(); }
    @Override public Recipe<?> getBackingRecipe() { return recipe; }
    @Override public List<EmiIngredient> getInputs() { return consumed; }
    @Override public List<EmiIngredient> getCatalysts() { return retained; }
    @Override public List<EmiStack> getOutputs() { return products; }
    @Override public boolean supportsRecipeTree() { return false; }
    @Override public int getDisplayWidth() { return 180; }
    @Override public int getDisplayHeight() { return 118; }

    @Override public void addWidgets(WidgetHolder widgets) {
        widgets.addSlot(consumed.get(0), 4, 8)
                .appendTooltip(Component.translatable("better_electrochemistry.emi.input_anode", recipe.anodeCount()));
        widgets.addSlot(consumed.get(1), 29, 8)
                .appendTooltip(Component.translatable("better_electrochemistry.emi.input_seed", recipe.cathodeSeedCount()));
        widgets.addSlot(retained.get(0), 54, 8)
                .appendTooltip(Component.translatable("better_electrochemistry.emi.bath_retained", recipe.bathAmountMb()));
        widgets.addFillingArrow(86, 10, 60);
        widgets.addSlot(products.get(0), 141, 8).recipeContext(this)
                .appendTooltip(Component.translatable("better_electrochemistry.emi.output", recipe.output().getCount()));
        widgets.addText(Component.translatable("better_electrochemistry.emi.bath_retained", recipe.bathAmountMb()),
                4, 34, 0x404040, false);
        widgets.addText(Component.translatable("better_electrochemistry.emi.voltage", recipe.minVoltage()),
                4, 47, 0x404040, false);
        widgets.addText(Component.translatable("better_electrochemistry.emi.work", recipe.workJoules()),
                4, 60, 0x404040, false);
        widgets.addText(Component.translatable("better_electrochemistry.emi.basin"), 4, 73, 0x404040, false);
        // Informational only: a filled 250 mB canister is an exception/refund outcome,
        // not an ordinary batch product, catalyst, consumed input or free bucket.
        widgets.addSlot(recoveryCanister, 4, 86)
                .appendTooltip(Component.translatable("better_electrochemistry.emi.bath_refund"));
        widgets.addText(Component.translatable("better_electrochemistry.emi.bath_refund_short"), 26, 90,
                0x606060, false);
        widgets.addText(Component.translatable("better_electrochemistry.emi.no_transfer"), 4, 106,
                0x606060, false);
    }
}
