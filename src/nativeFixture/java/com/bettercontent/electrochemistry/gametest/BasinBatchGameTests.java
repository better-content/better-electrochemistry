package com.bettercontent.electrochemistry.gametest;

import com.bettercontent.electrochemistry.basin.BasinBatchPort;
import com.bettercontent.electrochemistry.power.BasinPowerPort;
import com.bettercontent.electrochemistry.recipe.BasinBatchRecipe;
import com.bettercontent.electrochemistry.recipe.ElectrochemistryRecipes;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.mixer.CompactingRecipe;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.mixer.MixingRecipe;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlock;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.patryk3211.powergrid.collections.ModdedBlocks;
import org.patryk3211.powergrid.collections.ModdedItems;
import org.patryk3211.powergrid.electricity.creative.CreativeSourceBlock;
import org.patryk3211.powergrid.electricity.creative.CreativeSourceBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.battery.MultiBlockBatteryEntity;
import org.patryk3211.powergrid.electricity.deviceconnector.BridgeElectricBehaviour;
import org.patryk3211.powergrid.electricity.deviceconnector.DeviceConnectorBlock;
import org.patryk3211.powergrid.electricity.deviceconnector.DeviceConnectorBlockEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Isolated, test-only item batch witness. Never run the manual-ON endpoint suite with batch mixins. */
@GameTestHolder("better_electrochemistry_batch")
@PrefixGameTestTemplate(false)
public final class BasinBatchGameTests {
    private static final Logger LOG = LoggerFactory.getLogger(BasinBatchGameTests.class);
    private static final ResourceLocation RECIPE = new ResourceLocation("better_electrochemistry", "fixture_cobble_to_stone");
    private static final BlockPos SOURCE = new BlockPos(3, 2, 5);
    private static final BlockPos BASIN = new BlockPos(8, 2, 5);

    private static BasinBlockEntity basin(GameTestHelper helper) {
        return (BasinBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(BASIN));
    }

    private static BasinBatchPort batch(GameTestHelper helper) {
        return (BasinBatchPort) basin(helper);
    }

    private static BasinPowerPort power(GameTestHelper helper) {
        return (BasinPowerPort) basin(helper);
    }

    private static CreativeSourceBlockEntity source(GameTestHelper helper) {
        return (CreativeSourceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(SOURCE));
    }

    private static int cobbleInput(GameTestHelper helper) {
        var inventory = basin(helper).getInputInventory();
        int count = 0;
        for (int i = 0; i < inventory.getSlots(); i++) {
            var stack = inventory.getStackInSlot(i);
            if (stack.is(Items.COBBLESTONE)) count += stack.getCount();
            else helper.assertTrue(stack.isEmpty(), "Unexpected non-cobble Basin input " + stack);
        }
        return count;
    }

    private static int stoneOutput(GameTestHelper helper) {
        var inventory = basin(helper).getOutputInventory();
        int count = 0;
        for (int i = 0; i < inventory.getSlots(); i++) {
            var stack = inventory.getStackInSlot(i);
            if (stack.is(Items.STONE)) count += stack.getCount();
            else helper.assertTrue(stack.isEmpty(), "Unexpected non-stone Basin output " + stack);
        }
        // Count real ejected items as well as inventory so a second result cannot hide on the floor.
        for (var entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(BASIN)).inflate(2))) {
            var stack = entity.getItem();
            if (stack.is(Items.STONE)) count += stack.getCount();
            else helper.assertTrue(!stack.is(Items.COBBLESTONE), "Cobble escaped the one-item transaction");
        }
        return count;
    }

    private static BasinBatchRecipe loadedRecipe(GameTestHelper helper) {
        var raw = helper.getLevel().getRecipeManager().byKey(RECIPE).orElse(null);
        helper.assertTrue(raw instanceof BasinBatchRecipe,
                "Fixture-only JSON is not LOADED in RecipeManager: " + RECIPE);
        var recipe = (BasinBatchRecipe) raw;
        var expected = new BasinBatchRecipe(RECIPE, Items.COBBLESTONE, 1,
                new ItemStack(Items.STONE), 10f, 24d);
        helper.assertTrue(recipe.getId().equals(RECIPE)
                        && recipe.getType() == ElectrochemistryRecipes.BASIN_BATCH.get()
                        && recipe.getSerializer() == ElectrochemistryRecipes.BASIN_BATCH_SERIALIZER.get()
                        && recipe.inputItem() == Items.COBBLESTONE && recipe.inputCount() == 1
                        && recipe.output().is(Items.STONE) && recipe.output().getCount() == 1
                        && recipe.minVoltage() == 10f && recipe.workJoules() == 24d
                        && recipe.fingerprint().equals(expected.fingerprint()),
                "Loaded item batch recipe fields/fingerprint mismatch: " + recipe.getId());
        LOG.info("native-batch-fixture stage=recipe-loaded id={} fingerprint={} minVolts={} workJoules={}",
                recipe.getId(), recipe.fingerprint(), recipe.minVoltage(), recipe.workJoules());
        return recipe;
    }

    private static void click(GameTestHelper helper, FakePlayer player, BlockPos relative,
                              double x, double y, double z, Direction face) {
        var absolute = helper.absolutePos(relative);
        var hit = Vec3.atLowerCornerOf(absolute).add(x, y, z);
        var result = player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, absolute, false)));
        helper.assertTrue(result.consumesAction(), "Real PowerGrid light-wire click failed: " + result);
    }

    private static void connect(GameTestHelper helper, FakePlayer player, int terminal) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModdedItems.WIRE.get(), 64));
        var stack = player.getMainHandItem();
        click(helper, player, SOURCE, .5, .9, terminal == 0 ? .33 : .67, Direction.UP);
        helper.assertTrue(stack.hasTag() && stack.getTag().contains("Connection"),
                "Native first wire click did not record source endpoint");
        click(helper, player, BASIN, terminal == 0 ? .05 : .95, .45, .5,
                terminal == 0 ? Direction.WEST : Direction.EAST);
        helper.assertTrue(!stack.hasTag() || !stack.getTag().contains("Connection"),
                "Native second wire click did not complete Basin connection");
    }

    private static void rig(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded("create") && ModList.get().isLoaded("powergrid"),
                "Create and native PowerGrid must both load");
        loadedRecipe(helper);
        helper.setBlock(SOURCE, ModdedBlocks.CREATIVE_VOLTAGE_SOURCE.getDefaultState()
                .setValue(CreativeSourceBlock.HORIZONTAL_AXIS, Direction.Axis.Z));
        helper.setBlock(BASIN.below(), Blocks.BEDROCK.defaultBlockState());
        helper.setBlock(BASIN, AllBlocks.BASIN.getDefaultState());
        helper.assertTrue(basin(helper) instanceof BasinPowerPort && basin(helper) instanceof BasinBatchPort,
                "Ordinary Create Basin did not acquire both native electrical and batch mixins");
        var player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "batch_light_wires"));
        source(helper).setValue(24f);
        for (int i = 0; i < 2; i++) {
            helper.assertTrue(new BlockWireEndpoint(helper.absolutePos(SOURCE), i).isValid(helper.getLevel())
                            && new BlockWireEndpoint(helper.absolutePos(BASIN), i).isValid(helper.getLevel()),
                    "Real source/Basin PowerGrid terminal " + i + " invalid");
            connect(helper, player, i);
        }
    }

    private static int wireCount(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(HangingWireEntity.class,
                new AABB(helper.absolutePos(SOURCE)).minmax(new AABB(helper.absolutePos(BASIN))).inflate(3)).size();
    }

    private static void addOneCobble(GameTestHelper helper) {
        helper.assertTrue(basin(helper).getInputInventory()
                .insertItem(0, new ItemStack(Items.COBBLESTONE), false).isEmpty(),
                "Could not insert exactly one cobble into real Create Basin input");
    }

    private static void log(GameTestHelper helper, String stage) {
        var sample = power(helper).electrochemistry$powerSample();
        LOG.info("native-batch-fixture stage={} sourceVolts={} availableVolts={} loadedVolts={} amps={} "
                        + "watts={} loadEnabled={} cables={} wires={} escrow={} workJ={} inputCobble={} outputStone={}",
                stage, source(helper).getValue(), power(helper).electrochemistry$availableVoltage(),
                sample.voltage(), sample.current(), sample.power(), power(helper).electrochemistry$isLoadEnabled(),
                power(helper).electrochemistry$hasBothCables(), wireCount(helper),
                batch(helper).electrochemistry$hasBatch(), batch(helper).electrochemistry$completedWorkJoules(),
                cobbleInput(helper), stoneOutput(helper));
    }

    @GameTest(template = "empty", timeoutTicks = 320)
    public static void loadedItemRecipeIdleLowVoltageThenExactlyOneProductiveBatch(GameTestHelper helper) {
        helper.runAtTickTime(5, () -> rig(helper));
        boolean[] eligible = {false}, escrowSeen = {false}, positiveLoadSeen = {false};
        double[] maxWork = {0}, integratedNativeJ = {0}, peakNativeW = {0};
        int[] stableOutputTicks = {0};
        helper.runAtTickTime(35, () -> {
            var port = power(helper);
            log(helper, "idle-24v");
            helper.assertTrue(wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                            && port.electrochemistry$availableVoltage() > 10
                            && !port.electrochemistry$isLoadEnabled()
                            && port.electrochemistry$powerSample().power() == 0
                            && !batch(helper).electrochemistry$hasBatch() && stoneOutput(helper) == 0,
                    "No-input 24V Basin drew load or reserved phantom work");
            source(helper).setValue(1f);
            addOneCobble(helper);
        });
        helper.runAtTickTime(65, () -> {
            var port = power(helper);
            log(helper, "ineligible-1v");
            helper.assertTrue(wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                            && port.electrochemistry$availableVoltage() > 0
                            && port.electrochemistry$availableVoltage() < 10
                            && !port.electrochemistry$isLoadEnabled()
                            && port.electrochemistry$powerSample().power() == 0
                            && !batch(helper).electrochemistry$hasBatch()
                            && batch(helper).electrochemistry$completedWorkJoules() == 0
                            && cobbleInput(helper) == 1 && stoneOutput(helper) == 0,
                    "Sub-minimum 1V reserved, energized or completed a batch");
            source(helper).setValue(24f);
            eligible[0] = true;
        });
        helper.onEachTick(() -> {
            if (!eligible[0]) return;
            int output = stoneOutput(helper);
            helper.assertTrue(output <= 1, "Duplicated native stone output: " + output);
            var state = batch(helper);
            var sample = power(helper).electrochemistry$powerSample();
            if (state.electrochemistry$hasBatch()) {
                escrowSeen[0] = true;
                helper.assertTrue(cobbleInput(helper) == 0 && output == 0,
                        "Reserved cobble remained available or produced early output");
                double work = state.electrochemistry$completedWorkJoules();
                helper.assertTrue(work >= maxWork[0] && work <= 24,
                        "Batch work regressed or exceeded loaded 24J recipe: " + work);
                maxWork[0] = work;
                // onEachTick observes actual native solved Basin watts once per world tick;
                // do not manufacture energy by manually calling a BE tick or Java simulator.
                if (sample.voltage() >= 10 && sample.current() > 0 && sample.power() > 0) {
                    integratedNativeJ[0] += sample.power() / 20.0;
                    peakNativeW[0] = Math.max(peakNativeW[0], sample.power());
                    positiveLoadSeen[0] = true;
                }
            }
            if (output != 1) return;
            // Solver and GameTest observers can differ by one/two world-tick phases;
            // bound that scheduling skew by two measured peak-power samples, not 24J.
            double skewJ = peakNativeW[0] * (2.0 / 20.0) + .05;
            helper.assertTrue(escrowSeen[0] && positiveLoadSeen[0] && maxWork[0] >= 24
                            && Math.abs(maxWork[0] - Math.min(24, integratedNativeJ[0])) <= skewJ
                            && integratedNativeJ[0] <= 24 + skewJ
                            && !state.electrochemistry$hasBatch() && cobbleInput(helper) == 0,
                    "Stone not fully paid by native watts/tick: workJ=" + maxWork[0]
                            + " integratedNativeJ=" + integratedNativeJ[0] + " skewJ=" + skewJ);
            if (++stableOutputTicks[0] == 30) {
                log(helper, "one-stone-stable");
                LOG.info("native-batch-fixture stage=work-balance completedWorkJ={} integratedNativeJ={} "
                                + "peakNativeWatts={} twoTickToleranceJ={}",
                        maxWork[0], integratedNativeJ[0], peakNativeW[0], skewJ);
                helper.assertTrue(!power(helper).electrochemistry$isLoadEnabled()
                                && power(helper).electrochemistry$powerSample().power() == 0,
                        "Completed batch continued to draw native Basin power");
                helper.succeed();
            }
        });
        helper.runAtTickTime(290, () -> {
            log(helper, "no-progress-tick290"); // Diagnostic only; original failed run stays failed.
            helper.assertTrue(stableOutputTicks[0] >= 30,
                    "24V productive batch did not yield one stable stone by 290 ticks");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 340)
    public static void activeBatchStopsAtOneVoltThenResumesAtTwentyFour(GameTestHelper helper) {
        int[] stage = {0}, pauseTicks = {0}, stableOutputTicks = {0};
        double[] heldWork = {0}, maxWork = {0};
        boolean[] rigReady = {false}, resumedPowerAndWork = {false};
        helper.runAtTickTime(5, () -> {
            rig(helper);
            addOneCobble(helper);
            rigReady[0] = true;
        });
        helper.onEachTick(() -> {
            if (!rigReady[0]) return;
            var state = batch(helper);
            var port = power(helper);
            var sample = port.electrochemistry$powerSample();
            int output = stoneOutput(helper);
            helper.assertTrue(output <= 1, "Duplicated stone during pause/resume: " + output);
            if (stage[0] == 0) {
                double work = state.electrochemistry$completedWorkJoules();
                if (!state.electrochemistry$hasBatch() || work <= 1) return;
                maxWork[0] = work;
                helper.assertTrue(work < 24 && sample.power() > 0 && cobbleInput(helper) == 0,
                        "Could not witness partly paid 24V escrow before lowering source");
                log(helper, "partial-24v");
                source(helper).setValue(1f);
                stage[0] = 1;
                return;
            }
            if (stage[0] == 1) {
                helper.assertTrue(state.electrochemistry$hasBatch() && output == 0,
                        "Dropping source to 1V lost escrow or yielded unpaid output");
                if (port.electrochemistry$isLoadEnabled() || sample.power() != 0
                        || port.electrochemistry$availableVoltage() <= 0
                        || port.electrochemistry$availableVoltage() >= 10) return;
                heldWork[0] = state.electrochemistry$completedWorkJoules();
                helper.assertTrue(heldWork[0] > 0 && heldWork[0] < 24,
                        "Low-voltage transition failed to preserve partial work");
                log(helper, "paused-1v");
                stage[0] = 2;
                return;
            }
            if (stage[0] == 2) {
                helper.assertTrue(wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                                && !port.electrochemistry$isLoadEnabled() && sample.power() == 0
                                && state.electrochemistry$hasBatch() && cobbleInput(helper) == 0
                                && output == 0 && Math.abs(state.electrochemistry$completedWorkJoules() - heldWork[0]) < 1e-6,
                        "Sustained 1V outage spent Joules/work or lost reserved cobble");
                if (++pauseTicks[0] == 50) {
                    log(helper, "paused-1v-stable");
                    source(helper).setValue(24f);
                    stage[0] = 3;
                } // 50 normal world ticks, then restore voltage.
                return;
            }
            if (stage[0] == 3 && output == 0) {
                maxWork[0] = Math.max(maxWork[0], state.electrochemistry$completedWorkJoules());
                if (sample.voltage() >= 10 && sample.power() > 0
                        && state.electrochemistry$completedWorkJoules() > heldWork[0])
                    resumedPowerAndWork[0] = true;
                helper.assertTrue(state.electrochemistry$hasBatch() && cobbleInput(helper) == 0,
                        "Escrow vanished before resumed batch output");
                return;
            }
            helper.assertTrue(output == 1 && resumedPowerAndWork[0] && maxWork[0] >= 24
                            && !state.electrochemistry$hasBatch() && cobbleInput(helper) == 0,
                    "24V restore did not complete exactly one fully paid 24J batch");
            if (++stableOutputTicks[0] == 25) {
                log(helper, "resumed-one-stone-stable");
                helper.assertTrue(!port.electrochemistry$isLoadEnabled() && sample.power() == 0,
                        "Finished resumed batch kept the native switch ON");
                helper.succeed();
            }
        });
        helper.runAtTickTime(110, () -> {
            log(helper, "no-progress-tick110"); // Diagnostic only; no manual tick or bypass.
            helper.assertTrue(stage[0] >= 2,
                    "24→1V transition did not reach switched-OFF native pause");
        });
        helper.runAtTickTime(310, () -> helper.assertTrue(stableOutputTicks[0] >= 25,
                "24→1→24V escrow batch failed to complete one stable stone"));
    }

    @GameTest(template = "empty", timeoutTicks = 460)
    public static void naturalTenVoltBrownoutStopsProbesThenRecoversAtTwentyFour(GameTestHelper helper) {
        // This is NOT the separate 24->1V outage fixture. Do not setLoadEnabled here:
        // only recipe-owned native circuit switching can establish a brownout latch.
        boolean[] inputReady = {false}, reservationSeen = {false},
                precedingOnTenVolts = {false}, staleCreditOnEcho = {false},
                sawNaturalDroop = {false}, resumedPaidWork = {false};
        int[] phase = {0}, insertionTicks = {0}, traceTicks = {0}, initialPositiveTicks = {0},
                steadyTicks = {0}, stableOutputTicks = {0};
        double[] initialNativeJ = {0}, initialPeakW = {0}, maxInitialWorkJ = {0},
                precedingOnVolts = {0}, precedingOnAmps = {0}, precedingOnWatts = {0},
                precedingOnWireMaxAmps = {0}, precedingOnWorkJ = {0},
                settledWorkJ = {0}, steadyNativeJ = {0}, steadyPeakW = {0},
                maxResidualWireA = {0}, maxRecoveredWork = {0};
        helper.runAtTickTime(5, () -> {
            rig(helper);
            source(helper).setValue(10f); // Exact native source control, not a simulated FE reading.
        });
        helper.runAtTickTime(35, () -> {
            log(helper, "brownout-open-10v");
            var port = power(helper);
            helper.assertTrue(source(helper).getValue() == 10f
                            && wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                            && port.electrochemistry$availableVoltage() >= 10f
                            && Math.abs(port.electrochemistry$availableVoltage() - 10f) <= .01f
                            && !port.electrochemistry$isLoadEnabled()
                            && port.electrochemistry$powerSample().power() == 0,
                    "INCONCLUSIVE setup: native two-wire OFF-circuit voltage did not reach min10V");
            addOneCobble(helper);
            inputReady[0] = true;
        });
        helper.onEachTick(() -> {
            if (!inputReady[0]) return;
            var port = power(helper);
            var state = batch(helper);
            var sample = port.electrochemistry$powerSample();
            int output = stoneOutput(helper);
            helper.assertTrue(output <= 1, "Brownout duplicated stone output: " + output);
            if (phase[0] == 0) {
                helper.assertTrue(output == 0, "Exactly10V brownout produced stone during startup probe");
                double workJ = state.electrochemistry$completedWorkJoules();
                maxInitialWorkJ[0] = Math.max(maxInitialWorkJ[0], workJ);
                if (sample.power() > 0) {
                    if (sample.voltage() < 10f) sawNaturalDroop[0] = true;
                    initialPositiveTicks[0]++;
                    initialNativeJ[0] += sample.power() / 20.0;
                    initialPeakW[0] = Math.max(initialPeakW[0], sample.power());
                }
                // Basin.tick can detect the solved <10V load and switch OFF before
                // GameTest's observer runs. Only accept its immediate post-open node
                // voltage as a loaded-solver echo when BOTH physical wires still
                // carry load-sized current, following a real ON 10V/positive-W tick.
                if (precedingOnTenVolts[0] && !port.electrochemistry$isLoadEnabled()) {
                    double available = port.electrochemistry$availableVoltage();
                    double[] wireAmps = helper.getLevel().getEntitiesOfClass(HangingWireEntity.class,
                            new AABB(helper.absolutePos(SOURCE))
                                    .minmax(new AABB(helper.absolutePos(BASIN))).inflate(3))
                            .stream().mapToDouble(wire -> Math.abs(wire.current())).sorted().toArray();
                    if (wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                            && state.electrochemistry$hasBatch()
                            && available > 0 && available < 10f
                            && wireAmps.length == 2 && wireAmps[0] > .05 && wireAmps[1] > .05) {
                        boolean zeroWork = precedingOnWorkJ[0] == 0 && workJ == 0;
                        if (zeroWork) sawNaturalDroop[0] = true;
                        else staleCreditOnEcho[0] = true;
                        LOG.info("native-batch-fixture stage=brownout-solved-loaded-after-switch-off "
                                        + "precedingOnVolts={} precedingOnAmps={} precedingOnWatts={} "
                                        + "precedingWireMaxAmps={} precedingWorkJ={} "
                                        + "postOpenNodeVolts={} wireAbsAmps={} escrow={} workJ={} "
                                        + "initialNativeJ={} validZeroWorkDroop={}",
                                precedingOnVolts[0], precedingOnAmps[0], precedingOnWatts[0],
                                precedingOnWireMaxAmps[0], precedingOnWorkJ[0],
                                available, java.util.Arrays.toString(wireAmps),
                                state.electrochemistry$hasBatch(), workJ, initialNativeJ[0], zeroWork);
                    }
                }
                boolean candidateOnTen = port.electrochemistry$isLoadEnabled()
                        && sample.power() > 0 && sample.voltage() >= 10f && sample.voltage() <= 10.01f
                        && state.electrochemistry$hasBatch() && port.electrochemistry$hasBothCables()
                        && wireCount(helper) == 2;
                precedingOnTenVolts[0] = false;
                if (candidateOnTen) {
                    double[] onWireAmps = helper.getLevel().getEntitiesOfClass(HangingWireEntity.class,
                            new AABB(helper.absolutePos(SOURCE))
                                    .minmax(new AABB(helper.absolutePos(BASIN))).inflate(3))
                            .stream().mapToDouble(wire -> Math.abs(wire.current())).sorted().toArray();
                    if (onWireAmps.length == 2 && onWireAmps[0] < .0005 && onWireAmps[1] < .0005) {
                        precedingOnTenVolts[0] = true;
                        precedingOnVolts[0] = sample.voltage();
                        precedingOnAmps[0] = sample.current();
                        precedingOnWatts[0] = sample.power();
                        precedingOnWireMaxAmps[0] = onWireAmps[1];
                        precedingOnWorkJ[0] = workJ;
                    }
                }
                ++insertionTicks[0];
                // Normal Create inventory changes may only reach the candidate's 20-tick
                // fallback scan; insertion ticks are NOT circuit startup ticks.
                if (state.electrochemistry$hasBatch() || port.electrochemistry$isLoadEnabled())
                    reservationSeen[0] = true;
                if (reservationSeen[0] && ++traceTicks[0] <= 10) {
                    // First ON sample can pair the previous OFF solution (10V) with
                    // new ON conductance; trace it, not proof of loaded eligibility.
                    var currents = helper.getLevel().getEntitiesOfClass(HangingWireEntity.class,
                            new AABB(helper.absolutePos(SOURCE))
                                    .minmax(new AABB(helper.absolutePos(BASIN))).inflate(3))
                            .stream().map(wire -> wire.current()).toList();
                    LOG.info("native-batch-fixture stage=brownout-startup tick={} sinceInput={} "
                                    + "sourceVolts={} availableVolts={} loadedVolts={} loadedAmps={} "
                                    + "loadedWatts={} loadOn={} wireAmps={} wireCount={} escrow={} "
                                    + "inputCobble={} outputStone={} workJ={} initialNativeJ={} sawNaturalDroop={}",
                            traceTicks[0], insertionTicks[0], source(helper).getValue(),
                            port.electrochemistry$availableVoltage(), sample.voltage(),
                            sample.current(), sample.power(), port.electrochemistry$isLoadEnabled(),
                            currents, wireCount(helper), state.electrochemistry$hasBatch(),
                            cobbleInput(helper), output, workJ, initialNativeJ[0], sawNaturalDroop[0]);
                } else if (!reservationSeen[0]
                        && (insertionTicks[0] <= 2 || insertionTicks[0] % 10 == 0)) {
                    LOG.info("native-batch-fixture stage=brownout-wait-reservation sinceInput={} "
                                    + "availableVolts={} inputCobble={} escrow={} loadOn={} workJ={}",
                            insertionTicks[0], port.electrochemistry$availableVoltage(),
                            cobbleInput(helper), state.electrochemistry$hasBatch(),
                            port.electrochemistry$isLoadEnabled(), workJ);
                }
                return;
            }
            if (phase[0] == 1) {
                double wireA = helper.getLevel().getEntitiesOfClass(HangingWireEntity.class,
                        new AABB(helper.absolutePos(SOURCE))
                                .minmax(new AABB(helper.absolutePos(BASIN))).inflate(3))
                        .stream().mapToDouble(wire -> Math.abs(wire.current())).max().orElse(Double.NaN);
                maxResidualWireA[0] = Math.max(maxResidualWireA[0], wireA);
                if (sample.power() > 0) {
                    steadyNativeJ[0] += sample.power() / 20.0;
                    steadyPeakW[0] = Math.max(steadyPeakW[0], sample.power());
                }
                helper.assertTrue(wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                                && !port.electrochemistry$isLoadEnabled() && sample.power() == 0
                                && Double.isFinite(wireA) && wireA < .0005
                                && state.electrochemistry$hasBatch()
                                && Math.abs(state.electrochemistry$completedWorkJoules() - settledWorkJ[0]) < 1e-6
                                && cobbleInput(helper) == 0 && output == 0,
                        "Exactly10V repeated native drain/work or lost escrow after bounded initial probe: "
                                + sample + ", wireA=" + wireA);
                if (++steadyTicks[0] == 120) {
                    log(helper, "brownout-10v-held-120ticks");
                    LOG.info("native-batch-fixture stage=brownout-balance initialProbeTicks={} initialNativeJ={} "
                                    + "initialPeakWatts={} maxStartupWorkJ={} settledWorkJ={} "
                                    + "steadyNativeJ={} steadyPeakWatts={} maxResidualWireAmps={}",
                            initialPositiveTicks[0], initialNativeJ[0], initialPeakW[0],
                            maxInitialWorkJ[0], settledWorkJ[0], steadyNativeJ[0],
                            steadyPeakW[0], maxResidualWireA[0]);
                    source(helper).setValue(24f);
                    phase[0] = 2;
                }
                return;
            }
            if (output == 0) {
                maxRecoveredWork[0] = Math.max(maxRecoveredWork[0], state.electrochemistry$completedWorkJoules());
                if (sample.voltage() >= 10 && sample.current() > 0 && sample.power() > 0
                        && state.electrochemistry$completedWorkJoules() > settledWorkJ[0])
                    resumedPaidWork[0] = true;
                helper.assertTrue(state.electrochemistry$hasBatch() && cobbleInput(helper) == 0,
                        "24V recovery lost the brownout escrow before producing one stone");
                return;
            }
            helper.assertTrue(output == 1 && resumedPaidWork[0] && maxRecoveredWork[0] >= 24
                            && !state.electrochemistry$hasBatch() && cobbleInput(helper) == 0,
                    "24V recovery failed to produce one fully paid stone");
            if (++stableOutputTicks[0] == 25) {
                log(helper, "brownout-recovered-one-stone");
                helper.assertTrue(!port.electrochemistry$isLoadEnabled() && sample.power() == 0,
                        "Recovered finished batch continued drawing native power");
                helper.assertTrue(maxInitialWorkJ[0] == 0,
                        "STALE_10V_WORK: before settled native loaded<10V, escrow was credited "
                                + maxInitialWorkJ[0] + "J; initial native=" + initialNativeJ[0]
                                + "J peak=" + initialPeakW[0] + "W; steady 120 ticks="
                                + steadyNativeJ[0] + "J; 24V recovery produced exactly one stone");
                helper.succeed();
            }
        });
        helper.runAtTickTime(105, () -> {
            var port = power(helper);
            if (reservationSeen[0]) return;
            String state = "inputCobble=" + cobbleInput(helper) + " availableVolts="
                    + port.electrochemistry$availableVoltage() + " wires=" + wireCount(helper)
                    + " escrow=" + batch(helper).electrochemistry$hasBatch();
            helper.assertTrue(cobbleInput(helper) != 1
                            || port.electrochemistry$availableVoltage() < 10f,
                    "CANDIDATE_ELIGIBILITY_FAIL: no native escrow after 70 insertion ticks at min10V: " + state);
            helper.assertTrue(false, "INCONCLUSIVE setup: no native escrow by tick105: " + state);
        });
        helper.runAtTickTime(115, () -> {
            helper.assertTrue(!staleCreditOnEcho[0],
                    "STALE_10V_WORK: solved sub-minimum load echoed after switch-off but startup "
                            + "credited work=" + maxInitialWorkJ[0] + "J native=" + initialNativeJ[0] + "J");
            helper.assertTrue(sawNaturalDroop[0],
                    "INCONCLUSIVE setup: no natural ON sample <10V or matched just-opened "
                            + "loaded-solver echo by tick115; post-escrow traceTicks=" + traceTicks[0]
                            + " nativeJ=" + initialNativeJ[0] + " maxWorkJ=" + maxInitialWorkJ[0]);
        });
        helper.runAtTickTime(135, () -> {
            log(helper, "brownout-after-natural-probe");
            helper.assertTrue(sawNaturalDroop[0],
                    "INCONCLUSIVE setup: no natural closed-load sample established <10V droop");
            double transientBoundJ = initialPeakW[0] * 2 / 20.0 + .005;
            helper.assertTrue(initialNativeJ[0] <= transientBoundJ,
                    "Exactly10V native source drew more than two peak watt-ticks: ticks="
                            + initialPositiveTicks[0] + " nativeJ=" + initialNativeJ[0]
                            + " peakW=" + initialPeakW[0] + " boundJ=" + transientBoundJ);
            settledWorkJ[0] = batch(helper).electrochemistry$completedWorkJoules();
            helper.assertTrue(batch(helper).electrochemistry$hasBatch()
                            && !power(helper).electrochemistry$isLoadEnabled()
                            && power(helper).electrochemistry$powerSample().power() == 0,
                    "Exactly10V native source did not settle OFF with cobble held in escrow");
            phase[0] = 1; // Start 120 normal world ticks AFTER initial probe settling.
        });
        helper.runAtTickTime(420, () -> helper.assertTrue(stableOutputTicks[0] >= 25,
                "Brownout held escrow but did not resume one paid stone by deadline"));
    }

    @GameTest(template = "empty", timeoutTicks = 650)
    public static void finiteNativeBatteryPaysForExactlyOneItemBatch(GameTestHelper helper) {
        // Native non-FE BATTERY and polarized connector. Test-only 75% charge is
        // not a survival acquisition, and the batch alone owns load switching.
        BlockPos batteryRelative = SOURCE.west();
        BlockPos batteryPos = helper.absolutePos(batteryRelative);
        BlockPos connectorPos = helper.absolutePos(SOURCE);
        BlockPos basinPos = helper.absolutePos(BASIN);
        boolean[] observing = {false}, escrowSeen = {false}, productiveSeen = {false},
                conductingWiresSeen = {false};
        int[] stableOutputTicks = {0};
        double[] chargedJ = {0}, startingJ = {0}, maxPaidWorkJ = {0},
                basinNativeJ = {0}, wireLossJ = {0}, peakBasinW = {0},
                maxWireAmps = {0};
        helper.runAtTickTime(5, () -> {
            helper.assertTrue(ModList.get().isLoaded("powergrid") && ModList.get().isLoaded("create"),
                    "Native PowerGrid and Create must both load for finite-source recipe work");
            loadedRecipe(helper); // Fourth exact RecipeManager fingerprint for fail-closed count4 gate.
            helper.setBlock(batteryRelative, ModdedBlocks.BATTERY.getDefaultState());
            helper.setBlock(SOURCE, ModdedBlocks.DEVICE_CONNECTOR.getDefaultState()
                    .setValue(DeviceConnectorBlock.FACING, Direction.WEST)
                    .setValue(DeviceConnectorBlock.POLARIZED, true)
                    .setValue(DeviceConnectorBlock.ROTATION, 1));
            helper.setBlock(BASIN.below(), Blocks.BEDROCK.defaultBlockState());
            helper.setBlock(BASIN, AllBlocks.BASIN.getDefaultState());
            helper.assertTrue(basin(helper) instanceof BasinPowerPort && basin(helper) instanceof BasinBatchPort,
                    "Ordinary Create Basin lacks native PowerGrid/recipe ports");
            var battery = (MultiBlockBatteryEntity) helper.getLevel().getBlockEntity(batteryPos);
            var connector = (DeviceConnectorBlockEntity) helper.getLevel().getBlockEntity(connectorPos);
            helper.assertTrue(battery != null && battery.isController() && battery.getWidth() == 1
                            && battery.getHeight() == 1 && battery.getCapacity() > 0,
                    "Normal one-block PowerGrid BATTERY is not an initialized 1x1 controller");
            helper.assertTrue(connector != null && connector.getBlockState().getBlock() instanceof DeviceConnectorBlock
                            && ((DeviceConnectorBlock) connector.getBlockState().getBlock()).canSurvive(
                            connector.getBlockState(), helper.getLevel(), connectorPos),
                    "Native polarized connector cannot survive on BATTERY face");
            var bridge = (BridgeElectricBehaviour) connector.getBehaviour(ElectricBehaviour.TYPE);
            helper.assertTrue(bridge != null && bridge.getMainBehaviour().isPresent() && !bridge.isFE(),
                    "Connector is not backed by a native non-FE PowerGrid BATTERY behaviour");
            battery.setEnergy(battery.getCapacity() * .75); // Fixture state, not acquisition evidence.
            chargedJ[0] = battery.getEnergy();
            helper.assertTrue(Double.isFinite(chargedJ[0]) && chargedJ[0] > 24,
                    "Finite PowerGrid BATTERY did not hold enough test-only Joules");
            var player = FakePlayerFactory.get(helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "finite_batch_native_wires"));
            var electric = (IElectric) connector.getBlockState().getBlock();
            for (int i = 0; i < 2; i++) {
                var placement = electric.terminal(connector.getBlockState(), i);
                helper.assertTrue(placement != null && electric.terminalIndexAt(
                        connector.getBlockState(), placement.getOrigin()) == i,
                        "Native connector origin cannot select terminal " + i);
                helper.assertTrue(new BlockWireEndpoint(connectorPos, i).isValid(helper.getLevel())
                                && new BlockWireEndpoint(basinPos, i).isValid(helper.getLevel()),
                        "Native connector/Basin physical wire terminal invalid at " + i);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModdedItems.WIRE.get(), 64));
                var stack = player.getMainHandItem();
                var origin = placement.getOrigin();
                click(helper, player, SOURCE, origin.x, origin.y, origin.z, Direction.EAST);
                helper.assertTrue(stack.hasTag() && stack.getTag().contains("Connection"),
                        "First real wire click did not store connector endpoint " + i);
                click(helper, player, BASIN, i == 0 ? .05 : .95, .45, .5,
                        i == 0 ? Direction.WEST : Direction.EAST);
                helper.assertTrue(!stack.hasTag() || !stack.getTag().contains("Connection"),
                        "Second real wire click did not join Basin terminal " + i);
            }
        });
        helper.runAtTickTime(35, () -> {
            var battery = (MultiBlockBatteryEntity) helper.getLevel().getBlockEntity(batteryPos);
            var port = power(helper);
            startingJ[0] = battery.getEnergy(); // Include ONLY post-wiring idle baseline onward.
            helper.assertTrue(wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                            && port.electrochemistry$availableVoltage() >= 10f
                            && !port.electrochemistry$isLoadEnabled()
                            && port.electrochemistry$powerSample().power() == 0
                            && Double.isFinite(startingJ[0]) && startingJ[0] > 24,
                    "Native stored-J source was not idle, charged and above min10V before cobble: "
                            + startingJ[0] + "J availableV=" + port.electrochemistry$availableVoltage());
            LOG.info("native-batch-fixture stage=finite-battery-idle chargedJ={} startingJ={} "
                            + "openVolts={} wires={} inputCobble={}",
                    chargedJ[0], startingJ[0], port.electrochemistry$availableVoltage(),
                    wireCount(helper), cobbleInput(helper));
            addOneCobble(helper);
            observing[0] = true;
        });
        helper.onEachTick(() -> {
            if (!observing[0]) return;
            var battery = (MultiBlockBatteryEntity) helper.getLevel().getBlockEntity(batteryPos);
            helper.assertTrue(battery != null && battery.isController()
                            && Double.isFinite(battery.getEnergy()) && battery.getEnergy() >= 0,
                    "Normal native BATTERY vanished or lost valid stored-J accounting");
            var port = power(helper);
            var state = batch(helper);
            var sample = port.electrochemistry$powerSample();
            int output = stoneOutput(helper);
            helper.assertTrue(output <= 1, "Finite BATTERY recipe produced duplicate stone: " + output);
            if (output == 0) {
                var liveWires = helper.getLevel().getEntitiesOfClass(HangingWireEntity.class,
                        new AABB(connectorPos).minmax(new AABB(basinPos)).inflate(3));
                helper.assertTrue(liveWires.size() == 2 && port.electrochemistry$hasBothCables(),
                        "Finite BATTERY lost one of two native PowerGrid wire entities");
                boolean bothConducting = true;
                for (var wire : liveWires) {
                    var nativeWire = wire.getWire();
                    helper.assertTrue(nativeWire != null && Double.isFinite(nativeWire.getResistance())
                                    && nativeWire.getResistance() > 0
                                    && Float.isFinite(wire.current()),
                            "Nonphysical native wire current/resistance in BATTERY batch");
                    double amps = Math.abs(wire.current());
                    maxWireAmps[0] = Math.max(maxWireAmps[0], amps);
                    wireLossJ[0] += amps * amps * nativeWire.getResistance() / 20.0;
                    if (amps <= .0001) bothConducting = false;
                }
                if (state.electrochemistry$hasBatch()) {
                    escrowSeen[0] = true;
                    helper.assertTrue(cobbleInput(helper) == 0,
                            "Finite BATTERY escrow did not reserve exactly one cobble");
                    double work = state.electrochemistry$completedWorkJoules();
                    helper.assertTrue(work >= maxPaidWorkJ[0] && work <= 24,
                            "Finite BATTERY useful work regressed or exceeded recipe24J: " + work);
                    maxPaidWorkJ[0] = work;
                }
                if (sample.power() > 0) {
                    helper.assertTrue(state.electrochemistry$hasBatch()
                                    && sample.voltage() >= 10 && sample.current() > 0,
                            "Finite BATTERY drew Basin power outside eligible reserved batch: " + sample);
                    productiveSeen[0] = true;
                    if (bothConducting) conductingWiresSeen[0] = true;
                    basinNativeJ[0] += sample.power() / 20.0;
                    peakBasinW[0] = Math.max(peakBasinW[0], sample.power());
                }
                return;
            }
            helper.assertTrue(!port.electrochemistry$isLoadEnabled() && sample.power() == 0,
                    "Completed finite BATTERY batch drew native Basin watts during stable output");
            double endJ = battery.getEnergy();
            double sourceDeltaJ = startingJ[0] - endJ;
            double skewJ = 2 * peakBasinW[0] / 20.0 + .05;
            helper.assertTrue(escrowSeen[0] && productiveSeen[0] && conductingWiresSeen[0]
                            && maxPaidWorkJ[0] >= 24
                            && basinNativeJ[0] >= 24 - skewJ && basinNativeJ[0] <= 24 + skewJ
                            && sourceDeltaJ > 0 && sourceDeltaJ < startingJ[0]
                            && sourceDeltaJ + skewJ >= 24 + wireLossJ[0]
                            && sourceDeltaJ + skewJ >= basinNativeJ[0] + wireLossJ[0]
                            && !state.electrochemistry$hasBatch() && cobbleInput(helper) == 0,
                    "Stored native BATTERY Joules did not pay for one fixture batch: start="
                            + startingJ[0] + " end=" + endJ + " delta=" + sourceDeltaJ
                            + " BasinNative=" + basinNativeJ[0] + " wiresI2R=" + wireLossJ[0]
                            + " work=" + maxPaidWorkJ[0] + " peakW=" + peakBasinW[0]
                            + " twoPeakTickSkew=" + skewJ);
            if (++stableOutputTicks[0] == 25) {
                helper.assertTrue(!port.electrochemistry$isLoadEnabled() && sample.power() == 0,
                        "Completed finite BATTERY batch kept drawing native Basin watts");
                LOG.info("native-batch-fixture stage=finite-battery-paid chargedJ={} startingJ={} "
                                + "endingJ={} sourceDeltaJ={} completedWorkJ={} BasinNativeJ={} "
                                + "realWireI2RLossJ={} peakBasinWatts={} maxRealWireAmps={} "
                                + "twoPeakTickSkewJ={} surplusInternalJ={} outputStone={}",
                        chargedJ[0], startingJ[0], endJ, sourceDeltaJ, maxPaidWorkJ[0],
                        basinNativeJ[0], wireLossJ[0], peakBasinW[0], maxWireAmps[0],
                        skewJ, sourceDeltaJ - basinNativeJ[0] - wireLossJ[0], output);
                helper.succeed();
            }
        });
        helper.runAtTickTime(600, () -> helper.assertTrue(stableOutputTicks[0] >= 25,
                "Normal native BATTERY did not fund one stable recipe output by tick600: work="
                        + maxPaidWorkJ[0] + " BasinNative=" + basinNativeJ[0]
                        + " sourceDelta=" + (startingJ[0] - ((MultiBlockBatteryEntity)
                        helper.getLevel().getBlockEntity(batteryPos)).getEnergy())));
    }

    private static int ordinaryResultCount(GameTestHelper helper, Item expected) {
        int count = 0;
        var inventory = basin(helper).getOutputInventory();
        for (int i = 0; i < inventory.getSlots(); i++) {
            var stack = inventory.getStackInSlot(i);
            if (stack.is(expected)) count += stack.getCount();
            else helper.assertTrue(stack.isEmpty(), "Ordinary operator made an unexpected output: " + stack);
        }
        for (var entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(BASIN)).inflate(2))) {
            var stack = entity.getItem();
            if (stack.is(expected)) count += stack.getCount();
            else helper.assertTrue(!stack.is(Items.STONE) && !stack.is(Items.COBBLESTONE),
                    "Electrical stone or unprocessed cobble escaped ordinary operator: " + stack);
        }
        return count;
    }

    @GameTest(template = "empty", timeoutTicks = 420)
    public static void realMixerOwnsOneVoltCycleAcrossTwentyFourVoltRise(GameTestHelper helper) {
        boolean[] inputReady = {false}, runningAtOneVolt = {false},
                highVoltsDuringRunning = {false};
        int[] stableOutputTicks = {0};
        BlockPos operator = BASIN.above(2);
        BlockPos cog = operator.east();
        BlockPos motor = cog.below();
        helper.runAtTickTime(5, () -> {
            rig(helper); // Exactly one electrical RecipeManager fingerprint receipt for count6 gate.
            var raw = helper.getLevel().getRecipeManager().byKey(new ResourceLocation(
                    "better_electrochemistry", "fixture_mixing_cobble_to_gravel")).orElse(null);
            helper.assertTrue(raw instanceof MixingRecipe && raw.getType() == AllRecipeTypes.MIXING.getType(),
                    "Fixture-only ordinary Create MIXING recipe not loaded with native type");
            var recipe = (MixingRecipe) raw;
            helper.assertTrue(recipe.getIngredients().size() == 1
                            && recipe.getIngredients().get(0).test(new ItemStack(Items.COBBLESTONE))
                            && recipe.getRollableResults().size() == 1
                            && recipe.getRollableResults().get(0).getStack().is(Items.GRAVEL)
                            && recipe.getRollableResults().get(0).getStack().getCount() == 1,
                    "Loaded Create mixer recipe does not consume one cobble for one gravel");
            LOG.info("native-batch-fixture stage=ordinary-mixing-recipe-loaded id={} type={} result={}",
                    recipe.getId(), recipe.getType(), recipe.getRollableResults().get(0).getStack());
            source(helper).setValue(1f);
            helper.setBlock(operator, AllBlocks.MECHANICAL_MIXER.getDefaultState());
            helper.setBlock(cog, AllBlocks.COGWHEEL.getDefaultState()
                    .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
            helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(CreativeMotorBlock.FACING, Direction.UP));
        });
        helper.runAtTickTime(10, () -> {
            var realMotor = (CreativeMotorBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(motor));
            helper.assertTrue(realMotor != null, "Actual Mixer motor BE did not initialize");
            realMotor.generatedSpeed.setValue(64); // Native source control, not downstream speed flag.
        });
        helper.runAtTickTime(35, () -> {
            var mixer = (MechanicalMixerBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(operator));
            var port = power(helper);
            helper.assertTrue(mixer != null && mixer.getSpeed() != 0 && mixer.hasSource(),
                    "SETUP FAIL: placed motor/cog did not actually power ordinary Mixer");
            helper.assertTrue(wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                            && port.electrochemistry$availableVoltage() > 0
                            && port.electrochemistry$availableVoltage() < 10
                            && !port.electrochemistry$isLoadEnabled() && port.electrochemistry$powerSample().power() == 0,
                    "SETUP FAIL: real PowerGrid source/wires not settled at 1V/OFF before Mixer input");
            addOneCobble(helper);
            inputReady[0] = true;
        });
        helper.onEachTick(() -> {
            if (!inputReady[0]) return;
            var mixer = (MechanicalMixerBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(operator));
            var port = power(helper);
            var state = batch(helper);
            int gravel = ordinaryResultCount(helper, Items.GRAVEL);
            helper.assertTrue(gravel <= 1 && mixer.getSpeed() != 0 && mixer.hasSource()
                            && wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                            && !state.electrochemistry$hasBatch()
                            && state.electrochemistry$completedWorkJoules() == 0
                            && !port.electrochemistry$isLoadEnabled()
                            && port.electrochemistry$powerSample().power() == 0,
                    "Ordinary Mixer lost kinetic drive, or electrical batch reserved/drew/duplicated");
            if (!runningAtOneVolt[0]) {
                helper.assertTrue(source(helper).getValue() == 1f
                                && port.electrochemistry$availableVoltage() > 0
                                && port.electrochemistry$availableVoltage() < 10 && gravel == 0,
                        "SETUP FAIL: Mixer did not begin with eligible input under real 1V exclusion");
                if (!mixer.running) return;
                helper.assertTrue(cobbleInput(helper) == 1,
                        "SETUP FAIL: Mixer running flag did not correspond to held input cobble");
                runningAtOneVolt[0] = true;
                LOG.info("native-batch-fixture stage=mixer-running-at-1v speed={} hasSource={} "
                                + "running={} availableV={} inputCobble={} escrow={} electricalW={}",
                        mixer.getSpeed(), mixer.hasSource(), mixer.running,
                        port.electrochemistry$availableVoltage(), cobbleInput(helper),
                        state.electrochemistry$hasBatch(), port.electrochemistry$powerSample().power());
                source(helper).setValue(24f); // Mid-cycle rise; no manual operator/load state mutation.
                return;
            }
            if (mixer.running && port.electrochemistry$availableVoltage() >= 10)
                highVoltsDuringRunning[0] = true;
            if (gravel == 0) return;
            helper.assertTrue(highVoltsDuringRunning[0] && gravel == 1 && cobbleInput(helper) == 0,
                    "Mixer failed one real 1→24V in-flight ordinary cycle without electrical escrow");
            if (++stableOutputTicks[0] == 25) {
                helper.assertTrue(!mixer.running,
                        "Mixer still running after 25 stable ticks with one gravel output");
                LOG.info("native-batch-fixture stage=mixer-inflight-finished gravel={} stone=0 "
                                + "runningAtOneV={} highVWhileRunning={} speed={} escrow={} workJ={}",
                        gravel, runningAtOneVolt[0], highVoltsDuringRunning[0],
                        mixer.getSpeed(), state.electrochemistry$hasBatch(),
                        state.electrochemistry$completedWorkJoules());
                helper.succeed();
            }
        });
        helper.runAtTickTime(160, () -> helper.assertTrue(runningAtOneVolt[0],
                "SETUP FAIL: powered ordinary Mixer never naturally entered running state at 1V"));
        helper.runAtTickTime(380, () -> helper.assertTrue(stableOutputTicks[0] >= 25,
                "Powered Mixer did not finish exactly one gravel after 24V in-flight rise"));
    }

    @GameTest(template = "empty", timeoutTicks = 420)
    public static void realPressOwnsOneVoltCycleAcrossTwentyFourVoltRise(GameTestHelper helper) {
        boolean[] inputReady = {false}, runningAtOneVolt = {false},
                highVoltsDuringRunning = {false};
        int[] stableOutputTicks = {0};
        BlockPos operator = BASIN.above(2);
        BlockPos motor = operator.east();
        helper.runAtTickTime(5, () -> {
            rig(helper); // Exactly one electrical RecipeManager fingerprint receipt for count6 gate.
            var raw = helper.getLevel().getRecipeManager().byKey(new ResourceLocation(
                    "better_electrochemistry", "fixture_compacting_cobble_to_sand")).orElse(null);
            helper.assertTrue(raw instanceof CompactingRecipe && raw.getType() == AllRecipeTypes.COMPACTING.getType(),
                    "Fixture-only ordinary Create COMPACTING recipe not loaded with native type");
            var recipe = (CompactingRecipe) raw;
            helper.assertTrue(recipe.getIngredients().size() == 1
                            && recipe.getIngredients().get(0).test(new ItemStack(Items.COBBLESTONE))
                            && recipe.getRollableResults().size() == 1
                            && recipe.getRollableResults().get(0).getStack().is(Items.SAND)
                            && recipe.getRollableResults().get(0).getStack().getCount() == 1,
                    "Loaded Create Press recipe does not consume one cobble for one sand");
            LOG.info("native-batch-fixture stage=ordinary-compacting-recipe-loaded id={} type={} result={}",
                    recipe.getId(), recipe.getType(), recipe.getRollableResults().get(0).getStack());
            source(helper).setValue(1f);
            helper.setBlock(operator, AllBlocks.MECHANICAL_PRESS.getDefaultState()
                    .setValue(MechanicalPressBlock.HORIZONTAL_FACING, Direction.EAST));
            helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(CreativeMotorBlock.FACING, Direction.WEST));
        });
        helper.runAtTickTime(10, () -> {
            var realMotor = (CreativeMotorBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(motor));
            helper.assertTrue(realMotor != null, "Actual Press motor BE did not initialize");
            realMotor.generatedSpeed.setValue(64); // Native source control, not downstream speed flag.
        });
        helper.runAtTickTime(35, () -> {
            var press = (MechanicalPressBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(operator));
            var port = power(helper);
            helper.assertTrue(press != null && press.getSpeed() != 0 && press.hasSource()
                            && press.getPressingBehaviour() != null,
                    "SETUP FAIL: placed motor did not actually power ordinary Press/behaviour");
            helper.assertTrue(wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                            && port.electrochemistry$availableVoltage() > 0
                            && port.electrochemistry$availableVoltage() < 10
                            && !port.electrochemistry$isLoadEnabled() && port.electrochemistry$powerSample().power() == 0,
                    "SETUP FAIL: real PowerGrid source/wires not settled at 1V/OFF before Press input");
            addOneCobble(helper);
            inputReady[0] = true;
        });
        helper.onEachTick(() -> {
            if (!inputReady[0]) return;
            var press = (MechanicalPressBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(operator));
            var behaviour = press.getPressingBehaviour();
            var port = power(helper);
            var state = batch(helper);
            int sand = ordinaryResultCount(helper, Items.SAND);
            helper.assertTrue(sand <= 1 && press.getSpeed() != 0 && press.hasSource()
                            && wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                            && !state.electrochemistry$hasBatch()
                            && state.electrochemistry$completedWorkJoules() == 0
                            && !port.electrochemistry$isLoadEnabled()
                            && port.electrochemistry$powerSample().power() == 0,
                    "Ordinary Press lost kinetic drive, or electrical batch reserved/drew/duplicated");
            if (!runningAtOneVolt[0]) {
                helper.assertTrue(source(helper).getValue() == 1f
                                && port.electrochemistry$availableVoltage() > 0
                                && port.electrochemistry$availableVoltage() < 10 && sand == 0,
                        "SETUP FAIL: Press did not begin with eligible input under real 1V exclusion");
                if (behaviour == null || !behaviour.running) return;
                helper.assertTrue(behaviour.onBasin() && cobbleInput(helper) == 1,
                        "SETUP FAIL: Press running flag was not ordinary Basin compacting of cobble");
                runningAtOneVolt[0] = true;
                LOG.info("native-batch-fixture stage=press-running-at-1v speed={} hasSource={} "
                                + "running={} onBasin={} availableV={} inputCobble={} escrow={} electricalW={}",
                        press.getSpeed(), press.hasSource(), behaviour.running, behaviour.onBasin(),
                        port.electrochemistry$availableVoltage(), cobbleInput(helper),
                        state.electrochemistry$hasBatch(), port.electrochemistry$powerSample().power());
                source(helper).setValue(24f); // Mid-cycle rise; no manual operator/load state mutation.
                return;
            }
            if (behaviour != null && behaviour.running && behaviour.onBasin()
                    && port.electrochemistry$availableVoltage() >= 10)
                highVoltsDuringRunning[0] = true;
            if (sand == 0) return;
            helper.assertTrue(highVoltsDuringRunning[0] && sand == 1 && cobbleInput(helper) == 0,
                    "Press failed one real 1→24V in-flight ordinary cycle without electrical escrow");
            if (++stableOutputTicks[0] == 25) {
                helper.assertTrue(behaviour != null && !behaviour.running,
                        "Press still running after 25 stable ticks with one sand output");
                LOG.info("native-batch-fixture stage=press-inflight-finished sand={} stone=0 "
                                + "runningAtOneV={} highVWhileRunning={} speed={} escrow={} workJ={}",
                        sand, runningAtOneVolt[0], highVoltsDuringRunning[0],
                        press.getSpeed(), state.electrochemistry$hasBatch(),
                        state.electrochemistry$completedWorkJoules());
                helper.succeed();
            }
        });
        helper.runAtTickTime(160, () -> helper.assertTrue(runningAtOneVolt[0],
                "SETUP FAIL: powered ordinary Press never naturally entered on-Basin running state at 1V"));
        helper.runAtTickTime(380, () -> helper.assertTrue(stableOutputTicks[0] >= 25,
                "Powered Press did not finish exactly one sand after 24V in-flight rise"));
    }

    /** Separate from stoneOutput: a full dirt obstruction is intentional here. */
    private static void assertFullDirtOutputNoStone(GameTestHelper helper) {
        var output = basin(helper).getOutputInventory();
        helper.assertTrue(output != null && output.getSlots() > 0,
                "Normal Create Basin has no writable output slots for output-stall fixture");
        for (int i = 0; i < output.getSlots(); i++) {
            var stack = output.getStackInSlot(i);
            int full = Math.min(output.getSlotLimit(i), Items.DIRT.getMaxStackSize());
            helper.assertTrue(full > 0 && stack.is(Items.DIRT) && stack.getCount() == full,
                    "Obstructed Basin output slot " + i + " not full unrelated dirt: " + stack);
        }
        for (var entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(BASIN)).inflate(3))) {
            var stack = entity.getItem();
            helper.assertTrue(!stack.is(Items.STONE) && !stack.is(Items.COBBLESTONE),
                    "Blocked output ejected stone/cobble despite electrical escrow: " + stack);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void fullNativeOutputStallsThenResumesOnePaidBatch(GameTestHelper helper) {
        int[] phase = {0}, settleTicks = {0}, heldTicks = {0}, stableOutputTicks = {0};
        double[] partialWorkJ = {0}, heldWorkJ = {0}, maxPaidWorkJ = {0};
        boolean[] inputReady = {false}, resumedNativePower = {false};
        helper.runAtTickTime(5, () -> {
            rig(helper); // One exact loaded electrical RecipeManager receipt, no new recipe.
            addOneCobble(helper);
            inputReady[0] = true;
        });
        helper.onEachTick(() -> {
            if (!inputReady[0]) return;
            var state = batch(helper);
            var port = power(helper);
            var sample = port.electrochemistry$powerSample();
            if (phase[0] == 0) {
                int stone = stoneOutput(helper); // Legal only before dirt obstruction.
                double workJ = state.electrochemistry$completedWorkJoules();
                if (!state.electrochemistry$hasBatch() || workJ <= 1) return;
                helper.assertTrue(stone == 0 && cobbleInput(helper) == 0
                                && workJ < 24 && sample.voltage() >= 10
                                && sample.current() > 0 && sample.power() > 0,
                        "SETUP FAIL: no real partially paid 24V escrow before output obstruction");
                partialWorkJ[0] = workJ;
                var output = basin(helper).getOutputInventory();
                helper.assertTrue(output != null && output.getSlots() > 0,
                        "SETUP FAIL: no actual Basin output inventory to obstruct");
                for (int i = 0; i < output.getSlots(); i++) {
                    int full = Math.min(output.getSlotLimit(i), Items.DIRT.getMaxStackSize());
                    helper.assertTrue(full > 0, "Basin output slot " + i + " has no capacity");
                    output.setStackInSlot(i, new ItemStack(Items.DIRT, full));
                }
                assertFullDirtOutputNoStone(helper);
                LOG.info("native-batch-fixture stage=output-full-at-partial workJ={} nativeW={} "
                                + "slots={} escrow={} wires={}", partialWorkJ[0], sample.power(),
                        output.getSlots(), state.electrochemistry$hasBatch(), wireCount(helper));
                phase[0] = 1;
                return;
            }
            if (phase[0] == 1) {
                assertFullDirtOutputNoStone(helper);
                helper.assertTrue(wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                                && state.electrochemistry$hasBatch() && cobbleInput(helper) == 0
                                && state.electrochemistry$completedWorkJoules() >= partialWorkJ[0]
                                && state.electrochemistry$completedWorkJoules() < 24,
                        "Blocked native batch lost escrow or finished work before OFF settle");
                if (++settleTicks[0] > 12)
                    helper.assertTrue(false, "Full Basin output did not switch native load OFF within 12 ticks");
                if (port.electrochemistry$isLoadEnabled() || sample.power() != 0) return;
                heldWorkJ[0] = state.electrochemistry$completedWorkJoules();
                phase[0] = 2;
                return;
            }
            if (phase[0] == 2) {
                assertFullDirtOutputNoStone(helper);
                helper.assertTrue(wireCount(helper) == 2 && port.electrochemistry$hasBothCables()
                                && !port.electrochemistry$isLoadEnabled() && sample.power() == 0
                                && state.electrochemistry$hasBatch() && cobbleInput(helper) == 0
                                && Math.abs(state.electrochemistry$completedWorkJoules() - heldWorkJ[0]) < 1e-6,
                        "Blocked native output repeatedly drained power/work or lost one escrow");
                if (++heldTicks[0] == 50) {
                    LOG.info("native-batch-fixture stage=output-full-held-50ticks partialJ={} "
                                    + "heldJ={} nativeW={} escrow={} wires={}",
                            partialWorkJ[0], heldWorkJ[0], sample.power(),
                            state.electrochemistry$hasBatch(), wireCount(helper));
                    var output = basin(helper).getOutputInventory();
                    for (int i = 0; i < output.getSlots(); i++) {
                        int count = output.getStackInSlot(i).getCount();
                        var extracted = output.extractItem(i, count, false); // Real native inventory extraction.
                        helper.assertTrue(extracted.is(Items.DIRT) && extracted.getCount() == count
                                        && output.getStackInSlot(i).isEmpty(),
                                "Actual Basin output did not release full dirt slot " + i);
                    }
                    helper.assertTrue(stoneOutput(helper) == 0,
                            "Basin yielded stone before full output obstruction was removed");
                    phase[0] = 3;
                }
                return;
            }
            int stone = stoneOutput(helper); // Dirt removed; catches in-slot AND ejected duplicates.
            helper.assertTrue(stone <= 1, "Native output released duplicate stone: " + stone);
            if (stone == 0) {
                double workJ = state.electrochemistry$completedWorkJoules();
                maxPaidWorkJ[0] = Math.max(maxPaidWorkJ[0], workJ);
                helper.assertTrue(state.electrochemistry$hasBatch() && cobbleInput(helper) == 0
                                && workJ >= heldWorkJ[0] && workJ <= 24,
                        "Output release did not preserve/resume original partly paid cobble escrow");
                if (sample.voltage() >= 10 && sample.current() > 0 && sample.power() > 0
                        && workJ > heldWorkJ[0]) resumedNativePower[0] = true;
                return;
            }
            helper.assertTrue(stone == 1 && resumedNativePower[0] && maxPaidWorkJ[0] >= 24
                            && !state.electrochemistry$hasBatch() && cobbleInput(helper) == 0
                            && !port.electrochemistry$isLoadEnabled() && sample.power() == 0,
                    "Unblocked native output did not yield exactly one fully paid stone/OFF load");
            if (++stableOutputTicks[0] == 25) {
                LOG.info("native-batch-fixture stage=output-unblocked-one-stone "
                                + "partialJ={} heldJ={} completedWorkJ={} outputStone={} "
                                + "loadOn={} nativeW={} wires={}",
                        partialWorkJ[0], heldWorkJ[0], maxPaidWorkJ[0], stone,
                        port.electrochemistry$isLoadEnabled(), sample.power(), wireCount(helper));
                helper.succeed();
            }
        });
        helper.runAtTickTime(140, () -> helper.assertTrue(phase[0] >= 2,
                "SETUP FAIL: native batch did not reach full-output OFF stall by tick140"));
        helper.runAtTickTime(370, () -> helper.assertTrue(stableOutputTicks[0] >= 25,
                "Full-output native escrow did not resume one stable stone by tick370"));
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void physicallyBreakingPartialBasinRefundsCobbleOnceAndCleansWires(GameTestHelper helper) {
        boolean[] inputReady = {false}, physicallyBroken = {false};
        int[] postBreakTicks = {0};
        double[] partialWorkJ = {0};
        BlockPos basinPos = helper.absolutePos(BASIN);
        helper.runAtTickTime(5, () -> {
            rig(helper); // A fresh world and one loaded electrical RecipeManager receipt.
            addOneCobble(helper);
            inputReady[0] = true;
        });
        helper.onEachTick(() -> {
            if (!inputReady[0]) return;
            if (!physicallyBroken[0]) {
                var state = batch(helper);
                var sample = power(helper).electrochemistry$powerSample();
                double workJ = state.electrochemistry$completedWorkJoules();
                if (!state.electrochemistry$hasBatch() || workJ <= 1) return;
                helper.assertTrue(workJ < 24 && cobbleInput(helper) == 0
                                && stoneOutput(helper) == 0 && sample.voltage() >= 10
                                && sample.current() > 0 && sample.power() > 0
                                && wireCount(helper) == 2,
                        "SETUP FAIL: no real partially paid native escrow/wires before Basin break");
                partialWorkJ[0] = workJ;
                helper.assertTrue(helper.getLevel().destroyBlock(basinPos, true),
                        "Actual Basin block destruction did not occur");
                physicallyBroken[0] = true; // No direct Mixin refund/remove callback invocation.
                return;
            }
            int cobble = 0, stone = 0;
            for (var entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(basinPos).inflate(3))) {
                var stack = entity.getItem();
                if (stack.is(Items.COBBLESTONE)) cobble += stack.getCount();
                if (stack.is(Items.STONE)) stone += stack.getCount();
            }
            helper.assertTrue(helper.getLevel().getBlockState(basinPos).isAir()
                            && helper.getLevel().getBlockEntity(basinPos) == null,
                    "Physically destroyed native Basin reappeared or retained BE");
            if (++postBreakTicks[0] < 3) return; // Native item/wire removals may queue until next tick.
            helper.assertTrue(cobble == 1 && stone == 0 && wireCount(helper) == 0
                            && !new BlockWireEndpoint(basinPos, 0).isValid(helper.getLevel())
                            && !new BlockWireEndpoint(basinPos, 1).isValid(helper.getLevel()),
                    "Native Basin break did not refund EXACT one cobble/no stone/two wires gone: "
                            + "ticks=" + postBreakTicks[0] + " cobble=" + cobble
                            + " stone=" + stone + " wires=" + wireCount(helper));
            if (postBreakTicks[0] == 33) {
                LOG.info("native-batch-fixture stage=physical-basin-break partialJ={} "
                                + "droppedCobble={} droppedStone={} hangingWires={} "
                                + "basinTerminal0Valid={} basinTerminal1Valid={} stableTicks={}",
                        partialWorkJ[0], cobble, stone, wireCount(helper),
                        new BlockWireEndpoint(basinPos, 0).isValid(helper.getLevel()),
                        new BlockWireEndpoint(basinPos, 1).isValid(helper.getLevel()),
                        postBreakTicks[0] - 3);
                helper.succeed();
            }
        });
        helper.runAtTickTime(130, () -> helper.assertTrue(physicallyBroken[0],
                "SETUP FAIL: no real partial Basin escrow before physical break by tick130"));
        helper.runAtTickTime(165, () -> helper.assertTrue(postBreakTicks[0] >= 33,
                "Physically broken Basin did not show one stable refunded cobble for 30 ticks"));
    }

    @GameTest(template = "empty", timeoutTicks = 420)
    public static void freshNoCableBasinRunsOrdinaryMixerFromRest(GameTestHelper helper) {
        // Separate fresh world: NO PowerGrid source block or wire clicks. The
        // loaded electrical recipe may exist, but cannot seize an unwired Basin.
        BlockPos operator = BASIN.above(2);
        BlockPos cog = operator.east();
        BlockPos motor = cog.below();
        boolean[] inputReady = {false}, actualCycleSeen = {false};
        int[] stableOutputTicks = {0};
        helper.runAtTickTime(5, () -> {
            helper.assertTrue(ModList.get().isLoaded("create") && ModList.get().isLoaded("powergrid"),
                    "Actual Create/PowerGrid mods must be loaded for no-cable arbitration");
            loadedRecipe(helper); // Exactly one electrical fingerprint receipt for future count10.
            var raw = helper.getLevel().getRecipeManager().byKey(new ResourceLocation(
                    "better_electrochemistry", "fixture_mixing_cobble_to_gravel")).orElse(null);
            helper.assertTrue(raw instanceof MixingRecipe && raw.getType() == AllRecipeTypes.MIXING.getType(),
                    "SETUP FAIL: fixture Create MIXING recipe absent or wrong native type");
            var recipe = (MixingRecipe) raw;
            helper.assertTrue(recipe.getIngredients().size() == 1
                            && recipe.getIngredients().get(0).test(new ItemStack(Items.COBBLESTONE))
                            && recipe.getRollableResults().size() == 1
                            && recipe.getRollableResults().get(0).getStack().is(Items.GRAVEL)
                            && recipe.getRollableResults().get(0).getStack().getCount() == 1,
                    "SETUP FAIL: loaded ordinary Mixer recipe is not cobble1→gravel1");
            LOG.info("native-batch-fixture stage=no-cable-mixing-recipe-loaded id={} type={} result={}",
                    recipe.getId(), recipe.getType(), recipe.getRollableResults().get(0).getStack());
            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(SOURCE)).isAir(),
                    "SETUP FAIL: fresh no-cable Mixer fixture contains a source block");
            helper.setBlock(BASIN.below(), Blocks.BEDROCK.defaultBlockState());
            helper.setBlock(BASIN, AllBlocks.BASIN.getDefaultState());
            helper.setBlock(operator, AllBlocks.MECHANICAL_MIXER.getDefaultState());
            helper.setBlock(cog, AllBlocks.COGWHEEL.getDefaultState()
                    .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
            helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(CreativeMotorBlock.FACING, Direction.UP));
            helper.assertTrue(basin(helper) instanceof BasinPowerPort && basin(helper) instanceof BasinBatchPort,
                    "No-cable ordinary Basin did not acquire the real native ports");
        });
        helper.runAtTickTime(10, () -> {
            var nativeMotor = (CreativeMotorBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(motor));
            helper.assertTrue(nativeMotor != null, "SETUP FAIL: no-cable Mixer motor BE absent");
            nativeMotor.generatedSpeed.setValue(64); // Native motor control, never downstream setSpeed.
        });
        helper.runAtTickTime(35, () -> {
            var mixer = (MechanicalMixerBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(operator));
            var port = power(helper);
            helper.assertTrue(mixer != null && mixer.getSpeed() != 0 && mixer.hasSource(),
                    "SETUP FAIL: genuine Mixer motor/cog did not drive actual speed/source");
            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(SOURCE)).isAir()
                            && wireCount(helper) == 0 && !port.electrochemistry$hasBothCables()
                            && port.electrochemistry$availableVoltage() == 0
                            && !port.electrochemistry$isLoadEnabled()
                            && port.electrochemistry$powerSample().power() == 0,
                    "SETUP FAIL: no-cable Mixer Basin had a phantom source/voltage/draw");
            addOneCobble(helper);
            inputReady[0] = true;
        });
        helper.onEachTick(() -> {
            if (!inputReady[0]) return;
            var mixer = (MechanicalMixerBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(operator));
            var port = power(helper);
            var state = batch(helper);
            int gravel = ordinaryResultCount(helper, Items.GRAVEL); // Rejects in-slot/ejected stone.
            helper.assertTrue(gravel <= 1 && mixer.getSpeed() != 0 && mixer.hasSource()
                            && helper.getLevel().getBlockState(helper.absolutePos(SOURCE)).isAir()
                            && wireCount(helper) == 0 && !port.electrochemistry$hasBothCables()
                            && port.electrochemistry$availableVoltage() == 0
                            && !port.electrochemistry$isLoadEnabled()
                            && port.electrochemistry$powerSample().power() == 0
                            && !state.electrochemistry$hasBatch()
                            && state.electrochemistry$completedWorkJoules() == 0,
                    "Fresh no-cable Mixer lost native drive or electrical recipe seized/drew work");
            if (!actualCycleSeen[0]) {
                helper.assertTrue(gravel == 0, "SETUP FAIL: Mixer output appeared without real running cycle");
                if (!mixer.running) return;
                helper.assertTrue(cobbleInput(helper) == 1,
                        "SETUP FAIL: ordinary Mixer running did not hold one real cobble input");
                actualCycleSeen[0] = true;
                LOG.info("native-batch-fixture stage=no-cable-mixer-running speed={} hasSource={} "
                                + "running={} inputCobble={} wires={} availableV={} escrow={} electricalW={}",
                        mixer.getSpeed(), mixer.hasSource(), mixer.running, cobbleInput(helper),
                        wireCount(helper), port.electrochemistry$availableVoltage(),
                        state.electrochemistry$hasBatch(), port.electrochemistry$powerSample().power());
                return;
            }
            if (gravel == 0) return;
            helper.assertTrue(gravel == 1 && cobbleInput(helper) == 0,
                    "Fresh unwired ordinary Mixer failed exactly one native gravel output");
            if (++stableOutputTicks[0] == 25) {
                helper.assertTrue(!mixer.running, "Mixer did not stop after 25 stable gravel ticks");
                LOG.info("native-batch-fixture stage=no-cable-mixer-finished gravel={} stone=0 "
                                + "speed={} wires={} escrow={} workJ={} electricalW={}",
                        gravel, mixer.getSpeed(), wireCount(helper), state.electrochemistry$hasBatch(),
                        state.electrochemistry$completedWorkJoules(),
                        port.electrochemistry$powerSample().power());
                helper.succeed();
            }
        });
        helper.runAtTickTime(160, () -> helper.assertTrue(actualCycleSeen[0],
                "SETUP FAIL: fresh unwired powered Mixer never naturally began Basin mixing"));
        helper.runAtTickTime(380, () -> helper.assertTrue(stableOutputTicks[0] >= 25,
                "Fresh unwired ordinary Mixer did not finish one stable gravel by tick380"));
    }

    @GameTest(template = "empty", timeoutTicks = 420)
    public static void freshNoCableBasinRunsOrdinaryPressFromRest(GameTestHelper helper) {
        BlockPos operator = BASIN.above(2);
        BlockPos motor = operator.east();
        boolean[] inputReady = {false}, actualCycleSeen = {false};
        int[] stableOutputTicks = {0};
        helper.runAtTickTime(5, () -> {
            helper.assertTrue(ModList.get().isLoaded("create") && ModList.get().isLoaded("powergrid"),
                    "Actual Create/PowerGrid mods must be loaded for no-cable arbitration");
            loadedRecipe(helper); // Exactly one electrical fingerprint receipt for future count10.
            var raw = helper.getLevel().getRecipeManager().byKey(new ResourceLocation(
                    "better_electrochemistry", "fixture_compacting_cobble_to_sand")).orElse(null);
            helper.assertTrue(raw instanceof CompactingRecipe && raw.getType() == AllRecipeTypes.COMPACTING.getType(),
                    "SETUP FAIL: fixture Create COMPACTING recipe absent or wrong native type");
            var recipe = (CompactingRecipe) raw;
            helper.assertTrue(recipe.getIngredients().size() == 1
                            && recipe.getIngredients().get(0).test(new ItemStack(Items.COBBLESTONE))
                            && recipe.getRollableResults().size() == 1
                            && recipe.getRollableResults().get(0).getStack().is(Items.SAND)
                            && recipe.getRollableResults().get(0).getStack().getCount() == 1,
                    "SETUP FAIL: loaded ordinary Press recipe is not cobble1→sand1");
            LOG.info("native-batch-fixture stage=no-cable-compacting-recipe-loaded id={} type={} result={}",
                    recipe.getId(), recipe.getType(), recipe.getRollableResults().get(0).getStack());
            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(SOURCE)).isAir(),
                    "SETUP FAIL: fresh no-cable Press fixture contains a source block");
            helper.setBlock(BASIN.below(), Blocks.BEDROCK.defaultBlockState());
            helper.setBlock(BASIN, AllBlocks.BASIN.getDefaultState());
            helper.setBlock(operator, AllBlocks.MECHANICAL_PRESS.getDefaultState()
                    .setValue(MechanicalPressBlock.HORIZONTAL_FACING, Direction.EAST));
            helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(CreativeMotorBlock.FACING, Direction.WEST));
            helper.assertTrue(basin(helper) instanceof BasinPowerPort && basin(helper) instanceof BasinBatchPort,
                    "No-cable ordinary Basin did not acquire the real native ports");
        });
        helper.runAtTickTime(10, () -> {
            var nativeMotor = (CreativeMotorBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(motor));
            helper.assertTrue(nativeMotor != null, "SETUP FAIL: no-cable Press motor BE absent");
            nativeMotor.generatedSpeed.setValue(64); // Native motor control, never downstream setSpeed.
        });
        helper.runAtTickTime(35, () -> {
            var press = (MechanicalPressBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(operator));
            var port = power(helper);
            helper.assertTrue(press != null && press.getSpeed() != 0 && press.hasSource()
                            && press.getPressingBehaviour() != null,
                    "SETUP FAIL: genuine Press motor did not drive actual speed/source/behaviour");
            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(SOURCE)).isAir()
                            && wireCount(helper) == 0 && !port.electrochemistry$hasBothCables()
                            && port.electrochemistry$availableVoltage() == 0
                            && !port.electrochemistry$isLoadEnabled()
                            && port.electrochemistry$powerSample().power() == 0,
                    "SETUP FAIL: no-cable Press Basin had a phantom source/voltage/draw");
            addOneCobble(helper);
            inputReady[0] = true;
        });
        helper.onEachTick(() -> {
            if (!inputReady[0]) return;
            var press = (MechanicalPressBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(operator));
            var behaviour = press.getPressingBehaviour();
            var port = power(helper);
            var state = batch(helper);
            int sand = ordinaryResultCount(helper, Items.SAND); // Rejects in-slot/ejected stone.
            helper.assertTrue(sand <= 1 && press.getSpeed() != 0 && press.hasSource()
                            && behaviour != null
                            && helper.getLevel().getBlockState(helper.absolutePos(SOURCE)).isAir()
                            && wireCount(helper) == 0 && !port.electrochemistry$hasBothCables()
                            && port.electrochemistry$availableVoltage() == 0
                            && !port.electrochemistry$isLoadEnabled()
                            && port.electrochemistry$powerSample().power() == 0
                            && !state.electrochemistry$hasBatch()
                            && state.electrochemistry$completedWorkJoules() == 0,
                    "Fresh no-cable Press lost native drive or electrical recipe seized/drew work");
            if (!actualCycleSeen[0]) {
                helper.assertTrue(sand == 0, "SETUP FAIL: Press output appeared without real running cycle");
                if (!behaviour.running) return;
                helper.assertTrue(behaviour.onBasin() && cobbleInput(helper) == 1,
                        "SETUP FAIL: Press running was not genuine Basin cobble compacting");
                actualCycleSeen[0] = true;
                LOG.info("native-batch-fixture stage=no-cable-press-running speed={} hasSource={} "
                                + "running={} onBasin={} inputCobble={} wires={} availableV={} "
                                + "escrow={} electricalW={}",
                        press.getSpeed(), press.hasSource(), behaviour.running, behaviour.onBasin(),
                        cobbleInput(helper), wireCount(helper), port.electrochemistry$availableVoltage(),
                        state.electrochemistry$hasBatch(), port.electrochemistry$powerSample().power());
                return;
            }
            if (sand == 0) return;
            helper.assertTrue(sand == 1 && cobbleInput(helper) == 0,
                    "Fresh unwired ordinary Press failed exactly one native sand output");
            if (++stableOutputTicks[0] == 25) {
                helper.assertTrue(!behaviour.running, "Press did not stop after 25 stable sand ticks");
                LOG.info("native-batch-fixture stage=no-cable-press-finished sand={} stone=0 "
                                + "speed={} wires={} escrow={} workJ={} electricalW={}",
                        sand, press.getSpeed(), wireCount(helper), state.electrochemistry$hasBatch(),
                        state.electrochemistry$completedWorkJoules(),
                        port.electrochemistry$powerSample().power());
                helper.succeed();
            }
        });
        helper.runAtTickTime(160, () -> helper.assertTrue(actualCycleSeen[0],
                "SETUP FAIL: fresh unwired powered Press never naturally began Basin compacting"));
        helper.runAtTickTime(380, () -> helper.assertTrue(stableOutputTicks[0] >= 25,
                "Fresh unwired ordinary Press did not finish one stable sand by tick380"));
    }
}
