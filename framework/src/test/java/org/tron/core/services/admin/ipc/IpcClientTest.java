package org.tron.core.services.admin.ipc;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.googlecode.jsonrpc4j.JsonRpcMethod;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class IpcClientTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  public void testClientDoesNotDeclareLogger() {
    try {
      IpcClient.class.getDeclaredField("logger");
      Assert.fail("IPC client must not initialize the node logging system");
    } catch (NoSuchFieldException expected) {
      // No logger field means loading IpcClient cannot initialize SLF4J through this class.
    }
  }

  @Test
  public void testBuildHelpLinesIncludesSortedCommandParameters() {
    IpcClient client = new IpcClient("unused");

    Assert.assertEquals(Arrays.asList(
        "admin_addPeer <endpoint:string>",
        "admin_blockIp <ip:string>",
        "admin_disconnectPeer <endpoint:string>",
        "admin_listActivePeers [format:json|text]",
        "admin_listBlockedIps",
        "admin_removePeer <endpoint:string>",
        "admin_unblockIp <ip:string>",
        "help [command]",
        "exit/quit"), client.buildHelpLines());
  }

  @Test
  public void testCompletionUsesCanonicalMethodNames() {
    IpcClient client = new IpcClient("unused");

    Assert.assertArrayEquals(new String[] {
        "admin_addPeer",
        "admin_blockIp",
        "admin_disconnectPeer",
        "admin_listActivePeers",
        "admin_listBlockedIps",
        "admin_removePeer",
        "admin_unblockIp"
    }, client.getCompletionCommandNames());
  }

  @Test
  public void testMissingJsonRpcParameterAnnotationIsRejected() {
    try {
      new IpcClient("unused", MissingParameterAnnotationApi.class);
      Assert.fail("Expected an unannotated JSON-RPC parameter to be rejected");
    } catch (IllegalStateException e) {
      Assert.assertEquals("Missing @JsonRpcParam on invalid parameter 0", e.getMessage());
    }
  }

  @Test
  public void testParseCommandLinePreservesQuotedArguments() {
    IpcClient client = new IpcClient("unused");

    Assert.assertEquals(Arrays.asList("custom_method", " hello world ", "second value"),
        client.parseCommandLine(" \tcustom_method \" hello world \" 'second value'  "));
  }

  @Test
  public void testConvertTypedArguments() {
    IpcClient client = new IpcClient("unused");
    TypeFactory typeFactory = TypeFactory.defaultInstance();

    Assert.assertEquals(42, client.convertArgument("42",
        typeFactory.constructType(Integer.TYPE), "number"));
    Assert.assertEquals(true, client.convertArgument("true",
        typeFactory.constructType(Boolean.TYPE), "enabled"));
    JavaType listType = typeFactory.constructCollectionType(java.util.List.class, Integer.class);
    Assert.assertEquals(Arrays.asList(1, 2),
        client.convertArgument("[1,2]", listType, "numbers"));

    try {
      client.convertArgument("null", typeFactory.constructType(Integer.TYPE), "number");
      Assert.fail("Expected null to be rejected for a primitive parameter");
    } catch (IllegalArgumentException e) {
      Assert.assertEquals("Invalid value for <number>; expected int", e.getMessage());
    }

    try {
      client.convertArgument("sensitive-value", typeFactory.constructType(Integer.TYPE), "number");
      Assert.fail("Expected an invalid typed parameter");
    } catch (IllegalArgumentException e) {
      Assert.assertEquals("Invalid value for <number>; expected int", e.getMessage());
      Assert.assertFalse(e.getMessage().contains("sensitive-value"));
    }
  }

  @Test
  public void testFormatResponseShowsResultOrStructuredError() {
    IpcClient client = new IpcClient("unused");

    Assert.assertEquals("done", client.formatResponse(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"done\"}"));
    String formattedObject = client.formatResponse(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"height\":10,\"ready\":true}}");
    Assert.assertTrue(formattedObject, formattedObject.contains(System.lineSeparator()));
    Assert.assertTrue(formattedObject, formattedObject.contains("\"height\" : 10"));
    Assert.assertEquals("Error -32602: Invalid params", client.formatResponse(
        "{\"jsonrpc\":\"2.0\",\"id\":1,"
            + "\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}"));
    Assert.assertEquals("", client.formatResponse(""));
  }

  @Test
  public void testListActivePeersDefaultsAndExplicitJsonKeepRpcRequestUnchanged()
      throws Exception {
    for (String command : Arrays.asList(
        "admin_listActivePeers", "admin_listActivePeers json")) {
      ExecResult execution = executeCommand(command,
          createActivePeersResponse("192.0.2.20:18888"));

      Assert.assertEquals(IpcClient.EXIT_SUCCESS, execution.exitCode);
      JsonNode request = OBJECT_MAPPER.readTree(execution.request);
      Assert.assertEquals("admin_listActivePeers", request.get("method").asText());
      Assert.assertEquals(0, request.get("params").size());
      Assert.assertTrue(execution.standardOutput,
          execution.standardOutput.contains("\"allCount\" : 1"));
      Assert.assertTrue(execution.standardOutput,
          execution.standardOutput.contains("\"remoteAddress\" : \"192.0.2.20:18888\""));
      Assert.assertFalse(execution.standardOutput,
          execution.standardOutput.contains(" | "));
      Assert.assertEquals("", execution.errorOutput);
    }
  }

  @Test
  public void testListActivePeersTextRendersTableWithoutChangingRpcRequest() throws Exception {
    ExecResult execution = executeCommand("admin_listActivePeers text",
        createActivePeersResponse("192.0.2.20:18888"));

    Assert.assertEquals(IpcClient.EXIT_SUCCESS, execution.exitCode);
    JsonNode request = OBJECT_MAPPER.readTree(execution.request);
    Assert.assertEquals("admin_listActivePeers", request.get("method").asText());
    Assert.assertEquals(0, request.get("params").size());
    Assert.assertTrue(execution.standardOutput,
        execution.standardOutput.contains("Peers: all=1, active=1, passive=0, valid=1"));
    for (String field : Arrays.asList(
        "remoteAddress", "connectSeconds", "averageLatencyMillis", "lastKnownBlockNum",
        "needSyncFromPeer", "needSyncFromUs", "syncToFetchSize", "syncToFetchSizePeekNum",
        "syncBlockRequestedSize", "remainNum", "syncChainRequestedMillis", "inactiveSeconds",
        "blockInProcess")) {
      Assert.assertTrue(field, execution.standardOutput.contains(field));
    }
    Assert.assertTrue(execution.standardOutput,
        execution.standardOutput.contains("192.0.2.20:18888"));
    Assert.assertFalse(execution.standardOutput,
        execution.standardOutput.contains("\"remoteAddress\""));
    Assert.assertEquals("", execution.errorOutput);
  }

  @Test
  public void testListActivePeersTextSanitizesTerminalControlCharacters() throws Exception {
    ExecResult execution = executeCommand("admin_listActivePeers text",
        createActivePeersResponse("peer\u001b[31m|address"));

    Assert.assertEquals(IpcClient.EXIT_SUCCESS, execution.exitCode);
    Assert.assertFalse(execution.standardOutput,
        execution.standardOutput.contains("\u001b"));
    Assert.assertTrue(execution.standardOutput,
        execution.standardOutput.contains("peer?[31m?address"));
  }

  @Test
  public void testListActivePeersRejectsUnsupportedOutputFormat() throws Exception {
    ExecResult execution = executeCommand("admin_listActivePeers yaml",
        createActivePeersResponse("192.0.2.20:18888"));

    Assert.assertEquals(IpcClient.EXIT_FAILURE, execution.exitCode);
    Assert.assertEquals("", execution.request);
    Assert.assertEquals("", execution.standardOutput);
    Assert.assertEquals("Invalid value for <format>; expected json or text"
        + System.lineSeparator(), execution.errorOutput);
  }

  @Test
  public void testExecSendsCommandAndPrintsFormattedResult() throws Exception {
    Socket socket = Mockito.mock(Socket.class);
    ByteArrayOutputStream requestOutput = new ByteArrayOutputStream();
    Mockito.when(socket.getOutputStream()).thenReturn(requestOutput);
    Mockito.when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(
        "\n{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"added\"}\n"
            .getBytes(StandardCharsets.UTF_8)));

    PrintStream originalOut = System.out;
    ByteArrayOutputStream consoleOutput = new ByteArrayOutputStream();
    PrintStream capturedOut = new PrintStream(consoleOutput, true, "UTF-8");
    try {
      System.setOut(capturedOut);
      Assert.assertEquals(IpcClient.EXIT_SUCCESS,
          new IpcClient("unused").runExec(socket,
              "  admin_addPeer \"192.0.2.20:18888\" \t"));
    } finally {
      System.setOut(originalOut);
      capturedOut.close();
    }

    JsonNode request = OBJECT_MAPPER.readTree(requestOutput.toString("UTF-8"));
    Assert.assertEquals("admin_addPeer", request.get("method").asText());
    Assert.assertEquals("192.0.2.20:18888", request.get("params").get(0).asText());
    Assert.assertEquals("added" + System.lineSeparator(),
        consoleOutput.toString("UTF-8"));
  }

  @Test
  public void testWelcomeShowsConnectionAndUsageHint() throws Exception {
    Path temporaryDirectory = Files.createTempDirectory("ipc-welcome-test-");
    File socketFile = temporaryDirectory.resolve("java-tron.1234.sock").toFile();
    PrintStream originalOut = System.out;
    ByteArrayOutputStream consoleOutput = new ByteArrayOutputStream();
    PrintStream capturedOut = new PrintStream(consoleOutput, true, "UTF-8");
    try {
      System.setOut(capturedOut);
      new IpcClient(socketFile.getPath()).printWelcome(socketFile);
    } finally {
      System.setOut(originalOut);
      capturedOut.close();
      Files.deleteIfExists(temporaryDirectory);
    }

    String welcome = consoleOutput.toString("UTF-8");
    Assert.assertTrue(welcome, welcome.contains("Welcome to the java-tron admin console."));
    Assert.assertTrue(welcome, welcome.contains("IPC endpoint: " + socketFile.getAbsolutePath()));
    Assert.assertFalse(welcome, welcome.contains("History:"));
    Assert.assertTrue(welcome, welcome.contains("Type \"help\" for available commands"));
  }

  @Test
  public void testExecWithInvalidSyntaxDoesNotExposeCommand() throws Exception {
    PrintStream originalErr = System.err;
    ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
    PrintStream capturedErr = new PrintStream(errorOutput, true, "UTF-8");
    try {
      System.setErr(capturedErr);
      Assert.assertEquals(IpcClient.EXIT_FAILURE,
          new IpcClient("unused").runExec(Mockito.mock(Socket.class),
              "admin_addPeer \"sensitive-value"));
    } finally {
      System.setErr(originalErr);
      capturedErr.close();
    }

    Assert.assertEquals("Invalid command syntax." + System.lineSeparator(),
        errorOutput.toString("UTF-8"));
    Assert.assertFalse(errorOutput.toString("UTF-8").contains("sensitive-value"));
  }

  @Test
  public void testMissingSocketFilePrintsConsoleError() throws Exception {
    Path temporaryDirectory = Files.createTempDirectory("ipc-client-test-");
    Path missingSocket = temporaryDirectory.resolve("missing.sock");
    PrintStream originalErr = System.err;
    ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
    PrintStream capturedErr = new PrintStream(errorOutput, true, "UTF-8");
    try {
      System.setErr(capturedErr);
      Assert.assertEquals(IpcClient.EXIT_FAILURE,
          new IpcClient(missingSocket.toString()).run());
    } finally {
      System.setErr(originalErr);
      capturedErr.close();
      Files.deleteIfExists(temporaryDirectory);
    }

    Assert.assertEquals("Error: IPC socket file does not exist: missing.sock"
            + System.lineSeparator(),
        errorOutput.toString("UTF-8"));
  }

  @Test
  public void testExecReturnsFailureForRpcError() throws Exception {
    Socket socket = Mockito.mock(Socket.class);
    Mockito.when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
    Mockito.when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(
        ("{\"jsonrpc\":\"2.0\",\"id\":1,"
            + "\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}\n")
            .getBytes(StandardCharsets.UTF_8)));

    PrintStream originalErr = System.err;
    ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
    PrintStream capturedErr = new PrintStream(errorOutput, true, "UTF-8");
    try {
      System.setErr(capturedErr);
      Assert.assertEquals(IpcClient.EXIT_FAILURE,
          new IpcClient("unused").runExec(socket, "admin_listBlockedIps"));
    } finally {
      System.setErr(originalErr);
      capturedErr.close();
    }

    Assert.assertEquals("Error -32603: Internal error" + System.lineSeparator(),
        errorOutput.toString("UTF-8"));
  }

  @Test
  public void testExecReturnsFailureWhenServerDisconnects() throws Exception {
    Socket socket = Mockito.mock(Socket.class);
    Mockito.when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
    Mockito.when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));

    PrintStream originalErr = System.err;
    ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
    PrintStream capturedErr = new PrintStream(errorOutput, true, "UTF-8");
    try {
      System.setErr(capturedErr);
      Assert.assertEquals(IpcClient.EXIT_FAILURE,
          new IpcClient("unused").runExec(socket, "admin_listBlockedIps"));
    } finally {
      System.setErr(originalErr);
      capturedErr.close();
    }

    Assert.assertEquals(
        "Disconnected from server before receiving a response." + System.lineSeparator(),
        errorOutput.toString("UTF-8"));
  }

  @Test
  public void testExecTimesOutWaitingForResponse() throws Exception {
    Socket socket = Mockito.mock(Socket.class);
    Mockito.when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
    Mockito.when(socket.getInputStream()).thenReturn(new InputStream() {
      @Override
      public int read() throws IOException {
        throw new SocketTimeoutException("timed out");
      }
    });

    PrintStream originalErr = System.err;
    ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
    PrintStream capturedErr = new PrintStream(errorOutput, true, "UTF-8");
    try {
      System.setErr(capturedErr);
      Assert.assertEquals(IpcClient.EXIT_FAILURE,
          new IpcClient("unused").runExec(socket, "admin_listBlockedIps"));
    } finally {
      System.setErr(originalErr);
      capturedErr.close();
    }

    Mockito.verify(socket).setSoTimeout(30_000);
    Assert.assertEquals("Timed out waiting for IPC response." + System.lineSeparator(),
        errorOutput.toString("UTF-8"));
  }

  @Test(timeout = 10_000)
  public void testSessionPrintsResponseAndExitsWhenServerDisconnects() throws Exception {
    CountDownLatch inputStarted = new CountDownLatch(1);
    CountDownLatch waitForInterrupt = new CountDownLatch(1);
    LineReader reader = Mockito.mock(LineReader.class);
    Mockito.when(reader.readLine("> ")).thenAnswer(invocation -> {
      inputStarted.countDown();
      try {
        waitForInterrupt.await();
        return "";
      } catch (InterruptedException e) {
        throw new UserInterruptException("");
      }
    });

    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket clientSocket = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket serverConnection = serverSocket.accept()) {
      IpcClient client = new IpcClient("unused");
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread sessionThread = new Thread(() -> {
        try {
          client.runSession(clientSocket, reader);
        } catch (Throwable throwable) {
          failure.set(throwable);
        }
      }, "ipc-client-test-session");
      sessionThread.setDaemon(true);
      sessionThread.start();

      Assert.assertTrue("IPC client did not start reading terminal input",
          inputStarted.await(5, TimeUnit.SECONDS));
      serverConnection.getOutputStream().write("\nresponse\n\n".getBytes(StandardCharsets.UTF_8));
      serverConnection.getOutputStream().flush();
      Mockito.verify(reader, Mockito.timeout(5_000)).printAbove("response");

      serverConnection.close();
      sessionThread.join(5_000);

      Assert.assertFalse("IPC client did not exit after server disconnected",
          sessionThread.isAlive());
      Assert.assertNull("IPC client session failed", failure.get());
      Mockito.verify(reader, Mockito.never()).printAbove("null");
      Mockito.verify(reader, Mockito.never()).printAbove("");
      Mockito.verify(reader).printAbove("Disconnected from server.");
    }
  }

  @Test(timeout = 10_000)
  public void testSessionExitDoesNotInterruptInputThread() throws Exception {
    LineReader reader = Mockito.mock(LineReader.class);
    Mockito.when(reader.readLine("> ")).thenReturn("exit");

    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket clientSocket = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket serverConnection = serverSocket.accept()) {
      IpcClient client = new IpcClient("unused");
      AtomicReference<Throwable> failure = new AtomicReference<>();
      AtomicBoolean interrupted = new AtomicBoolean(true);
      Thread sessionThread = new Thread(() -> {
        try {
          client.runSession(clientSocket, reader);
          interrupted.set(Thread.currentThread().isInterrupted());
        } catch (Throwable throwable) {
          failure.set(throwable);
        }
      }, "ipc-client-clean-exit-test-session");
      sessionThread.setDaemon(true);
      sessionThread.start();
      sessionThread.join(5_000);

      Assert.assertFalse("IPC client did not exit after the exit command", sessionThread.isAlive());
      Assert.assertNull("IPC client session failed", failure.get());
      Assert.assertFalse("Clean IPC client exit left the thread interrupted", interrupted.get());
      Mockito.verify(reader, Mockito.never()).printAbove("Disconnected from server.");
    }
  }

  @Test(timeout = 10_000)
  public void testSessionDoesNotSwallowUnexpectedRuntimeException() throws Exception {
    LineReader reader = Mockito.mock(LineReader.class);
    IllegalStateException expected = new IllegalStateException("unexpected failure");
    Mockito.when(reader.readLine("> ")).thenThrow(expected);

    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket clientSocket = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket serverConnection = serverSocket.accept()) {
      try {
        new IpcClient("unused").runSession(clientSocket, reader);
        Assert.fail("Expected the unexpected runtime exception to propagate");
      } catch (IllegalStateException e) {
        Assert.assertSame(expected, e);
      }
    }
  }

  private ExecResult executeCommand(String command, String response) throws Exception {
    Socket socket = Mockito.mock(Socket.class);
    ByteArrayOutputStream requestOutput = new ByteArrayOutputStream();
    Mockito.when(socket.getOutputStream()).thenReturn(requestOutput);
    Mockito.when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(
        (response + "\n").getBytes(StandardCharsets.UTF_8)));

    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
    ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
    PrintStream capturedOut = new PrintStream(standardOutput, true, "UTF-8");
    PrintStream capturedErr = new PrintStream(errorOutput, true, "UTF-8");
    int exitCode;
    try {
      System.setOut(capturedOut);
      System.setErr(capturedErr);
      exitCode = new IpcClient("unused").runExec(socket, command);
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
      capturedOut.close();
      capturedErr.close();
    }
    return new ExecResult(exitCode, requestOutput.toString("UTF-8"),
        standardOutput.toString("UTF-8"), errorOutput.toString("UTF-8"));
  }

  private String createActivePeersResponse(String remoteAddress) throws Exception {
    ObjectNode response = OBJECT_MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.put("id", 1);
    ObjectNode result = response.putObject("result");
    result.put("allCount", 1);
    result.put("activeCount", 1);
    result.put("passiveCount", 0);
    result.put("validCount", 1);
    ArrayNode peers = result.putArray("peers");
    ObjectNode peer = peers.addObject();
    peer.put("remoteAddress", remoteAddress);
    peer.put("connectSeconds", 125);
    peer.put("averageLatencyMillis", 35);
    peer.put("lastKnownBlockNum", 81_234_567);
    peer.put("needSyncFromPeer", false);
    peer.put("needSyncFromUs", false);
    peer.put("syncToFetchSize", 3);
    peer.put("syncToFetchSizePeekNum", 81_234_560);
    peer.put("syncBlockRequestedSize", 2);
    peer.put("remainNum", 7);
    peer.put("syncChainRequestedMillis", 450);
    peer.put("inactiveSeconds", 2);
    peer.put("blockInProcess", 1);
    return OBJECT_MAPPER.writeValueAsString(response);
  }

  private static class ExecResult {

    private final int exitCode;
    private final String request;
    private final String standardOutput;
    private final String errorOutput;

    private ExecResult(int exitCode, String request, String standardOutput, String errorOutput) {
      this.exitCode = exitCode;
      this.request = request;
      this.standardOutput = standardOutput;
      this.errorOutput = errorOutput;
    }
  }

  private interface MissingParameterAnnotationApi {

    @JsonRpcMethod("admin_invalid")
    String invalid(String value);
  }
}
