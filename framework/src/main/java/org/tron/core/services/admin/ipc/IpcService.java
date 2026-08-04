package org.tron.core.services.admin.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.springframework.stereotype.Component;
import org.tron.common.application.AbstractService;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.exit.ExitManager;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.config.args.Args;
import org.tron.core.services.admin.AdminJsonRpc;

@Component
@Slf4j(topic = "API")
public class IpcService extends AbstractService {

  private static final String ACCEPTOR_EXECUTOR_NAME = "admin-ipc-acceptor";
  private static final String CLIENT_EXECUTOR_NAME = "admin-ipc-client";
  private static final int CLIENT_HANDLER_THREADS = 4;
  private static final int MAX_PENDING_CLIENTS = 16;

  private final ExecutorService acceptorExecutor =
      ExecutorServiceManager.newSingleThreadExecutor(ACCEPTOR_EXECUTOR_NAME, true);
  private final ExecutorService clientExecutor =
      ExecutorServiceManager.newThreadPoolExecutor(
          CLIENT_HANDLER_THREADS, CLIENT_HANDLER_THREADS, 0L, TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(MAX_PENDING_CLIENTS), CLIENT_EXECUTOR_NAME, true);
  private final Set<AFUNIXSocket> activeClientSockets = ConcurrentHashMap.newKeySet();
  private volatile boolean isRunning = true;
  private AFUNIXServerSocket unixServerSocket;
  private Path socketFilePath;
  private final JsonRpcServer jsonRpcServer;

  public IpcService(AdminJsonRpc adminJsonRpc) {
    enable = isFullNode() && Args.getInstance().isIpcEnable();
    port = -1; //not used
    jsonRpcServer = new JsonRpcServer(new ObjectMapper(), adminJsonRpc, AdminJsonRpc.class);
  }

  @Override
  public void innerStart() throws Exception {
    socketFilePath = resolveSocketFilePath(Args.getInstance(), getPid());
    createParentDirectories(socketFilePath);
    Files.deleteIfExists(socketFilePath);

    File socketFile = socketFilePath.toFile();
    socketFile.deleteOnExit();

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
          if (isRunning) {
            logger.error("Handle IPC request error", throwable);
          }
          ExitManager.findTronError(throwable).ifPresent(e -> {
            throw e;
          });
        }
      }
    };
    ExecutorServiceManager.submit(acceptorExecutor, runnable);
  }

  private void registerClient(AFUNIXSocket client) {
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
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {

      String line;
      while ((line = reader.readLine()) != null) {
        String cmd = line.trim();
        logger.debug("Server received: {}", cmd);
        writer.write(handleCommand(cmd));
        writer.flush();
      }
    } catch (IOException e) {
      if (isRunning) {
        logger.error("Client disconnected {}", client);
      }
    }
  }

  private String handleCommand(String jsonRequest) {
    ByteArrayInputStream input =
        new ByteArrayInputStream(jsonRequest.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    String response;
    try {
      jsonRpcServer.handleRequest(input, output);
      response = output.toString(StandardCharsets.UTF_8.name());
    } catch (IOException e) {
      response = e.getMessage();
    }

    logger.debug("IPC response: {}", response);
    return response;
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

  static Path resolveSocketFilePath(CommonParameter parameter, String pid) {
    return Paths.get(parameter.getOutputDirectory(),
        "java-tron." + pid + ".sock");
  }

  static void createParentDirectories(Path socketFilePath) throws IOException {
    Path parent = socketFilePath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
  }

  static void setOwnerOnlyPermissions(Path socketFilePath) throws IOException {
    Files.setPosixFilePermissions(socketFilePath,
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
  }

  public static String getPid() {
    String name = ManagementFactory.getRuntimeMXBean().getName();
    return name.split("@")[0];
  }
}
