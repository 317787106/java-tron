package org.tron.core.services.admin.ipc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.lang.reflect.Type;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.SyntaxError;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.tron.core.services.admin.AdminJsonRpc;
import org.tron.program.Version;

@Slf4j(topic = "API")
public class IpcClient {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String socketFilePath;
  private final Map<String, String> commandLowerMap;
  private final Map<String, List<String>> commandParameters;
  private final Map<String, List<JavaType>> commandParameterTypes;
  private final DefaultParser commandParser = new DefaultParser().eofOnUnclosedQuote(true);
  private int requestId = 0;

  public IpcClient(String socketFilePath) {
    this.socketFilePath = socketFilePath;
    this.commandLowerMap = collectAdminCommands();
    this.commandParameters = collectAdminCommandParams();
    this.commandParameterTypes = collectAdminCommandParamTypes();
  }

  public static void start(String socketFilePath) {
    start(socketFilePath, null);
  }

  public static void start(String socketFilePath, String execCommand) {
    IpcClient ipcClient = new IpcClient(socketFilePath);
    try {
      ipcClient.run(execCommand);
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

  private Map<String, List<JavaType>> collectAdminCommandParamTypes() {
    Map<String, List<JavaType>> parameterTypes = new HashMap<>();
    for (Method method : AdminJsonRpc.class.getDeclaredMethods()) {
      JsonRpcMethod rpcMethod = method.getAnnotation(JsonRpcMethod.class);
      if (rpcMethod == null || rpcMethod.value() == null) {
        continue;
      }
      List<JavaType> types = new ArrayList<>();
      for (Type type : method.getGenericParameterTypes()) {
        types.add(OBJECT_MAPPER.getTypeFactory().constructType(type));
      }
      parameterTypes.put(rpcMethod.value().toLowerCase(Locale.ROOT), types);
    }
    return parameterTypes;
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
    String commandLowerCase = command.toLowerCase(Locale.ROOT);
    List<String> parameters = commandParameters.get(commandLowerCase);
    if (parameters == null || parameters.isEmpty()) {
      return command;
    }
    List<JavaType> parameterTypes = commandParameterTypes.get(commandLowerCase);
    List<String> typedParameters = new ArrayList<>();
    for (int i = 0; i < parameters.size(); i++) {
      typedParameters.add(parameters.get(i) + ":" + formatType(parameterTypes.get(i)));
    }
    return command + " <" + StringUtils.join(typedParameters, "> <") + ">";
  }

  public void run() throws IOException {
    run(null);
  }

  void run(String execCommand) throws IOException {
    File socketFile = new File(socketFilePath);
    if (!socketFile.exists()) {
      System.err.println("IPC socket file does not exist: " + socketFile.getName());
      return;
    }
    AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile);
    try (Socket socket = AFUNIXSocket.newInstance()) {
      socket.connect(address);
      if (execCommand != null) {
        runExec(socket, execCommand);
        return;
      }
      printWelcome(socketFile);
      try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
        LineReader reader = createLineReader(terminal);
        runSession(socket, reader);
      }
    }
  }

  void runExec(Socket socket, String commandLine) throws IOException {
    List<String> commandWords;
    try {
      commandWords = parseCommandLine(commandLine);
    } catch (SyntaxError e) {
      System.err.println("Invalid command syntax.");
      return;
    }
    if (commandWords.isEmpty()) {
      System.err.println("No command specified for --exec.");
      return;
    }
    if (isExitCommand(commandWords.get(0))) {
      return;
    }

    String request;
    try {
      request = buildRequest(commandWords);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return;
    }
    if (request == null) {
      return;
    }

    try (BufferedWriter serverWriter = new BufferedWriter(
        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader serverReader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
      serverWriter.write(request);
      serverWriter.newLine();
      serverWriter.flush();

      String response;
      do {
        response = serverReader.readLine();
      } while (response != null && response.trim().isEmpty());
      if (response == null) {
        System.err.println("Disconnected from server before receiving a response.");
        return;
      }
      System.out.println(formatResponse(response));
    }
  }

  void runSession(Socket socket, LineReader reader) throws IOException {
    AtomicBoolean connected = new AtomicBoolean(true);
    outputResponse(socket, reader, connected, Thread.currentThread());
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
          if (!response.trim().isEmpty()) {
            reader.printAbove(formatResponse(response));
          }
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
        .parser(commandParser)
        .variable(LineReader.INDENTATION, 2)
        .option(LineReader.Option.AUTO_FRESH_LINE, true)
        .option(LineReader.Option.CASE_INSENSITIVE, true)
        .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
        .option(LineReader.Option.HISTORY_REDUCE_BLANKS, true)
        .build();
  }

  void printWelcome(File socketFile) {
    System.out.println("Welcome to the java-tron admin console.");
    System.out.println("Client: java-tron/" + Version.getVersion());
    System.out.println("IPC endpoint: " + socketFile.getAbsolutePath());
    System.out.println("Type \"help\" for available commands; \"exit\" or Ctrl-D to quit.");
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
          List<String> commandWords = parseCommandLine(reader.readLine(prompt));
          if (commandWords.isEmpty()) {
            continue;
          }
          if (isExitCommand(commandWords.get(0))) {
            break;
          }

          String request = buildRequest(commandWords);
          if (request == null) {
            continue;
          }
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
        } catch (SyntaxError e) {
          System.err.println("Invalid command syntax.");
        } catch (IllegalArgumentException e) {
          System.err.println(e.getMessage());
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

  List<String> parseCommandLine(String commandLine) {
    if (commandLine == null || commandLine.trim().isEmpty()) {
      return Collections.emptyList();
    }
    ParsedLine parsedLine = commandParser.parse(
        commandLine, commandLine.length(), Parser.ParseContext.ACCEPT_LINE);
    return parsedLine.words();
  }

  private boolean isExitCommand(String command) {
    return "exit".equalsIgnoreCase(command) || "quit".equalsIgnoreCase(command);
  }

  private String buildRequest(List<String> commandWords) throws JsonProcessingException {
    String command = commandWords.get(0);
    String commandLowerCase = command.toLowerCase(Locale.ROOT);
    if ("help".equals(commandLowerCase)) {
      if (commandWords.size() == 2
          && commandLowerMap.containsKey(commandWords.get(1).toLowerCase(Locale.ROOT))) {
        String rpcMethod = commandWords.get(1).toLowerCase(Locale.ROOT);
        System.out.println("usage: " + formatUsage(commandLowerMap.get(rpcMethod)));
      } else {
        printHelp();
      }
      return null;
    }
    if (!commandLowerMap.containsKey(commandLowerCase)) {
      System.err.println("Invalid cmd: " + command);
      printHelp();
      return null;
    }
    if (commandWords.size() - 1 != commandParameters.get(commandLowerCase).size()) {
      System.err.println("Invalid parameter, usage: "
          + formatUsage(commandLowerMap.get(commandLowerCase)));
      return null;
    }

    List<String> rawValues = new ArrayList<>(
        commandWords.subList(1, commandWords.size()));
    List<Object> values = convertArguments(commandLowerCase, rawValues);
    return buildJsonWithParameter(commandLowerMap.get(commandLowerCase), values);
  }

  private List<Object> convertArguments(String command, List<String> values) {
    List<Object> convertedValues = new ArrayList<>();
    List<JavaType> parameterTypes = commandParameterTypes.get(command);
    List<String> parameterNames = commandParameters.get(command);
    for (int i = 0; i < values.size(); i++) {
      convertedValues.add(convertArgument(values.get(i), parameterTypes.get(i),
          parameterNames.get(i)));
    }
    return convertedValues;
  }

  Object convertArgument(String value, JavaType targetType, String parameterName) {
    Class<?> rawClass = targetType.getRawClass();
    if (String.class.equals(rawClass) || CharSequence.class.equals(rawClass)) {
      return value;
    }
    if (Character.class.equals(rawClass) || Character.TYPE.equals(rawClass)) {
      if (value.length() == 1) {
        return value.charAt(0);
      }
      throw invalidParameterType(parameterName, targetType, null);
    }
    if (rawClass.isPrimitive() && "null".equals(value.trim())) {
      throw invalidParameterType(parameterName, targetType, null);
    }
    Object convertedValue;
    try {
      if (rawClass.isEnum()) {
        convertedValue = OBJECT_MAPPER.convertValue(value, targetType);
      } else {
        convertedValue = OBJECT_MAPPER.readValue(value, targetType);
      }
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw invalidParameterType(parameterName, targetType, e);
    }
    if (convertedValue == null && rawClass.isPrimitive()) {
      throw invalidParameterType(parameterName, targetType, null);
    }
    return convertedValue;
  }

  private String formatType(JavaType type) {
    Class<?> rawClass = type.getRawClass();
    if (String.class.equals(rawClass) || CharSequence.class.equals(rawClass)) {
      return "string";
    }
    if (Boolean.class.equals(rawClass) || Boolean.TYPE.equals(rawClass)) {
      return "boolean";
    }
    if (Number.class.isAssignableFrom(rawClass) || rawClass.isPrimitive()) {
      return rawClass.getSimpleName().toLowerCase(Locale.ROOT);
    }
    if (rawClass.isArray() || java.util.Collection.class.isAssignableFrom(rawClass)) {
      return "array";
    }
    if (java.util.Map.class.isAssignableFrom(rawClass)) {
      return "object";
    }
    return rawClass.getSimpleName();
  }

  private IllegalArgumentException invalidParameterType(String parameterName, JavaType targetType,
      Throwable cause) {
    return new IllegalArgumentException(
        "Invalid value for <" + parameterName + ">; expected " + targetType.toCanonical(), cause);
  }

  String formatResponse(String response) {
    try {
      JsonNode root = OBJECT_MAPPER.readTree(response);
      if (root == null || root.isMissingNode()) {
        return response;
      }
      JsonNode error = root.get("error");
      if (error != null && !error.isNull()) {
        String code = error.has("code") ? " " + error.get("code").asText() : "";
        String message = error.has("message") ? error.get("message").asText() : "Unknown error";
        return "Error" + code + ": " + message;
      }
      if (root.has("result")) {
        return formatJsonValue(root.get("result"));
      }
      return formatJsonValue(root);
    } catch (JsonProcessingException e) {
      return response;
    }
  }

  private String formatJsonValue(JsonNode value) throws JsonProcessingException {
    if (value == null || value.isNull()) {
      return "null";
    }
    if (value.isTextual()) {
      return value.asText();
    }
    return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
  }

  private String buildJsonWithParameter(String cmd, List<Object> values)
      throws JsonProcessingException {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("jsonrpc", "2.0");
    params.put("method", cmd);
    params.put("params", values);
    params.put("id", ++requestId);
    return OBJECT_MAPPER.writeValueAsString(params);
  }
}
