/**
 * Off-world / station economy (stub, Phase 4+). All mutators must go through sim actions later.
 */
export type Ledger = { stationCredits: number; exportsPerTick: number };

export const emptyLedger = (): Ledger => ({ stationCredits: 0, exportsPerTick: 0 });
