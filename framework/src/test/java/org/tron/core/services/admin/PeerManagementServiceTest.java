package org.tron.core.services.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.InetAddresses;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.db.CommonStore;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.net.TronNetService;
import org.tron.core.net.peer.ActivePeerInfo;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.peer.PeerManager;
import org.tron.p2p.P2pConfig;
import org.tron.p2p.P2pService;
import org.tron.p2p.connection.Channel;

public class PeerManagementServiceTest {

  private static final byte[] DB_KEY_BLOCKED_IPS =
      "blocked-ips".getBytes(StandardCharsets.UTF_8);

  private CommonStore commonStore;
  private PeerManagementService service;
  private boolean originalDynamicConfigEnable;

  @Before
  public void setUp() throws Exception {
    originalDynamicConfigEnable = Args.getInstance().isDynamicConfigEnable();
    Args.getInstance().setDynamicConfigEnable(false);
    commonStore = Mockito.mock(CommonStore.class);
    service = new PeerManagementService();
    Field commonStoreField = PeerManagementService.class.getDeclaredField("commonStore");
    commonStoreField.setAccessible(true);
    commonStoreField.set(service, commonStore);
  }

  @After
  public void tearDown() {
    Args.getInstance().setDynamicConfigEnable(originalDynamicConfigEnable);
  }

  @Test
  public void configureUsesEmptyBlockedIpsWhenKeyDoesNotExist() {
    P2pConfig config = new P2pConfig();
    Mockito.when(commonStore.has(AdditionalMatchers.aryEq(
        DB_KEY_BLOCKED_IPS))).thenReturn(false);

    service.configure(config);

    Assert.assertEquals(Collections.emptyList(), service.listBlockedIps());
    Assert.assertEquals(Collections.emptySet(), config.getBlockedIps());
    Mockito.verify(commonStore, Mockito.never()).get(Mockito.any(byte[].class));
  }

  @Test
  public void configureNormalizesDeduplicatesAndSortsBlockedIps() throws Exception {
    P2pConfig config = new P2pConfig();
    byte[] storedValue = ("[\"2001:db8::2\",\"192.0.2.2\",\"192.0.2.2\"]")
        .getBytes(StandardCharsets.UTF_8);
    Mockito.when(commonStore.has(AdditionalMatchers.aryEq(
        DB_KEY_BLOCKED_IPS))).thenReturn(true);
    Mockito.when(commonStore.get(AdditionalMatchers.aryEq(
        DB_KEY_BLOCKED_IPS)))
        .thenReturn(new BytesCapsule(storedValue));

    service.configure(config);

    Assert.assertEquals(Arrays.asList("192.0.2.2", "2001:db8:0:0:0:0:0:2"),
        service.listBlockedIps());
    Assert.assertEquals(2, config.getBlockedIps().size());
    Assert.assertTrue(config.getBlockedIps().contains(InetAddress.getByName("192.0.2.2")));
    Assert.assertTrue(config.getBlockedIps().contains(InetAddress.getByName("2001:db8::2")));
  }

  @Test
  public void configureDeletesInvalidBlockedIpsAndContinuesWithEmptySnapshot() {
    P2pConfig config = new P2pConfig();
    Mockito.when(commonStore.has(AdditionalMatchers.aryEq(
        DB_KEY_BLOCKED_IPS))).thenReturn(true);
    Mockito.when(commonStore.get(AdditionalMatchers.aryEq(
        DB_KEY_BLOCKED_IPS)))
        .thenReturn(new BytesCapsule("not-json".getBytes(StandardCharsets.UTF_8)));

    service.configure(config);

    Assert.assertEquals(Collections.emptyList(), service.listBlockedIps());
    Assert.assertEquals(Collections.emptySet(), config.getBlockedIps());
    Mockito.verify(commonStore).delete(AdditionalMatchers.aryEq(
        DB_KEY_BLOCKED_IPS));
  }

  @Test
  public void configureContinuesWhenInvalidBlockedIpsCannotBeDeleted() {
    P2pConfig config = new P2pConfig();
    Mockito.when(commonStore.has(AdditionalMatchers.aryEq(
        DB_KEY_BLOCKED_IPS))).thenReturn(true);
    Mockito.when(commonStore.get(AdditionalMatchers.aryEq(
        DB_KEY_BLOCKED_IPS)))
        .thenReturn(new BytesCapsule("[null]".getBytes(StandardCharsets.UTF_8)));
    Mockito.doThrow(new IllegalStateException("delete failed")).when(commonStore)
        .delete(AdditionalMatchers.aryEq(DB_KEY_BLOCKED_IPS));

    service.configure(config);

    Assert.assertEquals(Collections.emptyList(), service.listBlockedIps());
    Assert.assertEquals(Collections.emptySet(), config.getBlockedIps());
  }

  @Test
  public void configurePropagatesDatabaseReadFailureWithoutDeletingKey() {
    P2pConfig config = new P2pConfig();
    Mockito.when(commonStore.has(AdditionalMatchers.aryEq(DB_KEY_BLOCKED_IPS)))
        .thenReturn(true);
    Mockito.when(commonStore.get(AdditionalMatchers.aryEq(DB_KEY_BLOCKED_IPS)))
        .thenThrow(new IllegalStateException("database unavailable"));

    try {
      service.configure(config);
      Assert.fail("Expected a CommonStore read failure to stop initialization");
    } catch (IllegalStateException e) {
      Assert.assertEquals("database unavailable", e.getMessage());
    }

    Mockito.verify(commonStore, Mockito.never()).delete(Mockito.any());
  }

  @Test
  public void configureFailureResetsReadyState() throws Exception {
    service.configure(new P2pConfig());
    service.init();
    Mockito.when(commonStore.has(AdditionalMatchers.aryEq(DB_KEY_BLOCKED_IPS)))
        .thenReturn(true);
    Mockito.when(commonStore.get(AdditionalMatchers.aryEq(DB_KEY_BLOCKED_IPS)))
        .thenThrow(new IllegalStateException("database unavailable"));

    try {
      service.configure(new P2pConfig());
      Assert.fail("Expected a CommonStore read failure");
    } catch (IllegalStateException expected) {
      Assert.assertEquals("database unavailable", expected.getMessage());
    }

    try {
      service.disconnectPeer("192.0.2.20:18888");
      Assert.fail("Expected failed configuration to leave the service unavailable");
    } catch (JsonRpcInternalException expected) {
      Assert.assertEquals("P2P service is not ready", expected.getMessage());
    }
  }

  @Test
  public void configureDeletesBlockedIpListThatExceedsLimit() {
    stubStoredBlockedIps(buildBlockedIpJson(PeerManagementService.MAX_BLOCKED_IPS + 1));
    P2pConfig config = new P2pConfig();

    service.configure(config);

    Assert.assertEquals(Collections.emptyList(), service.listBlockedIps());
    Mockito.verify(commonStore).delete(AdditionalMatchers.aryEq(DB_KEY_BLOCKED_IPS));
  }

  @Test
  public void addPeerAddsOnlyActiveNodeAndIsIdempotent() throws Exception {
    P2pConfig config = configureAndInit();
    InetAddress trustedAddress = InetAddress.getByName("192.0.2.10");
    config.getTrustNodes().add(trustedAddress);
    P2pService p2pService = Mockito.mock(P2pService.class);
    Mockito.when(p2pService.addActiveNode(Mockito.any(InetSocketAddress.class)))
        .thenReturn(true, false);

    try (MockedStatic<TronNetService> tronNetService = mockP2pService(p2pService)) {
      PeerOperationResult first = service.addPeer("192.0.2.20:18888");
      PeerOperationResult second = service.addPeer("192.0.2.20:18888");

      Assert.assertTrue(first.isSuccess());
      Assert.assertTrue(first.isChanged());
      Assert.assertTrue(second.isSuccess());
      Assert.assertFalse(second.isChanged());
      Assert.assertEquals(Collections.singletonList(trustedAddress), config.getTrustNodes());
      Mockito.verify(p2pService, Mockito.times(2)).addActiveNode(
          new InetSocketAddress(InetAddress.getByName("192.0.2.20"), 18888));
    }
  }

  @Test
  public void addPeerRejectsManuallyBlockedIpBeforeCallingLibp2p() throws Exception {
    stubStoredBlockedIps("[\"192.0.2.20\"]");
    configureAndInit();
    P2pService p2pService = Mockito.mock(P2pService.class);

    try (MockedStatic<TronNetService> tronNetService = mockP2pService(p2pService)) {
      try {
        service.addPeer("192.0.2.20:18888");
        Assert.fail("Expected a blocked active node to be rejected");
      } catch (JsonRpcInternalException e) {
        Assert.assertTrue(e.getMessage().contains("manually blocked"));
      }
      Mockito.verify(p2pService, Mockito.never()).addActiveNode(Mockito.any());
    }
  }

  @Test
  public void dynamicConfigRejectsAddAndRemoveWithoutParsingOrSideEffects() throws Exception {
    Args.getInstance().setDynamicConfigEnable(true);
    P2pService p2pService = Mockito.mock(P2pService.class);

    try (MockedStatic<TronNetService> tronNetService = mockP2pService(p2pService)) {
      PeerOperationResult addResult = service.addPeer("not-an-endpoint");
      PeerOperationResult removeResult = service.removePeer("not-an-endpoint");

      Assert.assertFalse(addResult.isSuccess());
      Assert.assertFalse(addResult.isChanged());
      Assert.assertEquals(0, addResult.getDisconnectedCount());
      Assert.assertTrue(addResult.getMessage().contains("node.dynamicConfig.enable"));
      Assert.assertFalse(removeResult.isSuccess());
      Assert.assertEquals(addResult.getMessage(), removeResult.getMessage());
      Mockito.verifyNoInteractions(p2pService);
    }
  }

  @Test
  public void removePeerRemovesActiveNodeAndDisconnectsEndpoint() throws Exception {
    configureAndInit();
    P2pService p2pService = Mockito.mock(P2pService.class);
    Mockito.when(p2pService.removeActiveNode(Mockito.any(InetSocketAddress.class)))
        .thenReturn(true);
    Mockito.when(p2pService.disconnect(Mockito.any(InetSocketAddress.class))).thenReturn(1);

    try (MockedStatic<TronNetService> tronNetService = mockP2pService(p2pService)) {
      PeerOperationResult result = service.removePeer("[2001:db8::20]:18888");

      Assert.assertTrue(result.isSuccess());
      Assert.assertTrue(result.isChanged());
      Assert.assertEquals(1, result.getDisconnectedCount());
      Mockito.verify(p2pService).removeActiveNode(
          new InetSocketAddress(InetAddress.getByName("2001:db8::20"), 18888));
      Mockito.verify(p2pService).disconnect(
          new InetSocketAddress(InetAddress.getByName("2001:db8::20"), 18888));
    }
  }

  @Test
  public void disconnectPeerDoesNotModifyActiveNodes() throws Exception {
    configureAndInit();
    P2pService p2pService = Mockito.mock(P2pService.class);
    Mockito.when(p2pService.disconnect(Mockito.any(InetSocketAddress.class))).thenReturn(1);

    try (MockedStatic<TronNetService> tronNetService = mockP2pService(p2pService)) {
      PeerOperationResult result = service.disconnectPeer("192.0.2.20:18888");

      Assert.assertTrue(result.isSuccess());
      Assert.assertFalse(result.isChanged());
      Assert.assertEquals(1, result.getDisconnectedCount());
      Mockito.verify(p2pService, Mockito.never()).removeActiveNode(Mockito.any());
    }
  }

  @Test
  public void peerOperationsRejectDomainsAndUnbracketedIpv6() throws Exception {
    assertInvalidEndpoint("peer.example:18888");
    assertInvalidEndpoint("2001:db8::20:18888");
    assertInvalidEndpoint("192.0.2.20:0");
    assertInvalidEndpoint("192.0.2.20:+18888");
    assertInvalidEndpoint("[192.0.2.20]:18888");
  }

  @Test
  public void blockIpRejectsEndpointDomainAndOverlongInput() throws Exception {
    configureAndInit();
    assertInvalidIp("192.0.2.20:18888");
    assertInvalidIp("peer.example");
    assertInvalidIp("1111111111111111111111111111111111111111111111");
  }

  @Test
  public void blockIpRejectsNewEntryWhenLimitIsReached() throws Exception {
    stubStoredBlockedIps(buildBlockedIpJson(PeerManagementService.MAX_BLOCKED_IPS));
    configureAndInit();
    P2pService p2pService = Mockito.mock(P2pService.class);

    try (MockedStatic<TronNetService> tronNetService = mockP2pService(p2pService)) {
      try {
        service.blockIp("192.0.2.20");
        Assert.fail("Expected the blocked IP limit to be enforced");
      } catch (JsonRpcInvalidParamsException e) {
        Assert.assertTrue(e.getMessage().contains("limit"));
      }
      Mockito.verify(commonStore, Mockito.never()).put(Mockito.any(), Mockito.any());
      Mockito.verify(p2pService, Mockito.never()).replaceBlockedIps(Mockito.anySet());
    }
  }

  @Test
  public void blockIpPersistsBeforeApplyingAndPublishesSnapshot() throws Exception {
    configureAndInit();
    P2pService p2pService = Mockito.mock(P2pService.class);
    Mockito.when(p2pService.replaceBlockedIps(Mockito.anySet())).thenReturn(2);

    try (MockedStatic<TronNetService> tronNetService = mockP2pService(p2pService)) {
      PeerOperationResult result = service.blockIp("2001:db8::20");

      Assert.assertTrue(result.isSuccess());
      Assert.assertTrue(result.isChanged());
      Assert.assertEquals(2, result.getDisconnectedCount());
      Assert.assertEquals(Collections.singletonList("2001:db8:0:0:0:0:0:20"),
          service.listBlockedIps());

      ArgumentCaptor<BytesCapsule> capsuleCaptor = ArgumentCaptor.forClass(BytesCapsule.class);
      Mockito.verify(commonStore).put(AdditionalMatchers.aryEq(
          DB_KEY_BLOCKED_IPS), capsuleCaptor.capture());
      JsonNode stored = new ObjectMapper().readTree(capsuleCaptor.getValue().getData());
      Assert.assertEquals("2001:db8:0:0:0:0:0:20", stored.get(0).asText());
      Mockito.verify(p2pService).replaceBlockedIps(Mockito.argThat(
          addresses -> addresses.contains(InetAddresses.forString("2001:db8::20"))));
    }
  }

  @Test
  public void repeatedBlockAndUnblockAreIdempotent() throws Exception {
    stubStoredBlockedIps("[\"192.0.2.20\"]");
    configureAndInit();
    P2pService p2pService = Mockito.mock(P2pService.class);

    try (MockedStatic<TronNetService> tronNetService = mockP2pService(p2pService)) {
      PeerOperationResult blockResult = service.blockIp("192.0.2.20");
      PeerOperationResult firstUnblock = service.unblockIp("192.0.2.20");
      PeerOperationResult secondUnblock = service.unblockIp("192.0.2.20");

      Assert.assertFalse(blockResult.isChanged());
      Assert.assertTrue(firstUnblock.isChanged());
      Assert.assertFalse(secondUnblock.isChanged());
      Assert.assertEquals(Collections.emptyList(), service.listBlockedIps());
      Mockito.verify(commonStore, Mockito.times(1)).put(Mockito.any(), Mockito.any());
      Mockito.verify(p2pService, Mockito.times(1)).replaceBlockedIps(Collections.emptySet());
    }
  }

  @Test
  public void databaseFailureLeavesRuntimeSnapshotsUnchanged() throws Exception {
    configureAndInit();
    P2pService p2pService = Mockito.mock(P2pService.class);
    Mockito.doThrow(new IllegalStateException("write failed")).when(commonStore)
        .put(Mockito.any(), Mockito.any());

    try (MockedStatic<TronNetService> tronNetService = mockP2pService(p2pService)) {
      try {
        service.blockIp("192.0.2.20");
        Assert.fail("Expected persistence failure");
      } catch (JsonRpcInternalException e) {
        Assert.assertEquals("Failed to persist blocked IP snapshot", e.getMessage());
      }
      Assert.assertEquals(Collections.emptyList(), service.listBlockedIps());
      Mockito.verify(p2pService, Mockito.never()).replaceBlockedIps(Mockito.anySet());
    }
  }

  @Test
  public void libp2pFailureKeepsPersistedIntentWithoutPublishingJavaSnapshot() throws Exception {
    configureAndInit();
    P2pService p2pService = Mockito.mock(P2pService.class);
    Mockito.doThrow(new IllegalStateException("apply failed")).when(p2pService)
        .replaceBlockedIps(Mockito.anySet());

    try (MockedStatic<TronNetService> tronNetService = mockP2pService(p2pService)) {
      try {
        service.blockIp("192.0.2.20");
        Assert.fail("Expected libp2p update failure");
      } catch (JsonRpcInternalException e) {
        Assert.assertEquals("Failed to apply blocked IP snapshot", e.getMessage());
      }
      Mockito.verify(commonStore).put(Mockito.any(), Mockito.any());
      Assert.assertEquals(Collections.emptyList(), service.listBlockedIps());
    }
  }

  @Test(timeout = 5_000)
  public void concurrentBlockRequestsPublishACompleteCombinedSnapshot() throws Exception {
    configureAndInit();
    P2pService p2pService = Mockito.mock(P2pService.class);
    Field p2pServiceField = TronNetService.class.getDeclaredField("p2pService");
    p2pServiceField.setAccessible(true);
    P2pService originalP2pService = (P2pService) p2pServiceField.get(null);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      p2pServiceField.set(null, p2pService);
      Future<PeerOperationResult> first = executor.submit(() -> {
        start.await();
        return service.blockIp("192.0.2.20");
      });
      Future<PeerOperationResult> second = executor.submit(() -> {
        start.await();
        return service.blockIp("192.0.2.21");
      });

      start.countDown();
      Assert.assertTrue(first.get().isChanged());
      Assert.assertTrue(second.get().isChanged());
      Assert.assertEquals(Arrays.asList("192.0.2.20", "192.0.2.21"),
          service.listBlockedIps());

      ArgumentCaptor<BytesCapsule> values = ArgumentCaptor.forClass(BytesCapsule.class);
      Mockito.verify(commonStore, Mockito.times(2))
          .put(AdditionalMatchers.aryEq(DB_KEY_BLOCKED_IPS), values.capture());
      List<BytesCapsule> storedValues = values.getAllValues();
      JsonNode finalStoredValue = new ObjectMapper()
          .readTree(storedValues.get(storedValues.size() - 1).getData());
      Assert.assertEquals(2, finalStoredValue.size());
      Mockito.verify(p2pService, Mockito.times(2)).replaceBlockedIps(Mockito.anySet());
    } finally {
      p2pServiceField.set(null, originalP2pService);
      executor.shutdownNow();
      executor.awaitTermination(1, TimeUnit.SECONDS);
    }
  }

  @Test
  public void writeOperationsFailClearlyBeforeP2pIsReady() throws Exception {
    service.configure(new P2pConfig());

    try {
      service.disconnectPeer("192.0.2.20:18888");
      Assert.fail("Expected the operation to require a ready P2P service");
    } catch (JsonRpcInternalException e) {
      Assert.assertEquals("P2P service is not ready", e.getMessage());
    }
  }

  @Test
  public void listActivePeersUsesStructuredPeerSnapshotsAndCurrentCounts() {
    PeerConnection activePeer = mockPeer(true, true, "192.0.2.20:18888", 42L);
    PeerConnection passivePeer = mockPeer(false, false, "192.0.2.21:18888", 84L);

    try (MockedStatic<PeerManager> peerManager = Mockito.mockStatic(PeerManager.class)) {
      peerManager.when(PeerManager::getPeers).thenReturn(Arrays.asList(activePeer, passivePeer));

      ActivePeerListResult result = service.listActivePeers();

      Assert.assertEquals(2, result.getAllCount());
      Assert.assertEquals(1, result.getActiveCount());
      Assert.assertEquals(1, result.getPassiveCount());
      Assert.assertEquals(1, result.getValidCount());
      Assert.assertEquals(2, result.getPeers().size());
      Assert.assertEquals("192.0.2.20:18888", result.getPeers().get(0).getRemoteAddress());
      Assert.assertEquals(42L, result.getPeers().get(0).getAverageLatencyMillis());
    }
  }

  @Test
  public void listActivePeersHandlesEmptyPeerSet() {
    try (MockedStatic<PeerManager> peerManager = Mockito.mockStatic(PeerManager.class)) {
      peerManager.when(PeerManager::getPeers).thenReturn(Collections.emptyList());

      ActivePeerListResult result = service.listActivePeers();

      Assert.assertEquals(0, result.getAllCount());
      Assert.assertEquals(0, result.getActiveCount());
      Assert.assertEquals(0, result.getPassiveCount());
      Assert.assertEquals(0, result.getValidCount());
      Assert.assertEquals(Collections.emptyList(), result.getPeers());
    }
  }

  private P2pConfig configureAndInit() {
    P2pConfig config = new P2pConfig();
    service.configure(config);
    service.init();
    return config;
  }

  private void stubStoredBlockedIps(String value) {
    Mockito.when(commonStore.has(AdditionalMatchers.aryEq(
        DB_KEY_BLOCKED_IPS))).thenReturn(true);
    Mockito.when(commonStore.get(AdditionalMatchers.aryEq(
        DB_KEY_BLOCKED_IPS)))
        .thenReturn(new BytesCapsule(value.getBytes(StandardCharsets.UTF_8)));
  }

  private MockedStatic<TronNetService> mockP2pService(P2pService p2pService) {
    MockedStatic<TronNetService> tronNetService = Mockito.mockStatic(TronNetService.class);
    tronNetService.when(TronNetService::getP2pService).thenReturn(p2pService);
    return tronNetService;
  }

  private void assertInvalidEndpoint(String endpoint) throws Exception {
    try {
      service.addPeer(endpoint);
      Assert.fail("Expected invalid endpoint: " + endpoint);
    } catch (JsonRpcInvalidParamsException expected) {
      Assert.assertNotNull(expected.getMessage());
    }
  }

  private void assertInvalidIp(String ip) throws Exception {
    try {
      service.blockIp(ip);
      Assert.fail("Expected invalid IP: " + ip);
    } catch (JsonRpcInvalidParamsException expected) {
      Assert.assertNotNull(expected.getMessage());
    }
  }

  private String buildBlockedIpJson(int size) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < size; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append('"').append("2001:db8::").append(Integer.toHexString(i)).append('"');
    }
    return json.append(']').toString();
  }

  private PeerConnection mockPeer(boolean active, boolean syncFinished, String remoteAddress,
      long averageLatencyMillis) {
    PeerConnection peer = Mockito.mock(PeerConnection.class);
    Channel channel = Mockito.mock(Channel.class);
    ActivePeerInfo peerInfo = Mockito.mock(ActivePeerInfo.class);
    Mockito.when(channel.isActive()).thenReturn(active);
    Mockito.when(peer.getChannel()).thenReturn(channel);
    Mockito.when(peer.isSyncFinish()).thenReturn(syncFinished);
    Mockito.when(peer.getActivePeerInfo()).thenReturn(peerInfo);
    Mockito.when(peerInfo.getRemoteAddress()).thenReturn(remoteAddress);
    Mockito.when(peerInfo.getAverageLatencyMillis()).thenReturn(averageLatencyMillis);
    return peer;
  }

}
