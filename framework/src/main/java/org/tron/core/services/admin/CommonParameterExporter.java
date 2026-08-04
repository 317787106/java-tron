package org.tron.core.services.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.parameter.CommonParameter;

@Component
@Slf4j(topic = "API")
public class CommonParameterExporter {

  static final String REDACTED_VALUE = "[REDACTED]";
  private static final String UNAVAILABLE_VALUE = "[UNAVAILABLE]";
  private static final String[] SENSITIVE_NAME_PARTS = {
      "private", "password", "passwd", "secret", "credential", "mnemonic",
      "accesskey", "apikey", "localwitness", "seedphrase", "dbconfig",
      "authorization", "authtoken"
  };
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public Map<String, Object> export() {
    return export(CommonParameter.getInstance());
  }

  Map<String, Object> export(CommonParameter parameter) {
    ObjectNode snapshot = OBJECT_MAPPER.createObjectNode();
    Field[] fields = CommonParameter.class.getFields();
    Arrays.sort(fields, Comparator.comparing(Field::getName));
    for (Field field : fields) {
      String fieldName = field.getName();
      if (isSensitiveName(fieldName)) {
        snapshot.put(fieldName, REDACTED_VALUE);
        continue;
      }

      try {
        Object target = Modifier.isStatic(field.getModifiers()) ? null : parameter;
        JsonNode value = OBJECT_MAPPER.valueToTree(field.get(target));
        snapshot.set(fieldName, sanitize(value));
      } catch (IllegalAccessException | IllegalArgumentException e) {
        logger.warn("Unable to export runtime parameter {}", fieldName);
        snapshot.put(fieldName, UNAVAILABLE_VALUE);
      }
    }
    return OBJECT_MAPPER.convertValue(snapshot,
        new TypeReference<LinkedHashMap<String, Object>>() { });
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
        if (isSensitiveName(field.getKey())) {
          sanitized.put(field.getKey(), REDACTED_VALUE);
        } else {
          sanitized.set(field.getKey(), sanitize(field.getValue()));
        }
      }
      return sanitized;
    }
    return value;
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
