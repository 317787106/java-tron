package org.tron.core.services.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.tron.common.args.GenesisBlock;
import org.tron.common.logsfilter.EventPluginConfig;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.parameter.Exportable;
import org.tron.core.config.args.Storage;
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

    Map<String, Object> snapshot = exporter.export(parameter);

    for (Field field : CommonParameter.class.getFields()) {
      if (field.isAnnotationPresent(Exportable.class)) {
        Assert.assertTrue("Missing exportable runtime parameter: " + field.getName(),
            snapshot.containsKey(field.getName()));
      } else {
        Assert.assertFalse("Unexpected runtime parameter: " + field.getName(),
            snapshot.containsKey(field.getName()));
      }
    }
    Assert.assertEquals(150051, snapshot.get("rpcPort"));
    Assert.assertEquals("runtime-chain", snapshot.get("chainId"));

    parameter.rpcPort = 250051;
    snapshot = exporter.export(parameter);
    Assert.assertEquals(250051, snapshot.get("rpcPort"));
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
        "dynamicEnergyThreshold", "dynamicEnergyIncreaseFactor", "dynamicEnergyMaxFactor");

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
    Map<?, ?> publishConfig = (Map<?, ?>) p2pConfig.get("publishConfig");
    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        publishConfig.get("accessKeyId"));
    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        publishConfig.get("accessKeySecret"));
    Assert.assertTrue(snapshot.containsKey("rateLimiterInitialization"));
    Assert.assertTrue(snapshot.containsKey("rocksDBCustomSettings"));
    Assert.assertTrue(snapshot.containsKey("seedNode"));
    Assert.assertTrue(snapshot.containsKey("eventFilter"));
    Assert.assertTrue(snapshot.containsKey("shutdownBlockTime"));
  }
}
