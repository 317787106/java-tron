package org.tron.core.net.service.fetchblock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.codahale.metrics.MetricRegistry;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.config.Parameter.NetConstants;
import org.tron.core.metrics.MetricsKey;
import org.tron.core.metrics.MetricsUtil;
import org.tron.core.net.PeerBlockTestSupport;
import org.tron.core.net.TronNetDelegate;
import org.tron.core.net.message.adv.FetchInvDataMessage;
import org.tron.core.net.message.adv.InventoryMessage;
import org.tron.core.net.messagehandler.InventoryMsgHandler;
import org.tron.core.net.peer.Item;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.service.adv.AdvService;
import org.tron.protos.Protocol.Inventory.InventoryType;

public class BlockFetchSchedulingTest {

  private FetchBlockService fetch;
  private AdvService adv;
  private InventoryMsgHandler inventoryHandler;
  private TronNetDelegate delegate;
  private ChainBaseManager chain;
  private PeerConnection first;
  private PeerConnection second;
  private List<PeerConnection> peers;
  private BlockCapsule block;
  private Item item;
  private int previousTimeout;

  @Before
  public void setUp() {
    previousTimeout = CommonParameter.getInstance().fetchBlockTimeout;
    CommonParameter.getInstance().fetchBlockTimeout = 1_000;
    fetch = new FetchBlockService();
    adv = new AdvService();
    inventoryHandler = new InventoryMsgHandler();
    delegate = mock(TronNetDelegate.class);
    chain = mock(ChainBaseManager.class);
    first = PeerBlockTestSupport.peer(18888);
    second = PeerBlockTestSupport.peer(18889);
    peers = new ArrayList<>(Arrays.asList(first, second));
    block = PeerBlockTestSupport.block(85636071);
    item = new Item(block.getBlockId(), InventoryType.BLOCK);
    when(delegate.getActivePeer()).thenReturn(peers);
    when(delegate.getHeadBlockId()).thenReturn(block.getParentBlockId());
    when(chain.getHeadBlockNum()).thenReturn(block.getNum() - 1);
    ReflectUtils.setFieldValue(fetch, "tronNetDelegate", delegate);
    ReflectUtils.setFieldValue(fetch, "chainBaseManager", chain);
    ReflectUtils.setFieldValue(adv, "tronNetDelegate", delegate);
    ReflectUtils.setFieldValue(adv, "fetchBlockService", fetch);
    ReflectUtils.setFieldValue(inventoryHandler, "tronNetDelegate", delegate);
    ReflectUtils.setFieldValue(inventoryHandler, "advService", adv);
  }

  @After
  public void tearDown() {
    fetch.close();
    adv.close();
    CommonParameter.getInstance().fetchBlockTimeout = previousTimeout;
  }

  @Test
  public void testFirstProviderCanHavePendingTransactions() throws Exception {
    first.getAdvInvRequest().put(new Item(Sha256Hash.ZERO_HASH, InventoryType.TRX),
        System.currentTimeMillis());

    inventoryHandler.processMessage(first, inventory());

    Assert.assertTrue(first.getAdvInvRequest().containsKey(item));
    verify(first).sendMessage(any(FetchInvDataMessage.class));
  }

  @Test
  public void testInitialFetchPrefersFewerBatchRequestsOverLatency() {
    Item transaction = new Item(Sha256Hash.ZERO_HASH, InventoryType.TRX);
    long now = System.currentTimeMillis();
    first.getAdvInvReceive().put(transaction, now);
    first.getAdvInvReceive().put(item, now);
    second.getAdvInvReceive().put(item, now);
    Assert.assertTrue(adv.addInv(transaction));

    try (MockedStatic<MetricsUtil> metrics = mockBlockLatencies(first, 10L, second, 500L)) {
      Assert.assertTrue(adv.addInv(item));
    }

    assertBlockRequests(second, item);
    Assert.assertTrue(first.getAdvInvRequest().containsKey(transaction));
    Assert.assertFalse(first.getAdvInvRequest().containsKey(item));
    Assert.assertTrue(second.getAdvInvRequest().containsKey(item));
    Assert.assertSame(second, ReflectUtils.getFieldObject(state(), "peer"));
  }

  @Test
  public void testInitialFetchDoesNotPreferPeerWithoutLatencySamples() {
    long now = System.currentTimeMillis();
    first.getAdvInvReceive().put(item, now);
    second.getAdvInvReceive().put(item, now);

    try (MockedStatic<MetricsUtil> metrics = mockBlockLatencies(first, 500L, second, null)) {
      Assert.assertTrue(adv.addInv(item));
    }

    assertBlockRequests(first, item);
    verify(second, never()).sendMessage(any());
    Assert.assertSame(first, ReflectUtils.getFieldObject(state(), "peer"));
  }

  @Test
  public void testBackupFetchStillPrefersLowerLatency() throws Exception {
    PeerConnection faster = PeerBlockTestSupport.peer(18890);
    peers.add(faster);
    beginAgedFetch(2_000);
    Long originalRequestTime = first.getAdvInvRequest().get(item);
    long now = System.currentTimeMillis();
    second.getAdvInvReceive().put(item, now);
    faster.getAdvInvReceive().put(item, now);
    Item transaction = new Item(Sha256Hash.ZERO_HASH, InventoryType.TRX);
    faster.getAdvInvRequest().put(transaction, now);

    try (MockedStatic<MetricsUtil> metrics = mockBlockLatencies(second, 500L, faster, 10L)) {
      tick(state());
    }

    assertBlockRequests(faster, item);
    verify(second, never()).sendMessage(any());
    Assert.assertTrue(faster.getAdvInvRequest().containsKey(transaction));
    Assert.assertTrue(faster.getAdvInvRequest().containsKey(item));
    Assert.assertEquals(originalRequestTime, first.getAdvInvRequest().get(item));
  }

  @Test
  public void testSlowBackupWaitsForActualTimeout() throws Exception {
    // Leave enough time for assertions without relying on sleeps or a sub-second test run.
    ReflectUtils.setFieldValue(fetch, "fetchTimeOut", 10_000L);
    second.getAdvInvReceive().put(item, System.currentTimeMillis());

    try (MockedStatic<MetricsUtil> metrics = mockBlockLatencies(first, 15_000L, second, 15_000L)) {
      beginAgedFetch(100);
      Object originalState = state();
      tick(state());

      Assert.assertSame(originalState, state());
      Assert.assertFalse(second.getAdvInvRequest().containsKey(item));
      verify(second, never()).sendMessage(any());

      ageState(11_000);
      Long originalRequestTime = first.getAdvInvRequest().get(item);
      tick(state());

      assertBlockRequests(second, item);
      Assert.assertEquals(originalRequestTime, first.getAdvInvRequest().get(item));
    }
  }

  @Test
  public void testFasterBackupCanStartBeforeTimeout() throws Exception {
    ReflectUtils.setFieldValue(fetch, "fetchTimeOut", 10_000L);
    second.getAdvInvReceive().put(item, System.currentTimeMillis());

    try (MockedStatic<MetricsUtil> metrics = mockBlockLatencies(first, 8_000L, second, 100L)) {
      beginAgedFetch(100);
      Long originalRequestTime = first.getAdvInvRequest().get(item);
      tick(state());

      assertBlockRequests(second, item);
      Assert.assertTrue(System.currentTimeMillis() - originalRequestTime < 10_000);
      Assert.assertEquals(originalRequestTime, first.getAdvInvRequest().get(item));
    }
  }

  @Test
  public void testQueuedBlocksAreFetchedInHeightOrder() throws Exception {
    List<Item> blocks = queueBlocksInReverseHeightOrder();
    Item transaction = new Item(Sha256Hash.ZERO_HASH, InventoryType.TRX);
    first.getAdvInvRequest().put(transaction, System.currentTimeMillis());

    consumeInventory();

    assertBlockRequests(first, blocks.get(0));
    Assert.assertTrue(first.getAdvInvRequest().containsKey(blocks.get(0)));
    Assert.assertTrue(first.getAdvInvRequest().containsKey(transaction));
    Assert.assertFalse(first.getAdvInvRequest().containsKey(blocks.get(1)));
    Assert.assertFalse(pendingInventory().containsKey(blocks.get(0)));
    Assert.assertTrue(pendingInventory().containsKey(blocks.get(1)));
    Assert.assertEquals(blocks.get(0).getHash(), ReflectUtils.getFieldObject(state(), "hash"));
  }

  @Test
  public void testQueuedNextBlockFetchedAfterHeadAdvances() throws Exception {
    List<Item> blocks = queueBlocksInReverseHeightOrder();
    consumeInventory();
    Assert.assertTrue(first.getAdvInvRequest().containsKey(blocks.get(0)));
    Assert.assertFalse(first.getAdvInvRequest().containsKey(blocks.get(1)));

    fetch.blockFetchSuccess(blocks.get(0).getHash());
    first.getAdvInvRequest().remove(blocks.get(0));
    when(chain.getHeadBlockNum()).thenReturn(block.getNum());
    when(delegate.getHeadBlockId()).thenReturn(new BlockId(blocks.get(0).getHash()));

    consumeInventory();

    assertBlockRequests(first, blocks.get(0), blocks.get(1));
    Assert.assertFalse(first.getAdvInvRequest().containsKey(blocks.get(0)));
    Assert.assertTrue(first.getAdvInvRequest().containsKey(blocks.get(1)));
    Assert.assertTrue(pendingInventory().isEmpty());
    Assert.assertEquals(blocks.get(1).getHash(), ReflectUtils.getFieldObject(state(), "hash"));
  }

  @Test
  public void testOnlyHigherBlockCanStillBeFetched() throws Exception {
    Item higher = new Item(new BlockId(Sha256Hash.ZERO_HASH, block.getNum() + 1),
        InventoryType.BLOCK);

    inventoryHandler.processMessage(first,
        new InventoryMessage(Collections.singletonList(higher.getHash()), InventoryType.BLOCK));

    assertBlockRequests(first, higher);
    Assert.assertTrue(first.getAdvInvRequest().containsKey(higher));
    Assert.assertTrue(pendingInventory().isEmpty());
  }

  @Test
  public void testBlockEligibilityExcludesBusySyncingAndDisconnectedPeers() {
    first.getAdvInvReceive().put(item, System.currentTimeMillis());
    second.getAdvInvReceive().put(item, System.currentTimeMillis());
    first.getSyncBlockInProcess().add(block.getParentBlockId());
    Assert.assertFalse(fetch.canFetchBlock(first, item, System.currentTimeMillis()));
    Assert.assertTrue(fetch.canFetchBlock(second, item, System.currentTimeMillis()));
    second.setNeedSyncFromPeer(true);
    Assert.assertFalse(fetch.canFetchBlock(second, item, System.currentTimeMillis()));
    second.setNeedSyncFromPeer(false);
    second.setNeedSyncFromUs(true);
    Assert.assertFalse(fetch.canFetchBlock(second, item, System.currentTimeMillis()));
    second.setNeedSyncFromUs(false);
    when(second.getChannel().isDisconnect()).thenReturn(true);
    Assert.assertFalse(fetch.canFetchBlock(second, item, System.currentTimeMillis()));
  }

  @Test
  public void testBlockEligibilityRequiresRecentInventory() {
    Assert.assertFalse(fetch.canFetchBlock(first, item, System.currentTimeMillis()));
    first.getAdvInvReceive().put(item,
        System.currentTimeMillis() - NetConstants.ADV_TIME_OUT - 1_000);
    Assert.assertFalse(fetch.canFetchBlock(first, item, System.currentTimeMillis()));
  }

  private InventoryMessage inventory() {
    return new InventoryMessage(Collections.singletonList(item.getHash()), InventoryType.BLOCK);
  }

  private void beginAgedFetch(long age) {
    first.getAdvInvRequest().put(item, System.currentTimeMillis() - age);
    fetch.fetchBlock(Collections.singletonList(item.getHash()), first);
    Assert.assertNotNull(state());
    // The base registers tracking at send time; control elapsed time independently of retries.
    ageState(age);
  }

  private List<Item> queueBlocksInReverseHeightOrder() throws Exception {
    peers.remove(second);
    byte[] hash = new byte[Sha256Hash.LENGTH];
    hash[hash.length - 1] = 1;
    Item lower = new Item(new BlockId(hash, block.getNum()), InventoryType.BLOCK);
    Item higher = new Item(new BlockId(Sha256Hash.ZERO_HASH, block.getNum() + 1),
        InventoryType.BLOCK);
    Item busy = new Item(block.getParentBlockId(), InventoryType.BLOCK);
    first.getAdvInvRequest().put(busy, System.currentTimeMillis());

    inventoryHandler.processMessage(first,
        new InventoryMessage(Arrays.asList(lower.getHash(), higher.getHash()),
            InventoryType.BLOCK));

    verify(first, never()).sendMessage(any(FetchInvDataMessage.class));
    Assert.assertEquals(2, pendingInventory().size());
    // Assert the fixture order so the test cannot pass merely because the map visits lower first.
    Assert.assertEquals(Arrays.asList(higher, lower),
        new ArrayList<>(pendingInventory().keySet()));
    first.getAdvInvRequest().remove(busy);
    return Arrays.asList(lower, higher);
  }

  private Map<Item, Long> pendingInventory() {
    return (Map<Item, Long>) ReflectUtils.getFieldObject(adv, "invToFetch");
  }

  private void consumeInventory() throws Exception {
    Method method = AdvService.class.getDeclaredMethod("consumerInvToFetch");
    method.setAccessible(true);
    method.invoke(adv);
  }

  private void assertBlockRequests(PeerConnection peer, Item... blocks) {
    ArgumentCaptor<FetchInvDataMessage> requests =
        ArgumentCaptor.forClass(FetchInvDataMessage.class);
    verify(peer, times(blocks.length)).sendMessage(requests.capture());
    for (int i = 0; i < blocks.length; i++) {
      Assert.assertEquals(InventoryType.BLOCK, requests.getAllValues().get(i).getInventoryType());
      Assert.assertEquals(Collections.singletonList(blocks[i].getHash()),
          requests.getAllValues().get(i).getHashList());
    }
  }

  private MockedStatic<MetricsUtil> mockBlockLatencies(PeerConnection left, Long leftLatency,
      PeerConnection right, Long rightLatency) {
    return mockBlockLatencies(Arrays.asList(left, right), leftLatency, rightLatency);
  }

  private MockedStatic<MetricsUtil> mockBlockLatencies(List<PeerConnection> candidates,
      Long... latencies) {
    Assert.assertEquals(candidates.size(), latencies.length);
    MetricRegistry registry = new MetricRegistry();
    for (int i = 0; i < candidates.size(); i++) {
      PeerConnection peer = candidates.get(i);
      InetSocketAddress address = new InetSocketAddress("127.0.0." + (i + 2),
          peer.getInetSocketAddress().getPort());
      when(peer.getChannel().getInetAddress()).thenReturn(address.getAddress());
      when(peer.getChannel().getInetSocketAddress()).thenReturn(address);
      if (latencies[i] != null) {
        registry.histogram(MetricsKey.NET_LATENCY_FETCH_BLOCK + peer.getInetAddress())
            .update(latencies[i]);
      }
    }
    MockedStatic<MetricsUtil> metrics = mockStatic(MetricsUtil.class);
    metrics.when(() -> MetricsUtil.getHistogram(anyString()))
        .thenAnswer(call -> registry.histogram(call.getArgument(0)));
    return metrics;
  }

  private void ageState(long age) {
    long time = System.currentTimeMillis() - age;
    ReflectUtils.setFieldValue(state(), "time", time);
    PeerConnection provider = (PeerConnection) ReflectUtils.getFieldObject(state(), "peer");
    provider.getAdvInvRequest().put(item, time);
  }

  private Object state() {
    return ReflectUtils.getFieldObject(fetch, "fetchBlockInfo");
  }

  private void tick(Object state) throws Exception {
    Class<?> stateClass = Class.forName(FetchBlockService.class.getName() + "$FetchBlockInfo");
    Method method = FetchBlockService.class.getDeclaredMethod("fetchBlockProcess", stateClass);
    method.setAccessible(true);
    method.invoke(fetch, state);
  }

}
