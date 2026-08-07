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
  static final int EXIT_SUCCESS = 0;
  static final int EXIT_FAILURE = 1;

  private final String socketFilePath;
  private final Map<String, AdminCommand> adminCommands;
  private final DefaultParser commandParser = new DefaultParser().eofOnUnclosedQuote(true);
  private int requestId = 0;

  public IpcClient(String socketFilePath) {
    this(socketFilePath, AdminJsonRpc.class);
  }

  IpcClient(String socketFilePath, Class<?> adminApi) {
    this.socketFilePath = socketFilePath;
    this.adminCommands = collectAdminCommands(adminApi);
  }

  public static int start(String socketFilePath) {
    return start(socketFilePath, null);
  }

  public static int start(String socketFilePath, String execCommand) {
    IpcClient ipcClient = new IpcClient(socketFilePath);
    try {
      return ipcClient.run(execCommand);
    } catch (IOException e) {
      System.err.println("Failed to communicate with IPC server.");
      logger.debug("IPC client communication failed: {}", e.getClass().getSimpleName());
      return EXIT_FAILURE;
    }
  }

  private Map<String, AdminCommand> collectAdminCommands(Class<?> adminApi) {
    Map<String, AdminCommand> commands = new HashMap<>();
    for (Method method : adminApi.getDeclaredMethods()) {
      JsonRpcMethod rpcMethod = method.getAnnotation(JsonRpcMethod.class);
      if (rpcMethod == null || rpcMethod.value() == null) {
        continue;
      }

      List<String> parameterNames = new ArrayList<>();
      List<JavaType> parameterTypes = new ArrayList<>();
      Annotation[][] paramAnnotations = method.getParameterAnnotations();
      Type[] genericParameterTypes = method.getGenericParameterTypes();
      for (int i = 0; i < paramAnnotations.length; i++) {
        String parameterName = null;
        for (Annotation anno : paramAnnotations[i]) {
          if (anno instanceof JsonRpcParam) {
            parameterName = ((JsonRpcParam) anno).value();
            break;
          }
        }
        if (StringUtils.isEmpty(parameterName)) {
          throw new IllegalStateException("Missing @JsonRpcParam on " + method.getName()
              + " parameter " + i);
        }
        parameterNames.add(parameterName);
        parameterTypes.add(OBJECT_MAPPER.getTypeFactory().constructType(genericParameterTypes[i]));
      }

      AdminCommand command = new AdminCommand(rpcMethod.value(), parameterNames, parameterTypes);
      commands.put(rpcMethod.value().toLowerCase(Locale.ROOT), command);
    }
    return commands;
  }

  private void printHelp() {
    System.out.println("Available commands:");
    for (String usage : buildHelpLines()) {
      System.out.println("  " + usage);
    }
  }

  List<String> buildHelpLines() {
    List<String> commands = new ArrayList<>();
    for (AdminCommand command : adminCommands.values()) {
      commands.add(command.name);
    }
    Collections.sort(commands);
    List<String> helpLines = new ArrayList<>();
    for (String command : commands) {
      helpLines.add(formatUsage(adminCommands.get(command.toLowerCase(Locale.ROOT))));
    }
    helpLines.add("help [command]");
    helpLines.add("exit/quit");
    return helpLines;
  }

  private String formatUsage(AdminCommand command) {
    if (command.parameterNames.isEmpty()) {
      return command.name;
    }
    List<String> typedParameters = new ArrayList<>();
    for (int i = 0; i < command.parameterNames.size(); i++) {
      typedParameters.add(command.parameterNames.get(i) + ":"
          + formatType(command.parameterTypes.get(i)));
    }
    return command.name + " <" + StringUtils.join(typedParameters, "> <") + ">";
  }

  public int run() throws IOException {
    return run(null);
  }

  int run(String execCommand) throws IOException {
    File socketFile = new File(socketFilePath);
    if (!socketFile.exists()) {
      System.err.println("IPC socket file does not exist: " + socketFile.getName());
      return EXIT_FAILURE;
    }
    AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile);
    try (Socket socket = AFUNIXSocket.newInstance()) {
      socket.connect(address);
      if (execCommand != null) {
        return runExec(socket, execCommand);
      }
      printWelcome(socketFile);
      try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
        LineReader reader = createLineReader(terminal);
        runSession(socket, reader);
      }
      return EXIT_SUCCESS;
    }
  }

  int runExec(Socket socket, String commandLine) throws IOException {
    List<String> commandWords;
    try {
      commandWords = parseCommandLine(commandLine);
    } catch (SyntaxError e) {
      System.err.println("Invalid command syntax.");
      return EXIT_FAILURE;
    }
    if (commandWords.isEmpty()) {
      System.err.println("No command specified for --exec.");
      return EXIT_FAILURE;
    }
    if (isExitCommand(commandWords.get(0))) {
      return EXIT_SUCCESS;
    }

    String request;
    try {
      request = buildRequest(commandWords);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return EXIT_FAILURE;
    }
    if (request == null) {
      return "help".equalsIgnoreCase(commandWords.get(0)) ? EXIT_SUCCESS : EXIT_FAILURE;
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
        return EXIT_FAILURE;
      }
      String formattedResponse = formatResponse(response);
      if (isSuccessfulResponse(response)) {
        System.out.println(formattedResponse);
        return EXIT_SUCCESS;
      }
      System.err.println(formattedResponse);
      return EXIT_FAILURE;
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
        if (notifyDisconnected(connected, reader)) {
          inputThread.interrupt();
        }
      }
    }, "admin-ipc-client-reader");
    readerThread.setDaemon(true);
    readerThread.start();
  }

  private LineReader createLineReader(Terminal terminal) {
    Completer commandCompleter =
        new IpcCommandCompleter(getCompletionCommandNames());
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

  String[] getCompletionCommandNames() {
    return adminCommands.values().stream()
        .map(command -> command.name)
        .sorted()
        .toArray(String[]::new);
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

    try {
      BufferedWriter serverWriter = new BufferedWriter(
          new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
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

  private boolean notifyDisconnected(AtomicBoolean connected, LineReader reader) {
    if (connected.compareAndSet(true, false)) {
      reader.printAbove("Disconnected from server.");
      return true;
    }
    return false;
  }

  List<String> parseCommandLine(String commandLine) {
    if (commandLine == null) {
      return Collections.emptyList();
    }
    String normalizedCommandLine = commandLine.trim();
    if (normalizedCommandLine.isEmpty()) {
      return Collections.emptyList();
    }
    ParsedLine parsedLine = commandParser.parse(
        normalizedCommandLine, normalizedCommandLine.length(), Parser.ParseContext.ACCEPT_LINE);
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
          && adminCommands.containsKey(commandWords.get(1).toLowerCase(Locale.ROOT))) {
        String rpcMethod = commandWords.get(1).toLowerCase(Locale.ROOT);
        System.out.println("usage: " + formatUsage(adminCommands.get(rpcMethod)));
      } else {
        printHelp();
      }
      return null;
    }
    AdminCommand adminCommand = adminCommands.get(commandLowerCase);
    if (adminCommand == null) {
      System.err.println("Invalid cmd: " + command);
      printHelp();
      return null;
    }
    if (commandWords.size() - 1 != adminCommand.parameterNames.size()) {
      System.err.println("Invalid parameter, usage: "
          + formatUsage(adminCommand));
      return null;
    }

    List<String> rawValues = new ArrayList<>(
        commandWords.subList(1, commandWords.size()));
    List<Object> values = convertArguments(adminCommand, rawValues);
    return buildJsonWithParameter(adminCommand.name, values);
  }

  private List<Object> convertArguments(AdminCommand command, List<String> values) {
    List<Object> convertedValues = new ArrayList<>();
    for (int i = 0; i < values.size(); i++) {
      convertedValues.add(convertArgument(values.get(i), command.parameterTypes.get(i),
          command.parameterNames.get(i)));
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

  boolean isSuccessfulResponse(String response) {
    try {
      JsonNode root = OBJECT_MAPPER.readTree(response);
      if (root == null || !root.isObject()) {
        return false;
      }
      JsonNode error = root.get("error");
      return (error == null || error.isNull()) && root.has("result");
    } catch (JsonProcessingException e) {
      return false;
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

  private static class AdminCommand {

    private final String name;
    private final List<String> parameterNames;
    private final List<JavaType> parameterTypes;

    private AdminCommand(String name, List<String> parameterNames,
        List<JavaType> parameterTypes) {
      this.name = name;
      this.parameterNames = parameterNames;
      this.parameterTypes = parameterTypes;
    }
  }
}
