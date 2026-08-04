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
import java.util.concurrent.ExecutorService;
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

  private final String esName = "admin-ipc-server";
  private final ExecutorService pool = ExecutorServiceManager.newSingleThreadExecutor(esName, true);
  private volatile boolean isRunning = true;
  private AFUNIXServerSocket unixServerSocket;
  private volatile AFUNIXSocket activeClientSocket;
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
    unixServerSocket.setShutdownOnClose(true);

    logger.info("IpcService started, listening on {}", socketFile.getAbsolutePath());
    Runnable runnable = () -> {
      while (isRunning) {
        AFUNIXSocket client = null;
        try {
          client = unixServerSocket.accept();
          activeClientSocket = client;
          if (isRunning) {
            handleClient(client);
          }
        } catch (Throwable throwable) {
          if (isRunning) {
            logger.error("Handle IPC request error", throwable);
          }
          ExitManager.findTronError(throwable).ifPresent(e -> {
            throw e;
          });
        } finally {
          closeClientSocket(client);
          activeClientSocket = null;
        }
      }
    };
    ExecutorServiceManager.submit(pool, runnable);
  }

  private void handleClient(AFUNIXSocket client) {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {

      String line;
      while ((line = reader.readLine()) != null) {
        String cmd = line.trim();
        logger.info("Server received: {}", cmd);
        writer.write(handleCommand(cmd));
        writer.newLine();
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

    logger.info("IPC response: {}", response);
    return response;
  }

  @Override
  public void innerStop() throws Exception {
    logger.info("Begin to stop IpcService ...");
    isRunning = false;
    closeClientSocket(activeClientSocket);
    if (unixServerSocket != null) {
      unixServerSocket.close();
    }
    ExecutorServiceManager.shutdownAndAwaitTermination(pool, esName);
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

  public static String getPid() {
    String name = ManagementFactory.getRuntimeMXBean().getName();
    return name.split("@")[0];
  }
}
