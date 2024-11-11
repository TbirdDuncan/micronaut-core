/*
 * Copyright 2017-2021 original authors
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

import io.micronaut.context.AbstractExecutableMethodsDefinition;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.KotlinParameterElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.sourcegen.ByteCodeWriter;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.GeneratorAdapter;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Switch based dispatch writer.
 *
 * @author Denis Stepanov
 * @since 4.7
 */
@Internal
public final class DispatchWriter2 implements ClassOutputWriter {

    private static final MethodDef GET_ACCESSIBLE_TARGET_METHOD = MethodDef.builder("getAccessibleTargetMethodByIndex")
            .returns(Method.class)
            .addParameters(int.class)
            .build();

    private static final MethodDef UNKNOWN_DISPATCH_AT_INDEX = MethodDef.builder("unknownDispatchAtIndexException")
            .addParameters(int.class)
            .returns(RuntimeException.class)
            .build();

    private static final String FIELD_INTERCEPTABLE = "$interceptable";

    private static final ClassTypeDef TYPE_REFLECTION_UTILS = ClassTypeDef.of(ReflectionUtils.class);

    private static final Method METHOD_GET_REQUIRED_METHOD = ReflectionUtils.getRequiredInternalMethod(ReflectionUtils.class, "getRequiredMethod", Class.class, String.class, Class[].class);

    private static final Method METHOD_INVOKE_METHOD = ReflectionUtils.getRequiredInternalMethod(ReflectionUtils.class, "invokeMethod", Object.class, java.lang.reflect.Method.class, Object[].class);

    private static final Method METHOD_GET_FIELD_VALUE =
            ReflectionUtils.getRequiredInternalMethod(ReflectionUtils.class, "getField", Class.class, String.class, Object.class);

    private static final Method METHOD_SET_FIELD_VALUE = ReflectionUtils.getRequiredInternalMethod(ReflectionUtils.class, "setField", Class.class, String.class, Object.class, Object.class);

    private final List<DispatchTarget> dispatchTargets = new ArrayList<>();
    private final ClassTypeDef thisType;
    private final ClassTypeDef dispatchSuperType;

    private boolean hasInterceptedMethod;

    public DispatchWriter2(ClassTypeDef thisType) {
        this(thisType, ClassTypeDef.of(AbstractExecutableMethodsDefinition.class));
    }

    public DispatchWriter2(ClassTypeDef thisType, ClassTypeDef dispatchSuperType) {
        super();
        this.thisType = thisType;
        this.dispatchSuperType = dispatchSuperType;
    }

    /**
     * Adds new set field dispatch target.
     *
     * @param beanField The field
     * @return the target index
     */
    public int addSetField(FieldElement beanField) {
        return addDispatchTarget(new FieldSetDispatchTarget(beanField));
    }

    /**
     * Adds new get field dispatch target.
     *
     * @param beanField The field
     * @return the target index
     */
    public int addGetField(FieldElement beanField) {
        return addDispatchTarget(new FieldGetDispatchTarget(beanField));
    }

    /**
     * Adds new method dispatch target.
     *
     * @param declaringType The declaring type
     * @param methodElement The method element
     * @return the target index
     */
    public int addMethod(TypedElement declaringType, MethodElement methodElement) {
        return addMethod(declaringType, methodElement, false);
    }

    /**
     * Adds new method dispatch target.
     *
     * @param declaringType  The declaring type
     * @param methodElement  The method element
     * @param useOneDispatch If method should be dispatched using "dispatchOne"
     * @return the target index
     */
    public int addMethod(TypedElement declaringType, MethodElement methodElement, boolean useOneDispatch) {
        DispatchTarget dispatchTarget = findDispatchTarget(declaringType, methodElement);
        return addDispatchTarget(dispatchTarget);
    }

    private DispatchTarget findDispatchTarget(TypedElement declaringType, MethodElement methodElement) {
        List<ParameterElement> argumentTypes = Arrays.asList(methodElement.getSuspendParameters());
        boolean isKotlinDefault = argumentTypes.stream().anyMatch(p -> p instanceof KotlinParameterElement kp && kp.hasDefault());
        ClassElement declaringClassType = (ClassElement) declaringType;
        if (methodElement.isReflectionRequired()) {
            if (isKotlinDefault) {
                throw new ProcessingException(methodElement, "Kotlin default methods are not supported for reflection invocation");
            }
            return new MethodReflectionDispatchTarget(declaringType, methodElement, dispatchTargets.size());
        } else if (isKotlinDefault) {
            return new KotlinMethodWithDefaultsDispatchTarget(declaringClassType, methodElement);
        }
        return new MethodDispatchTarget(declaringClassType, methodElement);
    }

    /**
     * Adds new interceptable method dispatch target.
     *
     * @param declaringType                    The declaring type
     * @param methodElement                    The method element
     * @param interceptedProxyClassName        The interceptedProxyClassName
     * @param interceptedProxyBridgeMethodName The interceptedProxyBridgeMethodName
     * @return the target index
     */
    public int addInterceptedMethod(TypedElement declaringType,
                                    MethodElement methodElement,
                                    String interceptedProxyClassName,
                                    String interceptedProxyBridgeMethodName) {
        hasInterceptedMethod = true;
        return addDispatchTarget(new InterceptableMethodDispatchTarget(
                findDispatchTarget(declaringType, methodElement),
                declaringType,
                methodElement,
                interceptedProxyClassName,
                interceptedProxyBridgeMethodName)
        );
    }

    /**
     * Adds new custom dispatch target.
     *
     * @param dispatchTarget The dispatch target implementation
     * @return the target index
     */
    public int addDispatchTarget(DispatchTarget dispatchTarget) {
        dispatchTargets.add(dispatchTarget);
        return dispatchTargets.size() - 1;
    }

    /**
     * Build dispatch method if needed.
     *
     * @param classWriter The classwriter
     */
    @Nullable
    public void buildDispatchMethod(ClassWriter classWriter) {
        MethodDef methodDef = buildDispatchMethod();
        if (methodDef == null) {
            return;
        }

        ClassDef classDef = ClassDef.builder(thisType.getName()).build();
        new ByteCodeWriter().writeMethod(classWriter, classDef, methodDef);
    }

    @Nullable
    public MethodDef buildDispatchMethod() {
        int[] cases = dispatchTargets.stream()
                .filter(DispatchTarget::supportsDispatchMulti)
                .mapToInt(dispatchTargets::indexOf)
                .toArray();
        if (cases.length == 0) {
            return null;
        }

        return MethodDef.builder("dispatch")
                .addModifiers(Modifier.PROTECTED, Modifier.FINAL)
                .addParameters(int.class, Object.class, Object[].class)
                .returns(TypeDef.OBJECT)
                .build((aThis, methodParameters) -> {

                    VariableDef.MethodParameter methodIndex = methodParameters.get(0);
                    VariableDef.MethodParameter target = methodParameters.get(1);
                    VariableDef.MethodParameter argsArray = methodParameters.get(2);

                    Map<ExpressionDef.Constant, StatementDef> switchCases = CollectionUtils.newHashMap(cases.length + 1);
                    for (int caseIndex : cases) {
                        DispatchTarget dispatchTarget = dispatchTargets.get(caseIndex);
                        StatementDef statementDef = dispatchTarget.dispatch(target, argsArray);
                        switchCases.put(ExpressionDef.constant(caseIndex), statementDef);
                    }

                    switchCases.put(ExpressionDef.nullValue(), aThis.invoke(UNKNOWN_DISPATCH_AT_INDEX, methodIndex).doThrow());

                    return StatementDef.multi(
                            methodParameters.get(0).asStatementSwitch(TypeDef.OBJECT, switchCases),
                            ExpressionDef.nullValue().returning()
                    );
                });
    }

    /**
     * Build dispatch one method if needed.
     *
     * @param classWriter The classwriter
     */
    public void buildDispatchOneMethod(ClassWriter classWriter) {
        MethodDef methodDef = buildDispatchOneMethod();
        if (methodDef == null) {
            return;
        }

        ClassDef classDef = ClassDef.builder(thisType.getName()).build();
        new ByteCodeWriter().writeMethod(classWriter, classDef, methodDef);
    }

    public MethodDef buildDispatchOneMethod() {
        int[] cases = dispatchTargets.stream()
                .filter(DispatchTarget::supportsDispatchOne)
                .mapToInt(dispatchTargets::indexOf)
                .toArray();
        if (cases.length == 0) {
            return null;
        }

        return MethodDef.builder("dispatchOne")
                .addModifiers(Modifier.PROTECTED, Modifier.FINAL)
                .addParameters(int.class, Object.class, Object.class)
                .returns(TypeDef.OBJECT)
                .build((aThis, methodParameters) -> {

                    VariableDef.MethodParameter methodIndex = methodParameters.get(0);
                    VariableDef.MethodParameter target = methodParameters.get(1);
                    VariableDef.MethodParameter value = methodParameters.get(2);

                    Map<ExpressionDef.Constant, StatementDef> switchCases = CollectionUtils.newHashMap(cases.length + 1);
                    for (int caseIndex : cases) {
                        DispatchTarget dispatchTarget = dispatchTargets.get(caseIndex);
                        StatementDef statementDef = dispatchTarget.dispatch(target, value);
                        switchCases.put(ExpressionDef.constant(caseIndex), statementDef);
                    }

                    switchCases.put(ExpressionDef.nullValue(), aThis.invoke(UNKNOWN_DISPATCH_AT_INDEX, methodIndex).doThrow());

                    return StatementDef.multi(
                            methodParameters.get(0).asStatementSwitch(TypeDef.OBJECT, switchCases),
                            ExpressionDef.nullValue().returning()
                    );
                });
    }

    /**
     * Build get target method by index method if needed.
     *
     * @param classWriter The classwriter
     */
    public void buildGetTargetMethodByIndex(ClassWriter classWriter) {
        MethodDef methodDef = buildGetTargetMethodByIndex();
        if (methodDef == null) {
            return;
        }

        ClassDef classDef = ClassDef.builder(thisType.getName()).build();
        new ByteCodeWriter().writeMethod(classWriter, classDef, methodDef);
    }

    @Nullable
    public MethodDef buildGetTargetMethodByIndex() {
        int[] cases = dispatchTargets.stream()
                // Should we include methods that don't require reflection???
                .filter(dispatchTarget -> dispatchTarget.getMethodElement() != null)
                .mapToInt(dispatchTargets::indexOf)
                .toArray();
        if (cases.length == 0) {
            return null;
        }

        return MethodDef.builder("getTargetMethodByIndex")
                .addModifiers(Modifier.PROTECTED, Modifier.FINAL)
                .addParameters(int.class)
                .returns(Method.class)
                .build((aThis, methodParameters) -> {

                    VariableDef.MethodParameter methodIndex = methodParameters.get(0);

                    Map<ExpressionDef.Constant, StatementDef> switchCases = CollectionUtils.newHashMap(cases.length + 1);
                    for (int caseIndex : cases) {
                        DispatchTarget dispatchTarget = dispatchTargets.get(caseIndex);
                        MethodElement methodElement = dispatchTarget.getMethodElement();

                        StatementDef statement = TYPE_REFLECTION_UTILS.invokeStatic(METHOD_GET_REQUIRED_METHOD,

                                ExpressionDef.constant(ClassTypeDef.of(methodElement.getDeclaringType())),
                                ExpressionDef.constant(methodElement.getName()),
                                ClassTypeDef.of(Class.class).array().instantiate(
                                        Arrays.stream(methodElement.getSuspendParameters())
                                                .map(p -> ExpressionDef.constant(TypeDef.of(p.getType())))
                                                .toList()
                                )
                        ).returning();
                        switchCases.put(ExpressionDef.constant(caseIndex), statement);
                    }

                    switchCases.put(ExpressionDef.nullValue(), aThis.invoke(UNKNOWN_DISPATCH_AT_INDEX, methodIndex).doThrow());

                    return StatementDef.multi(
                            methodParameters.get(0).asStatementSwitch(TypeDef.OBJECT, switchCases),
                            ExpressionDef.nullValue().returning()
                    );
                });
    }

    @Override
    public void accept(ClassWriterOutputVisitor classWriterOutputVisitor) throws IOException {
        throw new IllegalStateException();
    }

    /**
     * @return all added dispatch targets
     */
    public List<DispatchTarget> getDispatchTargets() {
        return dispatchTargets;
    }

    /**
     * @return if intercepted method dispatch have been added
     */
    public boolean isHasInterceptedMethod() {
        return hasInterceptedMethod;
    }

    /**
     * Dispatch target implementation writer.
     */
    @Internal
    public interface DispatchTarget {

        /**
         * @return true if writer supports dispatch one.
         */
        default boolean supportsDispatchOne() {
            return true;
        }

        /**
         * @return true if writer supports dispatch multi.
         */
        default boolean supportsDispatchMulti() {
            return true;
        }

        StatementDef dispatch(ExpressionDef target, ExpressionDef valuesArray);

        MethodElement getMethodElement();

        TypedElement getDeclaringType();

    }

    /**
     * Dispatch target implementation writer.
     */
    @Internal
    public abstract static class AbstractDispatchTarget implements DispatchTarget {

        @Override
        public StatementDef dispatch(ExpressionDef target, ExpressionDef valuesArray) {
            ExpressionDef expression = dispatchMultiExpression(target, valuesArray);
            if (getMethodElement().getReturnType().isVoid()) {
                return StatementDef.multi(
                        (StatementDef) expression,
                        ExpressionDef.nullValue().returning()
                );
            }
            return expression.returning();
        }

        protected ExpressionDef dispatchMultiExpression(ExpressionDef target, ExpressionDef valuesArray) {
            MethodElement methodElement = getMethodElement();
            if (methodElement == null) {
                return dispatchMultiExpression(target, List.of(valuesArray.arrayElement(0)));
            }
            return dispatchMultiExpression(target,
                    IntStream.range(0, methodElement.getSuspendParameters().length).mapToObj(valuesArray::arrayElement).toList()
            );
        }

        protected ExpressionDef dispatchMultiExpression(ExpressionDef target, List<? extends ExpressionDef> values) {
            return dispatchOneExpression(target, values.get(0));
        }

        protected ExpressionDef dispatchOneExpression(ExpressionDef target, ExpressionDef value) {
            return dispatchExpression(target);
        }

        protected ExpressionDef dispatchExpression(ExpressionDef target) {
            throw new IllegalStateException("Not supported");
        }

    }

    /**
     * State carried between different {@link DispatchTarget}s. This allows for code size reduction
     * by sharing bytecode in the same method.
     */
    @Internal
    public interface DispatchTargetState {
        /**
         * Complete writing this state.
         *
         * @param writer The method writer
         */
        void complete(GeneratorAdapter writer);
    }

    /**
     * Field get dispatch target.
     */
    @Internal
    public static final class FieldGetDispatchTarget extends AbstractDispatchTarget {
        @NonNull
        final FieldElement beanField;

        public FieldGetDispatchTarget(FieldElement beanField) {
            this.beanField = beanField;
        }

        @Override
        public MethodElement getMethodElement() {
            return null;
        }

        @Override
        public TypedElement getDeclaringType() {
            return null;
        }

        @Override
        public ExpressionDef dispatchExpression(ExpressionDef bean) {
            final ClassTypeDef propertyType = ClassTypeDef.of(beanField.getType());
            final ClassTypeDef targetType = ClassTypeDef.of(beanField.getOwningType());

            if (beanField.isReflectionRequired()) {
                return TYPE_REFLECTION_UTILS.invokeStatic(
                        METHOD_GET_FIELD_VALUE,
                        ExpressionDef.constant(targetType), // Target class
                        ExpressionDef.constant(beanField.getName()), // Field name,
                        bean // Target instance
                ).cast(propertyType);
            } else {
                return bean.cast(targetType).field(beanField).cast(propertyType);
            }
        }

        @NonNull
        public FieldElement getField() {
            return beanField;
        }
    }

    /**
     * Field set dispatch target.
     */
    @Internal
    public static final class FieldSetDispatchTarget extends AbstractDispatchTarget {
        @NonNull
        final FieldElement beanField;

        public FieldSetDispatchTarget(FieldElement beanField) {
            this.beanField = beanField;
        }

        @Override
        public MethodElement getMethodElement() {
            return null;
        }

        @Override
        public TypedElement getDeclaringType() {
            return null;
        }

        @Override
        public StatementDef dispatch(ExpressionDef target, ExpressionDef valuesArray) {
            final ClassTypeDef propertyType = ClassTypeDef.of(beanField.getType());
            final ClassTypeDef targetType = ClassTypeDef.of(beanField.getOwningType());
            ExpressionDef.ArrayElement fieldValue = valuesArray.arrayElement(0);
            if (beanField.isReflectionRequired()) {
                return TYPE_REFLECTION_UTILS.invokeStatic(METHOD_SET_FIELD_VALUE,
                        ExpressionDef.constant(targetType), // Target class
                        ExpressionDef.constant(beanField.getName()), // Field name
                        target, // Target instance
                        fieldValue // Field value
                ).after(ExpressionDef.nullValue().returning());
            } else {
                return target.cast(propertyType)
                        .field(beanField)
                        .put(fieldValue.cast(propertyType))
                        .after(ExpressionDef.nullValue().returning());
            }
        }

        @NonNull
        public FieldElement getField() {
            return beanField;
        }
    }

    /**
     * Method invocation dispatch target.
     */
    @Internal
    public static final class MethodDispatchTarget extends AbstractDispatchTarget {
        final ClassElement declaringType;
        final MethodElement methodElement;

        private MethodDispatchTarget(ClassElement targetType,
                                     MethodElement methodElement) {
            this.declaringType = targetType;
            this.methodElement = methodElement;
        }

        @Override
        public ClassElement getDeclaringType() {
            return declaringType;
        }

        @Override
        public MethodElement getMethodElement() {
            return methodElement;
        }

        @Override
        public ExpressionDef dispatchMultiExpression(ExpressionDef target, List<? extends ExpressionDef> values) {
            ClassTypeDef targetType = ClassTypeDef.of(declaringType);
            if (methodElement.isStatic()) {
                return targetType.invokeStatic(methodElement, values);
            }
            return target.cast(targetType).invoke(methodElement, values);
        }

        @Override
        public ExpressionDef dispatchOneExpression(ExpressionDef target, ExpressionDef value) {
            ClassTypeDef targetType = ClassTypeDef.of(declaringType);
            if (methodElement.isStatic()) {
                return targetType.invokeStatic(methodElement, TypeDef.OBJECT.array().instantiate(value));
            }
            return target.cast(targetType).invoke(methodElement, value);
        }
    }

    /**
     * Method invocation dispatch target.
     */
    @Internal
    public static final class KotlinMethodWithDefaultsDispatchTarget extends AbstractDispatchTarget {
        final ClassElement declaringType;
        final MethodElement methodElement;

        private KotlinMethodWithDefaultsDispatchTarget(ClassElement targetType,
                                                       MethodElement methodElement) {
            this.declaringType = targetType;
            this.methodElement = methodElement;
        }

        @Override
        public ClassElement getDeclaringType() {
            return declaringType;
        }

        @Override
        public MethodElement getMethodElement() {
            return methodElement;
        }

        @Override
        public ExpressionDef dispatchMultiExpression(ExpressionDef target, List<? extends ExpressionDef> prevValues) {
            int numberOfMasks = WriterGenUtils.calculateNumberOfKotlinDefaultsMasks(List.of(methodElement.getSuspendParameters()));
            ExpressionDef[] masks = WriterGenUtils.computeKotlinDefaultsMask(numberOfMasks, (index, parameterElement) -> prevValues.get(index), null, List.of(methodElement.getSuspendParameters()));
            List<ExpressionDef> values = new ArrayList<>();
            values.add(target);
            values.addAll(prevValues);
            values.addAll(List.of(masks)); // Bit mask of defaults
            values.add(ExpressionDef.nullValue()); // Last parameter is just a marker and is always null

            MethodDef defaultKotlinMethod = WriterGenUtils.asDefaultKotlinMethod(TypeDef.of(declaringType), methodElement, numberOfMasks);

            return ClassTypeDef.of(declaringType).invokeStatic(defaultKotlinMethod, values);
        }

        @Override
        public ExpressionDef dispatchOneExpression(ExpressionDef target, ExpressionDef value) {
            int numberOfMasks = WriterGenUtils.calculateNumberOfKotlinDefaultsMasks(List.of(methodElement.getSuspendParameters()));
            ExpressionDef[] masks = WriterGenUtils.computeKotlinDefaultsMask(numberOfMasks, (index, parameterElement) -> {
                if (index != 0) {
                    throw new IllegalStateException("Only one parameter is supported!");
                }
                return value;
            }, null, List.of(methodElement.getSuspendParameters()));
            List<ExpressionDef> values = new ArrayList<>();
            values.add(target);
            values.add(value);
            values.addAll(List.of(masks)); // Bit mask of defaults
            values.add(ExpressionDef.nullValue()); // Last parameter is just a marker and is always null

            MethodDef defaultKotlinMethod = WriterGenUtils.asDefaultKotlinMethod(TypeDef.of(declaringType), methodElement, numberOfMasks);

            return ClassTypeDef.of(declaringType).invokeStatic(defaultKotlinMethod, values);
        }
    }

    /**
     * Method invocation dispatch target.
     */
    @Internal
    public static final class MethodReflectionDispatchTarget extends AbstractDispatchTarget {
        private final TypedElement declaringType;
        private final MethodElement methodElement;
        final int methodIndex;

        private MethodReflectionDispatchTarget(TypedElement declaringType,
                                               MethodElement methodElement,
                                               int methodIndex) {
            this.declaringType = declaringType;
            this.methodElement = methodElement;
            this.methodIndex = methodIndex;
        }

        @Override
        public TypedElement getDeclaringType() {
            return declaringType;
        }

        @Override
        public MethodElement getMethodElement() {
            return methodElement;
        }

        @Override
        public ExpressionDef dispatchMultiExpression(ExpressionDef target, ExpressionDef valuesArray) {
            return TYPE_REFLECTION_UTILS.invokeStatic(
                    METHOD_INVOKE_METHOD,

                    methodElement.isStatic() ? ExpressionDef.nullValue() : target,
                    new VariableDef.This().invoke(GET_ACCESSIBLE_TARGET_METHOD, ExpressionDef.constant(methodIndex)),
                    valuesArray
            );
        }

        @Override
        public ExpressionDef dispatchOneExpression(ExpressionDef target, ExpressionDef value) {
            return TYPE_REFLECTION_UTILS.invokeStatic(
                    METHOD_INVOKE_METHOD,

                    methodElement.isStatic() ? ExpressionDef.nullValue() : target,
                    new VariableDef.This().invoke(GET_ACCESSIBLE_TARGET_METHOD, ExpressionDef.constant(methodIndex)),
                    TypeDef.OBJECT.array().instantiate(value)
            );
        }

    }

    /**
     * Interceptable method invocation dispatch target.
     */
    @Internal
    public static final class InterceptableMethodDispatchTarget extends AbstractDispatchTarget {
        private final TypedElement declaringType;
        private final DispatchTarget dispatchTarget;
        private final String interceptedProxyClassName;
        private final String interceptedProxyBridgeMethodName;
        private final MethodElement methodElement;

        private InterceptableMethodDispatchTarget(DispatchTarget dispatchTarget,
                                                  TypedElement declaringType,
                                                  MethodElement methodElement,
                                                  String interceptedProxyClassName,
                                                  String interceptedProxyBridgeMethodName) {
            this.declaringType = declaringType;
            this.methodElement = methodElement;
            this.dispatchTarget = dispatchTarget;
            this.interceptedProxyClassName = interceptedProxyClassName;
            this.interceptedProxyBridgeMethodName = interceptedProxyBridgeMethodName;
        }

        @Override
        public TypedElement getDeclaringType() {
            return declaringType;
        }

        @Override
        public MethodElement getMethodElement() {
            return methodElement;
        }

        @Override
        public StatementDef dispatch(ExpressionDef target, ExpressionDef valuesArray) {
            VariableDef.Field interceptableField = new VariableDef.This()
                    .field(FIELD_INTERCEPTABLE, TypeDef.of(boolean.class));

            ClassTypeDef proxyType = ClassTypeDef.of(interceptedProxyClassName);

            return interceptableField.isTrue()
                    .asConditionAnd(target.instanceOf(proxyType))
                    .asConditionIfElse(
                            invokeProxyBridge(proxyType, target, valuesArray),
                            dispatchTarget.dispatch(target, valuesArray)
                    );
        }

        private StatementDef invokeProxyBridge(ClassTypeDef proxyType, ExpressionDef target, ExpressionDef valuesArray) {
            boolean suspend = methodElement.isSuspend();
            ExpressionDef.InvokeInstanceMethod invoke = target.cast(proxyType).invoke(
                    interceptedProxyBridgeMethodName,
                    Arrays.stream(methodElement.getSuspendParameters()).map(p -> TypeDef.of(p.getType())).toList(),
                    suspend ? TypeDef.OBJECT : TypeDef.of(methodElement.getReturnType()),
                    IntStream.range(0, methodElement.getSuspendParameters().length).mapToObj(valuesArray::arrayElement).toList()
            );
            if (dispatchTarget.getMethodElement().getReturnType().isVoid() && !suspend) {
                return StatementDef.multi(
                        invoke,
                        ExpressionDef.nullValue().returning()
                );
            }
            return invoke.returning();
        }
    }

}
