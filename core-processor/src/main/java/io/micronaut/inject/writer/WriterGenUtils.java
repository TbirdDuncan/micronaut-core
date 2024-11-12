/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.inject.writer;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.reflect.InstantiationUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.KotlinParameterElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PrimitiveElement;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The writer utils.
 *
 * @author Denis Stepanov
 * @since 4.7
 */
@Internal
public final class WriterGenUtils {

    private static final TypeDef KOTLIN_CONSTRUCTOR_MARKER = TypeDef.of("kotlin.jvm.internal.DefaultConstructorMarker");

    private static final java.lang.reflect.Method INSTANTIATE_METHOD = ReflectionUtils.getRequiredInternalMethod(
        InstantiationUtils.class,
        "instantiate",
        Class.class,
        Class[].class,
        Object[].class
    );

    /**
     * The number of Kotlin defaults masks.
     *
     * @param parameters The parameters
     * @return The number if masks
     * @since 4.6.2
     */
    public static int calculateNumberOfKotlinDefaultsMasks(List<ParameterElement> parameters) {
        return (int) Math.ceil(parameters.size() / 32.0);
    }

    /**
     * Checks if parameter include Kotlin defaults.
     *
     * @param arguments The arguments
     * @return true if include
     * @since 4.6.2
     */
    public static boolean hasKotlinDefaultsParameters(List<ParameterElement> arguments) {
        return arguments.stream().anyMatch(p -> p instanceof KotlinParameterElement kp && kp.hasDefault());
    }

    public static ExpressionDef invokeBeanConstructor(MethodElement constructor,
                                                      boolean allowKotlinDefaults,
                                                      @Nullable
                                                      BiFunction<Integer, ParameterElement, ExpressionDef> argumentValueProvider) {
        return invokeBeanConstructor(constructor, constructor.isReflectionRequired(), allowKotlinDefaults, argumentValueProvider, null);
    }

    public static ExpressionDef invokeBeanConstructor(MethodElement constructor,
                                                      boolean requiresReflection,
                                                      boolean allowKotlinDefaults,
                                                      @Nullable
                                                      BiFunction<Integer, ParameterElement, ExpressionDef> argumentValueProvider,
                                                      @Nullable
                                                      BiFunction<Integer, ParameterElement, ExpressionDef> argumentValueIsPresentProvider) {
        ClassTypeDef beanType = (ClassTypeDef) TypeDef.erasure(constructor.getOwningType());

        boolean isConstructor = constructor.getName().equals("<init>");
        boolean isCompanion = constructor.getOwningType().getSimpleName().endsWith("$Companion");

        List<ParameterElement> constructorArguments = Arrays.asList(constructor.getParameters());
        boolean isKotlinDefault = allowKotlinDefaults && hasKotlinDefaultsParameters(constructorArguments);

        ExpressionDef[] masksExpressions = null;
        if (isKotlinDefault) {
            int numberOfMasks = calculateNumberOfKotlinDefaultsMasks(constructorArguments);
            // Calculate the Kotlin defaults mask
            // Every bit indicated true/false if the parameter should have the default value set
            masksExpressions = computeKotlinDefaultsMask(numberOfMasks, argumentValueProvider, argumentValueIsPresentProvider, constructorArguments);
        }

        List<ExpressionDef> constructorValues = constructorValues(constructor.getParameters(), argumentValueProvider);

        if (requiresReflection && !isCompanion) { // Companion reflection not implemented
            return ClassTypeDef.of(InstantiationUtils.class).invokeStatic(
                INSTANTIATE_METHOD,

                ExpressionDef.constant(beanType),
                TypeDef.OBJECT.array().instantiate(constructorValues)
            );
        }

        if (isConstructor) {
            if (isKotlinDefault) {
                List<ExpressionDef> values = new ArrayList<>();
                values.addAll(constructorValues);
                values.addAll(List.of(masksExpressions)); // Bit mask of defaults
                values.add(ExpressionDef.constant(null)); // Last parameter is just a marker and is always null
                return beanType.instantiate(
                    getDefaultKotlinConstructorParameters(constructor.getParameters(), masksExpressions.length),
                    values
                );
            }
            return beanType.instantiate(constructor, constructorValues);
        } else if (constructor.isStatic()) {
            return beanType.invokeStatic(constructor, constructorValues);
        } else if (isCompanion) {
            if (constructor.isStatic()) {
                return beanType.invokeStatic(constructor, constructorValues);
            }
            return ((ClassTypeDef) TypeDef.erasure(constructor.getReturnType()))
                .getStaticField("Companion", beanType)
                .invoke(constructor, constructorValues);
        }
        throw new IllegalStateException("Unknown constructor");
    }

    private static List<ExpressionDef> constructorValues(ParameterElement[] constructorArguments,
                                                         @Nullable
                                                         BiFunction<Integer, ParameterElement, ExpressionDef> argumentValueProvider) {
        List<ExpressionDef> expressions = new ArrayList<>(constructorArguments.length);
        for (int i = 0; i < constructorArguments.length; i++) {
            ParameterElement constructorArgument = constructorArguments[i];
            ExpressionDef value = argumentValueProvider.apply(i, constructorArgument);
            if (value == null) {
                ClassElement type = constructorArgument.getType();
                if (type.isPrimitive() && !type.isArray()) {
                    if (type.equals(PrimitiveElement.BOOLEAN)) {
                        expressions.add(ExpressionDef.falseValue());
                    } else {
                        expressions.add(TypeDef.Primitive.INT.constant(0).cast(TypeDef.erasure(type)));
                    }
                } else {
                    expressions.add(ExpressionDef.nullValue());
                }
            } else {
                expressions.add(value);
            }
        }
        return expressions;
    }

    private static List<TypeDef> getDefaultKotlinConstructorParameters(ParameterElement[] constructorArguments, int numberOfMasks) {
        List<TypeDef> parameters = new ArrayList<>(constructorArguments.length + numberOfMasks + 1);
        for (ParameterElement constructorArgument : constructorArguments) {
            parameters.add(TypeDef.erasure(constructorArgument.getType()));
        }
        for (int i = 0; i < numberOfMasks; i++) {
            parameters.add(TypeDef.Primitive.INT);
        }
        parameters.add(KOTLIN_CONSTRUCTOR_MARKER);
        return parameters;
    }

    /**
     * Create a method for Kotlin default invocation.
     *
     * @param owningType    The owing type
     * @param method        The method
     * @param numberOfMasks The number of default masks
     * @return A new method
     */
    static MethodDef asDefaultKotlinMethod(TypeDef owningType, MethodElement method, int numberOfMasks) {
        ParameterElement[] prevParameters = method.getSuspendParameters();
        List<TypeDef> parameters = new ArrayList<>(1 + prevParameters.length + numberOfMasks + 1);
        parameters.add(owningType);
        for (ParameterElement constructorArgument : prevParameters) {
            parameters.add(TypeDef.erasure(constructorArgument.getType()));
        }
        for (int i = 0; i < numberOfMasks; i++) {
            parameters.add(TypeDef.Primitive.INT);
        }
        parameters.add(TypeDef.OBJECT);
        return MethodDef.builder(method.getName() + "$default")
            .addParameters(parameters)
            .returns(method.isSuspend() ? TypeDef.OBJECT : TypeDef.erasure(method.getReturnType()))
            .build();
    }

    public static ExpressionDef[] computeKotlinDefaultsMask(int numberOfMasks,
                                                            @Nullable
                                                            BiFunction<Integer, ParameterElement, ExpressionDef> argumentValueProvider,
                                                            @Nullable
                                                            BiFunction<Integer, ParameterElement, ExpressionDef> argumentValueIsPresentProvider,
                                                            List<ParameterElement> parameters) {
        ExpressionDef[] masksLocal = new ExpressionDef[numberOfMasks];
        for (int i = 0; i < numberOfMasks; i++) {
            int fromIndex = i * 32;
            List<ParameterElement> params = parameters.subList(fromIndex, Math.min(fromIndex + 32, parameters.size()));
            if (argumentValueIsPresentProvider == null && argumentValueProvider == null) {
                masksLocal[i] = TypeDef.Primitive.INT.constant((int) ((long) Math.pow(2, params.size() + 1) - 1));
            } else {
                ExpressionDef maskValue = TypeDef.Primitive.INT.constant(0);
                int maskIndex = 1;
                int paramIndex = fromIndex;
                for (ParameterElement parameter : params) {
                    if (parameter instanceof KotlinParameterElement kp && kp.hasDefault()) {
                        maskValue = writeMask(argumentValueProvider, argumentValueIsPresentProvider, kp, paramIndex, maskIndex, maskValue);
                    }
                    maskIndex *= 2;
                    paramIndex++;
                }
                masksLocal[i] = maskValue;
            }
        }
        return masksLocal;
    }

    private static ExpressionDef writeMask(@Nullable
                                           BiFunction<Integer, ParameterElement, ExpressionDef> argumentValueProvider,
                                           @Nullable
                                           BiFunction<Integer, ParameterElement, ExpressionDef> argumentValueIsPresentProvider,
                                           KotlinParameterElement kp,
                                           int paramIndex,
                                           int maskIndex,
                                           ExpressionDef maskValue) {
        ExpressionDef useDefaultMask = maskValue.math("|", TypeDef.Primitive.INT.constant(maskIndex));
        if (argumentValueIsPresentProvider != null) {
            return argumentValueIsPresentProvider.apply(paramIndex, kp).isTrue().asConditionIfElse(
                maskValue,
                useDefaultMask
            );
        } else if (kp.getType().isPrimitive() && !kp.getType().isArray()) {
            // We cannot recognize the default from a primitive value
            return maskValue;
        } else if (argumentValueProvider != null) {
            return argumentValueProvider.apply(paramIndex, kp).isNonNull()
                .asConditionIfElse(maskValue, useDefaultMask);
        }
        return maskValue;
    }

}
