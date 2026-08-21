package org.tron.core.services.admin;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.net.service.peermanagement.ActivePeerListResult;
import org.tron.core.net.service.peermanagement.PeerManagementService;
import org.tron.core.net.service.peermanagement.PeerOperationResult;

@Component
public class AdminJsonRpcImpl implements AdminJsonRpc {

  private final PeerManagementService peerManagementService;

  @Autowired
  public AdminJsonRpcImpl(@Autowired PeerManagementService peerManagementService) {
    this.peerManagementService = peerManagementService;
  }

  @Override
  public PeerOperationResult addPeer(String endpoint)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    return peerManagementService.addPeer(endpoint);
  }

  @Override
  public PeerOperationResult removePeer(String endpoint)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    return peerManagementService.removePeer(endpoint);
  }

  @Override
  public PeerOperationResult disconnectPeer(String endpoint)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    return peerManagementService.disconnectPeer(endpoint);
  }

  @Override
  public ActivePeerListResult listActivePeers() {
    return peerManagementService.listActivePeers();
  }

  @Override
  public PeerOperationResult blockIp(String ip)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    return peerManagementService.blockIp(ip);
  }

  @Override
  public PeerOperationResult unblockIp(String ip)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    return peerManagementService.unblockIp(ip);
  }

  @Override
  public List<String> listBlockedIps() {
    return peerManagementService.listBlockedIps();
  }
}
