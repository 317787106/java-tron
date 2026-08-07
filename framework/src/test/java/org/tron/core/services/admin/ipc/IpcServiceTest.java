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
import java.util.EnumSet;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.mockito.Mockito;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.config.args.Args;
import org.tron.core.exception.TronError;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.services.admin.AdminJsonRpc;
import org.tron.core.services.admin.AdminJsonRpcImpl;
import org.tron.core.services.admin.CommonParameterExporter;

public class IpcServiceTest {

  @Test
  public void testResolveSocketFilePathUsesOutputDirectory() throws Exception {
    IpcService service = newIpcService();
    CommonParameter parameter = new CommonParameter();
    parameter.outputDirectory = "node-output";

    Path socketFilePath = resolveSocketFilePath(service, parameter, "1234");

    Assert.assertEquals(
        Paths.get("node-output", "java-tron.1234.sock").toAbsolutePath().normalize(),
        socketFilePath);
  }

  @Test
  public void testResolveSocketFilePathFallsBackToTmpForLongOutputPath() throws Exception {
    IpcService service = newIpcService();
    CommonParameter parameter = new CommonParameter();
    parameter.outputDirectory = Paths.get("/tmp",
        "a-very-long-output-directory-name-that-makes-the-resulting-unix-domain-socket-path-"
            + "exceed-the-portable-limit").toString();

    Path socketFilePath = resolveSocketFilePath(service, parameter, "1234");

    Assert.assertEquals(Paths.get("/tmp", "java-tron.1234.sock"), socketFilePath);
  }

  @Test
  public void testResolveSocketFilePathCountsEncodedBytes() throws Exception {
    IpcService service = newIpcService();
    CommonParameter parameter = new CommonParameter();
    StringBuilder outputDirectory = new StringBuilder("/tmp/");
    for (int i = 0; i < 40; i++) {
      outputDirectory.append("目");
    }
    parameter.outputDirectory = outputDirectory.toString();

    Path socketFilePath = resolveSocketFilePath(service, parameter, "1234");

    Assert.assertEquals(Paths.get("/tmp", "java-tron.1234.sock"), socketFilePath);
  }

  @Test
  public void testValidateOutputDirectoryRejectsMissingDirectory() throws Exception {
    IpcService service = newIpcService();
    Path outputDirectory = Files.createTempDirectory("ipc-missing-output-test-");
    Files.delete(outputDirectory);

    try {
      validateOutputDirectory(service, outputDirectory);
      Assert.fail("Expected a missing output directory to be rejected");
    } catch (TronError e) {
      Assert.assertEquals("IPC output directory does not exist or is not a directory",
          e.getMessage());
    }
  }

  @Test
  public void testDeleteStaleSocketFileRejectsRegularFile() throws Exception {
    IpcService service = newIpcService();
    Path outputDirectory = Files.createTempDirectory("ipc-regular-file-test-");
    Path socketFile = outputDirectory.resolve("java-tron.1234.sock");
    Files.createFile(socketFile);
    try {
      deleteStaleSocketFile(service, socketFile);
      Assert.fail("Expected a regular file to be preserved");
    } catch (TronError e) {
      Assert.assertEquals("Refusing to replace a non-socket IPC endpoint", e.getMessage());
      Assert.assertTrue(Files.isRegularFile(socketFile, LinkOption.NOFOLLOW_LINKS));
    } finally {
      Files.deleteIfExists(socketFile);
      Files.deleteIfExists(outputDirectory);
    }
  }

  @Test
  public void testDeleteStaleSocketFileRejectsSymbolicLink() throws Exception {
    assumePosixFileSystem();
    IpcService service = newIpcService();
    Path outputDirectory = Files.createTempDirectory("ipc-symbolic-link-test-");
    Path targetFile = outputDirectory.resolve("target");
    Path socketFile = outputDirectory.resolve("java-tron.1234.sock");
    Files.createFile(targetFile);
    Files.createSymbolicLink(socketFile, targetFile.getFileName());
    try {
      deleteStaleSocketFile(service, socketFile);
      Assert.fail("Expected a symbolic link to be preserved");
    } catch (TronError e) {
      Assert.assertEquals("Refusing to replace a non-socket IPC endpoint", e.getMessage());
      Assert.assertTrue(Files.isSymbolicLink(socketFile));
      Assert.assertTrue(Files.exists(targetFile));
    } finally {
      Files.deleteIfExists(socketFile);
      Files.deleteIfExists(targetFile);
      Files.deleteIfExists(outputDirectory);
    }
  }

  @Test
  public void testValidateOutputDirectorySupportsPosixPermissions() throws Exception {
    assumePosixFileSystem();
    IpcService service = newIpcService();
    Path outputDirectory = Files.createTempDirectory("ipc-posix-output-test-");
    try {
      validateOutputDirectory(service, outputDirectory);
    } finally {
      Files.deleteIfExists(outputDirectory);
    }
  }

  @Test
  public void testHandleCommandReturnsSingleLineJsonResponse() throws Exception {
    IpcService service = new IpcService(
        new AdminJsonRpcImpl(new CommonParameterExporter()));

    String response = service.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_example\","
            + "\"params\":[\"a\",\"b\"],\"id\":7}");

    Assert.assertFalse(response, response.contains("\n"));
    Assert.assertFalse(response, response.contains("\r"));
    Assert.assertEquals("a:b", new ObjectMapper().readTree(response).get("result").asText());
  }

  @Test
  public void testHandleCommandReturnsJsonRpcErrorOnDispatcherFailure() throws Exception {
    IpcService service = Mockito.spy(new IpcService(
        new AdminJsonRpcImpl(new CommonParameterExporter())));
    Mockito.doThrow(new IOException("sensitive-detail"))
        .when(service).dispatchRequest(Mockito.any(ByteArrayInputStream.class),
            Mockito.any(ByteArrayOutputStream.class));

    String response = service.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_example\","
            + "\"params\":[\"a\",\"b\"],\"id\":9}");
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
    Mockito.when(adminJsonRpc.adminExample("a", "b"))
        .thenThrow(new JsonRpcInvalidParamsException("Invalid admin parameters"));
    IpcService service = new IpcService(adminJsonRpc);

    String response = service.handleCommand(
        "{\"jsonrpc\":\"2.0\",\"method\":\"admin_example\","
            + "\"params\":[\"a\",\"b\"],\"id\":10}");
    JsonNode responseNode = new ObjectMapper().readTree(response);

    Assert.assertEquals(-32602, responseNode.get("error").get("code").asInt());
    Assert.assertEquals("Invalid admin parameters",
        responseNode.get("error").get("message").asText());
    Assert.assertEquals(10, responseNode.get("id").asInt());
  }

  @Test
  public void testReadRequestAcceptsMaximumSize() throws Exception {
    IpcService service = newIpcService();
    int maxRequestSize = getStaticIntField("MAX_REQUEST_SIZE");
    byte[] request = new byte[maxRequestSize + 1];
    Arrays.fill(request, 0, maxRequestSize, (byte) '1');
    request[maxRequestSize] = '\n';

    Assert.assertEquals(maxRequestSize,
        readRequest(service, new ByteArrayInputStream(request)).length());
  }

  @Test(expected = IOException.class)
  public void testReadRequestRejectsOversizedInputWithoutNewline() throws Exception {
    IpcService service = newIpcService();
    int maxRequestSize = getStaticIntField("MAX_REQUEST_SIZE");
    ByteArrayInputStream input = new ByteArrayInputStream(new byte[maxRequestSize + 1]);

    readRequest(service, input);
  }

  @Test(timeout = 10_000)
  public void testSocketFileUsesOwnerOnlyPermissions() throws Exception {
    assumePosixFileSystem();
    CommonParameter parameter = Args.getInstance();
    String originalOutputDirectory = parameter.outputDirectory;
    Path outputDirectory = Files.createTempDirectory(Paths.get("/tmp"), "ipc-permission-test-");
    IpcService service = new IpcService(
        new AdminJsonRpcImpl(new CommonParameterExporter()));
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
  public void testHandlesMultipleClientsConcurrently() throws Exception {
    assumePosixFileSystem();
    CommonParameter parameter = Args.getInstance();
    String originalOutputDirectory = parameter.outputDirectory;
    Path outputDirectory = Files.createTempDirectory(Paths.get("/tmp"), "ipc-multi-client-test-");
    IpcService service = new IpcService(
        new AdminJsonRpcImpl(new CommonParameterExporter()));
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
      registerClient(service, client);

      Mockito.verify(client).setSoTimeout(10 * 60 * 1000);
    } finally {
      service.innerStop();
    }
  }

  @Test(timeout = 10_000)
  public void testStopClosesActiveClientSocket() throws Exception {
    assumePosixFileSystem();
    CommonParameter parameter = Args.getInstance();
    String originalOutputDirectory = parameter.outputDirectory;
    Path outputDirectory = Files.createTempDirectory("ipc-test-");
    IpcService service = new IpcService(
        new AdminJsonRpcImpl(new CommonParameterExporter()));
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
          writer.write("{\"jsonrpc\":\"2.0\",\"method\":\"admin_example\","
              + "\"params\":[\"a\",\"b\"],\"id\":1}");
          writer.newLine();
          writer.flush();
          Assert.assertNotNull(reader.readLine());

          long startNanos = System.nanoTime();
          service.innerStop();
          started = false;
          long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
          Assert.assertTrue("IPC service shutdown took " + elapsedMillis + " ms",
              elapsedMillis < 5_000);
        }
      }
    } finally {
      cleanupIpcService(service, started, parameter, originalOutputDirectory, socketFile,
          outputDirectory);
    }
  }

  @Test
  public void testCleanupRestoresOutputDirectoryWhenStopFails() throws Exception {
    CommonParameter parameter = new CommonParameter();
    String originalOutputDirectory = parameter.outputDirectory;
    Path outputDirectory = Files.createTempDirectory("ipc-cleanup-test-");
    Path socketFile = Files.createFile(outputDirectory.resolve("java-tron.1234.sock"));
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
    Assert.assertFalse(Files.exists(outputDirectory));
  }

  private IpcService newIpcService() {
    return new IpcService(new AdminJsonRpcImpl(new CommonParameterExporter()));
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

  private void validateOutputDirectory(IpcService service, Path outputDirectory) throws Exception {
    invokePrivate(service, "validateOutputDirectory", new Class<?>[] {Path.class},
        outputDirectory);
  }

  private void deleteStaleSocketFile(IpcService service, Path socketFile) throws Exception {
    invokePrivate(service, "deleteStaleSocketFile", new Class<?>[] {Path.class}, socketFile);
  }

  private String readRequest(IpcService service, InputStream input) throws Exception {
    return (String) invokePrivate(service, "readRequest",
        new Class<?>[] {InputStream.class}, input);
  }

  private int getStaticIntField(String fieldName) throws Exception {
    Field field = IpcService.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.getInt(null);
  }

  private void registerClient(IpcService service, AFUNIXSocket client) throws Exception {
    invokePrivate(service, "registerClient", new Class<?>[] {AFUNIXSocket.class}, client);
  }

  private String getPid(IpcService service) throws Exception {
    return (String) invokePrivate(service, "getPid", new Class<?>[0]);
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
    writer.write("{\"jsonrpc\":\"2.0\",\"method\":\"admin_example\","
        + "\"params\":[\"a\",\"b\"],\"id\":" + requestId + "}");
    writer.newLine();
    writer.flush();
    return reader.readLine();
  }

  private void assertSuccessfulResponse(String response, int requestId) {
    Assert.assertNotNull(response);
    Assert.assertTrue(response, response.contains("\"result\":\"a:b\""));
    Assert.assertTrue(response, response.contains("\"id\":" + requestId));
  }

  private void assumePosixFileSystem() {
    Assume.assumeTrue("IPC requires POSIX file permissions",
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
  }
}
