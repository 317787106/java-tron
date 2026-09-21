package org.tron.core.services.admin.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcInterceptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.tron.core.Constant;
import org.tron.core.net.service.peermanagement.BlockedIpInfo;
import org.tron.core.net.service.peermanagement.PeerOperationResult;
import org.tron.core.services.admin.AdminJsonRpc;

public class AdminRpcServletTest {

  private TestableServlet servlet;
  private AdminJsonRpc adminJsonRpc;

  @Before
  public void setUp() throws Exception {
    servlet = new TestableServlet();
    adminJsonRpc = mock(AdminJsonRpc.class);
    setField("adminJsonRpc", adminJsonRpc);
    setField("interceptor", mock(JsonRpcInterceptor.class));
    servlet.init(new MockServletConfig());
    setVirtualHosts("localhost");
  }

  @Test
  public void excessivelyNestedRequestIsRejected() throws Exception {
    StringBuilder request = new StringBuilder();
    for (int i = 0; i <= Constant.MAX_NESTING_DEPTH; i++) {
      request.append('[');
    }
    request.append('0');
    for (int i = 0; i <= Constant.MAX_NESTING_DEPTH; i++) {
      request.append(']');
    }

    MockHttpServletResponse response = doPost(request.toString());
    assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    assertEquals(0, response.getContentAsByteArray().length);
  }

  @Test
  public void requestWithTooManyTokensIsRejected() throws Exception {
    StringBuilder request = new StringBuilder("{\"params\":[");
    for (int i = 0; i < Constant.MAX_TOKEN_COUNT; i++) {
      if (i > 0) {
        request.append(',');
      }
      request.append('0');
    }
    request.append("]}");

    MockHttpServletResponse response = doPost(request.toString());
    assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    assertEquals(0, response.getContentAsByteArray().length);
  }

  @Test
  public void nonJsonContentTypeIsRejected() throws Exception {
    MockHttpServletResponse response = doPost(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"id\":1}",
        "text/plain");

    assertEquals(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, response.getStatus());
    assertEquals(0, response.getContentAsByteArray().length);
  }

  @Test
  public void missingContentTypeIsRejected() throws Exception {
    MockHttpServletResponse response = doPost(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"id\":1}", null);

    assertEquals(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, response.getStatus());
    assertEquals(0, response.getContentAsByteArray().length);
  }

  @Test
  public void jsonContentTypesAreAccepted() throws Exception {
    String body = "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"id\":1}";

    assertEquals(HttpServletResponse.SC_OK,
        doPost(body, "application/json; charset=UTF-8").getStatus());
    assertEquals(HttpServletResponse.SC_OK,
        doPost(body, "application/json-rpc").getStatus());
    assertEquals(HttpServletResponse.SC_OK,
        doPost(body, "application/vnd.tron+json").getStatus());
  }

  @Test
  public void unlistedVirtualHostIsRejected() throws Exception {
    MockHttpServletResponse response = doPost(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"id\":1}",
        "application/json", "evil.example:8575");

    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
  }

  @Test
  public void listedVirtualHostIsAcceptedCaseInsensitivelyAndWithoutPort() throws Exception {
    setVirtualHosts("admin.example.com");

    MockHttpServletResponse response = doPost(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"id\":1}",
        "application/json", "ADMIN.EXAMPLE.COM:8575");

    assertEquals(HttpServletResponse.SC_OK, response.getStatus());
  }

  @Test
  public void ipLiteralHostsAreAccepted() throws Exception {
    String body = "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"id\":1}";

    assertEquals(HttpServletResponse.SC_OK,
        doPost(body, "application/json", "127.0.0.1:8575").getStatus());
    assertEquals(HttpServletResponse.SC_OK,
        doPost(body, "application/json", "[::1]:8575").getStatus());
  }

  @Test
  public void wildcardVirtualHostAcceptsAnyHostname() throws Exception {
    setVirtualHosts("*");

    MockHttpServletResponse response = doPost(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"id\":1}",
        "application/json", "any.example:8575");

    assertEquals(HttpServletResponse.SC_OK, response.getStatus());
  }

  @Test
  public void peerManagementMethodIsAvailableOverHttp() throws Exception {
    Mockito.when(adminJsonRpc.addPeer("192.0.2.20:18888"))
        .thenReturn(new PeerOperationResult(true, true, 0, ""));

    MockHttpServletResponse response = doPost(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_addPeer\","
            + "\"params\":[\"192.0.2.20:18888\"],\"id\":8}");
    JsonNode result = new ObjectMapper().readTree(response.getContentAsByteArray()).get("result");

    assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    assertEquals(true, result.get("success").asBoolean());
    assertEquals(true, result.get("changed").asBoolean());
    assertEquals("", result.get("errorMessage").asText());
    assertFalse(result.has("message"));
    Mockito.verify(adminJsonRpc).addPeer("192.0.2.20:18888");
  }

  @Test
  public void peerOperationFailureUsesErrorMessageOverHttp() throws Exception {
    String errorMessage = "Peer modification is unavailable; update node.active instead.";
    Mockito.when(adminJsonRpc.addPeer("192.0.2.20:18888"))
        .thenReturn(new PeerOperationResult(false, false, 0, errorMessage));

    MockHttpServletResponse response = doPost(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_addPeer\","
            + "\"params\":[\"192.0.2.20:18888\"],\"id\":10}");
    JsonNode responseNode = new ObjectMapper().readTree(response.getContentAsByteArray());
    JsonNode result = responseNode.get("result");

    assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    assertEquals(10, responseNode.get("id").asInt());
    assertEquals(false, result.get("success").asBoolean());
    assertEquals(false, result.get("changed").asBoolean());
    assertEquals(0, result.get("disconnectedCount").asInt());
    assertEquals(errorMessage, result.get("errorMessage").asText());
    assertFalse(result.has("message"));
    assertFalse(responseNode.has("error"));
    Mockito.verify(adminJsonRpc).addPeer("192.0.2.20:18888");
  }

  private void setField(String name, Object value) throws Exception {
    Field field = AdminRpcServlet.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(servlet, value);
  }

  @Test
  public void blockedIpQueryIncludesCreationTimeOverHttp() throws Exception {
    Mockito.when(adminJsonRpc.listBlockedIps()).thenReturn(Arrays.asList(
        new BlockedIpInfo("192.0.2.20", 1_790_000_000_000L),
        new BlockedIpInfo("2001:db8:0:0:0:0:0:20", 1_790_000_001_000L)));

    MockHttpServletResponse response = doPost(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"id\":9}");
    ObjectMapper mapper = new ObjectMapper();
    JsonNode result = mapper.readTree(response.getContentAsByteArray());

    assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    assertEquals(9, result.get("id").asInt());
    assertEquals(mapper.readTree("[{\"ip\":\"192.0.2.20\",\"blockedAtMillis\":1790000000000},"
        + "{\"ip\":\"2001:db8:0:0:0:0:0:20\",\"blockedAtMillis\":1790000001000}]"),
        result.get("result"));
    Mockito.verify(adminJsonRpc).listBlockedIps();
  }

  private MockHttpServletResponse doPost(String body) throws Exception {
    return doPost(body, "application/json");
  }

  private MockHttpServletResponse doPost(String body, String contentType) throws Exception {
    return doPost(body, contentType, null);
  }

  private MockHttpServletResponse doPost(String body, String contentType, String host)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin");
    request.setContentType(contentType);
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    if (host != null) {
      request.addHeader("Host", host);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    servlet.callDoPost(request, response);
    return response;
  }

  private void setVirtualHosts(String... hosts) throws Exception {
    setField("virtualHostValidator", new VirtualHostValidator(Arrays.asList(hosts)));
  }

  private static class TestableServlet extends AdminRpcServlet {

    void callDoPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
      doPost(request, response);
    }
  }
}
