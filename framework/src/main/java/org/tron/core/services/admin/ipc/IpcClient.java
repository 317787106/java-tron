package org.tron.core.services.admin.ipc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.googlecode.jsonrpc4j.JsonRpcParam;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.tron.core.services.admin.AdminJsonRpc;

@Slf4j(topic = "API")
public class IpcClient {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String socketFilePath;
  private final Map<String, String> commandLowerMap;
  private final Map<String, List<String>> commandParameters;
  private int requestId = 0;

  public IpcClient(String socketFilePath) {
    this.socketFilePath = socketFilePath;
    this.commandLowerMap = collectAdminCommands();
    this.commandParameters = collectAdminCommandParams();
  }

  public static void start(String socketFilePath) {
    IpcClient ipcClient = new IpcClient(socketFilePath);
    try {
      ipcClient.run();
    } catch (IOException e) {
      logger.error("", e);
    }
  }

  private Map<String, String> collectAdminCommands() {
    Map<String, String> commandMap = new HashMap<>();
    Class<?> rpcInterface = AdminJsonRpc.class;
    for (Method method : rpcInterface.getDeclaredMethods()) {
      JsonRpcMethod rpcMethod = method.getAnnotation(JsonRpcMethod.class);
      if (rpcMethod != null && rpcMethod.value() != null) {
        commandMap.put(rpcMethod.value().toLowerCase(Locale.ROOT), rpcMethod.value());
      }
    }
    return commandMap;
  }

  private Map<String, List<String>> collectAdminCommandParams() {
    Map<String, List<String>> commandParameters = new HashMap<>();
    Class<?> rpcInterface = AdminJsonRpc.class;

    for (Method method : rpcInterface.getDeclaredMethods()) {
      JsonRpcMethod rpcMethod = method.getAnnotation(JsonRpcMethod.class);
      if (rpcMethod == null || rpcMethod.value() == null) {
        continue;
      }

      String methodName = rpcMethod.value().toLowerCase(Locale.ROOT);
      List<String> params = new ArrayList<>();
      Annotation[][] paramAnnotations = method.getParameterAnnotations();
      for (Annotation[] annotations : paramAnnotations) {
        for (Annotation anno : annotations) {
          if (anno instanceof JsonRpcParam) {
            JsonRpcParam p = (JsonRpcParam) anno;
            params.add(p.value());
          }
        }
      }
      commandParameters.put(methodName, params);
    }
    return commandParameters;
  }

  private void printHelp() {
    System.out.println("Available commands:");
    for (String usage : buildHelpLines()) {
      System.out.println("  " + usage);
    }
  }

  List<String> buildHelpLines() {
    List<String> commands = new ArrayList<>(commandLowerMap.values());
    Collections.sort(commands);
    List<String> helpLines = new ArrayList<>();
    for (String command : commands) {
      helpLines.add(formatUsage(command));
    }
    helpLines.add("help [command]");
    helpLines.add("exit");
    helpLines.add("quit");
    return helpLines;
  }

  private String formatUsage(String command) {
    List<String> parameters = commandParameters.get(command.toLowerCase(Locale.ROOT));
    if (parameters == null || parameters.isEmpty()) {
      return command;
    }
    return command + " <" + StringUtils.join(parameters, "> <") + ">";
  }

  public void run() throws IOException {
    File socketFile = new File(socketFilePath);
    if (!socketFile.exists()) {
      System.err.println("IPC socket file does not exist: " + socketFile.getName());
      return;
    }
    AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile);
    try (Socket socket = AFUNIXSocket.newInstance()) {
      socket.connect(address);
      System.out.println("Connected to server: " + socketFile.getAbsolutePath());
      try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
        LineReader reader = createLineReader(terminal);
        runSession(socket, reader);
      }
    }
  }

  void runSession(Socket socket, LineReader reader) throws IOException {
    AtomicBoolean connected = new AtomicBoolean(true);
    outputResponse(socket, reader, connected, Thread.currentThread());
    printHelp();
    try {
      inputRequest(socket, reader, connected);
    } finally {
      connected.set(false);
    }
  }

  /**
   * start a thread to receive response from IPC server
   */
  private void outputResponse(final Socket socket, LineReader reader, AtomicBoolean connected,
      Thread inputThread) throws IOException {
    final BufferedReader serverReader = new BufferedReader(
        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

    Thread readerThread = new Thread(() -> {
      try {
        String response;
        while ((response = serverReader.readLine()) != null) {
          reader.printAbove(response);
        }
      } catch (IOException e) {
        logger.debug("IPC response stream closed: {}", e.getMessage());
      } finally {
        notifyDisconnected(connected, reader);
        inputThread.interrupt();
      }
    }, "admin-ipc-client-reader");
    readerThread.setDaemon(true);
    readerThread.start();
  }

  private LineReader createLineReader(Terminal terminal) {
    Completer commandCompleter =
        new IpcCommandCompleter(commandLowerMap.keySet().toArray(new String[0]));
    ArgumentCompleter completer = new ArgumentCompleter(
        commandCompleter,
        NullCompleter.INSTANCE
    );
    return LineReaderBuilder.builder()
        .terminal(terminal)
        .completer(completer)
        .parser(new DefaultParser())
        .variable(LineReader.INDENTATION, 2)
        .option(LineReader.Option.AUTO_FRESH_LINE, true)
        .option(LineReader.Option.CASE_INSENSITIVE, true)
        .build();
  }

  /**
   * read from System.in and send command to IPC server
   */
  private void inputRequest(final Socket socket, LineReader reader, AtomicBoolean connected) {
    String prompt = "> ";

    try (BufferedWriter serverWriter = new BufferedWriter(
        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
      while (connected.get()) {
        try {
          String cmdLine = reader.readLine(prompt).trim();
          String[] cmdArray = cmdLine.split("\\s+");
          // split on trim() string will always return at the minimum: [""]
          String cmd = cmdArray[0];
          if ("".equals(cmd)) {
            continue;
          }
          String cmdLowerCase = cmd.toLowerCase(Locale.ROOT);

          if ("help".equals(cmdLowerCase)) {
            if (cmdArray.length == 2
                && commandLowerMap.containsKey(cmdArray[1].toLowerCase(Locale.ROOT))) {
              String rpcMethod = cmdArray[1].toLowerCase(Locale.ROOT);
              System.out.println("usage: " + formatUsage(commandLowerMap.get(rpcMethod)));
            } else {
              printHelp();
            }
            continue;
          } else if ("exit".equals(cmdLowerCase) || "quit".equals(cmdLowerCase)) {
            break;
          } else if (!commandLowerMap.containsKey(cmdLowerCase)) {
            System.err.println("Invalid cmd: " + cmd);
            printHelp();
            continue;
          } else if (cmdArray.length - 1 != commandParameters.get(cmdLowerCase).size()) {
            System.err.println("Invalid parameter, usage: "
                + formatUsage(commandLowerMap.get(cmdLowerCase)));
            continue;
          }

          List<String> values =
              new ArrayList<>(Arrays.asList(cmdArray).subList(1, cmdArray.length));

          String request = buildJsonWithParameter(commandLowerMap.get(cmdLowerCase), values);
          System.out.println("Sending request: " + request);
          serverWriter.write(request);
          serverWriter.newLine();
          serverWriter.flush();
        } catch (UserInterruptException e) {
          // Ctrl + C or server disconnected
          break;
        } catch (EndOfFileException e) {
          // Ctrl + D
          break;
        } catch (JsonProcessingException e) {
          logger.error("Failed to build IPC request", e);
        } catch (IOException e) {
          notifyDisconnected(connected, reader);
          break;
        } catch (Exception e) {
          logger.error("Failed to process IPC command", e);
        }
      }
    } catch (IOException e) {
      notifyDisconnected(connected, reader);
    }
  }

  private void notifyDisconnected(AtomicBoolean connected, LineReader reader) {
    if (connected.compareAndSet(true, false)) {
      reader.printAbove("Disconnected from server.");
    }
  }

  private String buildJsonWithParameter(String cmd, List<String> values)
      throws JsonProcessingException {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("jsonrpc", "2.0");
    params.put("method", cmd);
    params.put("params", values);
    params.put("id", ++requestId);
    return OBJECT_MAPPER.writeValueAsString(params);
  }
}
