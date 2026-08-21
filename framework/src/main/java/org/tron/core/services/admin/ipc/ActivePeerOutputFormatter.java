package org.tron.core.services.admin.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

final class ActivePeerOutputFormatter {

  private static final int MAX_TABLE_CELL_LENGTH = 64;
  private static final String METHOD = "admin_listActivePeers";
  private static final String[] TABLE_FIELDS = {
      "remoteAddress", "connectSeconds", "averageLatencyMillis", "lastKnownBlockNum",
      "needSyncFromPeer", "needSyncFromUs", "syncToFetchSize", "syncToFetchSizePeekNum",
      "syncBlockRequestedSize", "remainNum", "syncChainRequestedMillis", "inactiveSeconds",
      "blockInProcess"
  };

  ActivePeerOutputFormatter() {
  }

  boolean supports(String method) {
    return METHOD.equals(method);
  }

  String usage() {
    return METHOD + " [format:json|text]";
  }

  OutputFormat parseFormat(String value) {
    if ("json".equalsIgnoreCase(value)) {
      return OutputFormat.JSON;
    }
    if ("text".equalsIgnoreCase(value)) {
      return OutputFormat.TEXT;
    }
    throw new IllegalArgumentException("Invalid value for <format>; expected json or text");
  }

  String formatText(JsonNode result) {
    if (result == null || !result.isObject()) {
      return null;
    }
    JsonNode peers = result.get("peers");
    if (peers == null || !peers.isArray()) {
      return null;
    }

    List<List<String>> rows = new ArrayList<>();
    for (JsonNode peer : peers) {
      if (!peer.isObject()) {
        return null;
      }
      List<String> row = new ArrayList<>();
      for (String field : TABLE_FIELDS) {
        row.add(formatTableCell(peer.get(field)));
      }
      rows.add(row);
    }

    int[] widths = new int[TABLE_FIELDS.length];
    for (int i = 0; i < TABLE_FIELDS.length; i++) {
      widths[i] = TABLE_FIELDS[i].length();
      for (List<String> row : rows) {
        widths[i] = Math.max(widths[i], row.get(i).length());
      }
    }

    String lineSeparator = System.lineSeparator();
    StringBuilder table = new StringBuilder();
    table.append("Peers: all=").append(formatTableCell(result.get("allCount")))
        .append(", active=").append(formatTableCell(result.get("activeCount")))
        .append(", passive=").append(formatTableCell(result.get("passiveCount")))
        .append(", valid=").append(formatTableCell(result.get("validCount")))
        .append(lineSeparator).append(lineSeparator);
    appendTableRow(table, Arrays.asList(TABLE_FIELDS), widths);
    table.append(lineSeparator);
    appendTableSeparator(table, widths);
    for (List<String> row : rows) {
      table.append(lineSeparator);
      appendTableRow(table, row, widths);
    }
    return table.toString();
  }

  private String formatTableCell(JsonNode value) {
    if (value == null || value.isNull()) {
      return "-";
    }
    String text = value.asText();
    StringBuilder sanitized = new StringBuilder(Math.min(text.length(), MAX_TABLE_CELL_LENGTH));
    for (int i = 0; i < text.length() && sanitized.length() < MAX_TABLE_CELL_LENGTH; i++) {
      char character = text.charAt(i);
      // Limit cells to printable ASCII to prevent terminal control-sequence injection.
      sanitized.append(character >= 0x20 && character <= 0x7e && character != '|'
          ? character : '?');
    }
    if (text.length() > MAX_TABLE_CELL_LENGTH) {
      sanitized.replace(MAX_TABLE_CELL_LENGTH - 3, MAX_TABLE_CELL_LENGTH, "...");
    }
    return sanitized.toString();
  }

  private void appendTableRow(StringBuilder table, List<String> cells, int[] widths) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        table.append(" | ");
      }
      table.append(StringUtils.rightPad(cells.get(i), widths[i]));
    }
  }

  private void appendTableSeparator(StringBuilder table, int[] widths) {
    for (int i = 0; i < widths.length; i++) {
      if (i > 0) {
        table.append("-+-");
      }
      table.append(StringUtils.repeat('-', widths[i]));
    }
  }

  enum OutputFormat {
    JSON,
    TEXT
  }
}
