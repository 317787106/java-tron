package org.tron.core.services.admin.ipc;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class IpcClientTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  public void testBuildHelpLinesIncludesSortedCommandParameters() {
    IpcClient client = new IpcClient("unused");

    Assert.assertEquals(Arrays.asList(
        "admin_example <param1:string> <param2:string>",
        "admin_getRuntimeParameters",
        "help [command]",
        "exit",
        "quit"), client.buildHelpLines());
  }

  @Test
  public void testParseCommandLinePreservesQuotedArguments() {
    IpcClient client = new IpcClient("unused");

    Assert.assertEquals(Arrays.asList("admin_example", "hello world", "second value"),
        client.parseCommandLine("admin_example \"hello world\" 'second value'"));
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
  public void testExecSendsCommandAndPrintsFormattedResult() throws Exception {
    Socket socket = Mockito.mock(Socket.class);
    ByteArrayOutputStream requestOutput = new ByteArrayOutputStream();
    Mockito.when(socket.getOutputStream()).thenReturn(requestOutput);
    Mockito.when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(
        "\n{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"hello world:b\"}\n"
            .getBytes(StandardCharsets.UTF_8)));

    PrintStream originalOut = System.out;
    ByteArrayOutputStream consoleOutput = new ByteArrayOutputStream();
    PrintStream capturedOut = new PrintStream(consoleOutput, true, "UTF-8");
    try {
      System.setOut(capturedOut);
      new IpcClient("unused").runExec(socket, "admin_example \"hello world\" b");
    } finally {
      System.setOut(originalOut);
      capturedOut.close();
    }

    JsonNode request = OBJECT_MAPPER.readTree(requestOutput.toString("UTF-8"));
    Assert.assertEquals("admin_example", request.get("method").asText());
    Assert.assertEquals("hello world", request.get("params").get(0).asText());
    Assert.assertEquals("b", request.get("params").get(1).asText());
    Assert.assertEquals("hello world:b" + System.lineSeparator(),
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
      new IpcClient("unused").runExec(Mockito.mock(Socket.class),
          "admin_example \"sensitive-value");
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
      new IpcClient(missingSocket.toString()).run();
    } finally {
      System.setErr(originalErr);
      capturedErr.close();
      Files.deleteIfExists(temporaryDirectory);
    }

    Assert.assertEquals("IPC socket file does not exist: missing.sock" + System.lineSeparator(),
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
}
