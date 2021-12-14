package io.airbyte.integrations.destination.bigquery.formatter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.Schema;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.util.MoreIterators;
import io.airbyte.integrations.base.JavaBaseConstants;
import io.airbyte.integrations.destination.StandardNameTransformer;
import io.airbyte.integrations.destination.bigquery.BigQueryDenormalizedDestination;
import io.airbyte.integrations.destination.bigquery.BigQueryUtils;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class DefaultBigQueryDenormalizedRecordFormatter implements BigQueryRecordFormatter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultBigQueryDenormalizedRecordFormatter.class);

    private final StandardNameTransformer namingResolver;
    private final Set<String> invalidKeys = new HashSet<>();
    private final Set<String> fieldsWithRefDefinition;

    public DefaultBigQueryDenormalizedRecordFormatter(StandardNameTransformer namingResolver, Set<String> fieldsWithRefDefinition) {
        this.namingResolver = namingResolver;
        this.fieldsWithRefDefinition = fieldsWithRefDefinition;
    }

    @Override
    public JsonNode formatRecord(final Schema schema, final AirbyteRecordMessage recordMessage) {
        // Bigquery represents TIMESTAMP to the microsecond precision, so we convert to microseconds then
        // use BQ helpers to string-format correctly.
        Preconditions.checkArgument(recordMessage.getData().isObject());
        final ObjectNode data = (ObjectNode) formatData(schema.getFields(), recordMessage.getData());
        // replace ObjectNode with TextNode for fields with $ref definition key
        // Do not need to iterate through all JSON Object nodes, only first nesting object.
        if (!fieldsWithRefDefinition.isEmpty()) {
            fieldsWithRefDefinition.forEach(key -> {
                if (data.get(key) != null && !data.get(key).isNull()) {
                    data.put(key, data.get(key).toString());
                }
            });
        }
        addAirbyteColumns(data, recordMessage);

        return data;
    }

    protected void addAirbyteColumns(ObjectNode data, final AirbyteRecordMessage recordMessage) {
        final long emittedAtMicroseconds = TimeUnit.MICROSECONDS.convert(recordMessage.getEmittedAt(), TimeUnit.MILLISECONDS);
        final String formattedEmittedAt = QueryParameterValue.timestamp(emittedAtMicroseconds).getValue();

        data.put(JavaBaseConstants.COLUMN_NAME_AB_ID, UUID.randomUUID().toString());
        data.put(JavaBaseConstants.COLUMN_NAME_EMITTED_AT, formattedEmittedAt);
    }

    protected JsonNode formatData(final FieldList fields, final JsonNode root) {
        // handles empty objects and arrays
        if (fields == null) {
            return root;
        }
        final List<String> dateTimeFields = BigQueryUtils.getDateTimeFieldsFromSchema(fields);
        if (!dateTimeFields.isEmpty()) {
            BigQueryUtils.transformJsonDateTimeToBigDataFormat(dateTimeFields, (ObjectNode) root);
        }
        if (root.isObject()) {
            return getObjectNode(fields, root);
        } else if (root.isArray()) {
            return getArrayNode(fields, root);
        } else {
            return root;
        }
    }

    private JsonNode getArrayNode(FieldList fields, JsonNode root) {
        // Arrays can have only one field
        final Field arrayField = fields.get(0);
        // If an array of records, we should use subfields
        final FieldList subFields;
        if (arrayField.getSubFields() == null || arrayField.getSubFields().isEmpty()) {
            subFields = fields;
        } else {
            subFields = arrayField.getSubFields();
        }
        final JsonNode items = Jsons.jsonNode(MoreIterators.toList(root.elements()).stream()
                .map(p -> formatData(subFields, p))
                .collect(Collectors.toList()));

        // "Array of Array of" (nested arrays) are not permitted by BigQuery ("Array of Record of Array of"
        // is). Turn all "Array of" into "Array of Record of" instead
        return Jsons.jsonNode(ImmutableMap.of(BigQueryDenormalizedDestination.NESTED_ARRAY_FIELD, items));
    }

    private JsonNode getObjectNode(FieldList fields, JsonNode root) {
        final List<String> fieldNames = fields.stream().map(Field::getName).collect(Collectors.toList());
        return Jsons.jsonNode(Jsons.keys(root).stream()
                .filter(key -> {
                    final boolean validKey = fieldNames.contains(namingResolver.getIdentifier(key));
                    if (!validKey && !invalidKeys.contains(key)) {
                        LOGGER.warn("Ignoring field {} as it is not defined in catalog", key);
                        invalidKeys.add(key);
                    }
                    return validKey;
                })
                .collect(Collectors.toMap(namingResolver::getIdentifier,
                        key -> formatData(fields.get(namingResolver.getIdentifier(key)).getSubFields(), root.get(key)))));
    }
}
