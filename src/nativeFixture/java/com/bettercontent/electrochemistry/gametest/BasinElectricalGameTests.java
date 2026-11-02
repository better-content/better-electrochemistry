package com.bettercontent.electrochemistry.gametest;

import com.bettercontent.electrochemistry.BetterElectrochemistry;
import com.bettercontent.electrochemistry.power.BasinPowerPort;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.fml.ModList;
import org.patryk3211.powergrid.collections.ModdedBlocks;
import org.patryk3211.powergrid.collections.ModdedItems;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.battery.MultiBlockBatteryEntity;
import org.patryk3211.powergrid.electricity.battery.PotatoBatteryBlock;
import org.patryk3211.powergrid.electricity.battery.PotatoBatteryBlockEntity;
import org.patryk3211.powergrid.electricity.creative.CreativeSourceBlock;
import org.patryk3211.powergrid.electricity.creative.CreativeSourceBlockEntity;
import org.patryk3211.powergrid.electricity.deviceconnector.BridgeElectricBehaviour;
import org.patryk3211.powergrid.electricity.deviceconnector.DeviceConnectorBlock;
import org.patryk3211.powergrid.electricity.deviceconnector.DeviceConnectorBlockEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reduced-subset native endpoint witness; not a batch-processing, optional-mod or full-pack test. */
@GameTestHolder(BetterElectrochemistry.ID)
@PrefixGameTestTemplate(false)
public final class BasinElectricalGameTests {
    private static final BlockPos SOURCE = new BlockPos(3, 2, 5);
    private static final BlockPos BASIN = new BlockPos(8, 2, 5);
    private static final Logger LOG = LoggerFactory.getLogger(BasinElectricalGameTests.class);

    private static Vec3 hit(GameTestHelper helper, BlockPos pos, double x, double y, double z) {
        return Vec3.atLowerCornerOf(helper.absolutePos(pos)).add(x, y, z);
    }

    private static void click(GameTestHelper helper, FakePlayer player, BlockPos pos,
                              double x, double y, double z, Direction face) {
        var absolute = helper.absolutePos(pos);
        var stack = player.getMainHandItem();
        var result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit(helper, pos, x, y, z), face, absolute, false)));
        helper.assertTrue(result.consumesAction(), "Native light wire click failed at " + pos + ": " + result);
    }

    private static void connect(GameTestHelper helper, FakePlayer player, int terminal) {
        connect(helper, player, terminal, false);
    }

    private static void connect(GameTestHelper helper, FakePlayer player, int terminal, boolean potato) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModdedItems.WIRE.get(), 64));
        var stack = player.getMainHandItem();
        helper.assertTrue(((IElectric) helper.getLevel().getBlockState(helper.absolutePos(SOURCE)).getBlock())
                .accepts(stack), "PowerGrid source rejected actual light-wire item/tag");
        // Z-axis creative source terminals 0/1: z=2..6/16 and 10..14/16, y=13..16/16.
        // NORTH-facing potato battery terminals 0/1: z=4.5..6/16 and 10..11.5/16,
        // y=3..5.5/16. Basin BE terminals occupy opposing side rims.
        click(helper, player, SOURCE, .5, potato ? .27 : .9,
                terminal == 0 ? .33 : .67, potato ? Direction.NORTH : Direction.UP);
        helper.assertTrue(stack.hasTag() && stack.getTag().contains("Connection"),
                "First native click did not store a PowerGrid Connection endpoint");
        click(helper, player, BASIN, terminal == 0 ? .05 : .95, .45, .5,
                terminal == 0 ? Direction.WEST : Direction.EAST);
        helper.assertTrue(!stack.hasTag() || !stack.getTag().contains("Connection"),
                "Second native click did not complete the PowerGrid connection");
    }

    private static List<HangingWireEntity> wires(GameTestHelper helper) {
        // BLOCK-to-BLOCK PowerGrid clicks create HangingWireEntity, not BlockWireEntity.
        return helper.getLevel().getEntitiesOfClass(HangingWireEntity.class,
                new AABB(helper.absolutePos(SOURCE)).minmax(new AABB(helper.absolutePos(BASIN))).inflate(3));
    }

    private static void logSample(String stage, float sourceVoltage,
                                  BasinPowerPort.NativePowerSample sample, GameTestHelper helper) {
        LOG.info("native-electrical-fixture stage={} sourceVolts={} basinVolts={} basinAmps={} "
                        + "basinWatts={} hangingWireAmps={}", stage, sourceVoltage,
                sample.voltage(), sample.current(), sample.power(),
                wires(helper).stream().map(HangingWireEntity::current).toList());
    }

    @GameTest(template = "empty", timeoutTicks = 210)
    public static void realVoltageSourceLightWiresAndSwitchedBasinLoad(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded("powergrid") && ModList.get().isLoaded("create"),
                "Native PowerGrid + Create must actually load for endpoint proof");
        helper.setBlock(SOURCE, ModdedBlocks.CREATIVE_VOLTAGE_SOURCE.getDefaultState()
                .setValue(CreativeSourceBlock.HORIZONTAL_AXIS, Direction.Axis.Z));
        helper.setBlock(BASIN, AllBlocks.BASIN.getDefaultState());
        var sourcePos = helper.absolutePos(SOURCE);
        var basinPos = helper.absolutePos(BASIN);
        var player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "electrical_fixture"));
        var rated = new BasinPowerPort.NativePowerSample[1];
        helper.runAtTickTime(5, () -> {
            var basin = helper.getLevel().getBlockEntity(basinPos);
            helper.assertTrue(basin instanceof BasinBlockEntity && basin instanceof BasinPowerPort,
                    "Ordinary Create Basin BE did not acquire electrochemical native Mixin");
            var port = (BasinPowerPort) basin;
            var source = (CreativeSourceBlockEntity) helper.getLevel().getBlockEntity(sourcePos);
            source.setValue(24f); // Native physical source block/BE in a real PowerGrid world network.
            helper.assertTrue(Math.abs(source.getValue() - 24f) < .001f, "Native source failed 24 V setting");
            helper.assertTrue(IElectric.getAt(helper.getLevel(), basinPos) == basin,
                    "WireItem did not resolve the Basin BE as native IElectric");
            for (int i = 0; i < 2; i++) {
                helper.assertTrue(new BlockWireEndpoint(basinPos, i).isValid(helper.getLevel()),
                        "Basin terminal " + i + " invalid before native wire click");
                helper.assertTrue(new BlockWireEndpoint(sourcePos, i).isValid(helper.getLevel()),
                        "Native source terminal " + i + " invalid before native wire click");
                connect(helper, player, i);
            }
            // ServerLevel may queue newly created wire entities until the next world tick.
            var off = port.electrochemistry$powerSample();
            logSample("open-before-enable", source.getValue(), off, helper);
            helper.assertTrue(off.power() == 0,
                    "Normally-open idle load drew productive electrical power");
            port.electrochemistry$setLoadEnabled(true);
        });
        helper.runAtTickTime(30, () -> {
            var port = (BasinPowerPort) helper.getLevel().getBlockEntity(basinPos);
            var sample = port.electrochemistry$powerSample();
            var liveWires = wires(helper);
            helper.assertTrue(liveWires.size() == 2,
                    "Expected two native wire entities after tick; observed " + liveWires.size()
                    + ", Basin connections " +
                    ((com.simibubi.create.content.processing.basin.BasinBlockEntity)
                    helper.getLevel().getBlockEntity(basinPos)).getBehaviour(
                    org.patryk3211.powergrid.electricity.base.ElectricBehaviour.TYPE).getConnections().size());
            helper.assertTrue(sample.voltage() > 0 && sample.current() > 0 && sample.power() > 0,
                    "Native source + light wires produced no voltage/current/power: " + sample);
            helper.assertTrue(liveWires.stream()
                    .allMatch(wire -> wire.getWire() != null && Math.abs(wire.current()) > 0),
                    "Real PowerGrid wire entities had no active simulated current");
            rated[0] = sample;
            logSample("connected-24v", ((CreativeSourceBlockEntity)
                    helper.getLevel().getBlockEntity(sourcePos)).getValue(), sample, helper);
            var first = wires(helper).stream().filter(wire -> wire.isConnectedTo(basinPos, 0))
                    .findFirst().orElseThrow();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModdedItems.WIRE_CUTTER.get()));
            player.setShiftKeyDown(true); // Native wire cutter's server-side full-wire break path.
            InteractionResult cut = first.interact(player, InteractionHand.MAIN_HAND);
            player.setShiftKeyDown(false);
            helper.assertTrue(cut.consumesAction(), "Native wire cutter failed to disconnect terminal 0");
        });
        helper.runAtTickTime(50, () -> {
            var port = (BasinPowerPort) helper.getLevel().getBlockEntity(basinPos);
            var disconnected = port.electrochemistry$powerSample();
            logSample("single-wire-disconnected", ((CreativeSourceBlockEntity)
                    helper.getLevel().getBlockEntity(sourcePos)).getValue(), disconnected, helper);
            helper.assertTrue(wires(helper).size() == 1 && disconnected.power() == 0,
                    "Removing one real wire did not open the load circuit");
            connect(helper, player, 0);
        });
        helper.runAtTickTime(75, () -> {
            var port = (BasinPowerPort) helper.getLevel().getBlockEntity(basinPos);
            helper.assertTrue(wires(helper).size() == 2, "Native reconnect did not restore two wires");
            var source = (CreativeSourceBlockEntity) helper.getLevel().getBlockEntity(sourcePos);
            var reconnected = port.electrochemistry$powerSample();
            logSample("reconnected-24v", source.getValue(), reconnected, helper);
            helper.assertTrue(reconnected.current() > 0,
                    "Reconnect did not restore native current");
            source.setValue(1f);
        });
        helper.runAtTickTime(95, () -> {
            var port = (BasinPowerPort) helper.getLevel().getBlockEntity(basinPos);
            var source = (CreativeSourceBlockEntity) helper.getLevel().getBlockEntity(sourcePos);
            var low = port.electrochemistry$powerSample();
            logSample("connected-1v", source.getValue(), low, helper);
            helper.assertTrue(low.voltage() > 0 && low.voltage() < rated[0].voltage()
                            && low.current() > 0 && low.current() < rated[0].current()
                            && low.power() > 0 && low.power() < rated[0].power(),
                    "Native 1 V load did not scale below 24 V load: low=" + low + ", rated=" + rated[0]);
            // This is an electrical scaling test, NOT a recipe no-work threshold.
            source.setValue(24f);
        });
        helper.runAtTickTime(115, () -> {
            var port = (BasinPowerPort) helper.getLevel().getBlockEntity(basinPos);
            helper.assertTrue(port.electrochemistry$powerSample().current() > 0,
                    "Restoring 24 V failed to recover native current");
            port.electrochemistry$setLoadEnabled(false);
        });
        helper.runAtTickTime(135, () -> {
            var port = (BasinPowerPort) helper.getLevel().getBlockEntity(basinPos);
            var off = port.electrochemistry$powerSample();
            logSample("open-after-voltage-test", ((CreativeSourceBlockEntity)
                    helper.getLevel().getBlockEntity(sourcePos)).getValue(), off, helper);
            helper.assertTrue(off.power() == 0,
                    "Opening the batch switch did not stop observed productive power");
            helper.getLevel().destroyBlock(basinPos, true);
        });
        helper.runAtTickTime(155, () -> {
            helper.assertTrue(wires(helper).isEmpty(), "Breaking Basin left orphaned native wire entities");
            helper.succeed();
        });
    }

    // Known upstream PowerGrid 0.5.4 nondepletion: ../chem-native-qa-quant-battery-run1.log
    // PotatoBatteryBlockEntity.electricalTick overrides and skips the parent discharge call.
    // Keep the regression visible as OPTIONAL, never claim it passes or supplies finite energy.
    @GameTest(template = "empty", timeoutTicks = 120, required = false)
    public static void realChargedPotatoBatterySuppliesAndDepletes(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded("powergrid") && ModList.get().isLoaded("create"),
                "Native PowerGrid + Create must actually load for battery witness");
        helper.setBlock(SOURCE.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(SOURCE, ModdedBlocks.POTATO_BATTERY.getDefaultState()
                .setValue(PotatoBatteryBlock.HORIZONTAL_FACING, Direction.NORTH));
        helper.setBlock(BASIN, AllBlocks.BASIN.getDefaultState());
        var sourcePos = helper.absolutePos(SOURCE);
        var basinPos = helper.absolutePos(BASIN);
        var player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "potato_fixture"));
        var initialEnergy = new double[1];
        helper.runAtTickTime(5, () -> {
            var battery = (PotatoBatteryBlockEntity) helper.getLevel().getBlockEntity(sourcePos);
            helper.assertTrue(battery != null && battery.getCapacity() > 0,
                    "Native potato battery BE/charge capacity unavailable");
            battery.setEnergy(battery.getCapacity()); // Test-only charge; no crafting or acquisition claim.
            initialEnergy[0] = battery.getEnergy();
            helper.assertTrue(initialEnergy[0] > 0, "Test battery did not start charged");
            for (int i = 0; i < 2; i++) {
                helper.assertTrue(new BlockWireEndpoint(sourcePos, i).isValid(helper.getLevel()),
                        "Native potato battery terminal " + i + " invalid");
                helper.assertTrue(new BlockWireEndpoint(basinPos, i).isValid(helper.getLevel()),
                        "Basin terminal " + i + " invalid with potato source");
                connect(helper, player, i, true);
            }
            ((BasinPowerPort) helper.getLevel().getBlockEntity(basinPos))
                    .electrochemistry$setLoadEnabled(true);
        });
        helper.runAtTickTime(40, () -> {
            var battery = (PotatoBatteryBlockEntity) helper.getLevel().getBlockEntity(sourcePos);
            var port = (BasinPowerPort) helper.getLevel().getBlockEntity(basinPos);
            var sample = port.electrochemistry$powerSample();
            LOG.info("native-electrical-fixture stage=charged-potato initialJ={} remainingJ={} "
                            + "basinVolts={} basinAmps={} basinWatts={} hangingWireAmps={}",
                    initialEnergy[0], battery.getEnergy(), sample.voltage(), sample.current(),
                    sample.power(), wires(helper).stream().map(HangingWireEntity::current).toList());
            helper.assertTrue(wires(helper).size() == 2 && wires(helper).stream().allMatch(
                    wire -> wire.getWire() != null && Math.abs(wire.current()) > 0),
                    "Potato battery was not joined by two conducting native light wires");
            helper.assertTrue(sample.voltage() > 0 && sample.current() > 0 && sample.power() > 0,
                    "Charged noncreative battery gave no native current to Basin: " + sample);
            helper.assertTrue(battery.getEnergy() >= 0 && battery.getEnergy() < initialEnergy[0],
                    "Native battery energy did not decrease while serving load");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 125)
    public static void realAcidBatteryWithNativeConnectorDischarges(GameTestHelper helper) {
        // Native one-block member of PowerGrid's multiblock BATTERY, not an FE source.
        // The battery block itself has no wire terminals: its polarized connector does.
        var batteryRelative = SOURCE.west();
        helper.setBlock(batteryRelative, ModdedBlocks.BATTERY.getDefaultState());
        helper.setBlock(SOURCE, ModdedBlocks.DEVICE_CONNECTOR.getDefaultState()
                .setValue(DeviceConnectorBlock.FACING, Direction.WEST)
                .setValue(DeviceConnectorBlock.POLARIZED, true)
                .setValue(DeviceConnectorBlock.ROTATION, 1));
        helper.setBlock(BASIN, AllBlocks.BASIN.getDefaultState());
        var batteryPos = helper.absolutePos(batteryRelative);
        var connectorPos = helper.absolutePos(SOURCE);
        var basinPos = helper.absolutePos(BASIN);
        var player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "acid_battery_fixture"));
        var startingJ = new double[1];
        helper.runAtTickTime(5, () -> {
            var battery = (MultiBlockBatteryEntity) helper.getLevel().getBlockEntity(batteryPos);
            var connector = (DeviceConnectorBlockEntity) helper.getLevel().getBlockEntity(connectorPos);
            helper.assertTrue(battery != null && battery.isController()
                            && battery.getWidth() == 1 && battery.getHeight() == 1
                            && battery.getCapacity() > 0,
                    "Native acid battery not initialized as a charged-capable 1x1 controller");
            helper.assertTrue(connector != null && connector.getBlockState().getBlock() instanceof DeviceConnectorBlock
                            && ((DeviceConnectorBlock) connector.getBlockState().getBlock()).canSurvive(
                            connector.getBlockState(), helper.getLevel(), connectorPos),
                    "Native polarized connector cannot attach to battery face");
            var bridge = (BridgeElectricBehaviour) connector.getBehaviour(
                    org.patryk3211.powergrid.electricity.base.ElectricBehaviour.TYPE);
            helper.assertTrue(bridge != null && bridge.getMainBehaviour().isPresent() && !bridge.isFE(),
                    "Connector must proxy PowerGrid battery native ElectricBehaviour, not FE fallback");
            battery.setEnergy(battery.getCapacity() * .75); // Test-only state, not acquisition proof.
            startingJ[0] = battery.getEnergy();
            var electric = (IElectric) connector.getBlockState().getBlock();
            for (int i = 0; i < 2; i++) {
                var placement = electric.terminal(connector.getBlockState(), i);
                helper.assertTrue(placement != null && electric.terminalIndexAt(
                        connector.getBlockState(), placement.getOrigin()) == i,
                        "Native connector terminal origin cannot select index " + i);
                helper.assertTrue(new BlockWireEndpoint(connectorPos, i).isValid(helper.getLevel())
                                && new BlockWireEndpoint(basinPos, i).isValid(helper.getLevel()),
                        "Native connector/Basin endpoint invalid at index " + i);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModdedItems.WIRE.get(), 64));
                var stack = player.getMainHandItem();
                var origin = placement.getOrigin();
                click(helper, player, SOURCE, origin.x, origin.y, origin.z, Direction.EAST);
                helper.assertTrue(stack.hasTag() && stack.getTag().contains("Connection"),
                        "Native connector click did not store wire endpoint");
                click(helper, player, BASIN, i == 0 ? .05 : .95, .45, .5,
                        i == 0 ? Direction.WEST : Direction.EAST);
                helper.assertTrue(!stack.hasTag() || !stack.getTag().contains("Connection"),
                        "Native connector-to-Basin connection failed");
            }
            ((BasinPowerPort) helper.getLevel().getBlockEntity(basinPos))
                    .electrochemistry$setLoadEnabled(true);
        });
        helper.runAtTickTime(45, () -> {
            var battery = (MultiBlockBatteryEntity) helper.getLevel().getBlockEntity(batteryPos);
            var sample = ((BasinPowerPort) helper.getLevel().getBlockEntity(basinPos))
                    .electrochemistry$powerSample();
            LOG.info("native-electrical-fixture stage=acid-battery initialJ={} remainingJ={} "
                            + "basinVolts={} basinAmps={} basinWatts={} hangingWireAmps={}",
                    startingJ[0], battery.getEnergy(), sample.voltage(), sample.current(),
                    sample.power(), wires(helper).stream().map(HangingWireEntity::current).toList());
            helper.assertTrue(wires(helper).size() == 2 && wires(helper).stream().allMatch(
                    wire -> wire.getWire() != null && Math.abs(wire.current()) > 0),
                    "Acid battery connector did not provide two conducting native light wires");
            helper.assertTrue(sample.voltage() > 0 && sample.current() > 0 && sample.power() > 0,
                    "Finite native acid battery gave no converged PowerGrid Basin load: " + sample);
            helper.assertTrue(battery.getEnergy() >= 0 && battery.getEnergy() < startingJ[0],
                    "Native acid battery stored energy did not decrease while serving Basin load");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 110)
    public static void openCircuitVoltageRequiresTwoLiveWiresAndTracksSupply(GameTestHelper helper) {
        // Required pre-recipe arbitration witness; never turn the load on in this test.
        helper.setBlock(SOURCE, ModdedBlocks.CREATIVE_VOLTAGE_SOURCE.getDefaultState()
                .setValue(CreativeSourceBlock.HORIZONTAL_AXIS, Direction.Axis.Z));
        helper.setBlock(BASIN, AllBlocks.BASIN.getDefaultState());
        var sourcePos = helper.absolutePos(SOURCE);
        var basinPos = helper.absolutePos(BASIN);
        var player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "open_circuit_fixture"));
        helper.runAtTickTime(5, () -> {
            var source = (CreativeSourceBlockEntity) helper.getLevel().getBlockEntity(sourcePos);
            var port = (BasinPowerPort) helper.getLevel().getBlockEntity(basinPos);
            source.setValue(24f);
            helper.assertTrue(port.electrochemistry$availableVoltage() == 0,
                    "Basin reported available power without any native cable");
            for (int i = 0; i < 2; i++) {
                helper.assertTrue(new BlockWireEndpoint(sourcePos, i).isValid(helper.getLevel())
                                && new BlockWireEndpoint(basinPos, i).isValid(helper.getLevel()),
                        "Native open-circuit fixture endpoint " + i + " invalid");
                connect(helper, player, i);
            }
        });
        helper.runAtTickTime(30, () -> {
            var source = (CreativeSourceBlockEntity) helper.getLevel().getBlockEntity(sourcePos);
            var port = (BasinPowerPort) helper.getLevel().getBlockEntity(basinPos);
            float volts = port.electrochemistry$availableVoltage();
            LOG.info("native-electrical-fixture stage=open-24v sourceVolts={} availableVolts={} "
                            + "basinWatts={} hangingWireAmps={}",
                    source.getValue(), volts, port.electrochemistry$powerSample().power(),
                    wires(helper).stream().map(HangingWireEntity::current).toList());
            helper.assertTrue(wires(helper).size() == 2 && volts > 10f
                            && port.electrochemistry$powerSample().power() == 0,
                    "24 V open circuit with two wires did not expose >10 V without load: " + volts);
            source.setValue(1f);
        });
        helper.runAtTickTime(50, () -> {
            var source = (CreativeSourceBlockEntity) helper.getLevel().getBlockEntity(sourcePos);
            var port = (BasinPowerPort) helper.getLevel().getBlockEntity(basinPos);
            float volts = port.electrochemistry$availableVoltage();
            LOG.info("native-electrical-fixture stage=open-1v sourceVolts={} availableVolts={} "
                            + "basinWatts={}", source.getValue(), volts,
                    port.electrochemistry$powerSample().power());
            helper.assertTrue(volts > 0 && volts < 10f && port.electrochemistry$powerSample().power() == 0,
                    "1 V open circuit incorrectly offered >=10 V or drew active Basin power: " + volts);
            var cutWire = wires(helper).stream().filter(wire -> wire.isConnectedTo(basinPos, 0))
                    .findFirst().orElseThrow();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModdedItems.WIRE_CUTTER.get()));
            player.setShiftKeyDown(true);
            InteractionResult cut = cutWire.interact(player, InteractionHand.MAIN_HAND);
            player.setShiftKeyDown(false);
            helper.assertTrue(cut.consumesAction(), "Native wire cutter failed on open-circuit terminal");
            source.setValue(24f); // A cut terminal must report 0 even with sufficient source voltage.
        });
        helper.runAtTickTime(75, () -> {
            var port = (BasinPowerPort) helper.getLevel().getBlockEntity(basinPos);
            float volts = port.electrochemistry$availableVoltage();
            LOG.info("native-electrical-fixture stage=open-one-wire-cut sourceVolts=24 "
                            + "availableVolts={} survivingWires={}", volts, wires(helper).size());
            helper.assertTrue(wires(helper).size() == 1 && volts == 0,
                    "Open-circuit voltage remained available with one cable cut: " + volts);
            helper.succeed();
        });
    }
}
