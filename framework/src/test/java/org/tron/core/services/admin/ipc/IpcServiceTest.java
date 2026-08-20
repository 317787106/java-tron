package org.tron.core.services.admin.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.Constant;
import org.tron.core.config.args.Args;
import org.tron.core.exception.TronError;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.services.admin.AdminJsonRpc;
import org.tron.core.services.admin.AdminJsonRpcImpl;
import org.tron.core.services.admin.PeerManagementService;
import org.tron.core.services.admin.PeerOperationResult;

public class IpcServiceTest {

  private int originalMaxMessageSize;

  @Before
  public void setUp() {
    originalMaxMessageSize = Args.getInstance().maxMessageSize;
    Args.getInstance().maxMessageSize = 4 * 1024 * 1024;
  }

  @After
  public void tearDown() {
    Args.getInstance().maxMessageSize = originalMaxMessageSize;
  }

  @Test
  public void testServiceIsNotRunningBeforeStart() throws Exception {
    Assert.assertFalse(isRunning(newIpcService()));
  }

  @Test
  public void testRequestSizePreservesConfiguredZero() throws Exception {
    Args.getInstance().maxMessageSize = 0;

    Assert.assertEquals(0, getIntField(newIpcService(), "maxRequestSize"));
  }

  @Test
  public void testResolveSocketFilePathUsesOutputDirectory() throws Exception {
    IpcService service = newIpcService();
    CommonParameter parameter = new CommonParameter();
    parameter.outputDirectory = "/tmp/node-output";

    Path socketFilePath = resolveSocketFilePath(service, parameter, "1234");

    Assert.assertEquals(
        Paths.get("/tmp/node-output", ".ipc", "1234.sock"),
        socketFilePath);
  }

  @Test
  public void testResolveSocketFilePathRejectsLongOutputPath() throws Exception {
    IpcService service = newIpcService();
    CommonParameter parameter = new CommonParameter();
    parameter.outputDirectory = Paths.get("/tmp",
        "a-very-long-output-directory-name-that-makes-the-resulting-unix-domain-socket-path-"
            + "exceed-the-portable-limit").toString();

    try {
      resolveSocketFilePath(service, parameter, "1234");
      Assert.fail("Expected an overlong IPC socket path to be rejected");
    } catch (TronError e) {
      Path expectedSocketFile = Paths.get(parameter.outputDirectory, ".ipc", "1234.sock")
          .toAbsolutePath().normalize();
      Assert.assertTrue(e.getMessage().contains("exceeding the portable limit of 100 bytes"));
      Assert.assertTrue(e.getMessage().contains("node.admin.ipc.socketDirectory"));
      Assert.assertTrue(e.getMessage().contains(expectedSocketFile.toString()));
    }
  }

  @Test
  public void testSocketPathLengthCountsUtf8Bytes() {
    Path socketPath = Paths.get("/tmp/目录.sock");

    int encodedLength = IpcService.getSocketPathLength(socketPath, StandardCharsets.UTF_8);

    Assert.assertEquals(socketPath.toString().getBytes(StandardCharsets.UTF_8).length,
        encodedLength);
    Assert.assertTrue(encodedLength > socketPath.toString().length());
  }

  @Test
  public void testResolveSocketFilePathUsesConfiguredDirectory() throws Exception {
    IpcService service = newIpcService();
    CommonParameter parameter = new CommonParameter();
    parameter.outputDirectory = "node-output";
    parameter.ipcSocketDirectory = "/tmp/tron-ipc";

    Path socketFilePath = resolveSocketFilePath(service, parameter, "1234");

    Assert.assertEquals(Paths.get("/tmp/tron-ipc/.ipc/1234.sock"),
        socketFilePath);
  }

  @Test
  public void testResolveSocketFilePathRejectsRelativeConfiguredDirectory() throws Exception {
    IpcService service = newIpcService();
    CommonParameter parameter = new CommonParameter();
    parameter.ipcSocketDirectory = "relative-ipc";

    try {
      resolveSocketFilePath(service, parameter, "1234");
      Assert.fail("Expected a relative IPC socket directory to be rejected");
    } catch (TronError e) {
      Assert.assertEquals("node.admin.ipc.socketDirectory must be an absolute path",
          e.getMessage());
    }
  }

  @Test
  public void testResolveSocketFilePathRejectsLongConfiguredDirectory() throws Exception {
    IpcService service = newIpcService();
    CommonParameter parameter = new CommonParameter();
    parameter.outputDirectory = "/tmp";
    parameter.ipcSocketDirectory = Paths.get("/tmp",
        "a-very-long-explicit-ipc-directory-that-makes-the-resulting-unix-domain-socket-path-"
            + "exceed-the-portable-limit").toString();

    try {
      resolveSocketFilePath(service, parameter, "1234");
      Assert.fail("Expected an overlong configured IPC socket path to be rejected");
    } catch (TronError e) {
      Path expectedSocketFile = Paths.get(parameter.ipcSocketDirectory, ".ipc", "1234.sock")
          .toAbsolutePath().normalize();
      Assert.assertTrue(e.getMessage().contains("exceeding the portable limit of 100 bytes"));
      Assert.assertTrue(e.getMessage().contains("node.admin.ipc.socketDirectory"));
      Assert.assertTrue(e.getMessage().contains(expectedSocketFile.toString()));
    }
  }

  @Test
  public void testValidateSocketRootDirectoryRejectsMissingDirectory() throws Exception {
    IpcService service = newIpcService();
    Path outputDirectory = Files.createTempDirectory("ipc-missing-output-test-");
    Files.delete(outputDirectory);

    try {
      validateSocketRootDirectory(service, outputDirectory);
      Assert.fail("Expected a missing output directory to be rejected");
    } catch (TronError e) {
      Assert.assertEquals("IPC socket root directory does not exist or is not a directory",
          e.getMessage());
    }
  }

  @Test
  public void testRecreateSocketDirectoryRejectsRegularFile() throws Exception {
    IpcService service = newIpcService();
    Path outputDirectory = Files.createTempDirectory("ipc-regular-file-test-");
    Path socketDirectory = outputDirectory.resolve(".ipc");
    Files.createFile(socketDirectory);
    try {
      recreateSocketDirectory(service, socketDirectory);
      Assert.fail("Expected a regular file at the reserved directory path to be preserved");
    } catch (TronError e) {
      Assert.assertEquals("Refusing to replace a non-directory IPC path", e.getMessage());
      Assert.assertTrue(Files.isRegularFile(socketDirectory, LinkOption.NOFOLLOW_LINKS));
    } finally {
      Files.deleteIfExists(socketDirectory);
      Files.deleteIfExists(outputDirectory);
    }
  }

  @Test
  public void testRecreateSocketDirectoryRejectsSymbolicLink() throws Exception {
    assumePosixFileSystem();
    IpcService service = newIpcService();
    Path outputDirectory = Files.createTempDirectory("ipc-symbolic-link-test-");
    Path targetFile = outputDirectory.resolve("target");
    Path socketDirectory = outputDirectory.resolve(".ipc");
    Files.createFile(targetFile);
    Files.createSymbolicLink(socketDirectory, targetFile.getFileName());
    try {
      recreateSocketDirectory(service, socketDirectory);
      Assert.fail("Expected a symbolic link to be preserved");
    } catch (TronError e) {
      Assert.assertEquals("Refusing to replace a non-directory IPC path", e.getMessage());
      Assert.assertTrue(Files.isSymbolicLink(socketDirectory));
      Assert.assertTrue(Files.exists(targetFile));
    } finally {
      Files.deleteIfExists(socketDirectory);
      Files.deleteIfExists(targetFile);
      Files.deleteIfExists(outputDirectory);
    }
  }

  @Test
  public void testRecreateSocketDirectoryRemovesStaleFilesAndUsesOwnerOnlyPermissions()
      throws Exception {
    assumePosixFileSystem();
    IpcService service = newIpcService();
    Path outputDirectory = Files.createTempDirectory("ipc-stale-directory-test-");
    Path socketDirectory = Files.createDirectory(outputDirectory.resolve(".ipc"));
    Files.createFile(socketDirectory.resolve("1234.sock"));
    try {
      recreateSocketDirectory(service, socketDirectory);

      Assert.assertTrue(Files.isDirectory(socketDirectory));
      Assert.assertEquals(
          EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE),
          Files.getPosixFilePermissions(socketDirectory));
      Assert.assertFalse(Files.exists(socketDirectory.resolve("1234.sock")));
    } finally {
      Files.deleteIfExists(socketDirectory);
      Files.deleteIfExists(outputDirectory);
    }
  }

  @Test
  public void testValidateSocketRootDirectorySupportsPosixPermissions() throws Exception {
    assumePosixFileSystem();
    IpcService service = newIpcService();
    Path outputDirectory = Files.createTempDirectory("ipc-posix-output-test-");
    try {
      validateSocketRootDirectory(service, outputDirectory);
    } finally {
      Files.deleteIfExists(outputDirectory);
    }
  }

  @Test
  public void testHandleCommandReturnsSingleLineJsonResponse() throws Exception {
    IpcService service = newIpcService();

    String response = service.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"id\":7}");

    Assert.assertFalse(response, response.contains("\n"));
    Assert.assertFalse(response, response.contains("\r"));
    Assert.assertEquals(0, new ObjectMapper().readTree(response).get("result").size());
  }

  @Test
  public void testHandleCommandDispatchesPeerManagementMethod() throws Exception {
    PeerManagementService peerManagementService = Mockito.mock(PeerManagementService.class);
    Mockito.when(peerManagementService.addPeer("192.0.2.20:18888"))
        .thenReturn(new PeerOperationResult(true, true, 0, ""));
    IpcService service = new IpcService(new AdminJsonRpcImpl(peerManagementService));

    String response = service.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_addPeer\","
            + "\"params\":[\"192.0.2.20:18888\"],\"id\":8}");
    JsonNode result = new ObjectMapper().readTree(response).get("result");

    Assert.assertTrue(result.get("success").asBoolean());
    Assert.assertTrue(result.get("changed").asBoolean());
    Assert.assertEquals(0, result.get("disconnectedCount").asInt());
    Mockito.verify(peerManagementService).addPeer("192.0.2.20:18888");
  }

  @Test
  public void testPeerManagementErrorsUseAnnotatedJsonRpcCodes() throws Exception {
    PeerManagementService peerManagementService = Mockito.mock(PeerManagementService.class);
    Mockito.when(peerManagementService.addPeer("invalid"))
        .thenThrow(new JsonRpcInvalidParamsException("Invalid peer endpoint"));
    Mockito.when(peerManagementService.addPeer("192.0.2.20:18888"))
        .thenThrow(new JsonRpcInternalException("P2P service is not ready"));
    IpcService service = new IpcService(new AdminJsonRpcImpl(peerManagementService));

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
  public void testHandleCommandReturnsJsonRpcErrorOnDispatcherFailure() throws Exception {
    IpcService service = Mockito.spy(newIpcService());
    Mockito.doThrow(new IOException("sensitive-detail"))
        .when(service).dispatchRequest(Mockito.any(ByteArrayInputStream.class),
            Mockito.any(ByteArrayOutputStream.class));

    String response = service.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"id\":9}");
    JsonNode responseNode = new ObjectMapper().readTree(response);

    Assert.assertEquals("2.0", responseNode.get("jsonrpc").asText());
    Assert.assertEquals(-32603, responseNode.get("error").get("code").asInt());
    Assert.assertEquals("Internal error", responseNode.get("error").get("message").asText());
    Assert.assertEquals(9, responseNode.get("id").asInt());
    Assert.assertFalse(response, response.contains("sensitive-detail"));
    Assert.assertFalse(response, response.contains("\n"));
  }

  @Test
  public void testHandleCommandUsesAnnotatedErrorResolver() throws Exception {
    AdminJsonRpc adminJsonRpc = Mockito.mock(AdminJsonRpc.class);
    Mockito.when(adminJsonRpc.addPeer("invalid"))
        .thenThrow(new JsonRpcInvalidParamsException("Invalid admin parameters"));
    IpcService service = new IpcService(adminJsonRpc);

    String response = service.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_addPeer\","
            + "\"params\":[\"invalid\"],\"id\":10}");
    JsonNode responseNode = new ObjectMapper().readTree(response);

    Assert.assertEquals(-32602, responseNode.get("error").get("code").asInt());
    Assert.assertEquals("Invalid admin parameters",
        responseNode.get("error").get("message").asText());
    Assert.assertEquals(10, responseNode.get("id").asInt());
  }

  @Test
  public void testIpcMapperRejectsExcessiveNesting() throws Exception {
    StringBuilder request = new StringBuilder();
    for (int i = 0; i <= Constant.MAX_NESTING_DEPTH; i++) {
      request.append('[');
    }
    request.append('0');
    for (int i = 0; i <= Constant.MAX_NESTING_DEPTH; i++) {
      request.append(']');
    }

    ObjectMapper mapper = getStaticObjectMapper("OBJECT_MAPPER");
    try {
      mapper.readTree(request.toString());
      Assert.fail("Expected excessive IPC JSON nesting to be rejected");
    } catch (IOException e) {
      Assert.assertTrue(e.getMessage().contains("nesting depth"));
    }
  }

  @Test
  public void testReadRequestAcceptsMaximumSize() throws Exception {
    IpcService service = newIpcService();
    int maxRequestSize = getIntField(service, "maxRequestSize");
    byte[] request = new byte[maxRequestSize + 1];
    Arrays.fill(request, 0, maxRequestSize, (byte) '1');
    request[maxRequestSize] = '\n';

    Assert.assertEquals(maxRequestSize,
        readRequest(service, new ByteArrayInputStream(request)).length());
  }

  @Test(expected = IOException.class)
  public void testReadRequestRejectsOversizedInputWithoutNewline() throws Exception {
    IpcService service = newIpcService();
    int maxRequestSize = getIntField(service, "maxRequestSize");
    ByteArrayInputStream input = new ByteArrayInputStream(new byte[maxRequestSize + 1]);

    readRequest(service, input);
  }

  @Test(timeout = 10_000)
  public void testSocketFileUsesOwnerOnlyPermissions() throws Exception {
    assumePosixFileSystem();
    CommonParameter parameter = Args.getInstance();
    String originalOutputDirectory = parameter.outputDirectory;
    Path outputDirectory = Files.createTempDirectory(Paths.get("/tmp"), "ipc-permission-test-");
    IpcService service = newIpcService();
    boolean started = false;
    Path socketFile = null;
    try {
      parameter.outputDirectory = outputDirectory.toString();
      Assert.assertTrue(service.start().get());
      started = true;

      socketFile = resolveSocketFilePath(service, parameter, getPid(service));
      Assert.assertEquals(
          EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
          Files.getPosixFilePermissions(socketFile));
    } finally {
      cleanupIpcService(service, started, parameter, originalOutputDirectory, socketFile,
          outputDirectory);
    }
  }

  @Test(timeout = 10_000)
  public void testInnerStartCleansSocketWhenPermissionUpdateFails() throws Exception {
    assumePosixFileSystem();
    CommonParameter parameter = Args.getInstance();
    String originalOutputDirectory = parameter.outputDirectory;
    String originalSocketDirectory = parameter.ipcSocketDirectory;
    Path socketDirectory = Files.createTempDirectory(Paths.get("/tmp"), "ipc-perm-fail-");
    IpcService service = newIpcService();
    Path socketFile = null;
    try {
      parameter.outputDirectory = socketDirectory.toString();
      parameter.ipcSocketDirectory = "";
      socketFile = resolveSocketFilePath(service, parameter, getPid(service));
      Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE);
      try (MockedStatic<Files> files = Mockito.mockStatic(Files.class,
          Mockito.CALLS_REAL_METHODS)) {
        Path expectedSocketFile = socketFile;
        files.when(() -> Files.setPosixFilePermissions(expectedSocketFile, permissions))
            .thenThrow(new IOException("permission update failed"));

        try {
          service.innerStart();
          Assert.fail("Expected the permission update failure to be preserved");
        } catch (IOException e) {
          Assert.assertEquals("permission update failed", e.getMessage());
        }
      }

      Assert.assertFalse(Files.exists(socketFile));
    } finally {
      parameter.outputDirectory = originalOutputDirectory;
      parameter.ipcSocketDirectory = originalSocketDirectory;
      if (socketFile != null) {
        Files.deleteIfExists(socketFile);
        Files.deleteIfExists(socketFile.getParent());
      }
      Files.deleteIfExists(socketDirectory);
    }
  }

  @Test(timeout = 10_000)
  public void testInnerStartRollsBackWhenAcceptorSubmissionFails() throws Exception {
    assumePosixFileSystem();
    CommonParameter parameter = Args.getInstance();
    String originalOutputDirectory = parameter.outputDirectory;
    String originalSocketDirectory = parameter.ipcSocketDirectory;
    Path outputDirectory = Files.createTempDirectory(Paths.get("/tmp"),
        "ipc-submit-fail-");
    IpcService service = newIpcService();
    Path socketFile = null;
    try {
      parameter.outputDirectory = outputDirectory.toString();
      parameter.ipcSocketDirectory = "";
      socketFile = resolveSocketFilePath(service, parameter, getPid(service));
      getExecutorService(service, "acceptorExecutor").shutdownNow();

      try {
        service.innerStart();
        Assert.fail("Expected acceptor submission to fail");
      } catch (RejectedExecutionException e) {
        Assert.assertFalse(isRunning(service));
      }

      Assert.assertFalse(Files.exists(socketFile));
      Assert.assertFalse(Files.exists(socketFile.getParent()));
    } finally {
      parameter.outputDirectory = originalOutputDirectory;
      parameter.ipcSocketDirectory = originalSocketDirectory;
      service.innerStop();
      if (socketFile != null) {
        Files.deleteIfExists(socketFile);
        Files.deleteIfExists(socketFile.getParent());
      }
      Files.deleteIfExists(outputDirectory);
    }
  }

  @Test(timeout = 10_000)
  public void testHandlesMultipleClientsConcurrently() throws Exception {
    assumePosixFileSystem();
    CommonParameter parameter = Args.getInstance();
    String originalOutputDirectory = parameter.outputDirectory;
    Path outputDirectory = Files.createTempDirectory(Paths.get("/tmp"), "ipc-multi-client-test-");
    IpcService service = newIpcService();
    boolean started = false;
    Path socketFile = null;
    try {
      parameter.outputDirectory = outputDirectory.toString();
      service.innerStart();
      started = true;

      socketFile = resolveSocketFilePath(service, parameter, getPid(service));
      AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile.toFile());
      try (AFUNIXSocket firstClient = AFUNIXSocket.newInstance();
          AFUNIXSocket secondClient = AFUNIXSocket.newInstance()) {
        firstClient.connect(address);
        firstClient.setSoTimeout(5_000);
        BufferedWriter firstWriter = new BufferedWriter(
            new OutputStreamWriter(firstClient.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader firstReader = new BufferedReader(
            new InputStreamReader(firstClient.getInputStream(), StandardCharsets.UTF_8));
        assertSuccessfulResponse(sendRequest(firstWriter, firstReader, 1), 1);
        assertSuccessfulResponse(sendRequest(firstWriter, firstReader, 2), 2);

        secondClient.connect(address);
        secondClient.setSoTimeout(5_000);
        BufferedWriter secondWriter = new BufferedWriter(
            new OutputStreamWriter(secondClient.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader secondReader = new BufferedReader(
            new InputStreamReader(secondClient.getInputStream(), StandardCharsets.UTF_8));
        assertSuccessfulResponse(sendRequest(secondWriter, secondReader, 3), 3);
      }
    } finally {
      cleanupIpcService(service, started, parameter, originalOutputDirectory, socketFile,
          outputDirectory);
    }
  }

  @Test(timeout = 10_000)
  public void testRegisterClientUsesDefaultIdleTimeout() throws Exception {
    IpcService service = newIpcService();
    AFUNIXSocket client = Mockito.mock(AFUNIXSocket.class);
    Mockito.doThrow(new IOException("closed")).when(client).getInputStream();
    try {
      setField(service, "isRunning", true);
      registerClient(service, client);

      Mockito.verify(client).setSoTimeout(10 * 60 * 1000);
    } finally {
      service.innerStop();
    }
  }

  @Test(timeout = 10_000)
  public void testRejectsClientImmediatelyWhenAllHandlersAreBusy() throws Exception {
    IpcService service = newIpcService();
    CountDownLatch handlersStarted = new CountDownLatch(16);
    CountDownLatch releaseHandlers = new CountDownLatch(1);
    try {
      setField(service, "isRunning", true);
      for (int i = 0; i < 16; i++) {
        AFUNIXSocket client = Mockito.mock(AFUNIXSocket.class);
        Mockito.when(client.getInputStream()).thenAnswer(invocation -> {
          handlersStarted.countDown();
          try {
            releaseHandlers.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          throw new IOException("closed");
        });
        registerClient(service, client);
      }
      Assert.assertTrue("Expected all IPC handlers to start without queueing",
          handlersStarted.await(5, TimeUnit.SECONDS));

      AFUNIXSocket rejectedClient = Mockito.mock(AFUNIXSocket.class);
      registerClient(service, rejectedClient);

      Mockito.verify(rejectedClient).close();
      Assert.assertFalse(getActiveClientSockets(service).contains(rejectedClient));
    } finally {
      releaseHandlers.countDown();
      service.innerStop();
    }
  }

  @Test(timeout = 10_000)
  public void testStopClosesActiveClientSocket() throws Exception {
    assumePosixFileSystem();
    CommonParameter parameter = Args.getInstance();
    String originalOutputDirectory = parameter.outputDirectory;
    Path outputDirectory = Files.createTempDirectory(Paths.get("/tmp"), "ipc-test-");
    IpcService service = newIpcService();
    boolean started = false;
    Path socketFile = null;
    try {
      parameter.outputDirectory = outputDirectory.toString();
      service.innerStart();
      started = true;

      socketFile = resolveSocketFilePath(service, parameter, getPid(service));
      AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile.toFile());
      try (AFUNIXSocket client = AFUNIXSocket.newInstance()) {
        client.connect(address);
        client.setSoTimeout(5_000);
        try (BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
          writer.write(
              "{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\",\"id\":1}");
          writer.newLine();
          writer.flush();
          Assert.assertNotNull(reader.readLine());

          service.innerStop();
          started = false;
        }
      }
    } finally {
      cleanupIpcService(service, started, parameter, originalOutputDirectory, socketFile,
          outputDirectory);
    }
  }

  @Test(timeout = 5_000)
  public void testInnerStopContinuesCleanupAfterServerCloseFailure() throws Exception {
    IpcService service = newIpcService();
    AFUNIXServerSocket serverSocket = Mockito.mock(AFUNIXServerSocket.class);
    AFUNIXSocket clientSocket = Mockito.mock(AFUNIXSocket.class);
    Path socketRootDirectory = Files.createTempDirectory("ipc-stop-failure-test-");
    Path socketDirectory = Files.createDirectory(socketRootDirectory.resolve(".ipc"));
    Path socketFile = Files.createFile(socketDirectory.resolve("1234.sock"));
    Mockito.doThrow(new IOException("server close failed")).when(serverSocket).close();
    setField(service, "unixServerSocket", serverSocket);
    setField(service, "socketFilePath", socketFile);
    getActiveClientSockets(service).add(clientSocket);

    try {
      try {
        service.innerStop();
        Assert.fail("Expected the server socket close failure to be preserved");
      } catch (IOException e) {
        Assert.assertEquals("server close failed", e.getMessage());
      }

      Mockito.verify(clientSocket).shutdownInput();
      Mockito.verify(clientSocket).shutdownOutput();
      Mockito.verify(clientSocket).close();
      Assert.assertFalse(Files.exists(socketFile));
      Assert.assertFalse(Files.exists(socketDirectory));
    } finally {
      Files.deleteIfExists(socketFile);
      Files.deleteIfExists(socketDirectory);
      Files.deleteIfExists(socketRootDirectory);
    }
  }

  @Test(timeout = 5_000)
  public void testInnerStopSuppressesLaterCleanupFailure() throws Exception {
    IpcService service = newIpcService();
    AFUNIXServerSocket serverSocket = Mockito.mock(AFUNIXServerSocket.class);
    Path socketRootDirectory = Files.createTempDirectory("ipc-stop-suppressed-test-");
    Path socketDirectory = Files.createDirectory(
        socketRootDirectory.resolve(".ipc"));
    Path childFile = Files.createFile(socketDirectory.resolve("child"));
    Mockito.doThrow(new IOException("server close failed")).when(serverSocket).close();
    setField(service, "unixServerSocket", serverSocket);
    setField(service, "socketFilePath", socketDirectory.resolve("1234.sock"));

    try {
      service.innerStop();
      Assert.fail("Expected cleanup failures to be preserved");
    } catch (IOException e) {
      Assert.assertEquals("server close failed", e.getMessage());
      Assert.assertEquals(1, e.getSuppressed().length);
      Assert.assertTrue(e.getSuppressed()[0] instanceof IOException);
    } finally {
      Files.deleteIfExists(childFile);
      Files.deleteIfExists(socketDirectory);
      Files.deleteIfExists(socketRootDirectory);
    }
  }

  @Test
  public void testCleanupRestoresOutputDirectoryWhenStopFails() throws Exception {
    CommonParameter parameter = new CommonParameter();
    String originalOutputDirectory = parameter.outputDirectory;
    Path outputDirectory = Files.createTempDirectory("ipc-cleanup-test-");
    Path socketDirectory = Files.createDirectory(outputDirectory.resolve(".ipc"));
    Path socketFile = Files.createFile(socketDirectory.resolve("1234.sock"));
    parameter.outputDirectory = outputDirectory.toString();
    IpcService service = Mockito.mock(IpcService.class);
    Mockito.doThrow(new IOException("stop failed")).when(service).innerStop();

    try {
      cleanupIpcService(service, true, parameter, originalOutputDirectory, socketFile,
          outputDirectory);
      Assert.fail("Expected the stop failure to be preserved");
    } catch (IOException e) {
      Assert.assertEquals("stop failed", e.getMessage());
    }

    Assert.assertEquals(originalOutputDirectory, parameter.outputDirectory);
    Assert.assertFalse(Files.exists(socketFile));
    Assert.assertFalse(Files.exists(socketDirectory));
    Assert.assertFalse(Files.exists(outputDirectory));
  }

  private IpcService newIpcService() {
    PeerManagementService peerManagementService = Mockito.mock(PeerManagementService.class);
    Mockito.when(peerManagementService.listBlockedIps()).thenReturn(Collections.emptyList());
    return new IpcService(new AdminJsonRpcImpl(peerManagementService));
  }

  private void cleanupIpcService(IpcService service, boolean started, CommonParameter parameter,
      String originalOutputDirectory, Path socketFile, Path outputDirectory) throws Exception {
    parameter.outputDirectory = originalOutputDirectory;
    Exception failure = null;
    if (started) {
      try {
        service.innerStop();
      } catch (Exception e) {
        failure = e;
      }
    }
    try {
      if (socketFile != null) {
        Files.deleteIfExists(socketFile);
        Files.deleteIfExists(socketFile.getParent());
      }
    } catch (IOException e) {
      failure = mergeCleanupFailure(failure, e);
    }
    try {
      Files.deleteIfExists(outputDirectory);
    } catch (IOException e) {
      failure = mergeCleanupFailure(failure, e);
    }
    if (failure != null) {
      throw failure;
    }
  }

  private Exception mergeCleanupFailure(Exception failure, IOException cleanupFailure) {
    if (failure == null) {
      return cleanupFailure;
    }
    failure.addSuppressed(cleanupFailure);
    return failure;
  }

  private Path resolveSocketFilePath(IpcService service, CommonParameter parameter, String pid)
      throws Exception {
    return (Path) invokePrivate(service, "resolveSocketFilePath",
        new Class<?>[] {CommonParameter.class, String.class}, parameter, pid);
  }

  private void validateSocketRootDirectory(IpcService service, Path outputDirectory)
      throws Exception {
    invokePrivate(service, "validateSocketRootDirectory", new Class<?>[] {Path.class},
        outputDirectory);
  }

  private void recreateSocketDirectory(IpcService service, Path socketDirectory) throws Exception {
    invokePrivate(service, "recreateSocketDirectory", new Class<?>[] {Path.class},
        socketDirectory);
  }

  private String readRequest(IpcService service, InputStream input) throws Exception {
    return (String) invokePrivate(service, "readRequest",
        new Class<?>[] {InputStream.class}, input);
  }

  private int getIntField(IpcService service, String fieldName) throws Exception {
    Field field = IpcService.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.getInt(service);
  }

  private ObjectMapper getStaticObjectMapper(String fieldName) throws Exception {
    Field field = IpcService.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (ObjectMapper) field.get(null);
  }

  private boolean isRunning(IpcService service) throws Exception {
    Field field = IpcService.class.getDeclaredField("isRunning");
    field.setAccessible(true);
    return field.getBoolean(service);
  }

  private ExecutorService getExecutorService(IpcService service, String fieldName)
      throws Exception {
    Field field = IpcService.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (ExecutorService) field.get(service);
  }

  @SuppressWarnings("unchecked")
  private Set<AFUNIXSocket> getActiveClientSockets(IpcService service) throws Exception {
    Field field = IpcService.class.getDeclaredField("activeClientSockets");
    field.setAccessible(true);
    return (Set<AFUNIXSocket>) field.get(service);
  }

  private void setField(IpcService service, String fieldName, Object value) throws Exception {
    Field field = IpcService.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(service, value);
  }

  private String getPid(IpcService service) throws Exception {
    return (String) invokePrivate(service, "getPid", new Class<?>[0]);
  }

  private void registerClient(IpcService service, AFUNIXSocket client) throws Exception {
    invokePrivate(service, "registerClient", new Class<?>[] {AFUNIXSocket.class}, client);
  }

  private Object invokePrivate(IpcService service, String methodName, Class<?>[] parameterTypes,
      Object... arguments) throws Exception {
    Method method = IpcService.class.getDeclaredMethod(methodName, parameterTypes);
    method.setAccessible(true);
    try {
      return method.invoke(service, arguments);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new IllegalStateException(cause);
    }
  }

  private String sendRequest(BufferedWriter writer, BufferedReader reader, int requestId)
      throws IOException {
    writer.write("{\"jsonrpc\":\"2.0\",\"method\":\"admin_listBlockedIps\","
        + "\"id\":" + requestId + "}");
    writer.newLine();
    writer.flush();
    return reader.readLine();
  }

  private void assertSuccessfulResponse(String response, int requestId) {
    Assert.assertNotNull(response);
    Assert.assertTrue(response, response.contains("\"result\":[]"));
    Assert.assertTrue(response, response.contains("\"id\":" + requestId));
  }

  private void assumePosixFileSystem() {
    Assume.assumeTrue("IPC requires POSIX file permissions",
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
  }
}
