package org.tron.core.services.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.ConfigFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.iq80.leveldb.Options;
import org.junit.Assert;
import org.junit.Test;
import org.tron.common.args.Account;
import org.tron.common.args.GenesisBlock;
import org.tron.common.args.Witness;
import org.tron.common.logsfilter.EventPluginConfig;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.parameter.Exportable;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Property;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.config.args.Storage;
import org.tron.core.config.args.StorageConfig;
import org.tron.p2p.P2pConfig;
import org.tron.p2p.dns.update.PublishConfig;

public class CommonParameterExporterTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final CommonParameterExporter exporter = new CommonParameterExporter();

  @Test
  public void testExportIncludesOnlyAnnotatedFieldsAndLiveValues() {
    CommonParameter parameter = new CommonParameter();
    parameter.rpcPort = 150051;
    parameter.chainId = "runtime-chain";
    parameter.allowCreationOfContracts = 1L;

    Map<String, Object> snapshot = exporter.export(parameter);

    Assert.assertEquals(150051, snapshot.get("rpcPort"));
    Assert.assertEquals("runtime-chain", snapshot.get("chainId"));
    Assert.assertFalse(snapshot.containsKey("allowCreationOfContracts"));

    parameter.rpcPort = 250051;
    snapshot = exporter.export(parameter);
    Assert.assertEquals(250051, snapshot.get("rpcPort"));
  }

  @Test
  public void testExportUsesRuntimeParameterType() {
    ExtendedCommonParameter parameter = new ExtendedCommonParameter();

    Map<String, Object> snapshot = exporter.export(parameter);

    Assert.assertEquals("runtime-value", snapshot.get("runtimeOnly"));
  }

  @Test
  public void testExportSortsTopLevelKeysByFieldName() {
    Map<String, Object> snapshot = exporter.export(new CommonParameter());
    List<String> actualKeys = new ArrayList<>(snapshot.keySet());
    List<String> sortedKeys = new ArrayList<>(actualKeys);
    Collections.sort(sortedKeys);

    Assert.assertEquals(sortedKeys, actualKeys);
  }

  @Test
  public void testExportExcludesCommitteeParameters() {
    Map<String, Object> snapshot = exporter.export(new CommonParameter());
    List<String> committeeParameters = Arrays.asList(
        "allowCreationOfContracts", "allowMultiSign", "allowAdaptiveEnergy",
        "allowDelegateResource", "allowSameTokenName", "allowTvmTransferTrc10",
        "allowTvmConstantinople", "allowTvmSolidity059", "forbidTransferToContract",
        "allowShieldedTRC20Transaction", "allowMarketTransaction",
        "allowTransactionFeePool", "allowBlackHoleOptimization", "allowNewResourceModel",
        "allowTvmIstanbul", "allowProtoFilterNum", "allowAccountStateRoot",
        "changedDelegation", "allowPBFT", "pBFTExpireNum", "allowTvmFreeze",
        "allowTvmVote", "allowTvmLondon", "allowTvmCompatibleEvm",
        "allowHigherLimitForMaxCpuTimeOfOneTx", "allowNewRewardAlgorithm",
        "allowOptimizedReturnValueOfChainId", "allowTvmShangHai", "allowOldRewardOpt",
        "allowEnergyAdjustment", "allowStrictMath", "consensusLogicOptimization",
        "allowTvmCancun", "allowTvmBlob", "unfreezeDelayDays",
        "allowAccountAssetOptimization", "allowAssetOptimization", "allowNewReward",
        "memoFee", "allowDelegateOptimization", "allowDynamicEnergy",
        "dynamicEnergyThreshold", "dynamicEnergyIncreaseFactor", "dynamicEnergyMaxFactor",
        "maintenanceTimeInterval", "proposalExpireTime", "allowCancelAllUnfreezeV2",
        "maxCreateAccountTxSize");

    for (String fieldName : committeeParameters) {
      Assert.assertFalse("Committee parameter must not be exported: " + fieldName,
          snapshot.containsKey(fieldName));
    }
  }

  @Test
  public void testSanitizeRedactsSensitiveValuesRecursively() {
    ObjectNode source = OBJECT_MAPPER.createObjectNode();
    source.put("privateKey", "private-value");
    source.put("password", "password-value");
    source.put("zenTokenId", "000000");
    ObjectNode nested = source.putObject("dns");
    nested.put("accessKeyId", "access-key-value");
    nested.put("accessKeySecret", "secret-value");
    nested.put("dnsPrivate", "dns-private-value");
    nested.put("dbConfig", "database|username|password");
    nested.put("endpoint", "127.0.0.1");

    JsonNode sanitized = exporter.sanitize(source);

    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        sanitized.get("privateKey").asText());
    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        sanitized.get("password").asText());
    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        sanitized.get("dns").get("accessKeyId").asText());
    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        sanitized.get("dns").get("accessKeySecret").asText());
    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        sanitized.get("dns").get("dnsPrivate").asText());
    Assert.assertFalse(sanitized.get("dns").has("dbConfig"));
    Assert.assertEquals("000000", sanitized.get("zenTokenId").asText());
    Assert.assertEquals("127.0.0.1", sanitized.get("dns").get("endpoint").asText());
  }

  @Test
  public void testExportIncludesApprovedConfigurationsAndExcludesSecrets() {
    CommonParameter parameter = new CommonParameter();
    parameter.dnsPublishConfig = new PublishConfig();
    parameter.dnsPublishConfig.setDnsPrivate("dns-private-value");
    parameter.dnsPublishConfig.setAccessKeyId("access-key-value");
    parameter.dnsPublishConfig.setAccessKeySecret("secret-value");
    parameter.dnsPublishConfig.setDnsDomain("nodes.example.org");
    parameter.eventPluginConfig = new EventPluginConfig();
    parameter.eventPluginConfig.setDbConfig("mongodb://user:password@localhost/events");
    parameter.eventPluginConfig.setServerAddress("127.0.0.1:5555");
    parameter.outputDirectory = "node-output";
    parameter.logbackPath = "logback.xml";
    parameter.storage = new Storage();
    parameter.storage.setDbDirectory("database");
    parameter.genesisBlock = GenesisBlock.getDefault();
    parameter.p2pConfig = new P2pConfig();
    parameter.p2pConfig.setIp("127.0.0.1");
    parameter.p2pConfig.setSeedNodes(Collections.singletonList(
        InetSocketAddress.createUnresolved("seed.example.org", 18888)));
    parameter.p2pConfig.setActiveNodes(Collections.singletonList(
        InetSocketAddress.createUnresolved("active.example.org", 18888)));
    parameter.p2pConfig.setTrustNodes(Collections.singletonList(
        InetAddress.getLoopbackAddress()));
    parameter.p2pConfig.setNodeID(new byte[] {1, 2, 3});
    parameter.p2pConfig.setPublishConfig(parameter.dnsPublishConfig);

    Map<String, Object> snapshot = exporter.export(parameter);
    Assert.assertFalse(snapshot.containsKey("dnsPublishConfig"));
    Map<?, ?> eventPluginConfig = (Map<?, ?>) snapshot.get("eventPluginConfig");
    Assert.assertFalse(eventPluginConfig.containsKey("dbConfig"));
    Assert.assertEquals("127.0.0.1:5555", eventPluginConfig.get("serverAddress"));
    Assert.assertEquals("node-output", snapshot.get("outputDirectory"));
    Assert.assertEquals("logback.xml", snapshot.get("logbackPath"));
    Assert.assertEquals("database", ((Map<?, ?>) snapshot.get("storage")).get("dbDirectory"));
    Assert.assertEquals("0", ((Map<?, ?>) snapshot.get("genesisBlock")).get("number"));
    Map<?, ?> p2pConfig = (Map<?, ?>) snapshot.get("p2pConfig");
    Assert.assertEquals("127.0.0.1", p2pConfig.get("ip"));
    Assert.assertFalse(p2pConfig.containsKey("seedNodes"));
    Assert.assertFalse(p2pConfig.containsKey("activeNodes"));
    Assert.assertFalse(p2pConfig.containsKey("trustNodes"));
    Assert.assertFalse(p2pConfig.containsKey("nodeID"));
    Map<?, ?> publishConfig = (Map<?, ?>) p2pConfig.get("publishConfig");
    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        publishConfig.get("accessKeyId"));
    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        publishConfig.get("accessKeySecret"));
    Assert.assertEquals("nodes.example.org", publishConfig.get("dnsDomain"));
  }

  @Test
  public void testExportGenesisBlockUsesConfigurationFormats() {
    byte[] accountAddress = ByteArray.fromHexString(
        "410000000000000000000000000000000000000001");
    Account account = new Account();
    account.setAccountName("Zion");
    account.setAccountType("Normal");
    account.setAddress(accountAddress);
    account.setBalance("123456789");
    byte[] witnessAddress = ByteArray.fromHexString(
        "410000000000000000000000000000000000000002");
    Witness witness = new Witness();
    witness.setAddress(witnessAddress);
    witness.setUrl("https://witness.example.org");
    witness.setVoteCount(27L);
    GenesisBlock genesisBlock = new GenesisBlock();
    genesisBlock.setTimestamp("1234");
    genesisBlock.setParentHash("abcd");
    genesisBlock.setAssets(Collections.singletonList(account));
    genesisBlock.setWitnesses(Collections.singletonList(witness));
    CommonParameter parameter = new CommonParameter();
    parameter.genesisBlock = genesisBlock;

    Map<String, Object> snapshot = exporter.export(parameter);

    Map<?, ?> genesisSnapshot = (Map<?, ?>) snapshot.get("genesisBlock");
    Map<?, ?> assetSnapshot = (Map<?, ?>) ((List<?>) genesisSnapshot.get("assets")).get(0);
    Assert.assertEquals("Zion", assetSnapshot.get("accountName"));
    Assert.assertEquals("Normal", assetSnapshot.get("accountType"));
    Assert.assertEquals(ByteArray.toHexString(accountAddress), assetSnapshot.get("address"));
    Assert.assertEquals(123456789L, assetSnapshot.get("balance"));
    Map<?, ?> witnessSnapshot =
        (Map<?, ?>) ((List<?>) genesisSnapshot.get("witnesses")).get(0);
    Assert.assertEquals(ByteArray.toHexString(witnessAddress), witnessSnapshot.get("address"));
    Assert.assertEquals("https://witness.example.org", witnessSnapshot.get("url"));
    Assert.assertEquals(27L, witnessSnapshot.get("voteCount"));
  }

  @Test
  public void testExportStorageWithDatabaseOptions() {
    CommonParameter parameter = new CommonParameter();
    Storage storage = new Storage();
    storage.setDbDirectory("database");
    storage.setDbEngine("LEVELDB");
    Property property = new Property();
    property.setName("account");
    property.setPath("account-data");
    property.setDbOptions(new Options()
        .createIfMissing(true)
        .cacheSize(4_096L)
        .writeBufferSize(8_192)
        .maxOpenFiles(128)
        .blockSize(1_024));
    ReflectUtils.setFieldValue(storage, "propertyMap",
        Collections.singletonMap("account", property));
    parameter.storage = storage;

    Map<String, Object> snapshot = exporter.export(parameter);

    Map<?, ?> storageSnapshot = (Map<?, ?>) snapshot.get("storage");
    Assert.assertEquals("database", storageSnapshot.get("dbDirectory"));
    Map<?, ?> propertyMap = (Map<?, ?>) storageSnapshot.get("propertyMap");
    Map<?, ?> propertySnapshot = (Map<?, ?>) propertyMap.get("account");
    Assert.assertEquals("account-data", propertySnapshot.get("path"));
    Map<?, ?> optionSnapshot = (Map<?, ?>) propertySnapshot.get("dbOptions");
    Assert.assertEquals(4_096L, optionSnapshot.get("cacheSize"));
    Assert.assertEquals(8_192, optionSnapshot.get("writeBufferSize"));
    Assert.assertEquals(128, optionSnapshot.get("maxOpenFiles"));
    Assert.assertEquals(1_024, optionSnapshot.get("blockSize"));
  }

  @Test
  public void testExportStorageIncludesRuntimeTuningConfiguration() {
    StorageConfig.DbOptionOverride defaultOption = new StorageConfig.DbOptionOverride();
    defaultOption.setWriteBufferSize(8_192);
    defaultOption.setCacheSize(4_096L);
    StorageConfig storageConfig = new StorageConfig();
    ReflectUtils.setFieldValue(storageConfig, "defaultMDbOption", defaultOption);
    Storage storage = new Storage();
    storage.setDefaultDbOptions(storageConfig);
    storage.setCacheStrategies(ConfigFactory.parseString(
        "cache.strategies.account = \"LRU\""));
    String merkleRoot = "0000000000000000000000000000000000000000000000000000000000000001";
    storage.setDbRoots(ConfigFactory.parseString("merkleRoot.account = \"" + merkleRoot + "\""));
    CommonParameter parameter = new CommonParameter();
    parameter.storage = storage;

    Map<String, Object> snapshot = exporter.export(parameter);

    Map<?, ?> storageSnapshot = (Map<?, ?>) snapshot.get("storage");
    Assert.assertNotNull(storageSnapshot.get("defaultDbOptions"));
    Map<?, ?> defaultM = (Map<?, ?>) storageSnapshot.get("defaultMDbOption");
    Assert.assertEquals(8_192, defaultM.get("writeBufferSize"));
    Assert.assertEquals(4_096L, defaultM.get("cacheSize"));
    Assert.assertEquals("LRU",
        ((Map<?, ?>) storageSnapshot.get("cacheStrategies")).get("account"));
    Assert.assertEquals(merkleRoot,
        ((Map<?, ?>) storageSnapshot.get("dbRoots")).get("account"));
  }

  @Test
  public void testStoragePropertyFailureDoesNotHideStorage() {
    CommonParameter parameter = new CommonParameter();
    Storage storage = new Storage();
    storage.setDbDirectory("database");
    Property property = new Property() {
      @Override
      public Options getDbOptions() {
        throw new IllegalStateException("unavailable options");
      }
    };
    ReflectUtils.setFieldValue(storage, "propertyMap",
        Collections.singletonMap("broken", property));
    parameter.storage = storage;

    Map<String, Object> snapshot = exporter.export(parameter);

    Map<?, ?> storageSnapshot = (Map<?, ?>) snapshot.get("storage");
    Assert.assertEquals("database", storageSnapshot.get("dbDirectory"));
    Map<?, ?> propertyMap = (Map<?, ?>) storageSnapshot.get("propertyMap");
    Assert.assertEquals("[UNAVAILABLE]", propertyMap.get("broken"));
  }

  private static class ExtendedCommonParameter extends CommonParameter {

    @Exportable
    public String runtimeOnly = "runtime-value";
  }
}
