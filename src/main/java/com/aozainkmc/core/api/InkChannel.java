package com.aozainkmc.core.api;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModLoadingContext;
import org.slf4j.Logger;

/**
 * A named question any module can answer and any module can ask, without either side seeing
 * the other's classes. The owner of a question picks its id (its own mod id as namespace), a
 * question type and an answer type from classes everyone can load (JDK, Minecraft, NeoForge,
 * core), and documents what the answer means. Both sides then get the same channel by calling
 * {@link #of} with the same id and types; nothing has to be registered first, and load order
 * does not matter.
 *
 * <p>Any number of providers may answer. {@link #ask} returns every non-null answer in
 * registration order; the owner documents how answers combine. A provider that throws or
 * returns the wrong type is skipped and logged once, so one broken module cannot break others.
 * Asking a channel nobody answers costs one volatile read.
 *
 * <p>Core never interprets a channel: it only passes questions to providers.
 */
public final class InkChannel<Q, A> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, InkChannel<?, ?>> CHANNELS = new ConcurrentHashMap<>();

    private final ResourceLocation id;
    private final Class<?> questionType;
    private final Class<?> answerType;
    private final String declaredBy;
    private final List<Provider<Q, A>> providers = new CopyOnWriteArrayList<>();

    private record Provider<Q, A>(String owner, Function<? super Q, ? extends A> answer, Set<String> failures) {}

    /** What a channel looks like right now, for debugging and tooling. */
    public record Description(ResourceLocation id, Class<?> questionType, Class<?> answerType,
                              String declaredBy, List<String> providers) {}

    private InkChannel(ResourceLocation id, Class<?> questionType, Class<?> answerType, String declaredBy) {
        this.id = id;
        this.questionType = questionType;
        this.answerType = answerType;
        this.declaredBy = declaredBy;
    }

    /**
     * The channel with this id, created on first use. Every caller must pass the same question
     * and answer classes; a mismatch throws at once, naming the mod that declared it first.
     * Parameterised answers (a List, a Pair) are checked by their raw class.
     */
    @SuppressWarnings("unchecked")
    public static <Q, A> InkChannel<Q, A> of(ResourceLocation id, Class<? super Q> questionType, Class<? super A> answerType) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(questionType, "questionType");
        Objects.requireNonNull(answerType, "answerType");
        InkChannel<?, ?> channel = CHANNELS.computeIfAbsent(id, key -> new InkChannel<>(key, questionType, answerType, currentMod()));
        if (channel.questionType != questionType || channel.answerType != answerType) {
            throw new IllegalStateException("Ink channel " + id + " was declared by " + channel.declaredBy
                + " as " + channel.questionType.getName() + " -> " + channel.answerType.getName()
                + ", but " + currentMod() + " asked for " + questionType.getName() + " -> " + answerType.getName());
        }
        return (InkChannel<Q, A>) channel;
    }

    /** Every channel declared so far. */
    public static Collection<Description> all() {
        List<Description> described = new ArrayList<>();
        for (InkChannel<?, ?> channel : CHANNELS.values()) described.add(channel.describe());
        return described;
    }

    /** Adds an answer. Return null when this provider has nothing to say about a question. */
    public InkChannel<Q, A> provide(Function<? super Q, ? extends A> answer) {
        providers.add(new Provider<>(currentMod(), Objects.requireNonNull(answer, "answer"), ConcurrentHashMap.newKeySet()));
        return this;
    }

    /** Every non-null answer, in the order providers were added. Empty when nobody answers. */
    public List<A> ask(Q question) {
        if (providers.isEmpty()) return List.of();
        List<A> answers = new ArrayList<>(providers.size());
        for (Provider<Q, A> provider : providers) {
            A answer = answerFrom(provider, question);
            if (answer != null) answers.add(answer);
        }
        return answers;
    }

    /** The first non-null answer, for questions where one answer is enough. */
    public Optional<A> first(Q question) {
        for (Provider<Q, A> provider : providers) {
            A answer = answerFrom(provider, question);
            if (answer != null) return Optional.of(answer);
        }
        return Optional.empty();
    }

    public boolean hasProviders() {
        return !providers.isEmpty();
    }

    public ResourceLocation id() {
        return id;
    }

    public Description describe() {
        return new Description(id, questionType, answerType, declaredBy,
            providers.stream().map(Provider::owner).toList());
    }

    private A answerFrom(Provider<Q, A> provider, Q question) {
        Object answer;
        try {
            answer = provider.answer().apply(question);
        } catch (RuntimeException e) {
            if (provider.failures().add(e.getClass().getName())) {
                LOGGER.error("Ink channel {}: provider from {} threw; it is skipped for such questions", id, provider.owner(), e);
            }
            return null;
        }
        if (answer == null) return null;
        if (!answerType.isInstance(answer)) {
            if (provider.failures().add("type:" + answer.getClass().getName())) {
                LOGGER.error("Ink channel {}: provider from {} answered {} instead of {}; answer ignored",
                    id, provider.owner(), answer.getClass().getName(), answerType.getName());
            }
            return null;
        }
        @SuppressWarnings("unchecked")
        A typed = (A) answer;
        return typed;
    }

    private static String currentMod() {
        try {
            return ModLoadingContext.get().getActiveNamespace();
        } catch (RuntimeException | LinkageError e) {
            return "unknown";
        }
    }
}
