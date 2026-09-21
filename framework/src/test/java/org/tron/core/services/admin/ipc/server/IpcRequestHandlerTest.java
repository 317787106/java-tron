package org.tron.core.services.admin.ipc.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.tron.core.Constant;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.net.service.peermanagement.BlockedIpInfo;
import org.tron.core.net.service.peermanagement.PeerManagementService;
import org.tron.core.net.service.peermanagement.PeerOperationResult;
import org.tron.core.services.admin.AdminJsonRpc;
import org.tron.core.services.admin.AdminJsonRpcImpl;
import org.tron.core.services.admin.ipc.server.IpcRequestHandler.RequestTooLargeException;

public class IpcRequestHandlerTest {

  private static final int MAX_REQUEST_SIZE = 128;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final IpcRequestHandler handler =
      new IpcRequestHandler(Mockito.mock(AdminJsonRpc.class), MAX_REQUEST_SIZE);

  @Test
  public void testHandleCommandReturnsSingleLineJsonResponse() throws Exception {
    AdminJsonRpc api = Mockito.mock(AdminJsonRpc.class);
    Mockito.when(api.addPeer("192.0.2.20:18888"))
        .thenReturn(new PeerOperationResult(false, false, 0, "a\nb:c\rd"));
    IpcRequestHandler handler = new IpcRequestHandler(api, MAX_REQUEST_SIZE);
    String response = handler.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_addPeer\","
            + "\"params\":[\"192.0.2.20:18888\"],\"id\":7}");

    Assert.assertFalse(response, response.contains("\n"));
    Assert.assertFalse(response, response.contains("\r"));
    JsonNode result = OBJECT_MAPPER.readTree(response);
    Assert.assertEquals("a\nb:c\rd", result.get("result").get("errorMessage").asText());
    Assert.assertFalse(result.get("result").get("success").asBoolean());
    Assert.assertFalse(result.get("result").has("message"));
    Assert.assertFalse(result.has("error"));
    Assert.assertEquals(7, result.get("id").asInt());
  }

  @Test
  public void testBlockedIpQueryIncludesCreationTimeOverIpc() throws Exception {
    PeerManagementService peerManagementService = Mockito.mock(PeerManagementService.class);
    Mockito.when(peerManagementService.listBlockedIps()).thenReturn(Collections.singletonList(
        new BlockedIpInfo("192.0.2.20", 1_790_000_000_000L)));
    IpcRequestHandler handler = new IpcRequestHandler(
        new AdminJsonRpcImpl(peerManagementService), MAX_REQUEST_SIZE);

    JsonNode response = OBJECT_MAPPER.readTree(handler.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"id\":8}"));

    Assert.assertEquals(8, response.get("id").asInt());
    Assert.assertEquals(OBJECT_MAPPER.readTree(
        "[{\"ip\":\"192.0.2.20\",\"blockedAtMillis\":1790000000000}]"), response.get("result"));
    Mockito.verify(peerManagementService).listBlockedIps();
  }

  @Test
  public void testHandleCommandReturnsJsonRpcErrorOnDispatcherFailure() throws Exception {
    try (MockedConstruction<JsonRpcServer> servers = Mockito.mockConstruction(JsonRpcServer.class,
        (server, context) -> Mockito.doThrow(new IOException("sensitive-detail"))
            .when(server).handleRequest(Mockito.any(InputStream.class),
                Mockito.any(OutputStream.class)))) {
      IpcRequestHandler failingHandler =
          new IpcRequestHandler(Mockito.mock(AdminJsonRpc.class), MAX_REQUEST_SIZE);
      String response = failingHandler.handleCommand(
          "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\","
              + "\"params\":[],\"id\":9}");
      JsonNode responseNode = OBJECT_MAPPER.readTree(response);

      Assert.assertEquals(1, servers.constructed().size());
      Assert.assertEquals("2.0", responseNode.get("jsonrpc").asText());
      Assert.assertEquals(-32603, responseNode.get("error").get("code").asInt());
      Assert.assertEquals("Internal error", responseNode.get("error").get("message").asText());
      Assert.assertEquals(9, responseNode.get("id").asInt());
      Assert.assertFalse(response, response.contains("sensitive-detail"));
      Assert.assertFalse(response, response.contains("\n"));

      JsonNode malformed = OBJECT_MAPPER.readTree(failingHandler.handleCommand("{broken"));
      Assert.assertEquals(-32603, malformed.get("error").get("code").asInt());
      Assert.assertTrue(malformed.get("id").isNull());
    }
  }

  @Test
  public void testHandleCommandUsesAnnotatedErrorResolver() throws Exception {
    AdminJsonRpc adminJsonRpc = Mockito.mock(AdminJsonRpc.class);
    Mockito.when(adminJsonRpc.addPeer("invalid"))
        .thenThrow(new JsonRpcInvalidParamsException("Invalid admin parameters"));
    IpcRequestHandler errorHandler = new IpcRequestHandler(adminJsonRpc, MAX_REQUEST_SIZE);

    String response = errorHandler.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_addPeer\","
            + "\"params\":[\"invalid\"],\"id\":10}");
    JsonNode responseNode = OBJECT_MAPPER.readTree(response);

    Assert.assertEquals(-32602, responseNode.get("error").get("code").asInt());
    Assert.assertEquals("Invalid admin parameters",
        responseNode.get("error").get("message").asText());
    Assert.assertEquals(10, responseNode.get("id").asInt());
  }

  @Test
  public void testNotificationInvokesMethodWithoutResponse() throws Exception {
    AdminJsonRpc adminJsonRpc = Mockito.mock(AdminJsonRpc.class);
    IpcRequestHandler notificationHandler = new IpcRequestHandler(adminJsonRpc, MAX_REQUEST_SIZE);

    Assert.assertEquals("", notificationHandler.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"params\":[]}"));
    Mockito.verify(adminJsonRpc).listBlockedIps();
  }

  @Test
  public void testIpcMapperRejectsExcessiveNestingBeforeInvocation() throws Exception {
    AdminJsonRpc adminJsonRpc = Mockito.mock(AdminJsonRpc.class);
    Mockito.when(adminJsonRpc.listBlockedIps()).thenReturn(Collections.singletonList(
        new BlockedIpInfo("192.0.2.20", 1_790_000_000_000L)));
    IpcRequestHandler constrainedHandler = new IpcRequestHandler(adminJsonRpc, MAX_REQUEST_SIZE);
    String requestPrefix = "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\","
        + "\"params\":[],\"id\":11,\"extra\":";
    JsonNode valid = OBJECT_MAPPER.readTree(
        constrainedHandler.handleCommand(requestPrefix + "[0]}"));
    Assert.assertEquals("192.0.2.20", valid.get("result").get(0).get("ip").asText());
    Mockito.verify(adminJsonRpc).listBlockedIps();
    Mockito.clearInvocations(adminJsonRpc);

    StringBuilder nested = new StringBuilder();
    for (int i = 0; i <= Constant.MAX_NESTING_DEPTH; i++) {
      nested.append('[');
    }
    nested.append('0');
    for (int i = 0; i <= Constant.MAX_NESTING_DEPTH; i++) {
      nested.append(']');
    }
    JsonNode rejected = OBJECT_MAPPER.readTree(
        constrainedHandler.handleCommand(requestPrefix + nested + "}"));
    Assert.assertTrue(rejected.toString(), rejected.has("error"));
    Assert.assertFalse(rejected.has("result"));
    Mockito.verifyNoInteractions(adminJsonRpc);
  }

  @Test
  public void testHandleCommandDispatchesPeerManagementMethod() throws Exception {
    PeerManagementService peerManagementService = Mockito.mock(PeerManagementService.class);
    Mockito.when(peerManagementService.addPeer("192.0.2.20:18888"))
        .thenReturn(new PeerOperationResult(true, true, 0, ""));
    IpcRequestHandler service = new IpcRequestHandler(
        new AdminJsonRpcImpl(peerManagementService), MAX_REQUEST_SIZE);

    String response = service.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_addPeer\","
            + "\"params\":[\"192.0.2.20:18888\"],\"id\":8}");
    JsonNode result = new ObjectMapper().readTree(response).get("result");

    Assert.assertTrue(result.get("success").asBoolean());
    Assert.assertTrue(result.get("changed").asBoolean());
    Assert.assertEquals(0, result.get("disconnectedCount").asInt());
    Assert.assertEquals("", result.get("errorMessage").asText());
    Assert.assertFalse(result.has("message"));
    Mockito.verify(peerManagementService).addPeer("192.0.2.20:18888");
  }

  @Test
  public void testPeerManagementErrorsUseAnnotatedJsonRpcCodes() throws Exception {
    PeerManagementService peerManagementService = Mockito.mock(PeerManagementService.class);
    Mockito.when(peerManagementService.addPeer("invalid"))
        .thenThrow(new JsonRpcInvalidParamsException("Invalid peer endpoint"));
    Mockito.when(peerManagementService.addPeer("192.0.2.20:18888"))
        .thenThrow(new JsonRpcInternalException("P2P service is not ready"));
    IpcRequestHandler service = new IpcRequestHandler(
        new AdminJsonRpcImpl(peerManagementService), MAX_REQUEST_SIZE);

    JsonNode invalidParams = new ObjectMapper().readTree(service.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_addPeer\","
            + "\"params\":[\"invalid\"],\"id\":11}"));
    JsonNode internalError = new ObjectMapper().readTree(service.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_addPeer\","
            + "\"params\":[\"192.0.2.20:18888\"],\"id\":12}"));

    Assert.assertEquals(-32602, invalidParams.get("error").get("code").asInt());
    Assert.assertEquals("Invalid peer endpoint",
        invalidParams.get("error").get("message").asText());
    Assert.assertEquals(-32000, internalError.get("error").get("code").asInt());
    Assert.assertEquals("P2P service is not ready",
        internalError.get("error").get("message").asText());
  }

  @Test
  public void testReadRequestAcceptsMaximumSize() throws Exception {
    byte[] request = new byte[MAX_REQUEST_SIZE + 1];
    Arrays.fill(request, 0, MAX_REQUEST_SIZE, (byte) '1');
    request[MAX_REQUEST_SIZE] = '\n';

    Assert.assertEquals(MAX_REQUEST_SIZE,
        handler.readRequest(new ByteArrayInputStream(request)).length());
  }

  @Test(expected = RequestTooLargeException.class)
  public void testReadRequestRejectsOversizedInputWithoutNewline() throws Exception {
    handler.readRequest(new ByteArrayInputStream(new byte[MAX_REQUEST_SIZE + 1]));
  }

  @Test
  public void testReadRequestPreservesFramesAndDistinguishesEmptyLineFromEof() throws Exception {
    ByteArrayInputStream input = new ByteArrayInputStream(
        "first\r\n\n  second  \nlast".getBytes(StandardCharsets.UTF_8));

    Assert.assertEquals("first", handler.readRequest(input));
    Assert.assertEquals("", handler.readRequest(input));
    Assert.assertEquals("  second  ", handler.readRequest(input));
    Assert.assertEquals("last", handler.readRequest(input));
    Assert.assertNull(handler.readRequest(input));
  }

  @Test
  public void testReadRequestCountsBytesAndDecodesUtf8() throws Exception {
    IpcRequestHandler limited = new IpcRequestHandler(Mockito.mock(AdminJsonRpc.class), 3);
    Assert.assertEquals("中", limited.readRequest(new ByteArrayInputStream(
        "中\n".getBytes(StandardCharsets.UTF_8))));
    try {
      limited.readRequest(new ByteArrayInputStream("中文\n".getBytes(StandardCharsets.UTF_8)));
      Assert.fail("Expected multi-byte input to exceed the byte limit");
    } catch (RequestTooLargeException expected) {
      // A character count would incorrectly accept both characters.
    }
  }

  @Test
  public void testZeroLimitOnlyAcceptsEmptyLines() throws Exception {
    IpcRequestHandler zeroLimit = new IpcRequestHandler(Mockito.mock(AdminJsonRpc.class), 0);
    Assert.assertEquals(0, zeroLimit.getMaxRequestSize());
    Assert.assertEquals("", zeroLimit.readRequest(new ByteArrayInputStream(new byte[] {'\n'})));
    Assert.assertNull(zeroLimit.readRequest(new ByteArrayInputStream(new byte[0])));
    try {
      zeroLimit.readRequest(new ByteArrayInputStream(new byte[] {'a'}));
      Assert.fail("Expected a configured zero limit to reject nonempty input");
    } catch (RequestTooLargeException expected) {
      // Zero must not silently fall back to a default limit.
    }
  }
}
