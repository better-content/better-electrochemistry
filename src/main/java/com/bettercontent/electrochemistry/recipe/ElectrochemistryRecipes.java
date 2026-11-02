package com.bettercontent.electrochemistry.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** A small, explicit Basin recipe type. Only the item-only fixture subset is executable yet. */
public final class ElectrochemistryRecipes {
    public static final String MOD_ID = "better_electrochemistry";
    private static final DeferredRegister<RecipeType<?>> TYPES =
            DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, MOD_ID);
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, MOD_ID);
    public static final RegistryObject<RecipeType<BasinBatchRecipe>> BASIN_BATCH =
            TYPES.register("basin_batch", () -> RecipeType.simple(new ResourceLocation(MOD_ID, "basin_batch")));
    public static final RegistryObject<RecipeSerializer<BasinBatchRecipe>> BASIN_BATCH_SERIALIZER =
            SERIALIZERS.register("basin_batch", BasinBatchRecipe.Serializer::new);

    private ElectrochemistryRecipes() {}

    /** Called once from the coordinator-owned mod entrypoint on its mod event bus. */
    public static void register(IEventBus bus) {
        TYPES.register(bus);
        SERIALIZERS.register(bus);
    }
}
