package org.tron.core.services.jsonrpc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.tron.common.logsfilter.capsule.BlockFilterCapsule;
import org.tron.common.logsfilter.capsule.LogsFilterCapsule;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.config.args.Args;
import org.tron.core.db2.core.Chainbase.Cursor;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.exception.jsonrpc.JsonRpcExceedLimitException;
import org.tron.core.exception.jsonrpc.JsonRpcFilterOverflowException;
import org.tron.core.services.jsonrpc.TronJsonRpc.FilterRequest;
import org.tron.core.services.jsonrpc.TronJsonRpc.LogFilterElement;
import org.tron.core.services.jsonrpc.filters.BlockFilterAndResult;
import org.tron.core.services.jsonrpc.filters.FilterResult;
import org.tron.core.services.jsonrpc.filters.LogFilterAndResult;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.TransactionInfo;

@RunWith(Parameterized.class)
public class FilterOverflowTest {

  private final boolean solidity;
  private final boolean parallel;
  private final ObjectMapper mapper = new ObjectMapper();
  private TronJsonRpcImpl jsonRpc;
  private int savedLogLimit;
  private int savedBlockLimit;

  public FilterOverflowTest(boolean solidity, boolean parallel) {
    this.solidity = solidity;
    this.parallel = parallel;
  }

  @Parameterized.Parameters(name = "solidity={0}, parallel={1}")
  public static Collection<Object[]> parameters() {
    return Arrays.asList(new Object[][] {
        {false, false}, {false, true}, {true, false}, {true, true}
    });
  }

  @Before
  public void setUp() {
    savedLogLimit = Args.getInstance().getJsonRpcMaxLogFilterNum();
    savedBlockLimit = Args.getInstance().getJsonRpcMaxBlockFilterNum();
    Args.getInstance().setJsonRpcMaxLogFilterNum(2);
    Args.getInstance().setJsonRpcMaxBlockFilterNum(2);
    Wallet wallet = mock(Wallet.class);
    when(wallet.getCursor()).thenReturn(solidity ? Cursor.SOLIDITY : Cursor.HEAD);
    when(wallet.getNowBlock()).thenReturn(Block.getDefaultInstance());
    jsonRpc = new TronJsonRpcImpl(null, wallet);
    jsonRpc.setFilterParallelThreshold(parallel ? 0 : Integer.MAX_VALUE);
  }

  @After
  public void tearDown() throws Exception {
    try {
      jsonRpc.close();
    } finally {
      Args.getInstance().setJsonRpcMaxLogFilterNum(savedLogLimit);
      Args.getInstance().setJsonRpcMaxBlockFilterNum(savedBlockLimit);
    }
  }

  @Test(timeout = 15000)
  public void logOverflowIsIsolatedAndBothReadApisReportIt() throws Exception {
    String fullId = jsonRpc.newFilter(new FilterRequest());
    String healthyId = jsonRpc.newFilter(new FilterRequest());
    LogFilterAndResult full = logs().get(ByteArray.fromHex(fullId));
    full.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE - 1, logElement()));

    dispatchLogs(2);
    Assert.assertTrue(full.isOverflowed());
    Assert.assertEquals(0, full.size());
    Object[] received = jsonRpc.getFilterChanges(healthyId);
    Assert.assertEquals(2, received.length);
    Assert.assertEquals("0x0", ((LogFilterElement) received[0]).getLogIndex());
    Assert.assertEquals("0x1", ((LogFilterElement) received[1]).getLogIndex());

    assertRpcOverflow("eth_getFilterChanges", fullId);
    assertRpcOverflow("eth_getFilterLogs", fullId);
    dispatchLogs(2);
    Assert.assertEquals(0, full.size());
    Assert.assertEquals(2, jsonRpc.getFilterChanges(healthyId).length);
    Assert.assertTrue(logs().containsKey(ByteArray.fromHex(fullId)));
  }

  @Test(timeout = 15000)
  public void exactLogLimitCanBePolledThroughRpc() throws Exception {
    String id = jsonRpc.newFilter(new FilterRequest());
    LogFilterAndResult filter = logs().get(ByteArray.fromHex(id));
    filter.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE - 1, logElement()));
    dispatchLogs(1);
    Assert.assertFalse(filter.isOverflowed());
    JsonNode response = request("eth_getFilterChanges", id);
    Assert.assertFalse(response.has("error"));
    Assert.assertEquals(FilterResult.MAX_RESULT_SIZE, response.get("result").size());
    Assert.assertEquals(0, filter.size());
    dispatchLogs(1);
    Assert.assertEquals(1, jsonRpc.getFilterChanges(id).length);
  }

  @Test(timeout = 15000)
  public void blockOverflowIsIsolatedAndReportedThroughRpc() throws Exception {
    String fullId = jsonRpc.newBlockFilter();
    String healthyId = jsonRpc.newBlockFilter();
    BlockFilterAndResult full = blocks().get(ByteArray.fromHex(fullId));
    full.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE, "0x01"));
    jsonRpc.handleBLockFilter(new BlockFilterCapsule("02", solidity));
    Assert.assertTrue(full.isOverflowed());
    Assert.assertEquals(0, full.size());
    Assert.assertArrayEquals(new String[] {"0x02"}, jsonRpc.getFilterChanges(healthyId));
    assertRpcOverflow("eth_getFilterChanges", fullId);
    jsonRpc.handleBLockFilter(new BlockFilterCapsule("03", solidity));
    Assert.assertEquals(0, full.size());
    Assert.assertArrayEquals(new String[] {"0x03"}, jsonRpc.getFilterChanges(healthyId));
  }

  @Test(timeout = 15000)
  public void overflowRecordsCountTowardCapsUntilUninstalled() throws Exception {
    String logId = jsonRpc.newFilter(new FilterRequest());
    jsonRpc.newFilter(new FilterRequest());
    LogFilterAndResult logFilter = logs().get(ByteArray.fromHex(logId));
    logFilter.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE + 1, logElement()));
    Assert.assertThrows(JsonRpcExceedLimitException.class,
        () -> jsonRpc.newFilter(new FilterRequest()));
    Assert.assertTrue(jsonRpc.uninstallFilter(logId));
    Assert.assertThrows(ItemNotFoundException.class, () -> jsonRpc.getFilterChanges(logId));
    logFilter.add(logElement());
    Assert.assertEquals(0, logFilter.size());
    Assert.assertNotNull(jsonRpc.newFilter(new FilterRequest()));

    String blockId = jsonRpc.newBlockFilter();
    jsonRpc.newBlockFilter();
    BlockFilterAndResult blockFilter = blocks().get(ByteArray.fromHex(blockId));
    blockFilter.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE + 1, "0x01"));
    Assert.assertThrows(JsonRpcExceedLimitException.class, jsonRpc::newBlockFilter);
    Assert.assertTrue(jsonRpc.uninstallFilter(blockId));
    Assert.assertThrows(ItemNotFoundException.class, () -> jsonRpc.getFilterChanges(blockId));
    blockFilter.add("0x02");
    Assert.assertEquals(0, blockFilter.size());
    Assert.assertNotNull(jsonRpc.newBlockFilter());
  }

  @Test(timeout = 15000)
  public void expiredOverflowRecordsAreRemovedAndNotRevivedByPolling() throws Exception {
    String logId = jsonRpc.newFilter(new FilterRequest());
    String blockId = jsonRpc.newBlockFilter();
    LogFilterAndResult logFilter = logs().get(ByteArray.fromHex(logId));
    BlockFilterAndResult blockFilter = blocks().get(ByteArray.fromHex(blockId));
    logFilter.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE + 1, logElement()));
    blockFilter.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE + 1, "0x01"));
    Field deadline = FilterResult.class.getDeclaredField("expireTimeStamp");
    deadline.setAccessible(true);
    deadline.setLong(logFilter, 0);
    deadline.setLong(blockFilter, 0);

    Assert.assertThrows(ItemNotFoundException.class, () -> jsonRpc.getFilterChanges(logId));
    Assert.assertThrows(ItemNotFoundException.class, () -> jsonRpc.getFilterChanges(blockId));
    dispatchLogs(1);
    jsonRpc.handleBLockFilter(new BlockFilterCapsule("02", solidity));
    Assert.assertTrue(logs().isEmpty());
    Assert.assertTrue(blocks().isEmpty());
    Assert.assertEquals(0, deadline.getLong(logFilter));
    Assert.assertEquals(0, deadline.getLong(blockFilter));
  }

  @Test(timeout = 15000)
  public void closedServiceReleasesActiveResultsAndOverflowRecords() throws Exception {
    String logId = jsonRpc.newFilter(new FilterRequest());
    String blockId = jsonRpc.newBlockFilter();
    LogFilterAndResult logFilter = logs().get(ByteArray.fromHex(logId));
    BlockFilterAndResult blockFilter = blocks().get(ByteArray.fromHex(blockId));
    dispatchLogs(1);
    blockFilter.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE + 1, "0x01"));
    Assert.assertEquals(1, logFilter.size());
    Assert.assertTrue(blockFilter.isOverflowed());
    jsonRpc.close();
    Assert.assertTrue(logs().isEmpty());
    Assert.assertTrue(blocks().isEmpty());
    Assert.assertEquals(0, logFilter.size());
    Assert.assertThrows(ItemNotFoundException.class, blockFilter::popAll);
  }

  private void dispatchLogs(int count) {
    TransactionInfo.Log log = TransactionInfo.Log.newBuilder()
        .setAddress(ByteString.copyFrom(new byte[20])).build();
    TransactionInfo tx = TransactionInfo.newBuilder()
        .addAllLog(Collections.nCopies(count, log)).build();
    jsonRpc.handleLogsFilter(new LogsFilterCapsule(1, "01", null,
        Collections.singletonList(tx), solidity, false));
  }

  private LogFilterElement logElement() {
    return new LogFilterElement("00", 0L, "00", 0, "00", Collections.emptyList(),
        "", 0, false, 0);
  }

  private Map<String, LogFilterAndResult> logs() {
    return solidity ? jsonRpc.getEventFilter2ResultSolidity() : jsonRpc.getEventFilter2ResultFull();
  }

  private Map<String, BlockFilterAndResult> blocks() {
    return solidity ? jsonRpc.getBlockFilter2ResultSolidity() : jsonRpc.getBlockFilter2ResultFull();
  }

  private JsonNode request(String method, String id) throws Exception {
    JsonRpcServer server = new JsonRpcServer(jsonRpc, TronJsonRpc.class);
    server.setErrorResolver(JsonRpcErrorResolver.INSTANCE);
    ObjectNode request = mapper.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", 1);
    request.put("method", method);
    request.putArray("params").add(id);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    server.handleRequest(new ByteArrayInputStream(mapper.writeValueAsBytes(request)), output);
    return mapper.readTree(output.toByteArray());
  }

  private void assertRpcOverflow(String method, String id) throws Exception {
    JsonNode response = request(method, id);
    Assert.assertFalse(response.has("result"));
    Assert.assertEquals(1, response.get("id").asInt());
    Assert.assertEquals(-32005, response.at("/error/code").asInt());
    Assert.assertEquals("filter result limit exceeded; filter invalidated",
        response.at("/error/message").asText());
    Assert.assertEquals("FILTER_OVERFLOW", response.at("/error/data/reason").asText());
    Assert.assertEquals(FilterResult.MAX_RESULT_SIZE, response.at("/error/data/limit").asInt());
    Assert.assertThrows(JsonRpcFilterOverflowException.class, () -> jsonRpc.getFilterChanges(id));
  }
}
