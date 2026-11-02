package com.bettercontent.electrochemistry;

import com.bettercontent.electrochemistry.fluid.ElectrochemistryFluids;
import com.bettercontent.electrochemistry.recipe.ElectrochemistryRecipes;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Common entrypoint for the Basin recipe and its aqueous bath; no client classes load here. */
@Mod(BetterElectrochemistry.ID)
public final class BetterElectrochemistry {
    public static final String ID = "better_electrochemistry";

    public BetterElectrochemistry() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ElectrochemistryFluids.register(bus);
        ElectrochemistryRecipes.register(bus);
    }
}
