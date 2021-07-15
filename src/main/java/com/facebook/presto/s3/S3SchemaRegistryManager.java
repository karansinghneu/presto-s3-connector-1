/*
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
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

package com.facebook.presto.s3;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.HostAddress;
import com.facebook.presto.spi.PrestoException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import io.pravega.schemaregistry.client.SchemaRegistryClient;
import io.pravega.schemaregistry.client.SchemaRegistryClientConfig;
import io.pravega.schemaregistry.client.SchemaRegistryClientFactory;
import io.pravega.schemaregistry.client.exceptions.RegistryExceptions;
import io.pravega.schemaregistry.contract.data.*;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.inject.Inject;
import javax.ws.rs.ProcessingException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collectors;

import static com.facebook.presto.spi.StandardErrorCode.INVALID_TABLE_PROPERTY;
import static com.facebook.presto.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static java.lang.String.format;
import static com.facebook.presto.s3.S3Const.*;

public class S3SchemaRegistryManager {

    private final HostAddress schemaRegistryServerHost;
    private final int schemaRegistryServerPort;
    private final String schemaRegistryServerNamespace;
    private static final Logger log = Logger.get(com.facebook.presto.s3.S3SchemaRegistryManager.class);
    private final static String comment_var = "$comment";
    private final static String database_var = "database";
    private final static String tablename_var = "tablename";
    private final static String properties_var = "properties";
    private final static String schema_name_var = "schema_name";
    private final static String table_name_var = "table_name";
    private final static String schemaTableName_var = "schemaTableName";

    @Inject
    S3SchemaRegistryManager(S3ConnectorConfig s3ConnectorConfig) {
        schemaRegistryServerHost = s3ConnectorConfig.getSchemaRegistryServerIP();
        schemaRegistryServerPort = s3ConnectorConfig.getSchemaRegistryServerPort();
        schemaRegistryServerNamespace = s3ConnectorConfig.getSchemaRegistryServerNamespace();
    }

    public void createGroup (String schemaName, String owner) {

        SchemaRegistryClient client;
        log.info("Create S3 schema " + schemaName + ", with owner: " + owner
                + " to schema registry host: " +  schemaRegistryServerHost
                + " using namespace: " + schemaRegistryServerNamespace);
        String url = "http://" + schemaRegistryServerHost.getHostText() + ":" + schemaRegistryServerPort;
        client = SchemaRegistryClientFactory.withNamespace(schemaRegistryServerNamespace,
                SchemaRegistryClientConfig.builder().schemaRegistryUri(URI.create(url)).build());
        GroupProperties newGroupProperties = new GroupProperties(SerializationFormat.Json,
                Compatibility.allowAny(), true);

        client.addGroup(schemaName, newGroupProperties);
    }

    public void dropGroup (String schemaName) {

        SchemaRegistryClient client;
        log.info("Drop S3 schema " + schemaName
                + " from schema registry host: " +  schemaRegistryServerHost
                + " using namespace: " + schemaRegistryServerNamespace);
        String url = "http://" + schemaRegistryServerHost.getHostText() + ":" + schemaRegistryServerPort;
        client = SchemaRegistryClientFactory.withNamespace(schemaRegistryServerNamespace,
                SchemaRegistryClientConfig.builder().schemaRegistryUri(URI.create(url)).build());
        client.removeGroup(schemaName);
    }

    public void dropTable (S3TableHandle tableHandle) {
        SchemaRegistryClient client;
        String url = "http://" + schemaRegistryServerHost.getHostText() + ":" + schemaRegistryServerPort;
        log.info("Dropping table " + tableHandle.getTableName() + " on schema " + tableHandle.getSchemaName());
        try {
            client = SchemaRegistryClientFactory.withNamespace(schemaRegistryServerNamespace,
                    SchemaRegistryClientConfig.builder().schemaRegistryUri(URI.create(url)).build());
            // Remember - a "schema" in Presto is a "group" in Schema Registry
            for (SchemaWithVersion version : client.getSchemas(tableHandle.getSchemaName())) {
                if (version.getSchemaInfo().getType().equalsIgnoreCase(tableHandle.getTableName())) {
                    log.info("Delete schema version : " + version.getVersionInfo().toString());
                    client.deleteSchemaVersion(tableHandle.getSchemaName(), version.getVersionInfo());
                }
            }
        } catch (ProcessingException e) {
            return;
        } catch (RegistryExceptions.ResourceNotFoundException e1) {
            log.error("Exception: " + e1);
            return;
        }
    }

    public void createTable (ConnectorTableMetadata tableMetadata) {

        // Note, this method uses a combination of JSONObject and JsonNode objects
        // JSONObject is typically fine, but when the schema properties are added,
        // it does not report the properties in any expected order, but JsonNode objects do
        log.info("Create S3 table schema " + tableMetadata.getTable().getTableName()
                + ", in group " + tableMetadata.getTable().getSchemaName()
                + " to schema registry host: " +  schemaRegistryServerHost
                + " using namespace: " + schemaRegistryServerNamespace);
        String url = "http://" + schemaRegistryServerHost.getHostText() + ":" + schemaRegistryServerPort;
        SchemaRegistryClient client = SchemaRegistryClientFactory.withNamespace(schemaRegistryServerNamespace,
                SchemaRegistryClientConfig.builder().schemaRegistryUri(URI.create(url)).build());
        Map<String, Object> properties = tableMetadata.getProperties();
        String location;
        String file_format = null;
        String database = tableMetadata.getTable().getSchemaName();
        String tablename = tableMetadata.getTable().getTableName();
        String bucket = null;
        String prefix = null;
        String hasHeaderRow = DEFAULT_HAS_HEADER_ROW;
        String recordDelimiter = DEFAULT_RECORD_DELIMITER;
        String fieldDelimiter = DEFAULT_FIELD_DELIMITER;
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            if (property.getKey().equalsIgnoreCase("format")) {
                file_format = (String)property.getValue();
                if (!S3Const.isValidFormatForQuery(file_format)) {
                    throw new PrestoException(S3ErrorCode.S3_UNSUPPORTED_FORMAT,
                            format("Unsupported table format for query: %s", file_format));
                }
            }
            else if (property.getKey().equalsIgnoreCase("field_delimiter")) {
                fieldDelimiter = (String)property.getValue();
            }
            else if (property.getKey().equalsIgnoreCase("record_delimiter")) {
                recordDelimiter = (String)property.getValue();
            }
            else if (property.getKey().equalsIgnoreCase("has_header_row")) {
                hasHeaderRow = (String)property.getValue();
            }
            else if (property.getKey().equalsIgnoreCase("external_location")) {
                location = (String)property.getValue();
                try {
                    prefix = new URI(location).getPath();
                    bucket = new URI(location).getHost();
                } catch (URISyntaxException e) {
                    log.error("Incorrect location format: " + location);
                    throw new PrestoException(CONFIGURATION_INVALID,
                            format("Error processing schema string: %s", location));
                }
                log.debug("Table location. Bucket: " + bucket + ", prefix: " + prefix);
            }
        }
        ObjectNode schemaNode = JsonNodeFactory.instance.objectNode();
        JSONObject commentDetails = new JSONObject();
        commentDetails.put(database_var, database)
                .put(tablename_var, tablename);
        JSONArray prefixArray = new JSONArray();
        prefixArray.put(prefix);
        JSONObject sourceDetail = new JSONObject();
        sourceDetail.put(bucket, prefixArray);
        commentDetails.put("sources", sourceDetail)
            .put("hasHeaderRow", hasHeaderRow)
            .put("fieldDelimiter", fieldDelimiter)
            .put("recordDelimiter", recordDelimiter)
            .put("objectDataFormat", file_format.toLowerCase());
        log.debug("Comment Details: " + commentDetails.toString());
        schemaNode.put(comment_var, commentDetails.toString().replaceAll("\"", "\\\""))
            .put("description", "Format of row of data")
            .put("type", "object");
        String propertyString = "{";
        // Create a string for all the properties, then parse the string into a JsonNode
        for (int i = 0; i <= tableMetadata.getColumns().size() - 1; i++) {
            ColumnMetadata column = tableMetadata.getColumns().get(i);
            log.debug("Column name: " + column.getName() + ", type: " + column.getType());
            ObjectNode columnObjectNode = JsonNodeFactory.instance.objectNode();
            String columnType = "";
            if (column.getType().getDisplayName().equalsIgnoreCase("VARCHAR")) {
                columnType = "string";
            } else if (column.getType().getDisplayName().equalsIgnoreCase("DOUBLE")) {
                columnType = "number";
            } else if (column.getType().getDisplayName().equalsIgnoreCase("BIGINT")) {
                columnType = "integer";
            } else if (column.getType().getDisplayName().equalsIgnoreCase("BOOLEAN")) {
                columnType = "boolean";
            }  else if(column.getType().getDisplayName().equalsIgnoreCase("integer")){
                columnType = "integer";
            }
            columnObjectNode.put("type", columnType);
            propertyString += "\"" + column.getName() + "\":" + columnObjectNode;
            if (i == tableMetadata.getColumns().size() - 1) {
                propertyString += "}";
            } else {
                propertyString += ",";
            }
            // For now, there are no column properties
            // for (Map.Entry<String, Object> properties1 : column.getProperties().entrySet()) {
            //    String propertyName = properties1.getKey();
            // }
        }
        try {
            ObjectMapper objMapper = new ObjectMapper();
            JsonNode propertyNode = objMapper.readTree(propertyString);
            schemaNode.set(properties_var, propertyNode);
        } catch (IOException e) {
            throw new PrestoException(CONFIGURATION_INVALID,
                    format("Error processing schema string: %s", propertyString));
        }
        log.info("Add schema: " + schemaNode.toString());

        SchemaInfo newSchema = new SchemaInfo(tablename, SerializationFormat.Json,
                ByteBuffer.wrap(schemaNode.toString().getBytes(Charsets.UTF_8)),
                ImmutableMap.of());

        client.addSchema(database, newSchema);
    }

    public boolean schemaExists(String schemaName) {
        SchemaRegistryClient client;
        Iterator<Map.Entry<String, GroupProperties>> configuredGroups;
        try {
            String url = "http://" + schemaRegistryServerHost.getHostText() + ":" + schemaRegistryServerPort;
            client = SchemaRegistryClientFactory.withNamespace(schemaRegistryServerNamespace,
                    SchemaRegistryClientConfig.builder().schemaRegistryUri(URI.create(url)).build());
            // if schema registry is down, I think it should throw, but it doesn't.
            configuredGroups = client.listGroups();
            // If schema registry is down, listGroups will still succeed, but hasNext will throw
            // This seems odd to me, and I will create an issue on github.
            if (!configuredGroups.hasNext()) {
                log.error("No groups found at registry server "
                        + schemaRegistryServerHost.getHostText() + " at port " + schemaRegistryServerPort + ", or it is down");
                return false;
            }

        } catch (ProcessingException e) {
            log.error("Cannot connect to schema registry");
            return false;
        }

        while (configuredGroups.hasNext()) {
            Map.Entry<String, GroupProperties> nextGroup = configuredGroups.next();
            if (nextGroup.getKey().equalsIgnoreCase(schemaName)) {
                return true;
            }
        }
        return false;
    }

    public boolean tableSchemaExists(String schemaName, String tableName) {
        SchemaRegistryClient client;
        Iterator<Map.Entry<String, GroupProperties>> configuredGroups;
        try {
            String url = "http://" + schemaRegistryServerHost.getHostText() + ":" + schemaRegistryServerPort;
            client = SchemaRegistryClientFactory.withNamespace(schemaRegistryServerNamespace,
                    SchemaRegistryClientConfig.builder().schemaRegistryUri(URI.create(url)).build());
            // if schema registry is down, I think it should throw, but it doesn't.
            configuredGroups = client.listGroups();
            // If schema registry is down, listGroups will still succeed, but hasNext will throw
            // This seems odd to me, and I will create an issue on github.
            if (!configuredGroups.hasNext()) {
                log.error("No groups found at registry server "
                        + schemaRegistryServerHost.getHostText() + " at port " + schemaRegistryServerPort + ", or it is down");
                return false;
            }

        } catch (ProcessingException e) {
            log.error("Cannot connect to schema registry");
            return false;
        }

        while (configuredGroups.hasNext()) {
            Map.Entry<String, GroupProperties> nextGroup = configuredGroups.next();
            if (!nextGroup.getKey().equalsIgnoreCase(schemaName)) {
                continue;
            }
            for (String table :
                    client.getSchemas(nextGroup.getKey()).stream().map(x -> x.getSchemaInfo().getType()).collect(Collectors.toList())) {
                if (table.equalsIgnoreCase(tableName)) {
                    log.info("Found table: " + tableName);
                    return true;
                }
            }
            // Found DB/Group, but not table
            return false;
        }
        return false;
    }

    public JSONObject getSchemaRegistryConfig() {
        // Return the schemas defined in Schema Registry using same format
        // as defined in static JSON config file defined in presto-main/etc/s3.schemas.config.json
        SchemaRegistryClient client;
        Iterator<Map.Entry<String, GroupProperties>> configuredGroups;
        try {
            String url = "http://" + schemaRegistryServerHost.getHostText() + ":" + schemaRegistryServerPort;
            client = SchemaRegistryClientFactory.withNamespace(schemaRegistryServerNamespace,
                    SchemaRegistryClientConfig.builder().schemaRegistryUri(URI.create(url)).build());
            // if schema registry is down, I think it should throw, but it doesn't.
            configuredGroups = client.listGroups();
            // If schema registry is down, listGroups will still succeed, but hasNext will throw
            // This seems odd to me, and I will create an issue on github.
            if (!configuredGroups.hasNext()) {
                log.debug("No groups found at registry server "
                        + schemaRegistryServerHost.getHostText() + " at port " + schemaRegistryServerPort + ", or it is down");
                return new JSONObject();
            }

        } catch (ProcessingException e) {
            // Not necessarily an error
            log.debug("Cannot connect to schema registry server "
                    + schemaRegistryServerHost.getHostText() + " at port " + schemaRegistryServerPort);
            return new JSONObject();
        }

        JSONObject returnJSON = new JSONObject();
        JSONArray arrayOfSchemas = new JSONArray();
        while (configuredGroups.hasNext()) {
            Map.Entry<String, GroupProperties> nextGroup = configuredGroups.next();
            log.debug("Found group/DB in schema registry: " + nextGroup.getKey());
            boolean groupHasSchemas = false;
            for (String schemaName :
                    client.getSchemas(nextGroup.getKey()).stream().map(x -> x.getSchemaInfo().getType()).collect(Collectors.toList())) {
                groupHasSchemas = true;
                JSONObject schemaObject = new JSONObject();
                log.debug("Found tableName in schema registry: " + schemaName);
                ByteBuffer schemaData =
                        client.getLatestSchemaVersion(nextGroup.getKey(), schemaName).getSchemaInfo().getSchemaData();
                byte[] schemaDataByteArray = new byte[schemaData.remaining()];
                schemaData.get(schemaDataByteArray, 0, schemaDataByteArray.length);
                String schemaDataByteArrayStr = new String(schemaDataByteArray, Charsets.UTF_8);
                // Use JsonNode instead of JSONObject to preserve order of table columns in properties
                JsonNode jsonNodeProperties = null;
                try {
                    ObjectMapper objMapper = new ObjectMapper();
                    JsonNode jsonNode = objMapper.readTree(schemaDataByteArrayStr);
                    jsonNodeProperties = jsonNode.get(properties_var);
                } catch (IOException e) {
                    log.error("Exception: " + e);
                    return returnJSON;
                }

                JSONObject schemaJSON = new JSONObject(new String(schemaDataByteArray, Charsets.UTF_8));
                JSONObject commentInfo = new JSONObject(schemaJSON.getString(comment_var));
                String database = commentInfo.getString(database_var);
                String tablename = commentInfo.getString(tablename_var);
                JSONObject properties = schemaJSON.getJSONObject(properties_var);
                JSONObject schemaTableName = new JSONObject();
                JSONObject s3Table = new JSONObject();
                schemaTableName.put(schema_name_var, database)
                    .put(table_name_var, tablename);
                schemaObject.put(schemaTableName_var, schemaTableName);
                JSONArray columns = new JSONArray();
                Iterator<String> propertyKeys = jsonNodeProperties.fieldNames();
                while (propertyKeys.hasNext()) {
                    String propertyKey = propertyKeys.next();
                    JSONObject newObject = new JSONObject();
                    newObject.put("name", propertyKey);
                    String propertyValue = properties.getJSONObject(propertyKey).getString("type");
                    if (propertyValue.equalsIgnoreCase("string")) {
                        newObject.put("type", "VARCHAR");
                    } else if (propertyValue.equalsIgnoreCase("number")) {
                        newObject.put("type", "DOUBLE");
                    } else if (propertyValue.equalsIgnoreCase("integer")) {
                        newObject.put("type", "BIGINT");
                    } else if (propertyValue.equalsIgnoreCase("boolean")) {
                        newObject.put("type", "BOOLEAN");
                    } else {
                        throw new PrestoException(INVALID_TABLE_PROPERTY,
                                format("Unknown type %s for column name %s", propertyValue, propertyKey));
                    }
                    columns.put(newObject);
                }
                s3Table.put("name", tablename)
                    .put("columns", columns);
                if (commentInfo.has("objectDataFormat"))
                    s3Table.put("objectDataFormat", commentInfo.getString("objectDataFormat"));
                if (commentInfo.has("hasHeaderRow"))
                    s3Table.put("hasHeaderRow", commentInfo.getString("hasHeaderRow"));
                if (commentInfo.has("hasFooterRow"))
                    s3Table.put("hasFooterRow", commentInfo.getString("hasFooterRow"));
                if (commentInfo.has("recordDelimiter"))
                    s3Table.put("recordDelimiter", commentInfo.getString("recordDelimiter"));
                if (commentInfo.has("fieldDelimiter"))
                    s3Table.put("fieldDelimiter", commentInfo.getString("fieldDelimiter"));
                if (commentInfo.has("sources"))
                    s3Table.put("sources", commentInfo.getJSONObject("sources"));
                schemaObject.put("s3Table", s3Table);
                arrayOfSchemas.put(schemaObject);
                }
                if (!groupHasSchemas) {
                    // DB with no tables - create minimal schema
                    log.debug("No tables defined for group/DB: " + nextGroup.getKey());
                    JSONObject schemaTableName = new JSONObject();
                    schemaTableName.put(schema_name_var, nextGroup.getKey());
                    JSONObject schemaObject = new JSONObject();
                    schemaObject.put(schemaTableName_var, schemaTableName);
                    arrayOfSchemas.put(schemaObject);
                }
            }
        if (arrayOfSchemas.length() > 0) {
            returnJSON.put("schemas", arrayOfSchemas);
        }
        return (returnJSON);
    }
}