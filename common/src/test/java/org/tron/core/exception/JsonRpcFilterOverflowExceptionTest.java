package org.tron.core.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;
import org.tron.core.exception.jsonrpc.JsonRpcFilterOverflowException;

public class JsonRpcFilterOverflowExceptionTest {

  @Test
  public void exposesStructuredOverflowReasonAndLimit() {
    JsonRpcFilterOverflowException exception = new JsonRpcFilterOverflowException(20000);
    Assert.assertEquals("filter result limit exceeded; filter invalidated", exception.getMessage());
    JsonNode data = new ObjectMapper().valueToTree(exception.getData());
    Assert.assertEquals("FILTER_OVERFLOW", data.get("reason").asText());
    Assert.assertTrue(data.get("limit").isInt());
    Assert.assertEquals(20000, data.get("limit").asInt());
  }
}
