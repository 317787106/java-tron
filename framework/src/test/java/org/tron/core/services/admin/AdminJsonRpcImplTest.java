package org.tron.core.services.admin;

import java.util.Arrays;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.tron.core.net.service.peermanagement.ActivePeerListResult;
import org.tron.core.net.service.peermanagement.PeerManagementService;
import org.tron.core.net.service.peermanagement.PeerOperationResult;

public class AdminJsonRpcImplTest {

  private PeerManagementService peerManagementService;
  private AdminJsonRpcImpl adminJsonRpc;

  @Before
  public void setUp() {
    peerManagementService = Mockito.mock(PeerManagementService.class);
    adminJsonRpc = new AdminJsonRpcImpl(peerManagementService);
  }

  @Test
  public void peerManagementMethodsDelegateToSharedService() throws Exception {
    PeerOperationResult peerResult = new PeerOperationResult(true, true, 1, "");
    PeerOperationResult blockedIpResult = new PeerOperationResult(true, true, 2, "");
    ActivePeerListResult activePeers = new ActivePeerListResult(0, 0, 0, 0,
        java.util.Collections.emptyList());
    Mockito.when(peerManagementService.addPeer("192.0.2.20:18888")).thenReturn(peerResult);
    Mockito.when(peerManagementService.removePeer("192.0.2.20:18888")).thenReturn(peerResult);
    Mockito.when(peerManagementService.disconnectPeer("192.0.2.20:18888"))
        .thenReturn(peerResult);
    Mockito.when(peerManagementService.listActivePeers()).thenReturn(activePeers);
    Mockito.when(peerManagementService.blockIp("192.0.2.20")).thenReturn(blockedIpResult);
    Mockito.when(peerManagementService.unblockIp("192.0.2.20")).thenReturn(blockedIpResult);
    Mockito.when(peerManagementService.listBlockedIps())
        .thenReturn(Arrays.asList("192.0.2.20"));

    Assert.assertSame(peerResult, adminJsonRpc.addPeer("192.0.2.20:18888"));
    Assert.assertSame(peerResult, adminJsonRpc.removePeer("192.0.2.20:18888"));
    Assert.assertSame(peerResult, adminJsonRpc.disconnectPeer("192.0.2.20:18888"));
    Assert.assertSame(activePeers, adminJsonRpc.listActivePeers());
    Assert.assertSame(blockedIpResult, adminJsonRpc.blockIp("192.0.2.20"));
    Assert.assertSame(blockedIpResult, adminJsonRpc.unblockIp("192.0.2.20"));
    Assert.assertEquals(Arrays.asList("192.0.2.20"), adminJsonRpc.listBlockedIps());
  }
}
