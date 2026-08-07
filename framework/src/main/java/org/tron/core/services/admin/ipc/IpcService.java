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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
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

@Component
@Slf4j(topic = "API")
public class IpcService extends AbstractService {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String ACCEPTOR_EXECUTOR_NAME = "admin-ipc-acceptor";
  private static final String CLIENT_EXECUTOR_NAME = "admin-ipc-client";
  private static final int MAX_REQUEST_SIZE = 4 * 1024 * 1024;
  private static final int CLIENT_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000;

  private final ExecutorService acceptorExecutor =
      ExecutorServiceManager.newSingleThreadExecutor(ACCEPTOR_EXECUTOR_NAME, true);
  private final ExecutorService clientExecutor =
      ExecutorServiceManager.newThreadPoolExecutor(4, 16, 0L, TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(16), CLIENT_EXECUTOR_NAME, true);

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
    Path outputDirectory = socketFilePath.getParent();
    validateOutputDirectory(outputDirectory);
    deleteStaleSocketFile(socketFilePath);

    File socketFile = socketFilePath.toFile();
    AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile);
    unixServerSocket = AFUNIXServerSocket.bindOn(address);
    try {
      setOwnerOnlyPermissions(socketFilePath);
    } catch (IOException | RuntimeException e) {
      try {
        unixServerSocket.close();
      } catch (IOException closeException) {
        e.addSuppressed(closeException);
      }
      try {
        Files.deleteIfExists(socketFilePath);
      } catch (IOException deleteException) {
        e.addSuppressed(deleteException);
      }
      throw e;
    }
    unixServerSocket.setShutdownOnClose(true);

    logger.info("IpcService started, listening on {}", socketFile.getAbsolutePath());
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
              TimeUnit.MILLISECONDS.sleep(1_000);
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
      while ((line = readRequest(input, MAX_REQUEST_SIZE)) != null) {
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

  private String readRequest(InputStream input, int maxRequestSize) throws IOException {
    ByteArrayOutputStream request = new ByteArrayOutputStream();
    int value;
    while ((value = input.read()) != -1) {
      if (value == '\n') {
        break;
      }
      if (request.size() >= maxRequestSize) {
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
    if (unixServerSocket != null) {
      unixServerSocket.close();
    }
    for (AFUNIXSocket client : activeClientSockets) {
      closeClientSocket(client);
    }
    ExecutorServiceManager.shutdownAndAwaitTermination(acceptorExecutor, ACCEPTOR_EXECUTOR_NAME);
    ExecutorServiceManager.shutdownAndAwaitTermination(clientExecutor, CLIENT_EXECUTOR_NAME);
    if (socketFilePath != null) {
      Files.deleteIfExists(socketFilePath);
    }
    logger.info("IpcService stopped");
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

  private void closeAndRemoveClient(AFUNIXSocket client) {
    closeClientSocket(client);
    activeClientSockets.remove(client);
  }

  private Path resolveSocketFilePath(CommonParameter parameter, String pid) {
    return Paths.get(parameter.getOutputDirectory(),
        "java-tron." + pid + ".sock");
  }

  private void validateOutputDirectory(Path outputDirectory) throws IOException {
    if (outputDirectory == null || !Files.isDirectory(outputDirectory)) {
      throw new TronError("IPC output directory does not exist or is not a directory",
          ErrCode.API_SERVER_INIT);
    }
    if (!Files.getFileStore(outputDirectory)
        .supportsFileAttributeView(PosixFileAttributeView.class)) {
      throw new TronError("IPC requires a POSIX-compatible output directory",
          ErrCode.API_SERVER_INIT);
    }
  }

  private void deleteStaleSocketFile(Path socketFilePath) throws IOException {
    if (!Files.exists(socketFilePath, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    BasicFileAttributes attributes = Files.readAttributes(socketFilePath,
        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || !attributes.isOther()) {
      throw new TronError("Refusing to replace a non-socket IPC endpoint",
          ErrCode.API_SERVER_INIT);
    }
    Files.delete(socketFilePath);
  }

  private void setOwnerOnlyPermissions(Path socketFilePath) throws IOException {
    Files.setPosixFilePermissions(socketFilePath,
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
  }

  private String getPid() {
    String name = ManagementFactory.getRuntimeMXBean().getName();
    return name.split("@")[0];
  }

  private static final class RequestTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;
  }
}
