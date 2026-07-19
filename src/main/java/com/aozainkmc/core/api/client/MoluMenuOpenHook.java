package com.aozainkmc.core.api.client;

/**
 * Hook invoked on the client when the Molu menu (符咒簿) is opened.
 * Implementations are registered through {@link com.aozainkmc.core.AozaiInkCoreApi#registerService}.
 */
@FunctionalInterface
public interface MoluMenuOpenHook {
    void onMoluMenuOpened();
}
