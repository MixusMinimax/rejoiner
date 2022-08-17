// Copyright 2017 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.api.graphql.rejoiner;

import com.google.common.base.CaseFormat;
import com.google.common.base.Converter;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class ProtoDataFetcher implements DataFetcher<Object> {

  private static final Converter<String, String> UNDERSCORE_TO_CAMEL = CaseFormat.LOWER_UNDERSCORE.converterTo(CaseFormat.LOWER_CAMEL);
  private static final Converter<String, String> LOWER_CAMEL_TO_UPPER = CaseFormat.LOWER_CAMEL.converterTo(CaseFormat.UPPER_CAMEL);

  private final Descriptors.FieldDescriptor fieldDescriptor;
  private final String javaFieldName;

  private Method getterMethod = null;

  ProtoDataFetcher(Descriptors.FieldDescriptor fieldDescriptor) {
    this.fieldDescriptor = fieldDescriptor;

    final String fieldName = fieldDescriptor.getName();
    javaFieldName = fieldDescriptor.getName().contains("_") ? UNDERSCORE_TO_CAMEL.convert(fieldName) : fieldName;
  }

  @SuppressWarnings("UnstableApiUsage")
  @Override
  public Object get(DataFetchingEnvironment environment) throws Exception {
    final Object source = environment.getSource();
    if (source == null) {
      return null;
    } else if (source instanceof ListenableFuture<?> sourceFuture) {
      return Futures.transform(sourceFuture, sourceObject -> getInternal(sourceObject, environment.getFieldType()), MoreExecutors.directExecutor());
    } else {
      return getInternal(source, environment.getFieldType());
    }
  }

  private Object getInternal(Object source, GraphQLType fieldType) {
    if (source instanceof Message sourceMessage) {
      // enum
      if (fieldType instanceof GraphQLEnumType) {
        return sourceMessage.getField(fieldDescriptor).toString();
      }

      // enum list
      if (fieldDescriptor.isRepeated() && fieldDescriptor.getType() == Descriptors.FieldDescriptor.Type.ENUM) {
        List<Descriptors.EnumValueDescriptor> enumDescriptorList = (List<Descriptors.EnumValueDescriptor>) sourceMessage.getField(fieldDescriptor);
        return enumDescriptorList.stream()
                .map(Descriptors.EnumValueDescriptor::toString)
                .collect(Collectors.toList());
      }

      // lists, primitive values and messages which are contained in the sourceMessage
      if (fieldDescriptor.isRepeated() || fieldDescriptor.getType() != Descriptors.FieldDescriptor.Type.MESSAGE || sourceMessage.hasField(fieldDescriptor)) {
        return sourceMessage.getField(fieldDescriptor);
      }

      return null;
    }

    if (source instanceof Map<?, ?> sourceMap) {
      return sourceMap.get(javaFieldName);
    }

    initGetterMethod(source);
    try {
      return getterMethod.invoke(source);
    } catch (InvocationTargetException | IllegalAccessException e) {
      throw new RuntimeException("Failed to invoke method '" + getterMethod.getName() + "' on class '" + source.getClass().getSimpleName() + "'.", e);
    }
  }

  private void initGetterMethod(Object source) {
    if (getterMethod == null) {
      // no synchronization necessary because this line is idempotent
      final String methodNameSuffix = fieldDescriptor.isMapField() ? "Map" : fieldDescriptor.isRepeated() ? "List" : "";
      final String methodName = "get" + LOWER_CAMEL_TO_UPPER.convert(javaFieldName) + methodNameSuffix;

      try {
        getterMethod = source.getClass().getMethod(methodName);
      } catch (NoSuchMethodException e) {
        throw new RuntimeException("Method '" + methodName + "' which was expected on protobuf class '" + source.getClass().getSimpleName() + "' does not exist.", e);
      }
    }
  }

}
