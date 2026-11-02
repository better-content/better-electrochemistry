package com.bettercontent.electrochemistry.basin;

/** Server-authoritative state of the narrow item-only Basin fixture processor. */
public interface BasinBatchPort {
    /** Active escrow or an immediately matching electrical recipe claims this Basin. */
    boolean electrochemistry$ownsProcessing();
    boolean electrochemistry$hasBatch();
    double electrochemistry$completedWorkJoules();
}
