package com.bettercontent.electrochemistry.mixin;

import com.bettercontent.electrochemistry.power.BasinPowerPort;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a native two-terminal, normally-open PowerGrid load to the ordinary Basin BE.
 * No block replacement, FE capability, heater proxy, or idle electrical drain.
 * Native circuit behaviour owns its network registration, persistence and unload.
 */
@Mixin(value = BasinBlockEntity.class, remap = false)
public abstract class BasinPowerEndpointMixin extends SmartBlockEntity
        implements IElectric, IElectricEntity, BasinPowerPort {
    @Unique private static final ITerminalPlacement ELECTROCHEMISTRY_LEFT =
            new TerminalBoundingBox(Component.literal("Basin electrode -"), 0, 5, 6, 2, 10, 10);
    @Unique private static final ITerminalPlacement ELECTROCHEMISTRY_RIGHT =
            new TerminalBoundingBox(Component.literal("Basin electrode +"), 14, 5, 6, 16, 10, 10);
    @Unique private ElectricBehaviour electrochemistry$electric;
    @Unique private SwitchedWire electrochemistry$load;

    protected BasinPowerEndpointMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Inject(method = "addBehaviours", at = @At("TAIL"))
    private void electrochemistry$attachCircuit(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
        electrochemistry$electric = new ElectricBehaviour(this);
        behaviours.add(electrochemistry$electric);
    }

    @Override public int terminalCount() { return 2; }

    @Override public ITerminalPlacement terminal(BlockState state, int index) {
        return index == 0 ? ELECTROCHEMISTRY_LEFT : index == 1 ? ELECTROCHEMISTRY_RIGHT : null;
    }

    @Override public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        builder.setTerminalCount(2);
        // Never energize during initialization/reload. The selected recipe controls the switch.
        electrochemistry$load = builder.connectSwitch(100.0f,
                builder.terminalNode(0), builder.terminalNode(1), false);
    }

    @Override public void electrochemistry$setLoadEnabled(boolean enabled) {
        if (level == null || level.isClientSide || electrochemistry$load == null) return;
        electrochemistry$load.setState(enabled);
    }

    @Override public boolean electrochemistry$isLoadEnabled() {
        return electrochemistry$load != null && electrochemistry$load.getState();
    }

    @Override public boolean electrochemistry$hasBothCables() {
        return electrochemistry$bothCablesAttached();
    }

    @Unique private boolean electrochemistry$bothCablesAttached() {
        if (level == null || electrochemistry$electric == null) return false;
        for (int i = 0; i < 2; i++) {
            var endpoint = new BlockWireEndpoint(worldPosition, i);
            var connections = electrochemistry$electric.getConnections().get(endpoint);
            if (!endpoint.isValid(level) || connections == null || connections.stream().noneMatch(wire -> wire.isAlive()))
                return false;
        }
        return true;
    }

    @Override public float electrochemistry$availableVoltage() {
        SwitchedWire wire = electrochemistry$load;
        if (level == null || level.isClientSide || wire == null || wire.getState()
                || !wire.isConverged() || !electrochemistry$bothCablesAttached()) return 0;
        float volts = Math.abs(wire.potentialDifference());
        return Float.isFinite(volts) ? volts : 0;
    }

    @Override public NativePowerSample electrochemistry$powerSample() {
        SwitchedWire wire = electrochemistry$load;
        if (level == null || level.isClientSide || wire == null || !wire.getState()
                || !wire.isConverged() || electrochemistry$electric == null
                || !electrochemistry$bothCablesAttached()) return NativePowerSample.ZERO;
        float voltage = Math.abs(wire.potentialDifference());
        float current = Math.abs(wire.current());
        float power = Math.abs(wire.power());
        if (!Float.isFinite(voltage) || !Float.isFinite(current) || !Float.isFinite(power))
            return NativePowerSample.ZERO;
        return new NativePowerSample(voltage, current, power);
    }

    @Inject(method = "remove", at = @At("TAIL"))
    private void electrochemistry$removeCircuit(CallbackInfo ci) {
        if (electrochemistry$electric != null) electrochemistry$electric.remove();
        electrochemistry$load = null;
    }
}
