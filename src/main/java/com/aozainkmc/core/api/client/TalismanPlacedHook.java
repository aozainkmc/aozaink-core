package com.aozainkmc.core.api.client;

/**
 * Hook invoked on the client when a blank Yellow Talisman is placed on a crafting table.
 * Implementations are registered through {@link com.aozainkmc.core.AozaiInkCoreApi#registerService}.
 */
@FunctionalInterface
public interface TalismanPlacedHook {
    void onTalismanPlaced();
}
