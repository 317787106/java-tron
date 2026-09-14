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
import org.tron.core.net.peer.PeerConnection;
import org.tron.protos.Protocol.ReasonCode;

public class SyncBlockContributionTest {

  private SyncService sync;
  private TronNetDelegate delegate;
  private PeerConnection provider;
  private PeerConnection other;
  private BlockCapsule block;

  @Before
  public void setUp() {
    sync = new SyncService();
    delegate = mock(TronNetDelegate.class);
    provider = PeerBlockTestSupport.peer(18888);
    other = PeerBlockTestSupport.peer(18889);
    block = PeerBlockTestSupport.block(10);
    provider.getSyncBlockToFetch().add(block.getBlockId());
    other.getSyncBlockToFetch().add(block.getBlockId());
    when(delegate.getActivePeer()).thenReturn(Arrays.asList(provider, other));
    when(delegate.getHeadBlockId()).thenReturn(block.getParentBlockId());
    ReflectUtils.setFieldValue(sync, "tronNetDelegate", delegate);
    ReflectUtils.setFieldValue(sync, "pbftDataSyncHandler", mock(PbftDataSyncHandler.class));
  }

  @After
  public void tearDown() {
    sync.close();
  }

  @Test
  public void testOnlyValidatedProviderGetsTimestamps() throws Exception {
    process();
    Assert.assertTrue(provider.getLastInteractiveTime() > 1);
    Assert.assertTrue(provider.getBlockRcvTime() > 0);
    Assert.assertEquals(1, other.getLastInteractiveTime());
    Assert.assertEquals(0, other.getBlockRcvTime());
  }

  @Test
  public void testInvalidSignatureOnlyBlamesProvider() throws Exception {
    doThrow(new P2pException(TypeEnum.BLOCK_SIGN_INVALID, "bad signature"))
        .when(delegate).validSignature(block);
    process();
    verify(provider).disconnect(ReasonCode.BAD_BLOCK);
    verify(other, never()).disconnect(any());
    Assert.assertEquals(1, provider.getLastInteractiveTime());
    Assert.assertEquals(0, provider.getBlockRcvTime());
  }

  @Test
  public void testStateFailureDoesNotBanPeersOrImproveTimestamps() throws Exception {
    doThrow(new P2pException(TypeEnum.BAD_BLOCK, "state failure"))
        .when(delegate).processBlock(block, true);
    process();
    verify(provider).disconnect(ReasonCode.SYNC_FAIL);
    verify(other).disconnect(ReasonCode.SYNC_FAIL);
    verify(provider, never()).disconnect(ReasonCode.BAD_BLOCK);
    verify(other, never()).disconnect(ReasonCode.BAD_BLOCK);
    Assert.assertEquals(1, provider.getLastInteractiveTime());
    Assert.assertEquals(0, provider.getBlockRcvTime());
  }

  @Test
  public void testShutdownDoesNotImproveTimestamps() throws Exception {
    when(delegate.isHitDown()).thenReturn(true);
    process();
    Assert.assertEquals(1, provider.getLastInteractiveTime());
    Assert.assertEquals(0, provider.getBlockRcvTime());
  }

  @Test
  public void testOldSyncBlockDoesNotGetContribution() throws Exception {
    when(delegate.getHeadBlockId()).thenReturn(PeerBlockTestSupport.block(11).getBlockId());
    process();
    Assert.assertEquals(0, provider.getBlockRcvTime());
  }

  @Test
  public void testKnownSyncBlockDoesNotGetContribution() throws Exception {
    when(delegate.containBlock(block.getBlockId())).thenReturn(true);
    process();
    Assert.assertEquals(0, provider.getBlockRcvTime());
  }

  private void process() throws Exception {
    Method method = SyncService.class.getDeclaredMethod("processSyncBlock",
        BlockCapsule.class, PeerConnection.class);
    method.setAccessible(true);
    method.invoke(sync, block, provider);
  }
}
