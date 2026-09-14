package org.tron.core.net.service.fetchblock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.config.Parameter.NetConstants;
import org.tron.core.exception.P2pException;
import org.tron.core.exception.P2pException.TypeEnum;
import org.tron.core.net.PeerBlockTestSupport;
import org.tron.core.net.TronNetDelegate;
import org.tron.core.net.message.adv.BlockMessage;
import org.tron.core.net.message.adv.FetchInvDataMessage;
import org.tron.core.net.message.adv.InventoryMessage;
import org.tron.core.net.messagehandler.BlockMsgHandler;
import org.tron.core.net.messagehandler.InventoryMsgHandler;
import org.tron.core.net.peer.Item;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.peer.PeerStatusCheck;
import org.tron.core.net.service.adv.AdvService;
import org.tron.protos.Protocol.Inventory.InventoryType;
import org.tron.protos.Protocol.ReasonCode;

public class FetchBlockRetryTest {

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
  public void testShortTimeoutWithoutAlternativeRetainsState() throws Exception {
    beginAgedFetch(2_000);
    Object state = state();

    tick(state);

    Assert.assertSame(state, state());
    Assert.assertTrue(first.getAdvInvRequest().containsKey(item));
    verify(second, never()).sendMessage(any());
  }

  @Test
  public void testLateInventoryBypassesCacheForProviderSwitch() throws Exception {
    inventoryHandler.processMessage(first, inventory());
    Assert.assertNotNull(state());
    Assert.assertFalse(adv.addInv(item));
    ageState(2_000);
    long originalRequest = first.getAdvInvRequest().get(item);
    second.getAdvInvRequest().put(new Item(Sha256Hash.ZERO_HASH, InventoryType.TRX),
        System.currentTimeMillis());

    inventoryHandler.processMessage(second, inventory());
    tick(state());

    verify(second).sendMessage(any(FetchInvDataMessage.class));
    Assert.assertNotNull(state());
    Assert.assertSame(second, ReflectUtils.getFieldObject(state(), "peer"));
    Assert.assertEquals(Long.valueOf(originalRequest), first.getAdvInvRequest().get(item));
    Assert.assertTrue(second.getAdvInvRequest().containsKey(item));
    Assert.assertEquals(1, first.getLastInteractiveTime());
    Assert.assertEquals(1, second.getLastInteractiveTime());
    Assert.assertEquals(0, second.getBlockRcvTime());
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
  public void testSameSelectorExcludesBusySyncingAndDisconnectedPeers() {
    first.getAdvInvReceive().put(item, System.currentTimeMillis());
    second.getAdvInvReceive().put(item, System.currentTimeMillis());
    first.getSyncBlockInProcess().add(block.getParentBlockId());
    Assert.assertSame(second,
        fetch.selectBlockPeer(peers, item, System.currentTimeMillis()).get());
    second.setNeedSyncFromPeer(true);
    Assert.assertFalse(fetch.selectBlockPeer(peers, item, System.currentTimeMillis()).isPresent());
    second.setNeedSyncFromPeer(false);
    when(second.getChannel().isDisconnect()).thenReturn(true);
    Assert.assertFalse(fetch.selectBlockPeer(peers, item, System.currentTimeMillis()).isPresent());
  }

  @Test
  public void testSelectorRejectsStaleInventory() {
    first.getAdvInvReceive().put(item,
        System.currentTimeMillis() - NetConstants.ADV_TIME_OUT - 1_000);
    Assert.assertFalse(fetch.selectBlockPeer(peers, item, System.currentTimeMillis()).isPresent());
  }

  @Test
  public void testOnlyOriginalProviderGetsFinalTimeout() throws Exception {
    beginAgedFetch(2_000);
    second.getAdvInvReceive().put(item, System.currentTimeMillis());
    tick(state());
    first.getAdvInvRequest().put(item,
        System.currentTimeMillis() - NetConstants.ADV_TIME_OUT - 1_000);
    PeerStatusCheck status = new PeerStatusCheck();
    ReflectUtils.setFieldValue(status, "tronNetDelegate", delegate);
    try {
      status.statusCheck();
    } finally {
      status.close();
    }

    verify(first).disconnect(ReasonCode.TIME_OUT);
    verify(second, never()).disconnect(any());
    Assert.assertTrue(first.getAdvInvRequest().containsKey(item));
    Assert.assertTrue(second.getAdvInvRequest().containsKey(item));
  }

  @Test
  public void testFinalDeadlineDoesNotDiscardResponsibility() throws Exception {
    beginAgedFetch(NetConstants.ADV_TIME_OUT + 1_000);
    Object state = state();

    tick(state);

    Assert.assertSame(state, state());
    Assert.assertTrue(first.getAdvInvRequest().containsKey(item));
    verify(first, never()).disconnect(any());
  }

  @Test
  public void testDisconnectInvalidatesCacheWhenNoAlternativeExists() throws Exception {
    inventoryHandler.processMessage(first, inventory());
    disconnect(first);
    Assert.assertNull(state());

    second.getAdvInvReceive().put(item, System.currentTimeMillis());
    Assert.assertTrue(adv.addInv(item));
    verify(second).sendMessage(any(FetchInvDataMessage.class));
  }

  @Test
  public void testInvalidBlockDisconnectRetriesFromAlternative() throws Exception {
    inventoryHandler.processMessage(first, inventory());
    inventoryHandler.processMessage(second, inventory());
    BlockMsgHandler handler = new BlockMsgHandler();
    ReflectUtils.setFieldValue(handler, "tronNetDelegate", delegate);
    ReflectUtils.setFieldValue(handler, "fetchBlockService", fetch);
    ReflectUtils.setFieldValue(handler, "fastForward", false);
    when(delegate.validBlock(block))
        .thenThrow(new P2pException(TypeEnum.BLOCK_MERKLE_INVALID, "bad"));
    try {
      handler.processMessage(first, new BlockMessage(block));
      Assert.fail("Expected a bad Merkle root to be rejected");
    } catch (P2pException e) {
      Assert.assertEquals(TypeEnum.BLOCK_MERKLE_INVALID, e.getType());
    }
    Assert.assertTrue(first.getAdvInvRequest().containsKey(item));

    disconnect(first);

    Assert.assertTrue(second.getAdvInvRequest().containsKey(item));
    verify(second).sendMessage(any(FetchInvDataMessage.class));
    Assert.assertNotNull(state());
  }

  @Test
  public void testOriginalDisconnectDoesNotDuplicateOutstandingBackupRequest() throws Exception {
    beginAgedFetch(2_000);
    second.getAdvInvReceive().put(item, System.currentTimeMillis());
    tick(state());
    Object backupState = state();

    disconnect(first);

    Assert.assertSame(backupState, state());
    verify(second, times(1)).sendMessage(any(FetchInvDataMessage.class));
  }

  @Test
  public void testBackupDisconnectRestoresTrackingOfOriginalRequest() throws Exception {
    beginAgedFetch(2_000);
    second.getAdvInvReceive().put(item, System.currentTimeMillis());
    tick(state());

    disconnect(second);

    Assert.assertNotNull(state());
    Assert.assertSame(first, ReflectUtils.getFieldObject(state(), "peer"));
    verify(first, never()).sendMessage(any());
  }

  @Test
  public void testStaleWorkerCannotOverwriteNewFetch() throws Exception {
    beginAgedFetch(2_000);
    Object oldState = state();
    fetch.blockFetchSuccess(item.getHash());
    Item next = new Item(PeerBlockTestSupport.block(block.getNum() + 1).getBlockId(),
        InventoryType.BLOCK);
    when(chain.getHeadBlockNum()).thenReturn(block.getNum());
    second.getAdvInvRequest().put(next, System.currentTimeMillis());
    fetch.fetchBlock(Collections.singletonList(next.getHash()), second);
    Object newState = state();

    tick(oldState);
    fetch.blockFetchSuccess(item.getHash());

    Assert.assertSame(newState, state());
    verify(second, never()).sendMessage(any());
  }

  @Test
  public void testImmediateFirstResponseDoesNotResurrectCompletedFetch() throws Exception {
    doAnswer(call -> {
      Assert.assertNotNull(state());
      fetch.blockFetchSuccess(item.getHash());
      first.getAdvInvRequest().remove(item);
      return null;
    }).when(first).sendMessage(any(FetchInvDataMessage.class));

    inventoryHandler.processMessage(first, inventory());

    Assert.assertNull(state());
  }

  @Test
  public void testImmediateBackupResponseDoesNotResurrectCompletedFetch() throws Exception {
    beginAgedFetch(2_000);
    second.getAdvInvReceive().put(item, System.currentTimeMillis());
    doAnswer(call -> {
      fetch.blockFetchSuccess(item.getHash());
      second.getAdvInvRequest().remove(item);
      return null;
    }).when(second).sendMessage(any(FetchInvDataMessage.class));

    tick(state());

    Assert.assertNull(state());
    Assert.assertTrue(first.getAdvInvRequest().containsKey(item));
  }

  private InventoryMessage inventory() {
    return new InventoryMessage(Collections.singletonList(item.getHash()), InventoryType.BLOCK);
  }

  @Test
  public void testNewHeadReplacesObsoleteFetchWithoutLosingOriginalRequest() {
    beginAgedFetch(2_000);
    Item next = new Item(PeerBlockTestSupport.block(block.getNum() + 1).getBlockId(),
        InventoryType.BLOCK);
    when(chain.getHeadBlockNum()).thenReturn(block.getNum());
    second.getAdvInvRequest().put(next, System.currentTimeMillis());

    fetch.fetchBlock(Collections.singletonList(next.getHash()), second);

    Assert.assertEquals(next.getHash(), ReflectUtils.getFieldObject(state(), "hash"));
    Assert.assertTrue(first.getAdvInvRequest().containsKey(item));
  }

  @Test
  public void testWorkerStopsObsoleteFetchAfterHeadAdvances() throws Exception {
    beginAgedFetch(2_000);
    when(chain.getHeadBlockNum()).thenReturn(block.getNum());

    tick(state());

    Assert.assertNull(state());
    Assert.assertTrue(first.getAdvInvRequest().containsKey(item));
  }

  private void beginAgedFetch(long age) {
    first.getAdvInvRequest().put(item, System.currentTimeMillis() - age);
    fetch.fetchBlock(Collections.singletonList(item.getHash()), first);
    Assert.assertNotNull(state());
  }

  private void ageState(long age) {
    long time = System.currentTimeMillis() - age;
    ReflectUtils.setFieldValue(state(), "time", time);
    first.getAdvInvRequest().put(item, time);
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

  private void disconnect(PeerConnection peer) {
    when(peer.getChannel().isDisconnect()).thenReturn(true);
    peers.remove(peer);
    adv.onDisconnect(peer);
  }
}
