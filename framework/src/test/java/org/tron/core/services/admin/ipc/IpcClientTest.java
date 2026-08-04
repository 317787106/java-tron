package org.tron.core.services.admin.ipc;

import java.io.ByteArrayOutputStream;
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

  @Test
  public void testBuildHelpLinesIncludesSortedCommandParameters() {
    IpcClient client = new IpcClient("unused");

    Assert.assertEquals(Arrays.asList(
        "admin_example <param1> <param2>",
        "admin_getRuntimeParameters",
        "help [command]",
        "exit",
        "quit"), client.buildHelpLines());
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
      serverConnection.getOutputStream().write("response\n".getBytes(StandardCharsets.UTF_8));
      serverConnection.getOutputStream().flush();
      Mockito.verify(reader, Mockito.timeout(5_000)).printAbove("response");

      serverConnection.close();
      sessionThread.join(5_000);

      Assert.assertFalse("IPC client did not exit after server disconnected",
          sessionThread.isAlive());
      Assert.assertNull("IPC client session failed", failure.get());
      Mockito.verify(reader).printAbove("Disconnected from server.");
    }
  }
}
