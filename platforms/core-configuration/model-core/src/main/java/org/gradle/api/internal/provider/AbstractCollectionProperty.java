/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.provider;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.Transformer;
import org.gradle.api.internal.provider.Collectors.ElementFromProvider;
import org.gradle.api.internal.provider.Collectors.ElementsFromArray;
import org.gradle.api.internal.provider.Collectors.ElementsFromCollection;
import org.gradle.api.internal.provider.Collectors.ElementsFromCollectionProvider;
import org.gradle.api.internal.provider.Collectors.SingleElement;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.api.provider.HasMultipleValues;
import org.gradle.api.provider.Provider;
import org.gradle.internal.Cast;
import org.gradle.internal.Pair;
import org.gradle.internal.collect.PersistentList;
import org.gradle.internal.evaluation.EvaluationScopeContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toList;

/**
 * The base class for collection properties.
 * <p>
 * Value suppliers for collection properties are implementations of {@link CollectionSupplier}.
 * </p>
 * <p>
 * Elements stored in collection property values are implemented via various implementations of {@link Collector}.
 * </p>
 * <h2>Collection suppliers</h2>
 * The value of a collection property is represented at any time as an instance of an implementation of {@link CollectionSupplier}, namely:
 * <ul>
 *     <li>{@link EmptySupplier}, the initial value of a collection (or after {@link #empty()} is invoked)</li>
 *     <li>{@link NoValueSupplier}, when the collection value is unset (via {@link #set(Iterable)} or {@link #unset()}.</li>
 *     <li>{@link FixedSupplier}, when the collection is finalized - in that case, the fixed supplier will wrap the realized
 *     of the Java collection this collection property corresponds to</li>
 *     <li>{@link CollectingSupplier}, when the collection is still being added to - in that case,
 *     the collecting supplier will wrap a {@link Collector} that lazily represents the yet-to-be realized contents of the collection - see below for details</li>
 * </ul>
 *
 * <h2>Collectors</h2>
 * <p>
 *     While a collection property's contents are being built up, its value is represented by a {@link CollectingSupplier}.
 *     The collecting supplier will wrap a {@link Collector} instance that represents the various forms that elements can be added to a collection property (before the collection is finalized), namely:
 * </p>
 *     <ul>
 *         <li>{@link SingleElement} to represent a single element addition
 *         <li>{@link ElementFromProvider} to represent a single element added as a provider
 *         <li>{@link ElementsFromArray} to represent a single element added as an array</li>
 *         <li>{@link ElementsFromCollection} to represent a batch of elements added (or set wholesale) as an <code>Iterable</code>
 *         <li>{@link ElementsFromCollectionProvider} to represent a batch of elements added (or set wholesale) as a provider of <code>Iterable</code>
 *     </ul>
 * <p>Also, if a collection is built up via multiple additions, which is quite common, after each addition operation, its value will be represented via a new {@link CollectionSupplier} instance.
 * Each addition operation adds an individual collector to the shared underlying append-only list of collectors.
 * </p>
 *
 * @param <T> the type of element this collection property can hold
 * @param <C> the type of {@link Collection} (as returned by {@link ProviderInternal#getType()}) that corresponds to this collection property's realized value, for instance, when {@link Provider#get()} is invoked.
 */
public abstract class AbstractCollectionProperty<T, C extends Collection<T>> extends AbstractProperty<C, CollectionSupplier<T, C>>
    implements CollectionPropertyInternal<T, C> {

    private final Class<? extends Collection> collectionType;
    private final Class<T> elementType;
    private final Supplier<ImmutableCollection.Builder<T>> collectionFactory;
    private final ValueCollector<T> valueCollector;
    private CollectionSupplier<T, C> defaultValue;

    AbstractCollectionProperty(PropertyHost host, Class<? extends Collection> collectionType, Class<T> elementType, Supplier<ImmutableCollection.Builder<T>> collectionFactory) {
        super(host);
        this.collectionType = collectionType;
        this.elementType = elementType;
        this.collectionFactory = collectionFactory;
        valueCollector = new ValidatingValueCollector<>(collectionType, elementType, ValueSanitizers.forType(elementType));
        init();
    }

    private void init() {
        defaultValue = emptySupplier();
        init(defaultValue, noValueSupplier());
    }

    @Override
    protected CollectionSupplier<T, C> getDefaultValue() {
        return defaultValue;
    }

    @Override
    protected CollectionSupplier<T, C> getDefaultConvention() {
        return noValueSupplier();
    }

    private CollectionSupplier<T, C> emptySupplier() {
        return new EmptySupplier();
    }

    private CollectionSupplier<T, C> noValueSupplier() {
        return new NoValueSupplier(Value.missing());
    }

    /**
     * Creates an empty immutable collection.
     */
    protected abstract C emptyCollection();

    protected Configurer getConfigurer(boolean ignoreAbsent) {
        return new Configurer(ignoreAbsent);
    }

    protected void withActualValue(Action<Configurer> action) {
        setToConventionIfUnset();
        action.execute(getConfigurer(true));
    }

    @Override
    protected boolean isDefaultConvention() {
        return isNoValueSupplier(getConventionSupplier());
    }

    private boolean isNoValueSupplier(CollectionSupplier<T, C> valueSupplier) {
        // Cannot use plain NoValueSupplier because of Java restrictions:
        // a generic type [AbstractCollectionProperty<T, C>.]NoValueSupplier cannot be used in instanceof.
        return valueSupplier instanceof AbstractCollectionProperty<?, ?>.NoValueSupplier;
    }

    @Override
    public void add(final T element) {
        getConfigurer(false).add(element);
    }

    @Override
    public void add(final Provider<? extends T> providerOfElement) {
        getConfigurer(false).add(providerOfElement);
    }

    @Override
    @SafeVarargs
    @SuppressWarnings("varargs")
    public final void addAll(T... elements) {
        getConfigurer(false).addAll(elements);
    }

    @Override
    public void addAll(Iterable<? extends T> elements) {
        getConfigurer(false).addAll(elements);
    }

    @Override
    public void addAll(Provider<? extends Iterable<? extends T>> provider) {
        getConfigurer(false).addAll(provider);
    }

    @Override
    public void append(T element) {
        withActualValue(it -> it.add(element));
    }

    @Override
    public void append(Provider<? extends T> provider) {
        withActualValue(it -> it.add(provider));
    }

    @Override
    @SuppressWarnings("varargs")
    @SafeVarargs
    public final void appendAll(T... elements) {
        withActualValue(it -> it.addAll(elements));
    }

    @Override
    public void appendAll(Iterable<? extends T> elements) {
        withActualValue(it -> it.addAll(elements));
    }

    @Override
    public void appendAll(Provider<? extends Iterable<? extends T>> provider) {
        withActualValue(it -> it.addAll(provider));
    }

    @Override
    public int size() {
        return calculateOwnPresentValue().getWithoutSideEffect().size();
    }

    /**
     * Adds the given supplier as the new root supplier for this collection.
     *
     * @param collector the collector to add
     * @param ignoreAbsent whether elements that are missing values should be ignored
     */
    private void addExplicitCollector(Collector<T> collector, boolean ignoreAbsent) {
        assertCanMutate();
        CollectionSupplier<T, C> explicitValue = getExplicitValue(defaultValue);
        setSupplier(explicitValue.plus(ignoreAbsent ? new AbsentIgnoringCollector<>(collector) : collector));
    }

    @Override
    @Nonnull
    public Class<C> getType() {
        return Cast.uncheckedNonnullCast(collectionType);
    }

    @Override
    public Class<T> getElementType() {
        return elementType;
    }

    /**
     * Sets the value of this property the given value.
     */
    public void fromState(ExecutionTimeValue<? extends C> value) {
        if (value.isMissing()) {
            setSupplier(noValueSupplier());
        } else if (value.hasFixedValue()) {
            setSupplier(new FixedSupplier(value.getFixedValue(), Cast.uncheckedCast(value.getSideEffect())));
        } else {
            CollectingSupplier<T, C> asSupplier = Cast.uncheckedNonnullCast(value.getChangingValue());
            setSupplier(asSupplier);
        }
    }

    @Override
    public void setFromAnyValue(Object object) {
        if (object instanceof Provider) {
            set(Cast.<Provider<C>>uncheckedCast(object));
        } else {
            if (object != null && !(object instanceof Iterable)) {
                throw new IllegalArgumentException(String.format("Cannot set the value of a property of type %s using an instance of type %s.", collectionType.getName(), object.getClass().getName()));
            }
            set(Cast.<Iterable<? extends T>>uncheckedCast(object));
        }
    }

    @Override
    public void set(@Nullable final Iterable<? extends T> elements) {
        if (elements == null) {
            unsetValueAndDefault();
        } else {
            setSupplier(newSupplierOf(new ElementsFromCollection<>(elements)));
        }
    }

    @Override
    public void set(final Provider<? extends Iterable<? extends T>> provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Cannot set the value of a property using a null provider.");
        }
        ProviderInternal<? extends Iterable<? extends T>> p = Providers.internal(provider);
        if (p.getType() != null && !Iterable.class.isAssignableFrom(p.getType())) {
            throw new IllegalArgumentException(String.format("Cannot set the value of a property of type %s using a provider of type %s.", collectionType.getName(), p.getType().getName()));
        }
        if (p instanceof CollectionPropertyInternal) {
            CollectionPropertyInternal<T, C> collectionProp = Cast.uncheckedCast(p);
            if (!elementType.isAssignableFrom(collectionProp.getElementType())) {
                throw new IllegalArgumentException(String.format("Cannot set the value of a property of type %s with element type %s using a provider with element type %s.", collectionType.getName(), elementType.getName(), collectionProp.getElementType().getName()));
            }
        }
        setSupplier(newSupplierOf(new ElementsFromCollectionProvider<>(p)));
    }

    private void unsetValueAndDefault() {
        // assign no-value default before restoring to it
        defaultValue = noValueSupplier();
        unset();
    }

    @Override
    public HasMultipleValues<T> value(@Nullable Iterable<? extends T> elements) {
        set(elements);
        return this;
    }

    @Override
    public HasMultipleValues<T> value(Provider<? extends Iterable<? extends T>> provider) {
        set(provider);
        return this;
    }

    @Override
    public HasMultipleValues<T> empty() {
        setSupplier(emptySupplier());
        return this;
    }

    @Override
    protected Value<? extends C> calculateValueFrom(EvaluationScopeContext context, CollectionSupplier<T, C> value, ValueConsumer consumer) {
        return value.calculateValue(consumer);
    }

    @Override
    protected CollectionSupplier<T, C> finalValue(EvaluationScopeContext context, CollectionSupplier<T, C> value, ValueConsumer consumer) {
        Value<? extends C> result = value.calculateValue(consumer);
        if (!result.isMissing()) {
            return new FixedSupplier(result.getWithoutSideEffect(), Cast.uncheckedCast(result.getSideEffect()));
        } else if (result.getPathToOrigin().isEmpty()) {
            return noValueSupplier();
        } else {
            return new NoValueSupplier(result);
        }
    }

    @Override
    protected ExecutionTimeValue<? extends C> calculateOwnExecutionTimeValue(EvaluationScopeContext context, CollectionSupplier<T, C> value) {
        return value.calculateExecutionTimeValue();
    }

    @Override
    public HasMultipleValues<T> convention(@Nullable Iterable<? extends T> elements) {
        if (elements == null) {
            unsetConvention();
        } else {
            setConvention(newSupplierOf(new ElementsFromCollection<>(elements)));
        }
        return this;
    }

    @Override
    public HasMultipleValues<T> convention(Provider<? extends Iterable<? extends T>> provider) {
        setConvention(newSupplierOf(new ElementsFromCollectionProvider<>(Providers.internal(provider))));
        return this;
    }

    @Override
    protected String describeContents() {
        String typeDisplayName = collectionType.getSimpleName().toLowerCase(Locale.ROOT);
        return String.format("%s(%s, %s)", typeDisplayName, elementType, describeValue());
    }

    class NoValueSupplier implements CollectionSupplier<T, C> {
        private final Value<? extends C> value;

        public NoValueSupplier(Value<? extends C> value) {
            assert value.isMissing();
            this.value = value.asType();
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return false;
        }

        @Override
        public Value<? extends C> calculateValue(ValueConsumer consumer) {
            return value;
        }

        @Override
        public CollectionSupplier<T, C> plus(Collector<T> collector) {
            // No value + something = no value, unless something is absent-ignoring.
            if (isAbsentIgnoring(collector)) {
                return newSupplierOf(collector);
            }
            return this;
        }

        @Override
        public ExecutionTimeValue<? extends C> calculateExecutionTimeValue() {
            return ExecutionTimeValue.missing();
        }

        @Override
        public ValueProducer getProducer() {
            return ValueProducer.unknown();
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    private class EmptySupplier implements CollectionSupplier<T, C> {

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return true;
        }

        @Override
        public Value<? extends C> calculateValue(ValueConsumer consumer) {
            return Value.of(emptyCollection());
        }

        @Override
        public CollectionSupplier<T, C> plus(Collector<T> collector) {
            // empty + something = something
            return newSupplierOf(collector);
        }

        @Override
        public ExecutionTimeValue<? extends C> calculateExecutionTimeValue() {
            return ExecutionTimeValue.fixedValue(emptyCollection());
        }

        @Override
        public ValueProducer getProducer() {
            return ValueProducer.noProducer();
        }

        @Override
        public String toString() {
            return "[]";
        }
    }

    private class FixedSupplier implements CollectionSupplier<T, C> {
        private final C value;
        private final SideEffect<? super C> sideEffect;

        public FixedSupplier(C value, @Nullable SideEffect<? super C> sideEffect) {
            this.value = value;
            this.sideEffect = sideEffect;
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return true;
        }

        @Override
        public Value<? extends C> calculateValue(ValueConsumer consumer) {
            return Value.of(value).withSideEffect(sideEffect);
        }

        @Override
        public CollectionSupplier<T, C> plus(Collector<T> collector) {
            return newSupplierOf(new FixedValueCollector<>(value, sideEffect)).plus(collector);
        }

        @Override
        public ExecutionTimeValue<? extends C> calculateExecutionTimeValue() {
            return ExecutionTimeValue.fixedValue(value).withSideEffect(sideEffect);
        }

        @Override
        public ValueProducer getProducer() {
            return ValueProducer.unknown();
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    private CollectingSupplier<T, C> newSupplierOf(Collector<T> value) {
        return new CollectingSupplier<>(getType(), collectionFactory, valueCollector, value);
    }

    private static class CollectingSupplier<T, C extends Collection<T>> extends AbstractMinimalProvider<C> implements CollectionSupplier<T, C> {
        private final Class<C> type;
        private final Supplier<ImmutableCollection.Builder<T>> collectionFactory;
        private final ValueCollector<T> valueCollector;
        private final PersistentList<Collector<T>> reversedCollectors;

        public CollectingSupplier(Class<C> type, Supplier<ImmutableCollection.Builder<T>> collectionFactory, ValueCollector<T> valueCollector, Collector<T> value) {
            this(type, collectionFactory, valueCollector, PersistentList.of(value));
        }

        // A constructor for sharing.
        private CollectingSupplier(Class<C> type, Supplier<ImmutableCollection.Builder<T>> collectionFactory, ValueCollector<T> valueCollector, PersistentList<Collector<T>> reversedCollectors) {
            this.type = type;
            this.collectionFactory = collectionFactory;
            this.valueCollector = valueCollector;
            this.reversedCollectors = reversedCollectors;
        }

        @Override
        protected Value<? extends C> calculateOwnValue(ValueConsumer consumer) {
            return calculateValue(consumer);
        }

        @Nullable
        @Override
        public Class<C> getType() {
            return type;
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            // We're traversing the elements in reverse addition order.
            // When determining the presence of the value, the last argument wins.
            // See also #collectExecutionTimeValues().
            for (Collector<T> collector : reversedCollectors) {
                if (!collector.calculatePresence(consumer)) {
                    // We've found an argument of add/addAll that is missing.
                    // It makes the property missing regardless of what has been added before.
                    // Because of the reverse processing order, anything that was added after it was just add/addAll that do not change the presence.
                    return false;
                }
                if (isAbsentIgnoring(collector)) {
                    // We've found an argument of append/appendAll, and everything added before it was present.
                    // append/appendAll recovers the value of a missing property, so the property is also definitely present.
                    return true;
                }
            }
            // Nothing caused the property to become missing. There is at least one element by design, so the property is present.
            assert !reversedCollectors.isEmpty();
            return true;
        }

        @Override
        public Value<C> calculateValue(ValueConsumer consumer) {
            // TODO - don't make a copy when the collector already produces an immutable collection
            ArrayList<T> buffer = new ArrayList<>();
            CollectionBuilder<T, List<T>> builder = CollectionBuilder.of(buffer);
            // We cannot use 0 because we need to distinguish between "an empty list is definitely present" and "nothing is definitely present".
            // All collectors may produce empty lists.
            int lastDefinitelyPresentPosition = -1;
            int lastDefinitelyPresentSideEffect = 0;
            List<SideEffect<C>> sideEffects = new ArrayList<>();

            for (Collector<T> collector : reversedCollectors) {
                int sizeBefore = builder.build().size();
                Value<Void> result = collector.collectEntries(consumer, valueCollector, builder);
                if (result.isMissing()) {
                    // This is the argument of add/addAll and it is missing. It discards everything added before it (which we haven't processed yet), and
                    // everything that was added before the append/appendAll.
                    if (lastDefinitelyPresentPosition >= 0) {
                        // There was at least one append after this. Discard everything that happened in between.
                        buffer.subList(lastDefinitelyPresentPosition, buffer.size()).clear();
                        sideEffects.subList(lastDefinitelyPresentSideEffect, sideEffects.size()).clear();
                    } else {
                        // No append to recover. The property's value is missing.
                        return result.asType();
                    }
                    break;
                } else {
                    SideEffect<C> effect = SideEffect.fixedFrom(result);
                    if (effect != null) {
                        sideEffects.add(effect);
                    }

                    if (isAbsentIgnoring(collector)) {
                        // This is an argument of append/appendAll. Everything that was added after it is already present.
                        // Entries are already in the builder.
                        lastDefinitelyPresentPosition = buffer.size();
                        lastDefinitelyPresentSideEffect = sideEffects.size();
                    }
                    if (sizeBefore < buffer.size()) {
                        // This is a relatively well-known trick of reversing the order of substrings, described e.g. in "Programming Pearls" by Bentley, J., 1986
                        // For example, if we have two collectors [a, b] and [1, 2, 3], in that order. We process them in reverse:
                        // 1. [1, 2, 3] - contribute individual output
                        // 2. [3, 2, 1] - reverse individual output
                        // 3. [3, 2, 1, a, b] - contribute individual output of another one
                        // 4. [3, 2, 1, b, a] - reverse individual output of another one
                        // 5. [a, b, 1, 2, 3] - reverse the result.
                        Collections.reverse(buffer.subList(sizeBefore, buffer.size()));
                    }
                }
            }
            Collections.reverse(sideEffects);
            return Value.of(Cast.<C>uncheckedNonnullCast(collectionFactory.get().addAll(Lists.reverse(buffer)).build())).withSideEffect(SideEffect.composite(sideEffects));
        }

        @Override
        public CollectionSupplier<T, C> plus(Collector<T> addedCollector) {
            return new CollectingSupplier<>(type, collectionFactory, valueCollector, reversedCollectors.plus(addedCollector));
        }

        @Override
        public ExecutionTimeValue<? extends C> calculateExecutionTimeValue() {
            List<Pair<Collector<T>, ExecutionTimeValue<? extends Iterable<? extends T>>>> collectorsWithValues = collectExecutionTimeValues();
            if (collectorsWithValues.isEmpty()) {
                return ExecutionTimeValue.missing();
            }
            List<ExecutionTimeValue<? extends Iterable<? extends T>>> values = collectorsWithValues.stream().map(Pair::getRight).collect(toList());

            boolean fixed = true;
            boolean changingContent = false;

            for (ExecutionTimeValue<? extends Iterable<? extends T>> value : values) {
                assert !value.isMissing();

                if (value.isChangingValue()) {
                    fixed = false;
                } else if (value.hasChangingContent()) {
                    changingContent = true;
                }
            }

            if (fixed) {
                return getFixedExecutionTimeValue(values, changingContent);
            }

            PersistentList<Collector<T>> shrinkedReversedCollectors = PersistentList.of();
            for (Pair<Collector<T>, ExecutionTimeValue<? extends Iterable<? extends T>>> pair : collectorsWithValues) {
                Collector<T> elements = toCollector(pair.getRight());
                shrinkedReversedCollectors = shrinkedReversedCollectors.plus(isAbsentIgnoring(pair.getLeft())
                    ? new AbsentIgnoringCollector<>(elements)
                    : elements);
            }

            // At least one of the values is a changing value. Simplify the provider.
            return ExecutionTimeValue.changingValue(new CollectingSupplier<>(type, collectionFactory, valueCollector, shrinkedReversedCollectors));
        }

        private Collector<T> toCollector(ExecutionTimeValue<? extends Iterable<? extends T>> value) {
            Preconditions.checkArgument(!value.isMissing(), "Cannot get a collector for the missing value");
            if (value.isChangingValue() || value.hasChangingContent() || value.getSideEffect() != null) {
                return new ElementsFromCollectionProvider<>(value.toProvider());
            }
            return new ElementsFromCollection<>(value.getFixedValue());
        }

        // Returns an empty list when the overall value is missing.
        private List<Pair<Collector<T>, ExecutionTimeValue<? extends Iterable<? extends T>>>> collectExecutionTimeValues() {
            List<Pair<Collector<T>, ExecutionTimeValue<? extends Iterable<? extends T>>>> executionTimeValues = new ArrayList<>(reversedCollectors.size());

            // We traverse the collectors backwards (in reverse addition order) to simplify the logic and avoid processing things that are going to be discarded.
            // Because of that, values are collected in reverse order too.
            // See also #calculatePresence.
            // The values before that are certainly part of the result, e.g. because of absent-ignoring append/appendAll argument.
            int lastDefinitelyPresentValue = 0;
            for (Collector<T> collector : reversedCollectors) {
                ExecutionTimeValue<? extends Iterable<? extends T>> result = collector.calculateExecutionTimeValue();
                if (result.isMissing()) {
                    // This is an add/addAll argument, but it is a missing provider.
                    // Everything that was added before it isn't going to affect the result, so we stop the iteration.
                    // All add/addAll that happened after it (thus already processed) but before any append/appendAll - the contents of candidates - are also discarded.
                    executionTimeValues.subList(lastDefinitelyPresentValue, executionTimeValues.size()).clear();
                    break;
                }
                if (isAbsentIgnoring(collector)) {
                    // This is an argument of append/appendAll. With it the property is going to be present (though maybe empty).
                    // As all add/addAll arguments we've processed (thus added after this one) so far weren't missing, we're sure they'll be part of the final property's value.
                    // Move them to the executionTimeValues.
                    executionTimeValues.add(Pair.of(collector, result));
                    lastDefinitelyPresentValue = executionTimeValues.size();
                } else {
                    // This is an argument of add/addAll that isn't definitely missing. It might be part of the final value.
                    executionTimeValues.add(Pair.of(collector, result));
                }
            }
            // No missing values found, so all the candidates are part of the final value.
            return Lists.reverse(executionTimeValues);
        }

        private ExecutionTimeValue<C> getFixedExecutionTimeValue(List<ExecutionTimeValue<? extends Iterable<? extends T>>> values, boolean changingContent) {
            ImmutableCollection.Builder<T> builder = collectionFactory.get();
            SideEffectBuilder<C> sideEffectBuilder = SideEffect.builder();
            for (ExecutionTimeValue<? extends Iterable<? extends T>> value : values) {
                builder.addAll(value.getFixedValue());
                sideEffectBuilder.add(SideEffect.fixedFrom(value));
            }

            ExecutionTimeValue<C> mergedValue = ExecutionTimeValue.fixedValue(Cast.uncheckedNonnullCast(builder.build()));
            if (changingContent) {
                mergedValue = mergedValue.withChangingContent();
            }

            return mergedValue.withSideEffect(sideEffectBuilder.build());
        }

        @Override
        public ValueProducer getProducer() {
            List<ValueProducer> producers = reversedCollectors.stream().map(ValueSupplier::getProducer).collect(ImmutableList.toImmutableList()).reverse();
            return new ValueProducer() {
                @Override
                public void visitProducerTasks(Action<? super Task> visitor) {
                    producers.forEach(c -> c.visitProducerTasks(visitor));
                }

                @Override
                public boolean isKnown() {
                    return producers.stream().anyMatch(ValueProducer::isKnown);
                }

                @Override
                public void visitDependencies(TaskDependencyResolveContext context) {
                    producers.forEach(c -> c.visitDependencies(context));
                }

                @Override
                public void visitContentProducerTasks(Action<? super Task> visitor) {
                    producers.forEach(c -> c.visitContentProducerTasks(visitor));
                }
            };
        }

        @Override
        protected String toStringNoReentrance() {
            StringBuilder sb = new StringBuilder();
            reversedCollectors.forEach(collector -> {
                if (sb.length() > 0) {
                    sb.append(" + ");
                }
                sb.append(new StringBuilder(collector.toString()).reverse());
            });
            return sb.reverse().toString();
        }
    }

    /**
     * A fixed value collector, similar to {@link ElementsFromCollection} but with a side effect.
     */
    private static class FixedValueCollector<T, C extends Collection<T>> implements Collector<T> {
        @Nullable
        private final SideEffect<? super C> sideEffect;
        private final C collection;

        private FixedValueCollector(C collection, @Nullable SideEffect<? super C> sideEffect) {
            this.collection = collection;
            this.sideEffect = sideEffect;
        }

        @Override
        public Value<Void> collectEntries(ValueConsumer consumer, ValueCollector<T> collector, CollectionBuilder<T, ?> dest) {
            collector.addAll(collection, dest);
            return sideEffect != null
                ? Value.present().withSideEffect(SideEffect.fixed(collection, sideEffect))
                : Value.present();
        }

        @Override
        public int size() {
            return collection.size();
        }

        @Override
        public ExecutionTimeValue<? extends Iterable<? extends T>> calculateExecutionTimeValue() {
            return ExecutionTimeValue.fixedValue(collection).withSideEffect(sideEffect);
        }

        @Override
        public ValueProducer getProducer() {
            return ValueProducer.unknown();
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return true;
        }

        @Override
        public String toString() {
            return collection.toString();
        }
    }

    private static boolean isAbsentIgnoring(Collector<?> collector) {
        return collector instanceof AbsentIgnoringCollector<?>;
    }

    private static class AbsentIgnoringCollector<T> implements Collector<T> {
        private final Collector<T> delegate;

        private AbsentIgnoringCollector(Collector<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Value<Void> collectEntries(ValueConsumer consumer, ValueCollector<T> collector, CollectionBuilder<T, ?> dest) {
            List<T> candidateEntries = new ArrayList<>();
            Value<Void> value = delegate.collectEntries(consumer, collector, CollectionBuilder.of(candidateEntries));
            if (value.isMissing()) {
                return Value.present();
            }
            dest.addAll(candidateEntries);
            return Value.present().withSideEffect(SideEffect.fixedFrom(value));
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public ExecutionTimeValue<? extends Iterable<? extends T>> calculateExecutionTimeValue() {
            ExecutionTimeValue<? extends Iterable<? extends T>> executionTimeValue = delegate.calculateExecutionTimeValue();
            return executionTimeValue.isMissing() ? ExecutionTimeValue.fixedValue(ImmutableList.of()) : executionTimeValue;
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return true;
        }

        @Override
        public ValueProducer getProducer() {
            return delegate.getProducer();
        }
    }

    public void replace(Transformer<? extends @org.jetbrains.annotations.Nullable Provider<? extends Iterable<? extends T>>, ? super Provider<C>> transformation) {
        Provider<? extends Iterable<? extends T>> newValue = transformation.transform(shallowCopy());
        if (newValue != null) {
            set(newValue);
        } else {
            set((Iterable<? extends T>) null);
        }
    }

    private class Configurer {
        private final boolean ignoreAbsent;

        public Configurer(boolean ignoreAbsent) {
            this.ignoreAbsent = ignoreAbsent;
        }

        protected void addCollector(Collector<T> collector) {
            addExplicitCollector(collector, ignoreAbsent);
        }

        public void add(final T element) {
            Preconditions.checkNotNull(element, "Cannot add a null element to a property of type %s.", collectionType.getSimpleName());
            addCollector(new SingleElement<>(element));
        }

        public void add(final Provider<? extends T> providerOfElement) {
            addCollector(new ElementFromProvider<>(Providers.internal(providerOfElement)));
        }

        @SafeVarargs
        @SuppressWarnings("varargs")
        public final void addAll(T... elements) {
            addCollector(new ElementsFromArray<>(elements));
        }

        public void addAll(Iterable<? extends T> elements) {
            addCollector(new ElementsFromCollection<>(elements));
        }

        public void addAll(Provider<? extends Iterable<? extends T>> provider) {
            addCollector(new ElementsFromCollectionProvider<>(Providers.internal(provider)));
        }

    }
}
