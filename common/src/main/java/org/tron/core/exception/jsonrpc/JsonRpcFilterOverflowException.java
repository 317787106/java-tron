package org.tron.core.exception.jsonrpc;

import com.google.common.collect.ImmutableMap;

public class JsonRpcFilterOverflowException extends JsonRpcException {

  public JsonRpcFilterOverflowException(int limit) {
    super("filter result limit exceeded; filter invalidated",
        ImmutableMap.of("reason", "FILTER_OVERFLOW", "limit", limit));
  }
}
