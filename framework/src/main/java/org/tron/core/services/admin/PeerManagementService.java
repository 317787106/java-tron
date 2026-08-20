package org.tron.core.services.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
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
import org.tron.p2p.utils.NetUtil;

@Slf4j(topic = "net")
@Component
public class PeerManagementService {

  static final int MAX_BLOCKED_IPS = 10_000;
  private static final byte[] DB_KEY_BLOCKED_IPS =
      "blocked-ips".getBytes(StandardCharsets.UTF_8);
  private static final int MAX_IP_LENGTH = 45;
  private static final int MAX_ENDPOINT_LENGTH = 64;
  private static final int MAX_BLOCKED_IPS_VALUE_BYTES = 1024 * 1024;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String DYNAMIC_CONFIG_MESSAGE =
      "Peer modification is unavailable while node.dynamicConfig.enable is true; "
          + "update node.active in the configuration file instead.";

  @Autowired
  private CommonStore commonStore;

  private final Object managementLock = new Object();
  private volatile List<InetAddress> blockedIps = Collections.emptyList();
  private volatile boolean ready;

  public void configure(P2pConfig p2pConfig) {
    ready = false;
    Objects.requireNonNull(p2pConfig, "p2pConfig must not be null");
    List<InetAddress> loadedBlockedIps = loadBlockedIps();
    blockedIps = immutableCopy(loadedBlockedIps);
    p2pConfig.setBlockedIps(new HashSet<>(loadedBlockedIps));
  }

  public void init() {
    ready = true;
  }

  public void close() {
    ready = false;
  }

  /**
   * Returns the normalized snapshot of IPs manually blocked through the Admin interface.
   */
  public List<String> listBlockedIps() {
    return blockedIps.stream()
        .map(InetAddress::getHostAddress)
        .collect(Collectors.toList());
  }

  /**
   * Returns current peer connections with the same core statistics used by peer logging.
   */
  public ActivePeerList listActivePeers() {
    List<PeerConnection> peers = PeerManager.getPeers();
    List<ActivePeerInfo> peerInfos = new ArrayList<>(peers.size());
    int activeCount = 0;
    int validCount = 0;
    for (PeerConnection peer : peers) {
      if (peer.getChannel().isActive()) {
        activeCount++;
      }
      if (peer.isSyncFinish()) {
        validCount++;
      }
      peerInfos.add(peer.getStatsSnapshot());
    }
    return new ActivePeerList(peers.size(), activeCount, peers.size() - activeCount,
        validCount, peerInfos);
  }

  /**
   * Adds an endpoint to libp2p's active-node set without adding it to the trusted-node set.
   */
  public PeerOperationResult addPeer(String endpoint)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    if (Args.getInstance().isDynamicConfigEnable()) {
      return peerModificationUnavailable();
    }
    InetSocketAddress address = parseEndpoint(endpoint);
    synchronized (managementLock) {
      P2pService p2pService = requireP2pService();
      if (blockedIps.contains(address.getAddress())) {
        throw new JsonRpcInternalException(
            "Cannot add active node because IP " + address.getAddress().getHostAddress()
                + " is manually blocked");
      }
      try {
        boolean changed = p2pService.addActiveNode(address);
        logger.info("Admin add active node {}, changed {}", address, changed);
        return operationSucceeded(changed, 0);
      } catch (IllegalArgumentException e) {
        throw new JsonRpcInvalidParamsException("Invalid peer endpoint", e);
      } catch (RuntimeException e) {
        logger.error("Failed to add active node ({})", e.getClass().getSimpleName());
        throw new JsonRpcInternalException("Failed to add active node", e);
      }
    }
  }

  /**
   * Removes an endpoint from the active-node set and disconnects its current connection.
   */
  public PeerOperationResult removePeer(String endpoint)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    if (Args.getInstance().isDynamicConfigEnable()) {
      return peerModificationUnavailable();
    }
    InetSocketAddress address = parseEndpoint(endpoint);
    synchronized (managementLock) {
      P2pService p2pService = requireP2pService();
      try {
        boolean changed = p2pService.removeActiveNode(address);
        int disconnectedCount = p2pService.disconnect(address);
        logger.info("Admin remove active node {}, changed {}, disconnected channels {}",
            address, changed, disconnectedCount);
        return operationSucceeded(changed, disconnectedCount);
      } catch (IllegalArgumentException e) {
        throw new JsonRpcInvalidParamsException("Invalid peer endpoint", e);
      } catch (RuntimeException e) {
        logger.error("Failed to remove active node ({})", e.getClass().getSimpleName());
        throw new JsonRpcInternalException("Failed to remove active node", e);
      }
    }
  }

  /**
   * Disconnects the exact endpoint without changing the configured active-node set.
   */
  public PeerOperationResult disconnectPeer(String endpoint)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    InetSocketAddress address = parseEndpoint(endpoint);
    P2pService p2pService = requireP2pService();
    try {
      int disconnectedCount = p2pService.disconnect(address);
      logger.info("Admin disconnect peer {}, disconnected channels {}",
          address, disconnectedCount);
      return operationSucceeded(false, disconnectedCount);
    } catch (IllegalArgumentException e) {
      throw new JsonRpcInvalidParamsException("Invalid peer endpoint", e);
    } catch (RuntimeException e) {
      logger.error("Failed to disconnect peer ({})", e.getClass().getSimpleName());
      throw new JsonRpcInternalException("Failed to disconnect peer", e);
    }
  }

  /**
   * Persists an IP in the manual blocklist and disconnects its existing TCP connections.
   */
  public PeerOperationResult blockIp(String ip)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    InetAddress address = parseIp(ip);
    synchronized (managementLock) {
      P2pService p2pService = requireP2pService();
      if (blockedIps.contains(address)) {
        return operationSucceeded(false, 0);
      }
      if (blockedIps.size() >= MAX_BLOCKED_IPS) {
        throw new JsonRpcInvalidParamsException(
            "Blocked IP limit of " + MAX_BLOCKED_IPS + " has been reached");
      }
      List<InetAddress> nextBlockedIps = new ArrayList<>(blockedIps);
      nextBlockedIps.add(address);
      nextBlockedIps = normalizeBlockedIps(nextBlockedIps);
      return replaceBlockedIps(nextBlockedIps, address, true, p2pService);
    }
  }

  /**
   * Removes an IP from the persistent manual blocklist and applies the updated snapshot.
   */
  public PeerOperationResult unblockIp(String ip)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    InetAddress address = parseIp(ip);
    synchronized (managementLock) {
      P2pService p2pService = requireP2pService();
      if (!blockedIps.contains(address)) {
        return operationSucceeded(false, 0);
      }
      List<InetAddress> nextBlockedIps = new ArrayList<>(blockedIps);
      nextBlockedIps.remove(address);
      return replaceBlockedIps(nextBlockedIps, address, false, p2pService);
    }
  }

  private List<InetAddress> loadBlockedIps() {
    if (!commonStore.has(DB_KEY_BLOCKED_IPS)) {
      return Collections.emptyList();
    }
    BytesCapsule capsule = commonStore.get(DB_KEY_BLOCKED_IPS);
    byte[] storedValue = capsule == null ? null : capsule.getData();
    try {
      return deserializeBlockedIps(storedValue);
    } catch (IOException e) {
      logger.warn("Invalid blocked IP data in CommonStore key blocked-ips; deleting it ({})",
          e.getClass().getSimpleName());
      deleteInvalidBlockedIps();
      return Collections.emptyList();
    }
  }

  private void deleteInvalidBlockedIps() {
    try {
      commonStore.delete(DB_KEY_BLOCKED_IPS);
    } catch (RuntimeException e) {
      logger.warn("Failed to delete invalid CommonStore key blocked-ips ({})",
          e.getClass().getSimpleName());
    }
  }

  private PeerOperationResult replaceBlockedIps(List<InetAddress> nextBlockedIps,
      InetAddress changedAddress, boolean blocked, P2pService p2pService)
      throws JsonRpcInternalException {
    saveBlockedIps(nextBlockedIps);
    int disconnectedCount;
    try {
      disconnectedCount = p2pService.replaceBlockedIps(new HashSet<>(nextBlockedIps));
    } catch (RuntimeException e) {
      logger.error("Failed to apply blocked IP snapshot ({})", e.getClass().getSimpleName());
      throw new JsonRpcInternalException("Failed to apply blocked IP snapshot", e);
    }
    blockedIps = immutableCopy(nextBlockedIps);
    logger.info("Admin {} IP {}, disconnected channels {}",
        blocked ? "blocked" : "unblocked", changedAddress.getHostAddress(), disconnectedCount);
    return operationSucceeded(true, disconnectedCount);
  }

  private void saveBlockedIps(List<InetAddress> addresses) throws JsonRpcInternalException {
    List<String> serializedAddresses = addresses.stream()
        .map(InetAddress::getHostAddress)
        .collect(Collectors.toList());
    try {
      byte[] serialized = OBJECT_MAPPER.writeValueAsBytes(serializedAddresses);
      commonStore.put(DB_KEY_BLOCKED_IPS, new BytesCapsule(serialized));
    } catch (RuntimeException | IOException e) {
      logger.error("Failed to save CommonStore key blocked-ips ({})",
          e.getClass().getSimpleName());
      throw new JsonRpcInternalException("Failed to persist blocked IP snapshot", e);
    }
  }

  private P2pService requireP2pService() throws JsonRpcInternalException {
    if (!ready) {
      throw new JsonRpcInternalException("P2P service is not ready");
    }
    P2pService p2pService = TronNetService.getP2pService();
    if (p2pService == null) {
      throw new JsonRpcInternalException("P2P service is not ready");
    }
    return p2pService;
  }

  private InetAddress parseIp(String ip) throws JsonRpcInvalidParamsException {
    if (StringUtils.isEmpty(ip) || ip.length() > MAX_IP_LENGTH
        || !InetAddresses.isInetAddress(ip)) {
      throw new JsonRpcInvalidParamsException("IP must be an IPv4 or IPv6 literal");
    }
    return InetAddresses.forString(ip);
  }

  private InetSocketAddress parseEndpoint(String endpoint)
      throws JsonRpcInvalidParamsException {
    if (StringUtils.isEmpty(endpoint) || endpoint.length() > MAX_ENDPOINT_LENGTH) {
      throw new JsonRpcInvalidParamsException(
          "Endpoint must use IPv4:port or [IPv6]:port format");
    }
    try {
      return NetUtil.parseInetSocketAddress(endpoint);
    } catch (IllegalArgumentException e) {
      throw new JsonRpcInvalidParamsException("Invalid peer endpoint", e);
    }
  }

  private PeerOperationResult operationSucceeded(boolean changed,
      int disconnectedCount) {
    return new PeerOperationResult(true, changed, disconnectedCount, "");
  }

  private PeerOperationResult peerModificationUnavailable() {
    return new PeerOperationResult(false, false, 0, DYNAMIC_CONFIG_MESSAGE);
  }

  private List<InetAddress> deserializeBlockedIps(byte[] value) throws IOException {
    if (value == null || value.length == 0 || value.length > MAX_BLOCKED_IPS_VALUE_BYTES) {
      throw new IOException("Invalid blocked IP value size");
    }
    JsonNode root = OBJECT_MAPPER.readTree(value);
    if (root == null || !root.isArray() || root.size() > MAX_BLOCKED_IPS) {
      throw new IOException("Invalid blocked IP value structure");
    }
    List<InetAddress> addresses = new ArrayList<>(root.size());
    for (JsonNode element : root) {
      if (!element.isTextual()) {
        throw new IOException("Blocked IP entry must be a string");
      }
      String valueText = element.textValue();
      if (valueText == null || valueText.length() > MAX_IP_LENGTH
          || !InetAddresses.isInetAddress(valueText)) {
        throw new IOException("Invalid blocked IP entry");
      }
      addresses.add(InetAddresses.forString(valueText));
    }
    return normalizeBlockedIps(addresses);
  }

  private List<InetAddress> normalizeBlockedIps(List<InetAddress> addresses) {
    Map<String, InetAddress> normalized = new TreeMap<>();
    for (InetAddress address : addresses) {
      if (address == null) {
        throw new IllegalArgumentException("blockedIps must not contain null");
      }
      normalized.put(address.getHostAddress(), address);
    }
    return new ArrayList<>(normalized.values());
  }

  private List<InetAddress> immutableCopy(List<InetAddress> addresses) {
    return Collections.unmodifiableList(new ArrayList<>(addresses));
  }
}
