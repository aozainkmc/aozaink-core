package com.aozainkmc.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class InkChannelTest {

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    @Test
    void sameIdAndTypesGiveTheSameChannel() {
        InkChannel<String, Integer> a = InkChannel.of(id("same"), String.class, Integer.class);
        InkChannel<String, Integer> b = InkChannel.of(id("same"), String.class, Integer.class);
        assertSame(a, b);
    }

    @Test
    void mismatchedTypesFailAtOnce() {
        InkChannel.of(id("typed"), String.class, Integer.class);
        assertThrows(IllegalStateException.class, () -> InkChannel.of(id("typed"), String.class, Long.class));
    }

    @Test
    void nobodyAnsweringGivesNothing() {
        InkChannel<String, Integer> channel = InkChannel.of(id("empty"), String.class, Integer.class);
        assertFalse(channel.hasProviders());
        assertTrue(channel.ask("x").isEmpty());
        assertTrue(channel.first("x").isEmpty());
    }

    @Test
    void everyAnswerInOrderAndNullsSkipped() {
        InkChannel<String, Integer> channel = InkChannel.of(id("many"), String.class, Integer.class)
            .provide(String::length)
            .provide(q -> null)
            .provide(q -> 7);
        assertEquals(List.of(3, 7), channel.ask("abc"));
        assertEquals(3, channel.first("abc").orElseThrow());
    }

    @Test
    void brokenProviderIsSkipped() {
        InkChannel<String, Integer> channel = InkChannel.of(id("broken"), String.class, Integer.class)
            .provide(q -> { throw new IllegalArgumentException("boom"); })
            .provide(q -> 1);
        assertEquals(List.of(1), channel.ask("q"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void wrongAnswerTypeIsIgnored() {
        InkChannel raw = InkChannel.of(id("wrong"), String.class, Integer.class);
        raw.provide(q -> "not a number");
        raw.provide(q -> 2);
        assertEquals(List.of(2), raw.ask("q"));
    }

    @Test
    void parameterisedAnswersUseTheirRawClass() {
        InkChannel<String, Map<String, Double>> channel =
            InkChannel.<String, Map<String, Double>>of(id("generic"), String.class, Map.class)
                .provide(q -> Map.of(q, 0.5));
        assertEquals(0.5, channel.ask("k").get(0).get("k"));
    }

    @Test
    void describesItself() {
        InkChannel.of(id("described"), String.class, Integer.class).provide(q -> 1);
        InkChannel.Description description = InkChannel.all().stream()
            .filter(d -> d.id().equals(id("described"))).findFirst().orElseThrow();
        assertEquals(Integer.class, description.answerType());
        assertEquals(1, description.providers().size());
    }
}
