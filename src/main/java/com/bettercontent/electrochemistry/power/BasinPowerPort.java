package com.bettercontent.electrochemistry.power;

/** Native PowerGrid load attached to an existing Create Basin; no separate energy store. */
public interface BasinPowerPort {
    /** Enable the circuit only while a batch is eligible to make progress. */
    void electrochemistry$setLoadEnabled(boolean enabled);

    /** Read the native switch itself; this is false before circuit construction or after removal. */
    boolean electrochemistry$isLoadEnabled();

    /** True only with an alive native PowerGrid wire on each valid terminal. */
    boolean electrochemistry$hasBothCables();

    /** Solved open-circuit native voltage with both cable terminals attached; no productive load. */
    float electrochemistry$availableVoltage();

    /** Snapshot of the native circuit, not an FE or simulated power balance. */
    NativePowerSample electrochemistry$powerSample();

    record NativePowerSample(float voltage, float current, float power) {
        public static final NativePowerSample ZERO = new NativePowerSample(0, 0, 0);
    }
}
