package org.tron.core.services.admin.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.application.AbstractService;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.exit.ExitManager;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.config.args.Args;
import org.tron.core.exception.TronError;
import org.tron.core.exception.TronError.ErrCode;
import org.tron.core.services.admin.AdminJsonRpc;
import org.tron.core.services.jsonrpc.JsonRpcErrorResolver;
import org.tron.core.services.jsonrpc.JsonRpcMapper;

@Component
@Slf4j(topic = "API")
public class IpcService extends AbstractService {

  private static final ObjectMapper OBJECT_MAPPER = JsonRpcMapper.create();
  private static final String ACCEPTOR_EXECUTOR_NAME = "admin-ipc-acceptor";
  private static final String CLIENT_EXECUTOR_NAME = "admin-ipc-client";
  private static final int MAX_REQUEST_SIZE = 4 * 1024 * 1024; //same as HttpService.maxRequestSize
  private static final int CLIENT_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000;
  private static final String IPC_DIRECTORY_NAME = ".ipc";

  // macOS/Linux sun_path buffers are 104/108 bytes. Reserve one byte for the terminating null
  // and three bytes of portability margin below the smaller macOS limit.
  private static final int MAX_SOCKET_PATH_BYTES = 100;
  private final ExecutorService acceptorExecutor =
      ExecutorServiceManager.newSingleThreadExecutor(ACCEPTOR_EXECUTOR_NAME, true);
  private final ExecutorService clientExecutor =
      ExecutorServiceManager.newThreadPoolExecutor(4, 16, 60L, TimeUnit.SECONDS,
          new SynchronousQueue<>(), CLIENT_EXECUTOR_NAME, true);

  private volatile boolean isRunning = true;
  private AFUNIXServerSocket unixServerSocket;
  private Path socketFilePath;
  private final JsonRpcServer jsonRpcServer;

  private final Set<AFUNIXSocket> activeClientSockets = ConcurrentHashMap.newKeySet();

  @Autowired
  public IpcService(AdminJsonRpc adminJsonRpc) {
    enable = isFullNode() && Args.getInstance().isIpcEnable();
    jsonRpcServer = new JsonRpcServer(OBJECT_MAPPER, adminJsonRpc, AdminJsonRpc.class);
    jsonRpcServer.setErrorResolver(JsonRpcErrorResolver.INSTANCE);
    jsonRpcServer.setShouldLogInvocationErrors(false);
  }

  @Override
  public CompletableFuture<Boolean> start() {
    CompletableFuture<Boolean> resultFuture = new CompletableFuture<>();
    try {
      innerStart();
      resultFuture.complete(true);
    } catch (Exception e) {
      resultFuture.completeExceptionally(e);
    }
    return resultFuture;
  }

  @Override
  public void innerStart() throws Exception {
    socketFilePath = resolveSocketFilePath(Args.getInstance(), getPid());
    Path socketDirectory = socketFilePath.getParent();
    validateSocketRootDirectory(socketDirectory.getParent());
    try {
      recreateSocketDirectory(socketDirectory);
      File socketFile = socketFilePath.toFile();
      AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile);
      unixServerSocket = AFUNIXServerSocket.bindOn(address);
      setOwnerOnlyPermissions(socketFilePath);
      unixServerSocket.setShutdownOnClose(true);

      logger.info("IpcService started, listening on {}", socketFile.getAbsolutePath());
    } catch (IOException | RuntimeException e) {
      throw cleanupFailedStart(e);
    }
    Runnable runnable = () -> {
      while (isRunning) {
        try {
          registerClient(unixServerSocket.accept());
        } catch (Throwable throwable) {
          ExitManager.findTronError(throwable).ifPresent(e -> {
            throw e;
          });
          if (isRunning) {
            logger.error("Handle IPC request error", throwable);
            try {
              TimeUnit.MILLISECONDS.sleep(5_000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        }
      }
    };
    ExecutorServiceManager.submit(acceptorExecutor, runnable);
  }

  private void registerClient(AFUNIXSocket client) {
    try {
      client.setSoTimeout(CLIENT_IDLE_TIMEOUT_MILLIS);
    } catch (IOException e) {
      closeClientSocket(client);
      if (isRunning) {
        logger.warn("Failed to configure IPC client idle timeout");
      }
      return;
    }
    activeClientSockets.add(client);
    if (!isRunning) {
      closeAndRemoveClient(client);
      return;
    }
    try {
      ExecutorServiceManager.submit(clientExecutor, () -> {
        try {
          handleClient(client);
        } finally {
          closeAndRemoveClient(client);
        }
      });
    } catch (RejectedExecutionException e) {
      closeAndRemoveClient(client);
      if (isRunning) {
        logger.warn("Too many IPC clients; rejecting connection");
      }
    } catch (RuntimeException e) {
      closeAndRemoveClient(client);
      throw e;
    }
  }

  private void handleClient(AFUNIXSocket client) {
    try (BufferedInputStream input = new BufferedInputStream(client.getInputStream());
        BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {

      String line;
      while ((line = readRequest(input)) != null) {
        String cmd = line.trim();
        logger.debug("Received IPC request");
        String response = handleCommand(cmd);
        if (!response.isEmpty()) {
          writer.write(response);
          writer.newLine();
          writer.flush();
          logger.debug("Sent IPC response");
        }
      }
    } catch (SocketTimeoutException e) {
      logger.debug("Closing IPC client after {} ms without input", CLIENT_IDLE_TIMEOUT_MILLIS);
    } catch (RequestTooLargeException e) {
      logger.warn("IPC request exceeds maximum size of {} bytes", MAX_REQUEST_SIZE);
    } catch (IOException e) {
      if (isRunning) {
        logger.error("Client disconnected {}", client);
      }
    }
  }

  private String readRequest(InputStream input) throws IOException {
    ByteArrayOutputStream request = new ByteArrayOutputStream();
    int value;
    while ((value = input.read()) != -1) {
      if (value == '\n') {
        break;
      }
      if (request.size() >= MAX_REQUEST_SIZE) {
        throw new RequestTooLargeException();
      }
      request.write(value);
    }
    if (value == -1 && request.size() == 0) {
      return null;
    }
    byte[] bytes = request.toByteArray();
    int length = bytes.length;
    if (length > 0 && bytes[length - 1] == '\r') {
      length--;
    }
    return new String(bytes, 0, length, StandardCharsets.UTF_8);
  }

  String handleCommand(String jsonRequest) {
    ByteArrayInputStream input =
        new ByteArrayInputStream(jsonRequest.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    try {
      dispatchRequest(input, output);
      if (output.size() == 0) {
        return "";
      }
      JsonNode response = OBJECT_MAPPER.readTree(output.toByteArray());
      return response == null ? "" : OBJECT_MAPPER.writeValueAsString(response);
    } catch (Exception e) {
      logger.debug("Failed to dispatch IPC request");
      return buildInternalErrorResponse(jsonRequest);
    }
  }

  void dispatchRequest(ByteArrayInputStream input, ByteArrayOutputStream output)
      throws IOException {
    jsonRpcServer.handleRequest(input, output);
  }

  private String buildInternalErrorResponse(String jsonRequest) {
    JsonNode requestId = NullNode.getInstance();
    try {
      JsonNode request = OBJECT_MAPPER.readTree(jsonRequest);
      if (request != null && request.has("id")) {
        requestId = request.get("id");
      }
    } catch (IOException e) {
      logger.debug("Unable to read request id from invalid IPC request");
    }

    ObjectNode error = OBJECT_MAPPER.createObjectNode();
    error.put("code", -32603);
    error.put("message", "Internal error");
    ObjectNode response = OBJECT_MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("error", error);
    response.set("id", requestId);
    try {
      return OBJECT_MAPPER.writeValueAsString(response);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to serialize IPC error response", e);
    }
  }

  @Override
  public void innerStop() throws Exception {
    logger.info("Begin to stop IpcService ...");
    isRunning = false;

    Exception failure = null;
    failure = runCleanup(failure, this::closeServerSocket);
    failure = runCleanup(failure, this::shutdownActiveClients);
    failure = runCleanup(failure, this::shutdownExecutors);
    activeClientSockets.clear();
    failure = runCleanup(failure, this::deleteSocketFile);
    failure = runCleanup(failure, this::deleteSocketDirectory);

    if (failure != null) {
      throw failure;
    }
    logger.info("IpcService stopped");
  }

  private void closeServerSocket() throws IOException {
    if (unixServerSocket != null) {
      unixServerSocket.close();
    }
  }

  private void shutdownActiveClients() {
    for (AFUNIXSocket client : activeClientSockets) {
      shutdownClientSocket(client);
    }
  }

  private void shutdownExecutors() {
    // The accept and client workers can be blocked in native socket reads. Closing a junixsocket
    // from another thread does not always wake those reads promptly, while the shared shutdown
    // helper waits 60 seconds before interrupting them. Interrupt first so IPC shutdown remains
    // bounded instead of intermittently stalling until that fallback timeout.
    acceptorExecutor.shutdownNow();
    clientExecutor.shutdownNow();
    ExecutorServiceManager.shutdownAndAwaitTermination(acceptorExecutor, ACCEPTOR_EXECUTOR_NAME);
    ExecutorServiceManager.shutdownAndAwaitTermination(clientExecutor, CLIENT_EXECUTOR_NAME);
  }

  private void deleteSocketFile() throws IOException {
    if (socketFilePath != null) {
      Files.deleteIfExists(socketFilePath);
    }
  }

  private void deleteSocketDirectory() throws IOException {
    if (socketFilePath != null) {
      Files.deleteIfExists(socketFilePath.getParent());
    }
  }

  private Exception cleanupFailedStart(Exception failure) {
    if (unixServerSocket != null) {
      failure = runCleanup(failure, this::closeServerSocket);
      failure = runCleanup(failure, this::deleteSocketFile);
    }
    return runCleanup(failure, this::deleteSocketDirectory);
  }

  private Exception runCleanup(Exception failure, CleanupAction action) {
    try {
      action.run();
    } catch (Exception cleanupFailure) {
      if (failure == null) {
        return cleanupFailure;
      }
      failure.addSuppressed(cleanupFailure);
    }
    return failure;
  }

  private void closeClientSocket(AFUNIXSocket client) {
    if (client == null) {
      return;
    }
    try {
      client.close();
    } catch (IOException e) {
      logger.warn("Failed to close IPC client socket", e);
    }
  }

  private void shutdownClientSocket(AFUNIXSocket client) {
    if (client == null) {
      return;
    }
    try {
      client.shutdownInput();
    } catch (IOException e) {
      logger.debug("Failed to shut down IPC client input");
    }
    try {
      client.shutdownOutput();
    } catch (IOException e) {
      logger.debug("Failed to shut down IPC client output");
    }
    closeClientSocket(client);
  }

  private void closeAndRemoveClient(AFUNIXSocket client) {
    closeClientSocket(client);
    activeClientSockets.remove(client);
  }

  private Path resolveSocketFilePath(CommonParameter parameter, String pid) {
    String configuredDirectory = parameter.getIpcSocketDirectory();
    Path socketRootDirectory;
    if (configuredDirectory == null || configuredDirectory.trim().isEmpty()) {
      socketRootDirectory = Paths.get(parameter.getOutputDirectory());
    } else {
      socketRootDirectory = Paths.get(configuredDirectory);
      if (!socketRootDirectory.isAbsolute()) {
        throw new TronError("node.admin.ipc.socketDirectory must be an absolute path",
            ErrCode.API_SERVER_INIT);
      }
    }

    Path socketFile = socketRootDirectory.resolve(IPC_DIRECTORY_NAME)
        .resolve(pid + ".sock").toAbsolutePath().normalize();
    int socketPathLength = getSocketPathLength(socketFile);
    if (socketPathLength > MAX_SOCKET_PATH_BYTES) {
      throw new TronError("IPC socket path " + socketFile + " is " + socketPathLength
          + " bytes, exceeding the portable limit of " + MAX_SOCKET_PATH_BYTES
          + " bytes. Configure node.admin.ipc.socketDirectory to a shorter absolute directory",
          ErrCode.API_SERVER_INIT);
    }
    return socketFile;
  }

  private int getSocketPathLength(Path socketFile) {
    return socketFile.toString().getBytes(AFUNIXSocketAddress.addressCharset()).length;
  }

  private void validateSocketRootDirectory(Path socketRootDirectory) throws IOException {
    if (socketRootDirectory == null || !Files.isDirectory(socketRootDirectory)) {
      throw new TronError("IPC socket root directory does not exist or is not a directory",
          ErrCode.API_SERVER_INIT);
    }
    if (!Files.getFileStore(socketRootDirectory)
        .supportsFileAttributeView(PosixFileAttributeView.class)) {
      throw new TronError("IPC requires a POSIX-compatible socket root directory",
          ErrCode.API_SERVER_INIT);
    }
  }

  private void recreateSocketDirectory(Path socketDirectory) throws IOException {
    if (Files.exists(socketDirectory, LinkOption.NOFOLLOW_LINKS)) {
      BasicFileAttributes attributes = Files.readAttributes(socketDirectory,
          BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
        throw new TronError("Refusing to replace a non-directory IPC path",
            ErrCode.API_SERVER_INIT);
      }
      deleteDirectoryWithDirectEntries(socketDirectory);
    }
    Files.createDirectory(socketDirectory, PosixFilePermissions.asFileAttribute(
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE)));
  }

  private void deleteDirectoryWithDirectEntries(Path directory) throws IOException {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        Files.delete(entry);
      }
    }
    Files.delete(directory);
  }

  private void setOwnerOnlyPermissions(Path socketFilePath) throws IOException {
    Files.setPosixFilePermissions(socketFilePath,
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
  }

  private String getPid() {
    String name = ManagementFactory.getRuntimeMXBean().getName();
    return name.split("@")[0];
  }

  @FunctionalInterface
  private interface CleanupAction {

    void run() throws Exception;
  }

  private static final class RequestTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;
  }
}
