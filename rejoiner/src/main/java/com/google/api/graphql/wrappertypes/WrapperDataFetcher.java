package com.google.api.graphql.wrappertypes;


import com.google.common.base.CaseFormat;
import com.google.common.base.Converter;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLType;

import java.lang.reflect.Method;
import java.util.Map;

public class WrapperDataFetcher implements DataFetcher<Object> {
    private static final Converter<String, String> UNDERSCORE_TO_CAMEL =
            CaseFormat.LOWER_UNDERSCORE.converterTo(CaseFormat.LOWER_CAMEL);
    private static final Converter<String, String> LOWER_CAMEL_TO_UPPER =
            CaseFormat.LOWER_CAMEL.converterTo(CaseFormat.UPPER_CAMEL);

    private final Descriptors.FieldDescriptor fieldDescriptor;
    private final String convertedFieldName;
    private Method method = null;

    public WrapperDataFetcher(Descriptors.FieldDescriptor fieldDescriptor) {
        this.fieldDescriptor = fieldDescriptor;
        final String fieldName = fieldDescriptor.getName();
        convertedFieldName =
                fieldName.contains("_") ? UNDERSCORE_TO_CAMEL.convert(fieldName) : fieldName;
    }

    @Override
    public Object get(DataFetchingEnvironment environment) throws Exception {

        final Object source = environment.getSource();
        if (source == null) {
            return null;
        }

        if (source instanceof Message) {
            GraphQLType type = environment.getFieldType();

            if(fieldDescriptor.isRepeated() || fieldDescriptor.getType() != Descriptors.FieldDescriptor.Type.MESSAGE || ((Message) source).hasField(fieldDescriptor)){
                Message wrapper = (Message) ((Message) source).getField(fieldDescriptor);
                Descriptors.FieldDescriptor valueDescriptor = wrapper.getDescriptorForType().findFieldByName("value");
                return wrapper.getField(valueDescriptor);
            } else {
                return null;
            }
        }

        if (method == null) {
            // no synchronization necessary because this line is idempotent
            final String methodNameSuffix =
                    fieldDescriptor.isMapField() ? "Map" : fieldDescriptor.isRepeated() ? "List" : "";
            final String methodName =
                    "get" + LOWER_CAMEL_TO_UPPER.convert(convertedFieldName) + methodNameSuffix;
            method = source.getClass().getMethod(methodName);
        }
        return method.invoke(source);
    }
}
