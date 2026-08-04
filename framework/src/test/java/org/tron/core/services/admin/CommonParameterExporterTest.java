package org.tron.core.services.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.tron.common.logsfilter.EventPluginConfig;
import org.tron.common.parameter.CommonParameter;
import org.tron.p2p.dns.update.PublishConfig;

public class CommonParameterExporterTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final CommonParameterExporter exporter = new CommonParameterExporter();

  @Test
  public void testExportIncludesAllPublicFieldsAndLiveValues() {
    CommonParameter parameter = new CommonParameter();
    parameter.rpcPort = 150051;
    parameter.chainId = "runtime-chain";

    Map<String, Object> snapshot = exporter.export(parameter);

    for (Field field : CommonParameter.class.getFields()) {
      Assert.assertTrue("Missing runtime parameter: " + field.getName(),
          snapshot.containsKey(field.getName()));
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
  public void testSanitizeRedactsSensitiveValuesRecursively() {
    ObjectNode source = OBJECT_MAPPER.createObjectNode();
    source.put("privateKey", "private-value");
    source.put("password", "password-value");
    source.put("zenTokenId", "000000");
    ObjectNode nested = source.putObject("dns");
    nested.put("accessKeyId", "access-key-value");
    nested.put("accessKeySecret", "secret-value");
    nested.put("dnsPrivate", "dns-private-value");
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
    Assert.assertEquals("000000", sanitized.get("zenTokenId").asText());
    Assert.assertEquals("127.0.0.1", sanitized.get("dns").get("endpoint").asText());
  }

  @Test
  public void testExportRedactsConfiguredSecrets() {
    CommonParameter parameter = new CommonParameter();
    parameter.dnsPublishConfig = new PublishConfig();
    parameter.dnsPublishConfig.setDnsPrivate("dns-private-value");
    parameter.dnsPublishConfig.setAccessKeyId("access-key-value");
    parameter.dnsPublishConfig.setAccessKeySecret("secret-value");
    parameter.dnsPublishConfig.setDnsDomain("nodes.example.org");
    parameter.eventPluginConfig = new EventPluginConfig();
    parameter.eventPluginConfig.setDbConfig("mongodb://user:password@localhost/events");

    Map<String, Object> snapshot = exporter.export(parameter);
    Map<?, ?> dnsConfig = (Map<?, ?>) snapshot.get("dnsPublishConfig");
    Map<?, ?> eventConfig = (Map<?, ?>) snapshot.get("eventPluginConfig");

    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        dnsConfig.get("dnsPrivate"));
    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        dnsConfig.get("accessKeyId"));
    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        dnsConfig.get("accessKeySecret"));
    Assert.assertEquals("nodes.example.org", dnsConfig.get("dnsDomain"));
    Assert.assertEquals(CommonParameterExporter.REDACTED_VALUE,
        eventConfig.get("dbConfig"));
  }
}
