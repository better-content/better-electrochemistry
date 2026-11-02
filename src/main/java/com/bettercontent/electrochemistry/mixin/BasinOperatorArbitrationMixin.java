package com.bettercontent.electrochemistry.mixin;

import com.bettercontent.electrochemistry.basin.BasinBatchPort;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinOperatingBlockEntity;
import java.util.Optional;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only claim ordinary processing when an actual loaded batch matches or owns escrow. */
@Mixin(value = BasinOperatingBlockEntity.class, remap = false)
public abstract class BasinOperatorArbitrationMixin {
    @Shadow protected abstract Optional<BasinBlockEntity> getBasin();

    private boolean electrochemistry$ownsBasin() {
        return getBasin().filter(basin -> basin instanceof BasinBatchPort port
                && port.electrochemistry$ownsProcessing()).isPresent();
    }

    @Inject(method = "updateBasin", at = @At("HEAD"), cancellable = true)
    private void electrochemistry$preventOrdinaryStart(CallbackInfoReturnable<Boolean> cir) {
        if (electrochemistry$ownsBasin()) cir.setReturnValue(true);
    }

    @Inject(method = "matchBasinRecipe", at = @At("HEAD"), cancellable = true)
    private void electrochemistry$preventOrdinaryMatch(Recipe<?> recipe, CallbackInfoReturnable<Boolean> cir) {
        if (electrochemistry$ownsBasin()) cir.setReturnValue(false);
    }

    @Inject(method = "applyBasinRecipe", at = @At("HEAD"), cancellable = true)
    private void electrochemistry$preventOrdinaryConsumption(CallbackInfo ci) {
        if (electrochemistry$ownsBasin()) ci.cancel();
    }
}
