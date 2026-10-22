package org.tron.core.net.service.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.exception.P2pException;
import org.tron.core.exception.P2pException.TypeEnum;
import org.tron.core.net.PeerBlockTestSupport;
import org.tron.core.net.TronNetDelegate;
import org.tron.core.net.messagehandler.PbftDataSyncHandler;
import org.tron.core.net.peer.Item;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.service.adv.AdvService;
import org.tron.protos.Protocol.Inventory.InventoryType;
import org.tron.protos.Protocol.ReasonCode;

public class SyncBlockInventoryActivityTest {

  private SyncService sync;
  private AdvService adv;
  private TronNetDelegate delegate;
  private PeerConnection provider;
  private PeerConnection other;
  private BlockCapsule block;

  @Before
  public void setUp() {
    sync = new SyncService();
    adv = new AdvService();
    delegate = mock(TronNetDelegate.class);
    provider = PeerBlockTestSupport.peer(18888);
    other = PeerBlockTestSupport.peer(18889);
    block = PeerBlockTestSupport.block(10);
    provider.getSyncBlockToFetch().add(block.getBlockId());
    other.getSyncBlockToFetch().add(block.getBlockId());
    when(delegate.getActivePeer()).thenReturn(Arrays.asList(provider, other));
    when(delegate.getHeadBlockId()).thenReturn(block.getParentBlockId());
    ReflectUtils.setFieldValue(sync, "tronNetDelegate", delegate);
    ReflectUtils.setFieldValue(adv, "tronNetDelegate", delegate);
    ReflectUtils.setFieldValue(sync, "advService", adv);
    ReflectUtils.setFieldValue(sync, "pbftDataSyncHandler", mock(PbftDataSyncHandler.class));
  }

  @After
  public void tearDown() {
    sync.close();
    adv.close();
  }

  @Test
  public void testInvalidSignatureOnlyBlamesProvider() throws Exception {
    adv.recordInventory(other, new Item(block.getBlockId(), InventoryType.BLOCK), 100);
    doThrow(new P2pException(TypeEnum.BLOCK_SIGN_INVALID, "bad signature"))
        .when(delegate).validSignature(block);
    process();
    verify(provider).disconnect(ReasonCode.BAD_BLOCK);
    verify(other, never()).disconnect(any());
    assertUnconfirmed();
  }

  @Test
  public void testStateFailureDoesNotConfirmInventory() throws Exception {
    adv.recordInventory(other, new Item(block.getBlockId(), InventoryType.BLOCK), 100);
    doThrow(new P2pException(TypeEnum.BAD_BLOCK, "state failure"))
        .when(delegate).processBlock(block, true);
    process();
    verify(provider).disconnect(ReasonCode.BAD_BLOCK);
    verify(other).disconnect(ReasonCode.BAD_BLOCK);
    verify(provider, never()).disconnect(ReasonCode.SYNC_FAIL);
    verify(other, never()).disconnect(ReasonCode.SYNC_FAIL);
    assertUnconfirmed();
  }

  @Test
  public void testShutdownDoesNotConfirmInventory() throws Exception {
    adv.recordInventory(other, new Item(block.getBlockId(), InventoryType.BLOCK), 100);
    when(delegate.isHitDown()).thenReturn(true);
    process();
    assertUnconfirmed();
  }

  @Test
  public void testSyncBlockConfirmsEligibleInventoryWithoutContribution() throws Exception {
    Item item = new Item(block.getBlockId(), InventoryType.BLOCK);
    adv.recordInventory(other, item, 100);
    Assert.assertEquals(1, other.getLastInteractiveTime());

    process();

    Assert.assertEquals(100, other.getLastInteractiveTime());
    Assert.assertEquals(0, other.getBlockRcvTime());
    Assert.assertNull(other.getAdvBlockInvReceive().getIfPresent(item));
  }

  private void assertUnconfirmed() {
    Assert.assertEquals(1, other.getLastInteractiveTime());
    Assert.assertEquals(0, other.getBlockRcvTime());
    Assert.assertEquals(Long.valueOf(100), other.getAdvBlockInvReceive()
        .getIfPresent(new Item(block.getBlockId(), InventoryType.BLOCK)));
  }

  private void process() throws Exception {
    Method method = SyncService.class.getDeclaredMethod("processSyncBlock",
        BlockCapsule.class, PeerConnection.class);
    method.setAccessible(true);
    method.invoke(sync, block, provider);
  }
}
