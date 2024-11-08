/*
 * Copyright 2017-2023 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.runtime.beans;

import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.BootstrapContextCompatible;
import io.micronaut.context.annotation.Mapper;
import io.micronaut.context.annotation.Mapper.MergeStrategy;
import io.micronaut.context.expressions.ConfigurableExpressionEvaluationContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospection.Builder;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.beans.exceptions.IntrospectionException;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.format.Format;
import io.micronaut.core.expressions.EvaluatedExpression;
import io.micronaut.core.expressions.ExpressionEvaluationContext;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ObjectUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.annotation.EvaluatedAnnotationMetadata;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.qualifiers.Qualifiers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Introduction advice for {@link Mapper}.
 */
@InterceptorBean(Mapper.class)
@Internal
@BootstrapContextCompatible
final class MapperIntroduction implements MethodInterceptor<Object, Object> {

    private final ApplicationContext applicationContext;
    private final ConversionService conversionService;
    private final Map<ExecutableMethod<?, ?>, MapInvocation> cachedInvocations = new ConcurrentHashMap<>();

    MapperIntroduction(ConversionService conversionService, ApplicationContext applicationContext) {
        this.conversionService = conversionService;
        this.applicationContext = applicationContext;
    }

    @Override
    public int getOrder() {
        return -100; // higher precedence
    }

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        if (context.hasDeclaredAnnotation(Mapper.class)) {
            ExecutableMethod<Object, Object> key = context.getExecutableMethod();
            MapInvocation invocation = cachedInvocations.get(key);
            if (invocation == null) {
                if (context.getArguments().length == 1) {
                    invocation = createMappingInvocation(context);
                } else {
                    invocation = createMergingInvocation(context);
                }
                cachedInvocations.put(key, invocation);
            }

            return invocation.map(
                context
            );
        } else {
            return context.proceed();
        }
    }

    private MapInvocation createMappingInvocation(MethodInvocationContext<Object, Object> context) {
        Argument<Object> toType = context.getReturnType().asArgument();
        BeanIntrospection<Object> toIntrospection = BeanIntrospection.getIntrospection(toType.getType());
        // should never be empty, validated at compile time
        Argument<Object> fromArgument = (Argument<Object>) context.getArguments()[0];
        Class<Object> fromClass = fromArgument.getType();

        Supplier<MappingBuilder<Object>> builderSupplier = () -> new DefaultMappingBuilder<>(toIntrospection.builder());
        AnnotationMetadata annotationMetadata = context.getAnnotationMetadata();
        Mapper.ConflictStrategy conflictStrategy = annotationMetadata.enumValue(Mapper.class, "conflictStrategy", Mapper.ConflictStrategy.class)
            .orElse(null);
        boolean isMap = Map.class.isAssignableFrom(fromClass);
        BeanIntrospection<Object> fromIntrospection = isMap ? null : BeanIntrospection.getIntrospection(fromClass);

        if (annotationMetadata.isPresent(Mapper.class, AnnotationMetadata.VALUE_MEMBER)) {
            List<AnnotationValue<Mapper.Mapping>> annotations = context.getAnnotationValuesByType(Mapper.Mapping.class);

            Map<String, Function<Object, BiConsumer<Object, MappingBuilder<Object>>>> customMappers = buildCustomMappers(
                fromArgument, fromIntrospection, toIntrospection, conflictStrategy, annotations, isMap
            );

            List<Function<Object, BiConsumer<Object, MappingBuilder<Object>>>> rootMappers = buildRootMappers(
                fromIntrospection, conflictStrategy, annotations, isMap
            );

            // requires runtime evaluation
            if (!customMappers.isEmpty() || rootMappers != null) {
                if (isMap) {
                    return callContext -> {
                        MapStrategy mapStrategy = buildMapStrategy(conflictStrategy, customMappers, rootMappers, callContext);
                        return map(
                            (Map<String, Object>) callContext.getParameterValues()[0], mapStrategy, builderSupplier.get()
                        ).build();
                    };
                } else {
                    return callContext -> {
                        MapStrategy mapStrategy = buildMapStrategy(conflictStrategy, customMappers, rootMappers, callContext);
                        return map(
                            callContext.getParameterValues()[0], mapStrategy, fromIntrospection, builderSupplier.get()
                        ).build();
                    };
                }
            } else {
                return mapDefault(builderSupplier, fromIntrospection, isMap);
            }
        } else {
            return mapDefault(builderSupplier, fromIntrospection, isMap);
        }
    }

    private MergeStrategy createMergeStrategy(AnnotationMetadata annotationMetadata, ApplicationContext applicationContext) {
        return annotationMetadata.stringValue(Mapper.class, "mergeStrategy")
            .map(name -> {
                if (Mapper.MERGE_STRATEGY_NOT_NULL_OVERRIDE.equals(name)) {
                    return new NotNullOverrideMergeStrategy();
                }
                if (Mapper.MERGE_STRATEGY_ALWAYS_OVERRIDE.equals(name)) {
                    return new AlwaysOverrideMergeStrategy();
                }
                return applicationContext.getBean(Mapper.MergeStrategy.class, Qualifiers.byName(name));
            })
            .orElse(new NotNullOverrideMergeStrategy());
    }

    private MapInvocation createMergingInvocation(MethodInvocationContext<Object, Object> context) {
        Argument<Object> toType = context.getReturnType().asArgument();
        BeanIntrospection<Object> toIntrospection = BeanIntrospection.getIntrospection(toType.getType());
        AnnotationMetadata annotationMetadata = context.getAnnotationMetadata();
        Mapper.ConflictStrategy conflictStrategy = annotationMetadata.enumValue(Mapper.class, "conflictStrategy", Mapper.ConflictStrategy.class)
            .orElse(null);
        MergeStrategy mergeStrategy = createMergeStrategy(annotationMetadata, applicationContext);

        int nArguments = context.getArguments().length;
        MappingContext[] contexts = new MappingContext[nArguments];

        for (int i = 0; i < context.getArguments().length; i++) {
            // should never be empty, validated at compile time
            Argument<Object> fromArgument = (Argument<Object>) context.getArguments()[i];
            Class<Object> fromClass = fromArgument.getType();
            boolean isMap = Map.class.isAssignableFrom(fromClass);
            BeanIntrospection<Object> fromIntrospection = isMap ? null : BeanIntrospection.getIntrospection(fromClass);

            Map<String, Function<Object, BiConsumer<Object, MappingBuilder<Object>>>> customMapperSuppliers = null;
            List<Function<Object, BiConsumer<Object, MappingBuilder<Object>>>> rootMapperSuppliers = null;
            if (annotationMetadata.isPresent(Mapper.class, AnnotationMetadata.VALUE_MEMBER)) {
                List<AnnotationValue<Mapper.Mapping>> annotations = context.getAnnotationValuesByType(Mapper.Mapping.class);
                customMapperSuppliers = buildCustomMappers(fromArgument, fromIntrospection, toIntrospection, conflictStrategy, annotations, isMap);
                rootMapperSuppliers = buildRootMappers(fromIntrospection, conflictStrategy, annotations, isMap);
            }
            contexts[i] = new MappingContext(
                customMapperSuppliers, rootMapperSuppliers, isMap,
                (customMapperSuppliers != null && !customMapperSuppliers.isEmpty()) || rootMapperSuppliers != null,
                fromIntrospection
            );
        }

        return callContext -> {
            MergeBuilderWrapper<Object> builder = new MergeBuilderWrapper<>(toIntrospection.builder(), mergeStrategy);

            for (int i = 0; i < contexts.length; ++i) {
                builder.setArgIndex(i);
                MappingContext mappingContext = contexts[i];
                if (mappingContext.needsCustom) {
                    if (mappingContext.isMap) {
                        MapStrategy mapStrategy = buildMapStrategy(conflictStrategy, mappingContext.customMapperSuppliers, mappingContext.rootMapperSuppliers, callContext);
                        map((Map<String, Object>) callContext.getParameterValues()[i], mapStrategy, builder);
                    } else {
                        MapStrategy mapStrategy = buildMapStrategy(conflictStrategy, mappingContext.customMapperSuppliers, mappingContext.rootMapperSuppliers, callContext);
                        map(callContext.getParameterValues()[i], mapStrategy, mappingContext.fromIntrospection, builder);
                    }
                } else if (mappingContext.isMap) {
                    map(
                        (Map<String, Object>) callContext.getParameterValues()[i],
                        MapStrategy.DEFAULT,
                        builder
                    );
                } else {
                    map(
                        callContext.getParameterValues()[i],
                        MapStrategy.DEFAULT,
                        mappingContext.fromIntrospection,
                        builder
                    );
                }
            }
            return builder.build();
        };
    }

    private @Nullable List<Function<Object, BiConsumer<Object, MappingBuilder<Object>>>> buildRootMappers(
        BeanIntrospection<Object> fromIntrospection,
        Mapper.ConflictStrategy conflictStrategy,
        List<AnnotationValue<Mapper.Mapping>> annotations,
        boolean isMap) {
        List<Function<Object, BiConsumer<Object, MappingBuilder<Object>>>> rootMappers = new ArrayList<>(5);
        for (AnnotationValue<Mapper.Mapping> annotation : annotations) {
            // a root mapping contains no object to bind to, so we assume we bind to the root
            if (!annotation.contains(Mapper.Mapping.MEMBER_TO) && annotation.contains(Mapper.Mapping.MEMBER_FROM)) {
                Map<CharSequence, Object> values = annotation.getValues();
                Object from = values.get(Mapper.Mapping.MEMBER_FROM);
                Object condition = values.get(Mapper.Mapping.MEMBER_CONDITION);
                EvaluatedExpression evaluatedCondition = condition instanceof EvaluatedExpression ee ? ee : null;

                if (from instanceof EvaluatedExpression evaluatedExpression) {
                    if (evaluatedCondition != null) {
                        rootMappers.add(expressionEvaluationContext ->
                            (object, builder) -> {
                                ExpressionEvaluationContext evaluationContext = (ExpressionEvaluationContext) expressionEvaluationContext;
                                if (ObjectUtils.coerceToBoolean(evaluatedCondition.evaluate(evaluationContext))) {
                                    Object v = evaluatedExpression.evaluate(evaluationContext);
                                    if (v != null) {
                                        mapAllFromValue(conflictStrategy, builder, v);
                                    }
                                }
                            }
                        );
                    } else {
                        rootMappers.add((expressionEvaluationContext ->
                            (object, builder) -> {
                                ExpressionEvaluationContext evaluationContext = (ExpressionEvaluationContext) expressionEvaluationContext;
                                Object v = evaluatedExpression.evaluate(evaluationContext);
                                if (v != null) {
                                    mapAllFromValue(conflictStrategy, builder, v);
                                }
                            }
                        ));
                    }
                } else if (from != null) {
                    String propertyName = from.toString();
                    if (fromIntrospection != null) {
                        BeanProperty<Object, Object> fromProperty = fromIntrospection.getRequiredProperty(propertyName, Object.class);
                        rootMappers.add((expressionEvaluationContext -> (object, builder) -> {
                            Object result = fromProperty.get(object);
                            if (result != null) {
                                mapAllFromValue(conflictStrategy, builder, result);
                            }
                        }));
                    } else if (isMap) {
                        rootMappers.add((expressionEvaluationContext -> (object, builder) -> {
                            Object result = ((Map<String, Object>) object).get(propertyName);
                            if (result != null) {
                                mapAllFromValue(conflictStrategy, builder, result);
                            }
                        }));
                    }
                }
            }
        }
        if (rootMappers.isEmpty()) {
            return null;
        } else {
            return Collections.unmodifiableList(rootMappers);
        }
    }

    private void mapAllFromValue(Mapper.ConflictStrategy conflictStrategy, MappingBuilder<Object> builder, Object object) {
        BeanIntrospection<Object> nestedFrom;
        try {
            //noinspection unchecked
            nestedFrom = (BeanIntrospection<Object>) BeanIntrospection.getIntrospection(object.getClass());
        } catch (IntrospectionException e) {
            throw new IllegalArgumentException("Invalid @Mapping(from=..) declaration. The source property must declared @Introspected: " + e.getMessage(), e);
        }
        @NonNull Collection<BeanProperty<Object, Object>> propertyNames = nestedFrom.getBeanProperties();
        for (BeanProperty<Object, Object> property : propertyNames) {
            if (property.isWriteOnly()) {
                continue;
            }
            int i = builder.indexOf(property.getName());
            if (i > -1) {
                @SuppressWarnings("unchecked")
                Argument<Object> argument = (Argument<Object>) builder.getBuilderArguments()[i];
                Object propertyValue = property.get(object);
                if (argument.isInstance(propertyValue)) {
                    builder.with(i, argument, propertyValue, property.getName(), object);
                } else if (conflictStrategy == Mapper.ConflictStrategy.CONVERT) {
                    builder.convert(i, ConversionContext.of(argument), propertyValue, conversionService, property.getName(), object);
                } else {
                    throw new IllegalArgumentException("Cannot map invalid value [" + propertyValue + "] to type: " + argument);
                }
            }
        }
    }

    private static MapStrategy buildMapStrategy(
        Mapper.ConflictStrategy conflictStrategy,
        Map<String, Function<Object, BiConsumer<Object, MappingBuilder<Object>>>> customMappers,
        @Nullable List<Function<Object, BiConsumer<Object, MappingBuilder<Object>>>> rootMappers,
        MethodInvocationContext<Object, Object> callContext
    ) {
        MapStrategy mapStrategy = new MapStrategy(conflictStrategy);
        AnnotationMetadata callAnnotationMetadata = callContext.getAnnotationMetadata();
        if (callAnnotationMetadata instanceof EvaluatedAnnotationMetadata evaluatedAnnotationMetadata) {
            ConfigurableExpressionEvaluationContext evaluationContext = evaluatedAnnotationMetadata.getEvaluationContext();
            customMappers.forEach((name, mapperSupplier) -> mapStrategy.customMappers.put(name, mapperSupplier.apply(evaluationContext)));
            if (rootMappers != null) {
                for (Function<Object, BiConsumer<Object, MappingBuilder<Object>>> mapSupplier : rootMappers) {
                    mapStrategy.rootMappers.add(mapSupplier.apply(evaluationContext));
                }
            }
        } else {
            customMappers.forEach((name, mapperSupplier) -> mapStrategy.customMappers.put(name, mapperSupplier.apply(null)));
            if (rootMappers != null) {
                for (Function<Object, BiConsumer<Object, MappingBuilder<Object>>> mapSupplier : rootMappers) {
                    mapStrategy.rootMappers.add(mapSupplier.apply(null));
                }
            }
        }

        return mapStrategy;
    }

    private Map<String, Function<Object, BiConsumer<Object, MappingBuilder<Object>>>> buildCustomMappers(
        Argument<Object> methodArgument,
        BeanIntrospection<Object> fromIntrospection,
        BeanIntrospection<Object> toIntrospection,
        Mapper.ConflictStrategy conflictStrategy,
        List<AnnotationValue<Mapper.Mapping>> annotations,
        boolean isMap
    ) {
        Map<String, Function<Object, BiConsumer<Object, MappingBuilder<Object>>>> customMappers = new HashMap<>();
        BeanIntrospection.Builder<Object> builderMeta = toIntrospection.builder();
        @NonNull Argument<?>[] builderArguments = builderMeta.getBuilderArguments();
        for (AnnotationValue<Mapper.Mapping> mapping : annotations) {
            String to = mapping.stringValue(Mapper.Mapping.MEMBER_TO).orElse(null);
            String format = mapping.stringValue(Mapper.Mapping.MEMBER_FORMAT).orElse(null);


            if (StringUtils.isNotEmpty(to)) {
                int i = builderMeta.indexOf(to);
                if (i == -1) {
                    continue;
                }
                @SuppressWarnings("unchecked") Argument<Object> argument = (Argument<Object>) builderArguments[i];
                ArgumentConversionContext<?> conversionContext = null;
                if (format != null) {
                    conversionContext = ConversionContext.of(argument);
                    MutableAnnotationMetadata annotationMetadata = new MutableAnnotationMetadata();
                    annotationMetadata.addAnnotation(Format.class.getName(), Map.of(AnnotationMetadata.VALUE_MEMBER, format));
                    conversionContext = conversionContext.with(new AnnotationMetadataHierarchy(argument.getAnnotationMetadata(), annotationMetadata));
                } else if (conflictStrategy == Mapper.ConflictStrategy.CONVERT || conflictStrategy == null) {
                    conversionContext = ConversionContext.of(argument);
                }

                Object defaultValue;
                Map<CharSequence, Object> values = mapping.getValues();
                if (mapping.contains(Mapper.Mapping.MEMBER_DEFAULT_VALUE)) {
                    defaultValue = mapping.stringValue(Mapper.Mapping.MEMBER_DEFAULT_VALUE)
                        .flatMap(v -> conversionService.convert(v, argument))
                        .orElseThrow(() -> new IllegalStateException("Invalid defaultValue [" + values.get(Mapper.Mapping.MEMBER_DEFAULT_VALUE) + "] specified to @Mapping annotation for type " + argument));
                } else {
                    defaultValue = null;
                }

                Object from = values.get(Mapper.Mapping.MEMBER_FROM);
                Object condition = values.get(Mapper.Mapping.MEMBER_CONDITION);
                EvaluatedExpression evaluatedCondition = condition instanceof EvaluatedExpression ee ? ee : null;
                ArgumentConversionContext<Object> finalConversionContext = (ArgumentConversionContext<Object>) conversionContext;
                if (from instanceof EvaluatedExpression evaluatedExpression) {
                    if (evaluatedCondition != null) {
                        customMappers.put(to, (expressionEvaluationContext ->
                            (object, builder) -> {
                                ExpressionEvaluationContext evaluationContext = (ExpressionEvaluationContext) expressionEvaluationContext;
                                if (ObjectUtils.coerceToBoolean(evaluatedCondition.evaluate(evaluationContext))) {
                                    Object v = evaluatedExpression.evaluate(evaluationContext);
                                    handleValue(i, argument, defaultValue, finalConversionContext, builder, v, null, object);
                                } else if (defaultValue != null) {
                                    builder.with(i, argument, defaultValue, to, object);
                                }
                            }
                        ));
                    } else {
                        customMappers.put(to, (expressionEvaluationContext ->
                            (object, builder) -> {
                                ExpressionEvaluationContext evaluationContext = (ExpressionEvaluationContext) expressionEvaluationContext;
                                Object v = evaluatedExpression.evaluate(evaluationContext);
                                handleValue(i, argument, defaultValue, finalConversionContext, builder, v, null, object);
                            }
                        ));
                    }
                } else if (from != null) {
                    String propertyName = from.toString();
                    String methodArgumentName = null;
                    if (propertyName.contains(".")) {
                        int index = propertyName.indexOf('.');
                        methodArgumentName = propertyName.substring(0, index);
                        propertyName = propertyName.substring(index + 1);
                    }
                    String finalPropertyName = propertyName;
                    if (methodArgumentName == null || methodArgumentName.equals(methodArgument.getName())) {
                        if (fromIntrospection != null) {
                            BeanProperty<Object, Object> fromProperty = fromIntrospection.getRequiredProperty(propertyName, Object.class);
                            customMappers.put(to, (expressionEvaluationContext -> (object, builder) -> {
                                Object result = fromProperty.get(object);
                                handleValue(i, argument, defaultValue, finalConversionContext, builder, result, finalPropertyName, object);
                            }));
                        } else if (isMap) {
                            customMappers.put(to, (expressionEvaluationContext -> (object, builder) -> {
                                Object result = ((Map<String, Object>) object).get(finalPropertyName);
                                handleValue(i, argument, defaultValue, finalConversionContext, builder, result, finalPropertyName, object);
                            }));
                        }
                    }
                }

            }
        }
        return customMappers;
    }

    private void handleValue(int index, Argument<Object> argument, Object defaultValue, ArgumentConversionContext<Object> conversionContext, MappingBuilder<Object> builder, Object value, String mappedPropertyName, Object owner) {
        if (value == null) {
            if (defaultValue != null) {
                builder.with(index, argument, defaultValue, mappedPropertyName, owner);
            } else {
                builder.with(index, argument, null, mappedPropertyName, owner);
            }
        } else if (argument.isInstance(value)) {
            builder.with(index, argument, value, mappedPropertyName, owner);
        } else if (conversionContext != null) {
            builder.convert(index, conversionContext, value, conversionService, mappedPropertyName, owner);
        } else {
            throw new IllegalArgumentException("Cannot map invalid value [" + value + "] to type: " + argument);
        }
    }

    private MapInvocation mapDefault(Supplier<MappingBuilder<Object>> builderSupplier, BeanIntrospection<Object> fromIntrospection, boolean isMap) {
        MapInvocation invocation;
        if (isMap) {
            invocation = callContext -> map(
                (Map<String, Object>) callContext.getParameterValues()[0],
                MapStrategy.DEFAULT,
                builderSupplier.get()
            ).build();
        } else {
            invocation = callContext -> map(
                callContext.getParameterValues()[0],
                MapStrategy.DEFAULT,
                fromIntrospection,
                builderSupplier.get()
            ).build();
        }
        return invocation;
    }

    private  <I, O> MappingBuilder<O> map(I input, MapStrategy mapStrategy, BeanIntrospection<I> inputIntrospection, MappingBuilder<O> builder) {
        boolean isDefault = mapStrategy == MapStrategy.DEFAULT;
        Mapper.ConflictStrategy conflictStrategy = mapStrategy.conflictStrategy();
        @SuppressWarnings("unchecked") @NonNull Argument<Object>[] arguments = (Argument<Object>[]) builder.getBuilderArguments();

        if (!isDefault) {
            processCustomMappers(input, mapStrategy, builder);
        }
        for (BeanProperty<I, Object> beanProperty : inputIntrospection.getBeanProperties()) {
            if (!beanProperty.isWriteOnly()) {
                String propertyName = beanProperty.getName();
                if (!isDefault && mapStrategy.customMappers().containsKey(propertyName)) {
                    continue;
                }
                int i = builder.indexOf(propertyName);
                if (i > -1) {
                    Argument<Object> argument = arguments[i];
                    Object value = beanProperty.get(input);
                    if (value == null || argument.isInstance(value)) {
                        builder.with(i, argument, value, propertyName, input);
                    } else if (conflictStrategy == Mapper.ConflictStrategy.CONVERT) {
                        ArgumentConversionContext<Object> conversionContext = ConversionContext.of(argument);
                        builder.convert(i, conversionContext, value, conversionService, propertyName, input);
                    } else {
                        builder.with(i, argument, value, propertyName, input);
                    }
                }
            }
        }
        return builder;
    }

    private <I, O> void processCustomMappers(I input, MapStrategy mapStrategy, MappingBuilder<O> builder) {
        Map<String, BiConsumer<Object, MappingBuilder<Object>>> customMappers = mapStrategy.customMappers();
        customMappers.forEach((name, func) -> {
            int i = builder.indexOf(name);
            if (i > -1) {
                func.accept(input, (MappingBuilder<Object>) builder);
            }
        });
        List<BiConsumer<Object, MappingBuilder<Object>>> rootMappers = mapStrategy.rootMappers();
        for (BiConsumer<Object, MappingBuilder<Object>> rootMapper : rootMappers) {
            rootMapper.accept(input, (MappingBuilder<Object>) builder);
        }
    }

    private <O> MappingBuilder<O> map(Map<String, Object> input, MapStrategy mapStrategy, MappingBuilder<O> builder) {
        @NonNull Argument<Object>[] arguments = (Argument<Object>[]) builder.getBuilderArguments();
        handleMapInput(input, mapStrategy, builder, arguments);
        return builder;
    }

    private <O> void handleMapInput(Map<String, Object> input, MapStrategy mapStrategy, MappingBuilder<O> builder, @NonNull Argument<Object>[] arguments) {
        Mapper.ConflictStrategy conflictStrategy = mapStrategy.conflictStrategy();
        boolean isDefault = mapStrategy == MapStrategy.DEFAULT;
        if (!isDefault) {
            processCustomMappers(input, mapStrategy, builder);
        }
        input.forEach((key, value) -> {
            int i = builder.indexOf(key);
            if (!isDefault && mapStrategy.customMappers().containsKey(key)) {
                return;
            }
            if (i > -1) {
                Argument<Object> argument = arguments[i];
                if (conflictStrategy == Mapper.ConflictStrategy.CONVERT) {
                    builder.convert(i, ConversionContext.of(argument), value, conversionService, key, input);
                } else {
                    builder.with(i, argument, value, key, input);
                }
            }
        });
    }


    @FunctionalInterface
    private interface MapInvocation {
        Object map(MethodInvocationContext<Object, Object> invocationContext);
    }

    private record MapStrategy(
        Mapper.ConflictStrategy conflictStrategy,
        Map<String, BiConsumer<Object, MappingBuilder<Object>>> customMappers,
        List<BiConsumer<Object, MappingBuilder<Object>>> rootMappers) {
        static final MapStrategy DEFAULT = new MapStrategy(Mapper.ConflictStrategy.CONVERT, Collections.emptyMap(), List.of());

        private MapStrategy {
            if (conflictStrategy == null) {
                conflictStrategy = Mapper.ConflictStrategy.CONVERT;
            }
            if (customMappers == null) {
                customMappers = new HashMap<>(10);
            }
            if (rootMappers == null) {
                rootMappers = new ArrayList<>(3);
            }
        }

        public MapStrategy(Mapper.ConflictStrategy conflictStrategy) {
            this(conflictStrategy, new HashMap<>(10), new ArrayList<>(3));
        }
    }

    private interface MappingBuilder<B> {

        @NonNull <A> MappingBuilder<B> with(int index, Argument<A> argument, A value, String mappedPropertyName, Object owner);

        @NonNull Argument<?>[] getBuilderArguments();

        int indexOf(String name);

        B build(Object... params);

        <A> MappingBuilder<B> convert(int index, ArgumentConversionContext<A> of, A value, ConversionService conversionService, String mappedPropertyName, Object owner);
    }

    private static class DefaultMappingBuilder<B> implements MappingBuilder<B> {

        private final BeanIntrospection.Builder<B> builder;

        public DefaultMappingBuilder(Builder<B> builder) {
            this.builder = builder;
        }

        @Override
        public <A> MappingBuilder<B> with(int index, Argument<A> argument, A value, String mappedPropertyName, Object owner) {
            builder.with(index, argument, value);
            return this;
        }

        @Override
        public @NonNull Argument<?>[] getBuilderArguments() {
            return builder.getBuilderArguments();
        }

        @Override
        public int indexOf(String name) {
            return builder.indexOf(name);
        }

        @Override
        public B build(Object... params) {
            return builder.build(params);
        }

        @Override
        public <A> MappingBuilder<B> convert(int index, ArgumentConversionContext<A> of, A value, ConversionService conversionService, String mappedPropertyName, Object owner) {
            builder.convert(index, of, value, conversionService);
            return this;
        }
    }

    private static class MergeBuilderWrapper<B> implements MappingBuilder<B> {

        private final BeanIntrospection.Builder<B> builder;
        private final MergeStrategy mergeStrategy;
        private final Argument<?>[] arguments;
        private final Object[] params;
        private int argIndex = 0;

        public MergeBuilderWrapper(
                BeanIntrospection.Builder<B> builder,
                MergeStrategy mergeStrategy
        ) {
            this.builder = builder;
            this.arguments = builder.getBuilderArguments();
            this.params = new Object[arguments.length];
            this.mergeStrategy = mergeStrategy;
        }

        public MergeBuilderWrapper<B> setArgIndex(int argIndex) {
            this.argIndex = argIndex;
            return this;
        }

        @Override
        public <A> MappingBuilder<B> with(int index, Argument<A> argument, A value, String mappedPropertyName, Object owner) {
            if (argIndex == 0) {
                params[index] = value;
            } else {
                params[index] = mergeStrategy.merge(params[index], value, owner, argument.getName(), mappedPropertyName);
            }
            return this;
        }

        @Override
        public @NonNull Argument<?>[] getBuilderArguments() {
            return arguments;
        }

        @Override
        public int indexOf(String name) {
            return builder.indexOf(name);
        }

        @Override
        public B build(Object... builderParams) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] == null) {
                    continue;
                }
                builder.with(i, (Argument<Object>) arguments[i], params[i]);
            }
            return builder.build(builderParams);
        }

        @Override
        public <A> MappingBuilder<B> convert(int index, ArgumentConversionContext<A> conversionContext, A value, ConversionService conversionService, String mappedPropertyName, Object owner) {
            Argument<A> argument = conversionContext.getArgument();
            if (value != null) {
                if (!argument.isInstance(value)) {
                    value = conversionService.convertRequired(value, conversionContext);
                }
                with(index, argument, value, mappedPropertyName, owner);
            }
            return this;
        }
    }

    private record MappingContext(
        Map<String, Function<Object, BiConsumer<Object, MappingBuilder<Object>>>> customMapperSuppliers,
        List<Function<Object, BiConsumer<Object, MappingBuilder<Object>>>> rootMapperSuppliers,
        boolean isMap,
        boolean needsCustom,
        BeanIntrospection<Object> fromIntrospection
    ) {
    }

    private static final class NotNullOverrideMergeStrategy implements MergeStrategy {
        @Override
        public Object merge(Object currentValue, Object value, Object valueOwner, String propertyName, String mappedPropertyName) {
            return value != null ? value : currentValue;
        }
    }

    private static final class AlwaysOverrideMergeStrategy implements MergeStrategy {
        @Override
        public Object merge(Object currentValue, Object value, Object valueOwner, String propertyName, String mappedPropertyName) {
            return value;
        }
    }

}
