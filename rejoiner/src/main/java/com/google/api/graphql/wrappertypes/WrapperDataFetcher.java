package com.google.api.graphql.wrappertypes;


import com.google.common.base.CaseFormat;
import com.google.common.base.Converter;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class WrapperDataFetcher implements DataFetcher<Object> {

    private static final Converter<String, String> UNDERSCORE_TO_CAMEL = CaseFormat.LOWER_UNDERSCORE.converterTo(CaseFormat.LOWER_CAMEL);
    private static final Converter<String, String> LOWER_CAMEL_TO_UPPER = CaseFormat.LOWER_CAMEL.converterTo(CaseFormat.UPPER_CAMEL);

    private final Descriptors.FieldDescriptor fieldDescriptor;
    private final String javaFieldName;

    private Method getterMethod = null;

    public WrapperDataFetcher(Descriptors.FieldDescriptor fieldDescriptor) {
        this.fieldDescriptor = fieldDescriptor;

        final String fieldName = fieldDescriptor.getName();
        javaFieldName = fieldName.contains("_") ? UNDERSCORE_TO_CAMEL.convert(fieldName) : fieldName;
    }

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public Object get(DataFetchingEnvironment environment) throws Exception {
        final Object source = environment.getSource();
        if (source == null) {
            return null;
        } else if (source instanceof ListenableFuture<?> sourceFuture) {
            return Futures.transform(sourceFuture, this::getInternal, MoreExecutors.directExecutor());
        } else {
            return getInternal(source);
        }
    }

    private Object getInternal(Object source) {
        if (source instanceof Message sourceMessage) {
            // lists, primitive values and messages which are contained in the sourceMessage
            if (fieldDescriptor.isRepeated() || fieldDescriptor.getType() != Descriptors.FieldDescriptor.Type.MESSAGE || sourceMessage.hasField(fieldDescriptor)) {
                Message wrapper = (Message) sourceMessage.getField(fieldDescriptor);
                Descriptors.FieldDescriptor valueDescriptor = wrapper.getDescriptorForType().findFieldByName("value");
                return wrapper.getField(valueDescriptor);
            }

            return null;
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
