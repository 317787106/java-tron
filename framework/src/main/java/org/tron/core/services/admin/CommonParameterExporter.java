package org.tron.core.services.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
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
import org.tron.common.args.Account;
import org.tron.common.args.GenesisBlock;
import org.tron.common.args.Witness;
import org.tron.common.cache.CacheType;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.parameter.Exportable;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Property;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.config.args.Storage;
import org.tron.core.config.args.StorageConfig;
import org.tron.p2p.P2pConfig;
import org.tron.p2p.dns.update.PublishConfig;

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
    if (value instanceof GenesisBlock) {
      return snapshotGenesisBlock((GenesisBlock) value);
    }
    if (value instanceof P2pConfig) {
      return snapshotP2pConfig((P2pConfig) value);
    }
    if (value instanceof Storage) {
      return snapshotStorage((Storage) value);
    }
    return OBJECT_MAPPER.valueToTree(value);
  }

  private ObjectNode snapshotGenesisBlock(GenesisBlock genesisBlock) {
    ObjectNode snapshot = OBJECT_MAPPER.createObjectNode();
    snapshot.put("number", genesisBlock.getNumber());
    snapshot.put("timestamp", genesisBlock.getTimestamp());
    snapshot.put("parentHash", genesisBlock.getParentHash());
    ArrayNode assets = snapshot.putArray("assets");
    for (Account account : genesisBlock.getAssets()) {
      ObjectNode asset = assets.addObject();
      asset.put("accountName", account.getAccountName().toStringUtf8());
      asset.put("accountType", account.getAccountType().name());
      asset.put("address", ByteArray.toHexString(account.getAddress()));
      asset.put("balance", account.getBalance());
    }
    ArrayNode witnesses = snapshot.putArray("witnesses");
    for (Witness witness : genesisBlock.getWitnesses()) {
      ObjectNode witnessSnapshot = witnesses.addObject();
      witnessSnapshot.put("address", ByteArray.toHexString(witness.getAddress()));
      witnessSnapshot.put("url", witness.getUrl());
      witnessSnapshot.put("voteCount", witness.getVoteCount());
    }
    return snapshot;
  }

  /**
   * Exports only reviewed P2P configuration values. Runtime peer collections and the node ID are
   * deliberately excluded because they change as the node runs and may contain peer or witness
   * addresses that were never supplied by the operator.
   */
  private ObjectNode snapshotP2pConfig(P2pConfig config) {
    ObjectNode snapshot = OBJECT_MAPPER.createObjectNode();
    snapshot.put("ip", config.getIp());
    snapshot.put("lanIp", config.getLanIp());
    snapshot.put("ipv6", config.getIpv6());
    snapshot.put("port", config.getPort());
    snapshot.put("networkId", config.getNetworkId());
    snapshot.put("minConnections", config.getMinConnections());
    snapshot.put("maxConnections", config.getMaxConnections());
    snapshot.put("minActiveConnections", config.getMinActiveConnections());
    snapshot.put("maxConnectionsWithSameIp", config.getMaxConnectionsWithSameIp());
    snapshot.put("discoverEnable", config.isDiscoverEnable());
    snapshot.put("disconnectionPolicyEnable", config.isDisconnectionPolicyEnable());
    snapshot.put("nodeDetectEnable", config.isNodeDetectEnable());
    snapshot.set("treeUrls", OBJECT_MAPPER.valueToTree(new ArrayList<>(config.getTreeUrls())));
    snapshot.set("publishConfig", snapshotPublishConfig(config.getPublishConfig()));
    return snapshot;
  }

  private JsonNode snapshotPublishConfig(PublishConfig config) {
    if (config == null) {
      return OBJECT_MAPPER.nullNode();
    }
    ObjectNode snapshot = OBJECT_MAPPER.createObjectNode();
    snapshot.put("dnsPublishEnable", config.isDnsPublishEnable());
    putRedacted(snapshot, "dnsPrivate", config.getDnsPrivate());
    snapshot.set("knownTreeUrls",
        OBJECT_MAPPER.valueToTree(new ArrayList<>(config.getKnownTreeUrls())));
    ArrayNode staticNodes = snapshot.putArray("staticNodes");
    for (InetSocketAddress staticNode : config.getStaticNodes()) {
      staticNodes.add(formatSocketAddress(staticNode));
    }
    snapshot.put("dnsDomain", config.getDnsDomain());
    snapshot.put("changeThreshold", config.getChangeThreshold());
    snapshot.put("maxMergeSize", config.getMaxMergeSize());
    snapshot.put("dnsType", config.getDnsType() == null ? null : config.getDnsType().name());
    putRedacted(snapshot, "accessKeyId", config.getAccessKeyId());
    putRedacted(snapshot, "accessKeySecret", config.getAccessKeySecret());
    snapshot.put("aliDnsEndpoint", config.getAliDnsEndpoint());
    snapshot.put("awsHostZoneId", config.getAwsHostZoneId());
    snapshot.put("awsRegion", config.getAwsRegion());
    return snapshot;
  }

  private void putRedacted(ObjectNode snapshot, String name, String value) {
    if (value == null) {
      snapshot.putNull(name);
    } else {
      snapshot.put(name, REDACTED_VALUE);
    }
  }

  private String formatSocketAddress(InetSocketAddress address) {
    String host = address.getHostString();
    if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
      host = "[" + host + "]";
    }
    return host + ":" + address.getPort();
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
    snapshot.set("defaultDbOptions", snapshotDbOptions(storage.getDefaultDbOptions()));
    snapshot.set("defaultDbOption", snapshotDbOptionOverride(storage.getDefaultDbOption()));
    snapshot.set("defaultMDbOption", snapshotDbOptionOverride(storage.getDefaultMDbOption()));
    snapshot.set("defaultLDbOption", snapshotDbOptionOverride(storage.getDefaultLDbOption()));
    ObjectNode cacheStrategies = snapshot.putObject("cacheStrategies");
    for (Entry<CacheType, String> entry
        : new TreeMap<>(storage.getCacheStrategies()).entrySet()) {
      cacheStrategies.put(entry.getKey().toString(), entry.getValue());
    }
    snapshot.set("cacheDbs", OBJECT_MAPPER.valueToTree(
        new ArrayList<>(storage.getCacheDbs())));
    ObjectNode dbRoots = snapshot.putObject("dbRoots");
    for (Entry<String, Sha256Hash> entry : new TreeMap<>(storage.getDbRoots()).entrySet()) {
      dbRoots.put(entry.getKey(), entry.getValue().toString());
    }

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
    snapshot.set("dbOptions", snapshotDbOptions(property.getDbOptions()));
    return snapshot;
  }

  private JsonNode snapshotDbOptions(Options options) {
    if (options == null) {
      return OBJECT_MAPPER.nullNode();
    }
    ObjectNode optionSnapshot = OBJECT_MAPPER.createObjectNode();
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
    return optionSnapshot;
  }

  private JsonNode snapshotDbOptionOverride(StorageConfig.DbOptionOverride option) {
    if (option == null) {
      return OBJECT_MAPPER.nullNode();
    }
    ObjectNode snapshot = OBJECT_MAPPER.createObjectNode();
    putNullable(snapshot, "blockSize", option.getBlockSize());
    putNullable(snapshot, "writeBufferSize", option.getWriteBufferSize());
    putNullable(snapshot, "cacheSize", option.getCacheSize());
    putNullable(snapshot, "maxOpenFiles", option.getMaxOpenFiles());
    return snapshot;
  }

  private void putNullable(ObjectNode snapshot, String name, Number value) {
    snapshot.set(name, OBJECT_MAPPER.valueToTree(value));
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
