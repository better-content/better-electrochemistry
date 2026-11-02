package com.bettercontent.electrochemistry.fluid;

import com.bettercontent.electrochemistry.BetterElectrochemistry;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.common.SoundActions;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.templates.FluidHandlerItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * One aqueous copper(II) sulfate bath, not dry CuSO4 or acid. A Basin batch holds
 * 250 mB in escrow and returns that same volume; a vanilla bucket holds 1000 mB
 * and therefore cannot represent an exceptional 250 mB bath refund.
 */
public final class ElectrochemistryFluids {
    public static final int RECOVERY_CAPACITY_MB = 250;
    public static final String AQUEOUS_ID = "aqueous_copper_ii_sulfate";

    private static final DeferredRegister<FluidType> TYPES =
            DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, BetterElectrochemistry.ID);
    private static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(ForgeRegistries.FLUIDS, BetterElectrochemistry.ID);
    private static final DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, BetterElectrochemistry.ID);
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BetterElectrochemistry.ID);

    private static final ResourceLocation STILL_SPRITE =
            new ResourceLocation(BetterElectrochemistry.ID, "block/aqueous_copper_ii_sulfate_still");
    private static final ResourceLocation FLOW_SPRITE =
            new ResourceLocation(BetterElectrochemistry.ID, "block/aqueous_copper_ii_sulfate_flow");

    public static final RegistryObject<FluidType> TYPE = TYPES.register(AQUEOUS_ID, () -> new FluidType(
            FluidType.Properties.create().density(1100).viscosity(1500).canConvertToSource(false)
                    .sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL)
                    .sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY)) {
        // Forge invokes this only on the client. No Minecraft client class is
        // referenced by common registration or constructed on a dedicated server.
        @Override
        public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
            consumer.accept(new IClientFluidTypeExtensions() {
                @Override public ResourceLocation getStillTexture() { return STILL_SPRITE; }
                @Override public ResourceLocation getFlowingTexture() { return FLOW_SPRITE; }
                @Override public int getTintColor() { return 0xFFFFFFFF; }
            });
        }
    });

    // All registration suppliers are lazy; FLUID_PROPERTIES is initialized before
    // any deferred register event constructs a fluid, bucket, or liquid block.
    public static final RegistryObject<FlowingFluid> SOURCE =
            FLUIDS.register(AQUEOUS_ID, () -> new ForgeFlowingFluid.Source(FLUID_PROPERTIES));
    public static final RegistryObject<FlowingFluid> FLOWING =
            FLUIDS.register("flowing_" + AQUEOUS_ID, () -> new ForgeFlowingFluid.Flowing(FLUID_PROPERTIES));
    public static final RegistryObject<LiquidBlock> BLOCK = BLOCKS.register(AQUEOUS_ID,
            () -> new LiquidBlock(SOURCE, BlockBehaviour.Properties.of()
                    .noCollission().strength(100.0F).noLootTable()));
    public static final RegistryObject<Item> BUCKET = ITEMS.register(AQUEOUS_ID + "_bucket",
            () -> new BucketItem(SOURCE, new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));
    /** The one physically redeemable, 0..250 mB fluid item (not a paper NBT token). */
    public static final RegistryObject<Item> RECOVERY_CANISTER =
            ITEMS.register("bath_recovery_canister", BathRecoveryCanister::new);

    private static final ForgeFlowingFluid.Properties FLUID_PROPERTIES =
            new ForgeFlowingFluid.Properties(TYPE, SOURCE, FLOWING)
                    .block(BLOCK).bucket(BUCKET).slopeFindDistance(2).levelDecreasePerBlock(2);

    private ElectrochemistryFluids() {}

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        FLUIDS.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }

    /**
     * Make a physical, unstackable exception-refund item carrying an exact amount.
     * An unavailable registry, missing capability or unsuccessful fill fails closed;
     * callers must retain the Basin escrow rather than clearing it in that case.
     */
    public static ItemStack filledRecoveryCanister(int millibuckets) {
        if (millibuckets < 1 || millibuckets > RECOVERY_CAPACITY_MB)
            throw new IllegalArgumentException("Copper bath recovery must be 1..250 mB");
        ItemStack item = new ItemStack(RECOVERY_CANISTER.get());
        IFluidHandlerItem handler = item.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM)
                .orElseThrow(() -> new IllegalStateException("Copper bath recovery item lacks a fluid handler"));
        FluidStack requested = new FluidStack(SOURCE.get(), millibuckets);
        if (handler.fill(requested, IFluidHandler.FluidAction.EXECUTE) != millibuckets)
            throw new IllegalStateException("Copper bath recovery item rejected the escrowed fluid");
        ItemStack filled = handler.getContainer();
        FluidStack stored = handler.getFluidInTank(0);
        if (!filled.is(RECOVERY_CANISTER.get()) || filled.getCount() != 1
                || stored.getFluid() != SOURCE.get() || stored.getAmount() != millibuckets
                || stored.hasTag())
            throw new IllegalStateException("Copper bath recovery item did not retain the exact fluid");
        return filled;
    }

    /** Empty and filled states are the same item; capability NBT is the only fluid store. */
    private static final class BathRecoveryCanister extends Item {
        private BathRecoveryCanister() {
            super(new Item.Properties().stacksTo(1));
        }

        @Override
        public ICapabilityProvider initCapabilities(ItemStack stack, CompoundTag nbt) {
            return new FluidHandlerItemStack(stack, RECOVERY_CAPACITY_MB) {
                private boolean invalidPayload() {
                    CompoundTag tag = container.getTag();
                    if (tag == null || !tag.contains(FLUID_NBT_KEY)) return false;
                    try {
                        FluidStack raw = super.getFluid();
                        return raw.isEmpty() || raw.hasTag() || raw.getFluid() != SOURCE.get()
                                || raw.getAmount() < 1 || raw.getAmount() > RECOVERY_CAPACITY_MB;
                    } catch (RuntimeException malformedFluidNbt) {
                        // A malformed nested Fluid payload is inert, not a crash or refill.
                        return true;
                    }
                }

                @Override public FluidStack getFluid() {
                    return invalidPayload() ? FluidStack.EMPTY : super.getFluid();
                }

                @Override public boolean canFillFluidType(FluidStack fluid) {
                    return !fluid.isEmpty() && !fluid.hasTag() && fluid.getFluid() == SOURCE.get();
                }

                @Override public boolean canDrainFluidType(FluidStack fluid) {
                    return !fluid.isEmpty() && !fluid.hasTag() && fluid.getFluid() == SOURCE.get();
                }

                @Override public int fill(FluidStack fluid, FluidAction action) {
                    return invalidPayload() ? 0 : super.fill(fluid, action);
                }

                @Override public FluidStack drain(int maxDrain, FluidAction action) {
                    return invalidPayload() ? FluidStack.EMPTY : super.drain(maxDrain, action);
                }
            };
        }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            // A compatible tank receives (or fills) the *same* physical container.
            // Forge FluidUtil simulates before executing and updates the held stack.
            if (!context.getLevel().isClientSide && context.getPlayer() != null
                    && FluidUtil.interactWithFluidHandler(context.getPlayer(), context.getHand(),
                            context.getLevel(), context.getClickedPos(), context.getClickedFace()))
                return InteractionResult.CONSUME;
            return super.useOn(context);
        }
    }
}
