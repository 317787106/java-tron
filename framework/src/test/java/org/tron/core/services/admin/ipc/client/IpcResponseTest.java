package org.tron.core.services.admin.ipc.client;

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.tron.core.services.admin.ipc.client.ActivePeerOutputFormatter.OutputFormat;

public class IpcResponseTest {

  @Test
  public void testTextFormatIsMatchedByResponseIdAndConsumedOnce() {
    Map<Integer, OutputFormat> pendingFormats = new HashMap<>();
    pendingFormats.put(1, OutputFormat.TEXT);
    String peers = "\"result\":{\"allCount\":0,\"activeCount\":0,\"passiveCount\":0,"
        + "\"validCount\":0,\"peers\":[]}}";

    IpcResponse unrelated = IpcResponse.parse("{\"id\":2," + peers, pendingFormats);
    Assert.assertTrue(unrelated.isSuccessful());
    Assert.assertTrue(unrelated.getFormatted().contains("\"peers\""));
    Assert.assertEquals(OutputFormat.TEXT, pendingFormats.get(1));
    Assert.assertTrue(IpcResponse.parse("{\"id\":1.5," + peers, pendingFormats)
        .getFormatted().contains("\"peers\""));
    Assert.assertEquals(OutputFormat.TEXT, pendingFormats.get(1));
    IpcResponse matched = IpcResponse.parse("{\"id\":1," + peers, pendingFormats);
    Assert.assertTrue(matched.isSuccessful());
    Assert.assertTrue(matched.getFormatted().startsWith(
        "Peers: all=0, active=0, passive=0, valid=0"));
    Assert.assertTrue(pendingFormats.isEmpty());
    Assert.assertTrue(IpcResponse.parse("{\"id\":1," + peers, pendingFormats)
        .getFormatted().contains("\"peers\""));
  }

  @Test
  public void testTextFormatPreservesErrorsAndFallsBackForUnexpectedResults() {
    Map<Integer, OutputFormat> pendingFormats = new HashMap<>();
    pendingFormats.put(1, OutputFormat.TEXT);
    IpcResponse error = IpcResponse.parse(
        "{\"id\":1,\"error\":{\"code\":-32000,\"message\":\"Unavailable\"}}", pendingFormats);
    Assert.assertFalse(error.isSuccessful());
    Assert.assertEquals("Error -32000: Unavailable", error.getFormatted());
    Assert.assertTrue(pendingFormats.isEmpty());

    pendingFormats.put(2, OutputFormat.TEXT);
    IpcResponse fallback = IpcResponse.parse("{\"id\":2,\"result\":null}", pendingFormats);
    Assert.assertTrue(fallback.isSuccessful());
    Assert.assertEquals("null", fallback.getFormatted());
    Assert.assertTrue(pendingFormats.isEmpty());
  }

  @Test
  public void testTextResultIsUnquoted() {
    assertResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"done\"}", "done", true);
  }

  @Test
  public void testStructuredResultIsPrettyPrinted() {
    IpcResponse response = IpcResponse.parse(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"height\":10,\"ready\":true}}");

    Assert.assertTrue(response.isSuccessful());
    Assert.assertEquals(String.join(System.lineSeparator(),
        "{", "  \"height\" : 10,", "  \"ready\" : true", "}"), response.getFormatted());
  }

  @Test
  public void testNullAndScalarResultsAreSuccessful() {
    assertResponse("{\"result\":null}", "null", true);
    assertResponse("{\"result\":false}", "false", true);
    assertResponse("{\"result\":42,\"error\":null}", "42", true);
  }

  @Test
  public void testErrorTakesPrecedenceOverResult() {
    assertResponse("{\"result\":\"ignored\","
        + "\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}",
        "Error -32602: Invalid params", false);
    assertResponse("{\"error\":{\"message\":\"Failed\"}}", "Error: Failed", false);
    assertResponse("{\"error\":{}}", "Error: Unknown error", false);
  }

  @Test
  public void testMalformedResponsePreservesTextAndFails() {
    for (String input : new String[] {"", "  ", "response", "{broken json"}) {
      assertResponse(input, input, false);
    }
  }

  @Test
  public void testResponseWithoutResultFails() {
    assertResponse("null", "null", false);
    assertResponse("\"message\"", "message", false);
    assertResponse("{\"id\":1}", String.join(System.lineSeparator(),
        "{", "  \"id\" : 1", "}"), false);
  }

  private void assertResponse(String input, String formatted, boolean successful) {
    IpcResponse response = IpcResponse.parse(input);
    Assert.assertEquals(formatted, response.getFormatted());
    Assert.assertEquals(successful, response.isSuccessful());
  }
}
