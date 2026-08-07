package org.tron.core.services.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.iq80.leveldb.Options;
import org.springframework.stereotype.Component;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.parameter.Exportable;
import org.tron.common.utils.Property;
import org.tron.core.config.args.Storage;

@Component
@Slf4j(topic = "API")
public class CommonParameterExporter {

  static final String REDACTED_VALUE = "[REDACTED]";
  private static final String UNAVAILABLE_VALUE = "[UNAVAILABLE]";
  private static final String[] SENSITIVE_NAME_PARTS = {
      "private", "password", "passwd", "secret", "credential", "mnemonic",
      "accesskey", "apikey", "localwitness", "seedphrase",
      "authorization", "authtoken"
  };
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public Map<String, Object> export() {
    return export(CommonParameter.getInstance());
  }

  Map<String, Object> export(CommonParameter parameter) {
    ObjectNode snapshot = OBJECT_MAPPER.createObjectNode();
    Field[] fields = parameter.getClass().getFields();
    Arrays.sort(fields, Comparator.comparing(Field::getName));
    for (Field field : fields) {
      if (!field.isAnnotationPresent(Exportable.class)) {
        continue;
      }
      String fieldName = field.getName();
      if (isOmittedName(fieldName)) {
        continue;
      }
      if (isSensitiveName(fieldName)) {
        snapshot.put(fieldName, REDACTED_VALUE);
        continue;
      }

      try {
        Object target = Modifier.isStatic(field.getModifiers()) ? null : parameter;
        JsonNode value = snapshotValue(field.get(target));
        snapshot.set(fieldName, sanitize(value));
      } catch (IllegalAccessException | RuntimeException e) {
        logExportFailure(fieldName, e);
        snapshot.put(fieldName, UNAVAILABLE_VALUE);
      }
    }
    return OBJECT_MAPPER.convertValue(snapshot,
        new TypeReference<LinkedHashMap<String, Object>>() { });
  }

  private JsonNode snapshotValue(Object value) {
    if (value instanceof Storage) {
      return snapshotStorage((Storage) value);
    }
    return OBJECT_MAPPER.valueToTree(value);
  }

  /**
   * Builds an explicit snapshot because {@link Storage} contains runtime collaborators such as
   * LevelDB {@link Options} that are not regular Jackson beans. Serializing the live object can
   * fail and would also expose newly added third-party fields without an explicit review.
   */
  private ObjectNode snapshotStorage(Storage storage) {
    ObjectNode snapshot = OBJECT_MAPPER.createObjectNode();
    snapshot.put("dbDirectory", storage.getDbDirectory());
    snapshot.put("dbEngine", storage.getDbEngine());
    snapshot.put("dbSync", storage.isDbSync());
    snapshot.put("maxFlushCount", storage.getMaxFlushCount());
    snapshot.put("contractParseSwitch", storage.isContractParseSwitch());
    snapshot.put("transactionHistorySwitch", storage.getTransactionHistorySwitch());
    snapshot.put("checkpointVersion", storage.getCheckpointVersion());
    snapshot.put("checkpointSync", storage.isCheckpointSync());
    snapshot.put("estimatedBlockTransactions", storage.getEstimatedBlockTransactions());
    snapshot.put("txCacheInitOptimization", storage.isTxCacheInitOptimization());
    snapshot.set("cacheDbs", OBJECT_MAPPER.valueToTree(
        new ArrayList<>(storage.getCacheDbs())));

    Map<String, Property> propertyMap = storage.getPropertyMap();
    if (propertyMap == null) {
      snapshot.putNull("propertyMap");
      return snapshot;
    }
    ObjectNode properties = snapshot.putObject("propertyMap");
    for (Entry<String, Property> entry : new TreeMap<>(propertyMap).entrySet()) {
      try {
        properties.set(entry.getKey(), snapshotStorageProperty(entry.getValue()));
      } catch (RuntimeException e) {
        logExportFailure("storage.propertyMap entry", e);
        properties.put(entry.getKey(), UNAVAILABLE_VALUE);
      }
    }
    return snapshot;
  }

  /**
   * Copies only stable property and database-option values so one unsupported runtime object does
   * not make the entire storage section unavailable or expand the exported surface implicitly.
   */
  private ObjectNode snapshotStorageProperty(Property property) {
    ObjectNode snapshot = OBJECT_MAPPER.createObjectNode();
    snapshot.put("name", property.getName());
    snapshot.put("path", property.getPath());
    Options options = property.getDbOptions();
    if (options == null) {
      snapshot.putNull("dbOptions");
      return snapshot;
    }
    ObjectNode optionSnapshot = snapshot.putObject("dbOptions");
    optionSnapshot.put("createIfMissing", options.createIfMissing());
    optionSnapshot.put("errorIfExists", options.errorIfExists());
    optionSnapshot.put("writeBufferSize", options.writeBufferSize());
    optionSnapshot.put("maxOpenFiles", options.maxOpenFiles());
    optionSnapshot.put("blockRestartInterval", options.blockRestartInterval());
    optionSnapshot.put("blockSize", options.blockSize());
    optionSnapshot.put("compressionType", options.compressionType().name());
    optionSnapshot.put("verifyChecksums", options.verifyChecksums());
    optionSnapshot.put("cacheSize", options.cacheSize());
    optionSnapshot.put("paranoidChecks", options.paranoidChecks());
    return snapshot;
  }

  private void logExportFailure(String fieldName, Exception exception) {
    logger.warn("Unable to export runtime parameter {}: {}", fieldName,
        exception.getClass().getSimpleName());
    logger.debug("Runtime parameter export failure for " + fieldName, exception);
  }

  JsonNode sanitize(JsonNode value) {
    if (value == null || value.isNull() || value.isValueNode()) {
      return value;
    }
    if (value.isArray()) {
      ArrayNode sanitized = OBJECT_MAPPER.createArrayNode();
      for (JsonNode element : value) {
        sanitized.add(sanitize(element));
      }
      return sanitized;
    }
    if (value.isObject()) {
      ObjectNode sanitized = OBJECT_MAPPER.createObjectNode();
      Iterator<Entry<String, JsonNode>> fields = value.fields();
      while (fields.hasNext()) {
        Entry<String, JsonNode> field = fields.next();
        if (isOmittedName(field.getKey())) {
          continue;
        } else if (isSensitiveName(field.getKey())) {
          sanitized.put(field.getKey(), REDACTED_VALUE);
        } else {
          sanitized.set(field.getKey(), sanitize(field.getValue()));
        }
      }
      return sanitized;
    }
    return value;
  }

  private boolean isOmittedName(String name) {
    return "dbconfig".equals(name.toLowerCase(Locale.ROOT));
  }

  private boolean isSensitiveName(String name) {
    String normalized = name.toLowerCase(Locale.ROOT);
    if ("pwd".equals(normalized) || "key".equals(normalized)
        || "token".equals(normalized) || "auth".equals(normalized)) {
      return true;
    }
    for (String part : SENSITIVE_NAME_PARTS) {
      if (normalized.contains(part)) {
        return true;
      }
    }
    return false;
  }
}
