/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.transforms;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Validator;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.kafka.connect.transforms.util.Requirements.requireStructOrNull;

public abstract class HeaderFrom<R extends ConnectRecord<R>> implements Transformation<R> {

    protected String[] fields;
    protected String[] headers;
    protected Operation operation;

    protected List<Map.Entry<String, String>> fieldsHeadersZipped;

    private enum Operation {
        move,
        copy
    }

    private interface ConfigName {
        String FIELDS = "fields";
        String HEADERS = "headers";
        String OPERATION = "operation";
    }

    private static final String DEFAULT_OPERATION = "copy";

    private static final String PURPOSE = "Header creation from key/value";

    private static final Validator OPERATION_VALIDATOR = (name, value) -> {
        try {
            Operation.valueOf(value.toString().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ConfigException(
                    String.format("Only two operations are supported: copy and move (found: %s)", value.toString())
            );
        }
    };

    private static final Validator LIST_VALIDATOR = (name, value) -> {
        if (!Pattern.compile("(\\w+)(,\\w+)*").matcher(value.toString().replaceAll("\\s+", "")).matches()) {
            throw new ConfigException(
                    String.format(
                            "Config %s is suppose to be a comma-separated list of fields (found: %s)",
                            name,
                            value.toString()
                    )
            );
        }
    };

    public static final ConfigDef CONFIG_DEF = new ConfigDef()

            .define(ConfigName.FIELDS,
                    ConfigDef.Type.STRING,
                    "transformation, not, configured",
                    LIST_VALIDATOR,
                    ConfigDef.Importance.HIGH,
                    "Field names whose values are to be copied/moved to headers.")

            .define(ConfigName.HEADERS,
                    ConfigDef.Type.STRING,
                    "transformation, not, configured",
                    LIST_VALIDATOR,
                    ConfigDef.Importance.HIGH,
                    "Header names, in the same order as the field names listed in the fields configuration property")

            .define(ConfigName.OPERATION,
                    ConfigDef.Type.STRING,
                    DEFAULT_OPERATION,
                    OPERATION_VALIDATOR,
                    ConfigDef.Importance.HIGH,
                    "Operation applied on the filed (can be either move or copy)");

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void configure(Map<String, ?> configs) {
        final SimpleConfig config = new SimpleConfig(CONFIG_DEF, configs);
        fields = trimAll(config.getString(ConfigName.FIELDS).split(","));
        headers = trimAll(config.getString(ConfigName.HEADERS).split(","));
        operation = Operation.valueOf(config.getString(ConfigName.OPERATION).toLowerCase(Locale.ROOT));

        if (fields.length != headers.length) {
            throw new ConfigException(
                    String.format(
                            "The fields and headers should have the same number of elements. " +
                                    "Found: %s fields and %s headers",
                            fields.length,
                            headers.length
                    )
            );
        }

        fieldsHeadersZipped = IntStream
                .range(0, fields.length)
                .mapToObj(i -> new SimpleImmutableEntry<>(fields[i], headers[i]))
                .collect(Collectors.toList());
    }

    @Override
    public void close() {
    }

    private Headers addHeaders(R record) {
        Schema recordSchema = operatingSchema(record);
        Struct struct = requireStructOrNull(operatingValue(record), PURPOSE);

        Headers newHeaders = new ConnectHeaders(record.headers());

        fieldsHeadersZipped.forEach(pair -> {
            Object fieldValue = struct.get(pair.getKey());
            Schema fieldSchema = recordSchema.field(pair.getKey()).schema();
            newHeaders.add(pair.getValue(), new SchemaAndValue(fieldSchema, fieldValue));
        });

        return newHeaders;
    }

    protected abstract R removeFields(R record);

    @Override
    public R apply(R record) {
        if (record == null || operatingSchema(record) == null) return record;

        Headers newHeaders = addHeaders(record);
        R operatedRecord = operation == Operation.move ? removeFields(record) : record;

        return record.newRecord(
                record.topic(),
                record.kafkaPartition(),
                operatedRecord.keySchema(),
                operatedRecord.key(),
                operatedRecord.valueSchema(),
                operatedRecord.value(),
                record.timestamp(),
                newHeaders
        );
    }

    protected abstract Schema operatingSchema(R record);

    protected abstract Object operatingValue(R record);

    public static String[] trimAll(String[] array) {
        return Arrays
                .stream(array)
                .map(String::trim)
                .toArray(String[]::new);
    }

    private static Struct structWithRemovedFields(Struct struct, String[] fields) {
        List<String> previousFieldList = Arrays.asList(fields);

        SchemaBuilder newSchema = SchemaBuilder.struct();
        newSchema.doc(struct.schema().doc());

        struct.schema().fields().forEach(filed -> {
            if (!previousFieldList.contains(filed.name())) newSchema.field(filed.name(), filed.schema());
        });

        Struct newStruct = new Struct(newSchema.build());
        struct.schema().fields().forEach(filed -> {
            if (!previousFieldList.contains(filed.name())) newStruct.put(filed.name(), struct.get(filed));
        });

        return newStruct;
    }

    public static class Key<R extends ConnectRecord<R>> extends HeaderFrom<R> {

        @Override
        protected Schema operatingSchema(R record) {
            return record.keySchema();
        }

        @Override
        protected Object operatingValue(R record) {
            return record.key();
        }

        @Override
        protected R removeFields(R record) {
            Struct oldKey = requireStructOrNull(operatingValue(record), PURPOSE);
            Struct newKey = structWithRemovedFields(oldKey, fields);

            return record.newRecord(
                    record.topic(),
                    record.kafkaPartition(),
                    newKey.schema(),
                    newKey,
                    record.valueSchema(),
                    record.value(),
                    record.timestamp(),
                    record.headers()
            );
        }
    }

    public static class Value<R extends ConnectRecord<R>> extends HeaderFrom<R> {

        @Override
        protected Schema operatingSchema(R record) {
            return record.valueSchema();
        }

        @Override
        protected Object operatingValue(R record) {
            return record.value();
        }

        @Override
        protected R removeFields(R record) {
            Struct oldValue = requireStructOrNull(operatingValue(record), PURPOSE);
            Struct newValue = structWithRemovedFields(oldValue, fields);

            return record.newRecord(
                    record.topic(),
                    record.kafkaPartition(),
                    record.keySchema(),
                    record.key(),
                    newValue.schema(),
                    newValue,
                    record.timestamp(),
                    record.headers()
            );
        }
    }
}
