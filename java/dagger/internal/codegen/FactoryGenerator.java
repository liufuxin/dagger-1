/*
 * Copyright (C) 2014 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.google.common.base.Preconditions.checkArgument;
import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static com.squareup.javapoet.TypeSpec.classBuilder;
import static dagger.internal.codegen.AnnotationSpecs.Suppression.RAWTYPES;
import static dagger.internal.codegen.AnnotationSpecs.Suppression.UNCHECKED;
import static dagger.internal.codegen.AnnotationSpecs.suppressWarnings;
import static dagger.internal.codegen.CodeBlocks.makeParametersCodeBlock;
import static dagger.internal.codegen.ContributionBinding.Kind.INJECTION;
import static dagger.internal.codegen.ContributionBinding.Kind.PROVISION;
import static dagger.internal.codegen.ErrorMessages.CANNOT_RETURN_NULL_FROM_NON_NULLABLE_PROVIDES_METHOD;
import static dagger.internal.codegen.GwtCompatibility.gwtIncompatibleAnnotation;
import static dagger.internal.codegen.MapKeys.mapKeyFactoryMethod;
import static dagger.internal.codegen.SourceFiles.bindingTypeElementTypeVariableNames;
import static dagger.internal.codegen.SourceFiles.frameworkFieldUsages;
import static dagger.internal.codegen.SourceFiles.generateBindingFieldsForDependencies;
import static dagger.internal.codegen.SourceFiles.generatedClassNameForBinding;
import static dagger.internal.codegen.SourceFiles.parameterizedGeneratedTypeNameForBinding;
import static dagger.internal.codegen.TypeNames.factoryOf;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.codegen.InjectionMethods.InjectionSiteMethod;
import dagger.internal.codegen.InjectionMethods.ProvisionMethod;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.processing.Filer;
import javax.inject.Inject;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Generates {@link Factory} implementations from {@link ProvisionBinding} instances for
 * {@link Inject} constructors.
 *
 * @author Gregory Kick
 * @since 2.0
 */
final class FactoryGenerator extends SourceFileGenerator<ProvisionBinding> {
  private final Types types;
  private final CompilerOptions compilerOptions;

  FactoryGenerator(
      Filer filer,
      Elements elements,
      Types types,
      CompilerOptions compilerOptions) {
    super(filer, elements);
    this.types = types;
    this.compilerOptions = compilerOptions;
  }

  @Override
  ClassName nameGeneratedType(ProvisionBinding binding) {
    return generatedClassNameForBinding(binding);
  }

  @Override
  Optional<? extends Element> getElementForErrorReporting(ProvisionBinding binding) {
    return binding.bindingElement();
  }

  @Override
  Optional<TypeSpec.Builder> write(ClassName generatedTypeName, ProvisionBinding binding) {
    // We don't want to write out resolved bindings -- we want to write out the generic version.
    checkArgument(!binding.unresolved().isPresent());
    checkArgument(binding.bindingElement().isPresent());

    TypeName providedTypeName = TypeName.get(binding.contributedType());
    ParameterizedTypeName factoryTypeName = factoryOf(providedTypeName);
    ImmutableList<TypeVariableName> typeParameters = bindingTypeElementTypeVariableNames(binding);
    TypeSpec.Builder factoryBuilder = classBuilder(generatedTypeName).addModifiers(FINAL);
    // Use type parameters from the injected type or the module instance *only* if we require it.
    boolean factoryHasTypeParameters =
        (binding.bindingKind().equals(INJECTION) || binding.requiresModuleInstance())
            && !typeParameters.isEmpty();
    if (factoryHasTypeParameters) {
      factoryBuilder.addTypeVariables(typeParameters);
    }
    Optional<MethodSpec.Builder> constructorBuilder = Optional.empty();
    UniqueNameSet uniqueFieldNames = new UniqueNameSet();
    ImmutableMap.Builder<BindingKey, FieldSpec> fieldsBuilder = ImmutableMap.builder();

    switch (binding.factoryCreationStrategy()) {
      case SINGLETON_INSTANCE:
        FieldSpec.Builder instanceFieldBuilder =
            FieldSpec.builder(generatedTypeName, "INSTANCE", PRIVATE, STATIC, FINAL)
                .initializer("new $T()", generatedTypeName);

        // if the factory has type parameters, we're ignoring them in the initializer
        if (factoryHasTypeParameters) {
          instanceFieldBuilder.addAnnotation(suppressWarnings(RAWTYPES));
        }

        factoryBuilder.addField(instanceFieldBuilder.build());
        break;
      case CLASS_CONSTRUCTOR:
        constructorBuilder = Optional.of(constructorBuilder().addModifiers(PUBLIC));
        if (binding.requiresModuleInstance()) {
          addConstructorParameterAndTypeField(
              TypeName.get(binding.bindingTypeElement().get().asType()),
              "module",
              factoryBuilder,
              constructorBuilder.get());
        }
        for (Map.Entry<BindingKey, FrameworkField> entry :
            generateBindingFieldsForDependencies(binding).entrySet()) {
          BindingKey bindingKey = entry.getKey();
          FrameworkField bindingField = entry.getValue();
          FieldSpec field =
              addConstructorParameterAndTypeField(
                  bindingField.type(),
                  uniqueFieldNames.getUniqueName(bindingField.name()),
                  factoryBuilder,
                  constructorBuilder.get());
          fieldsBuilder.put(bindingKey, field);
        }
        break;
      case DELEGATE:
        return Optional.empty();
      default:
        throw new AssertionError();
    }
    ImmutableMap<BindingKey, FieldSpec> fields = fieldsBuilder.build();

    factoryBuilder.addModifiers(PUBLIC).addSuperinterface(factoryTypeName);

    // If constructing a factory for @Inject or @Provides bindings, we use a static create method
    // so that generated components can avoid having to refer to the generic types
    // of the factory.  (Otherwise they may have visibility problems referring to the types.)
    MethodSpec.Builder createMethod =
        methodBuilder("create").addModifiers(PUBLIC, STATIC).returns(factoryTypeName);
    if (factoryHasTypeParameters) {
      // The return type is usually the same as the implementing type, except in the case
      // of enums with type variables (where we cast).
      createMethod.addTypeVariables(typeParameters);
    }
    List<ParameterSpec> params =
        constructorBuilder.isPresent()
            ? constructorBuilder.get().build().parameters
            : ImmutableList.of();
    createMethod.addParameters(params);
    switch (binding.factoryCreationStrategy()) {
      case SINGLETON_INSTANCE:
        if (factoryHasTypeParameters) {
          // We use an unsafe cast here because the types are different.
          // It's safe because the type is never referenced anywhere.
          createMethod.addStatement("return ($T) INSTANCE", TypeNames.FACTORY);
          createMethod.addAnnotation(suppressWarnings(RAWTYPES, UNCHECKED));
        } else {
          createMethod.addStatement("return INSTANCE");
        }
        break;

      case CLASS_CONSTRUCTOR:
        createMethod.addStatement(
            "return new $T($L)",
            parameterizedGeneratedTypeNameForBinding(binding),
            makeParametersCodeBlock(Lists.transform(params, input -> CodeBlock.of("$N", input))));
        break;
      default:
        throw new AssertionError();
    }

    if (constructorBuilder.isPresent()) {
      factoryBuilder.addMethod(constructorBuilder.get().build());
    }

    CodeBlock parametersCodeBlock =
        makeParametersCodeBlock(
            frameworkFieldUsages(binding.provisionDependencies(), fields).values());

    MethodSpec.Builder getMethodBuilder =
        methodBuilder("get")
            .returns(providedTypeName)
            .addAnnotation(Override.class)
            .addModifiers(PUBLIC);

    if (binding.bindingKind().equals(PROVISION)) {
      // TODO(dpb): take advantage of the code in InjectionMethods so this doesn't have to be
      // duplicated
      binding
          .nullableType()
          .ifPresent(nullableType -> CodeBlocks.addAnnotation(getMethodBuilder, nullableType));
      CodeBlock methodCall =
          CodeBlock.of(
              "$L.$L($L)",
              binding.requiresModuleInstance()
                  ? "module"
                  : CodeBlock.of("$T", ClassName.get(binding.bindingTypeElement().get())),
              binding.bindingElement().get().getSimpleName(),
              parametersCodeBlock);
      getMethodBuilder.addStatement(
          "return $L",
          binding.shouldCheckForNull(compilerOptions)
              ? checkNotNullProvidesMethod(methodCall)
              : methodCall);
    } else if (!binding.injectionSites().isEmpty()) {
      CodeBlock instance = CodeBlock.of("instance");
      getMethodBuilder
          .addStatement("$1T $2L = new $1T($3L)", providedTypeName, instance, parametersCodeBlock)
          .addCode(
              InjectionSiteMethod.invokeAll(
                  binding.injectionSites(),
                  generatedTypeName,
                  instance,
                  binding.key().type(),
                  types,
                  frameworkFieldUsages(binding.dependencies(), fields)::get))
          .addStatement("return $L", instance);
    } else {
      getMethodBuilder.addStatement("return new $T($L)", providedTypeName, parametersCodeBlock);
    }

    factoryBuilder.addMethod(getMethodBuilder.build());
    factoryBuilder.addMethod(createMethod.build());

    ProvisionMethod.create(binding).ifPresent(factoryBuilder::addMethod);
    gwtIncompatibleAnnotation(binding).ifPresent(factoryBuilder::addAnnotation);
    mapKeyFactoryMethod(binding, types).ifPresent(factoryBuilder::addMethod);

    return Optional.of(factoryBuilder);
  }

  /**
   * Returns {@code Preconditions.checkNotNull(providesMethodInvocation)} with a message suitable
   * for {@code @Provides} methods.
   */
  static CodeBlock checkNotNullProvidesMethod(CodeBlock providesMethodInvocation) {
    return CodeBlock.of(
        "$T.checkNotNull($L, $S)",
        Preconditions.class,
        providesMethodInvocation,
        CANNOT_RETURN_NULL_FROM_NON_NULLABLE_PROVIDES_METHOD);
  }

  @CanIgnoreReturnValue
  private FieldSpec addConstructorParameterAndTypeField(
      TypeName typeName,
      String variableName,
      TypeSpec.Builder factoryBuilder,
      MethodSpec.Builder constructorBuilder) {
    FieldSpec field = FieldSpec.builder(typeName, variableName, PRIVATE, FINAL).build();
    factoryBuilder.addField(field);
    ParameterSpec parameter = ParameterSpec.builder(typeName, variableName).build();
    constructorBuilder.addParameter(parameter);
    constructorBuilder.addCode("this.$N = $N;", field, parameter);
    return field;
  }
}
