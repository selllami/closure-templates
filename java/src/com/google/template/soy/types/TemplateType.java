/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.template.soy.types;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Streams.stream;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.template.soy.base.internal.SanitizedContentKind;
import com.google.template.soy.base.internal.TemplateContentKind;
import com.google.template.soy.soytree.ParameterP;
import com.google.template.soy.soytree.SoyTypeP;

/** Template type, containing a list of named, typed parameters and a return type. */
@AutoValue
public abstract class TemplateType extends SoyType {
  public enum TemplateKind {
    BASIC,
    DELTEMPLATE,
    ELEMENT;
  }

  public abstract TemplateKind getTemplateKind();

  public abstract TemplateContentKind getContentKind();

  public abstract boolean isStrictHtml();

  public abstract ImmutableList<Parameter> getParameters();

  final ImmutableMap<String, SoyType> getParameterMap() {
    return stream(getParameters()).collect(toImmutableMap(Parameter::getName, Parameter::getType));
  }

  public abstract ImmutableList<DataAllCallSituation> getDataAllCallSituations();

  public abstract String getIdentifierForDebugging();

  public abstract boolean isInferredType();

  public abstract Builder toBuilder();

  public static Builder builder() {
    return new AutoValue_TemplateType.Builder();
  }

  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder setTemplateKind(TemplateKind templateKind);

    public abstract Builder setContentKind(TemplateContentKind sanitizedContentKind);

    public abstract Builder setStrictHtml(boolean isStrictHtml);

    public abstract Builder setParameters(ImmutableList<Parameter> parameters);

    public abstract Builder setDataAllCallSituations(
        ImmutableList<DataAllCallSituation> dataAllCallSituations);

    public abstract Builder setIdentifierForDebugging(String identifierForDebugging);

    public abstract Builder setInferredType(boolean isInferredType);

    public abstract TemplateType build();
  }

  @AutoValue
  public abstract static class Parameter {
    public static Parameter create(String name, SoyType type, boolean required) {
      return new AutoValue_TemplateType_Parameter(name, type, required);
    }

    public abstract String getName();

    public abstract SoyType getType();

    public abstract boolean isRequired();
  }

  @AutoValue
  public abstract static class DataAllCallSituation {
    public static DataAllCallSituation create(
        String templateName, boolean delCall, ImmutableSet<String> explicitlyPassedParameters) {
      return new AutoValue_TemplateType_DataAllCallSituation(
          templateName, delCall, explicitlyPassedParameters);
    }

    public abstract String getTemplateName();

    public abstract boolean isDelCall();

    public abstract ImmutableSet<String> getExplicitlyPassedParameters();
  }

  public static TemplateType declaredTypeOf(Iterable<Parameter> parameters, SoyType returnType) {
    SanitizedContentKind contentKind;
    if (returnType instanceof SanitizedType) {
      contentKind = ((SanitizedType) returnType).getContentKind();
    } else {
      // Only other valid type is string.
      contentKind = SanitizedContentKind.TEXT;
    }
    TemplateContentKind templateContentKind =
        TemplateContentKind.fromSanitizedContentKind(contentKind);
    return builder()
        // Declared templates can only be basic templates (no deltemplates/elements allowed).
        .setTemplateKind(TemplateKind.BASIC)
        .setContentKind(templateContentKind)
        // Declared HTML templates are implicitly strict. A separate check enforces that
        // non-strict templates may not be bound in template literals.
        .setStrictHtml(contentKind.isHtml())
        .setParameters(ImmutableList.copyOf(parameters))
        // data=all is banned on declared templates.
        .setDataAllCallSituations(ImmutableList.of())
        .setIdentifierForDebugging(stringRepresentation(parameters, templateContentKind))
        .setInferredType(false)
        .build();
  }

  @Override
  public final SoyType.Kind getKind() {
    return SoyType.Kind.TEMPLATE;
  }

  @Override
  final boolean doIsAssignableFromNonUnionType(SoyType srcType) {
    if (srcType.getKind() == SoyType.Kind.TEMPLATE) {
      TemplateType srcTemplate = (TemplateType) srcType;
      // The source template type's arguments must be a superset of this type's arguments (possibly
      // containing some optional parameters omitted from this type).
      if (!srcTemplate.getParameterMap().keySet().containsAll(this.getParameterMap().keySet())) {
        return false;
      }
      for (Parameter srcParameter : srcTemplate.getParameters()) {
        if (!this.getParameterMap().containsKey(srcParameter.getName())) {
          if (srcParameter.isRequired()) {
            return false;
          }
        } else {
          SoyType thisParameterType = this.getParameterMap().get(srcParameter.getName());
          // Check that each argument of the source type is assignable FROM the corresponding
          // argument
          // of this type. This is because the parameter types are constraints; assignability of a
          // template type is only possible when the constraints of the from-type are narrower.
          if (!srcParameter.getType().isAssignableFrom(thisParameterType)) {
            return false;
          }
        }
      }
      // TODO(b/167574941): Add element support.
      SanitizedContentKind thisKind = this.getContentKind().getSanitizedContentKind();
      SanitizedContentKind srcKind = srcTemplate.getContentKind().getSanitizedContentKind();
      if (!thisKind.isAssignableFrom(srcKind)) {
        return false;
      }
      return true;
    }
    return false;
  }

  @Override
  public final String toString() {
    return stringRepresentation(getParameters(), getContentKind());
  }

  static String stringRepresentation(
      Iterable<Parameter> parameters, TemplateContentKind contentKind) {
    StringBuilder sb = new StringBuilder();
    sb.append("(");
    boolean first = true;
    for (Parameter parameter : parameters) {
      if (first) {
        first = false;
      } else {
        sb.append(", ");
      }
      sb.append(parameter.getName());
      if (!parameter.isRequired()) {
        sb.append("?");
      }
      sb.append(": ");
      sb.append(parameter.getType());
    }
    sb.append(") => ");
    sb.append(SanitizedType.getTypeForContentKind(contentKind.getSanitizedContentKind()));
    return sb.toString();
  }

  @Override
  final void doToProto(SoyTypeP.Builder builder) {
    Preconditions.checkState(
        !isInferredType(), "Only declared types may be serialized to proto form.");
    SoyTypeP.TemplateTypeP.Builder templateBuilder = builder.getTemplateBuilder();
    for (Parameter parameter : getParameters()) {
      templateBuilder.addParameter(
          ParameterP.newBuilder()
              .setName(parameter.getName())
              .setType(parameter.getType().toProto())
              .setRequired(parameter.isRequired())
              .build());
    }
    templateBuilder.setReturnType(
        SanitizedType.getTypeForContentKind(getContentKind().getSanitizedContentKind()).toProto());
  }

  @Override
  public final <T> T accept(SoyTypeVisitor<T> visitor) {
    return visitor.visit(this);
  }
}
