package org.tron.core.net.peer;

import lombok.Getter;

/**
 * Immutable peer statistics shared by peer logging and Admin RPC responses.
 * Adding a getter changes the Admin response schema and must not expose sensitive state.
 */
@Getter
public class ActivePeerInfo {

  private final String remoteAddress;
  private final long connectSeconds;
  private final long averageLatencyMillis;
  private final long lastKnownBlockNum;
  private final boolean needSyncFromPeer;
  private final boolean needSyncFromUs;
  private final int syncToFetchSize;
  private final long syncToFetchSizePeekNum;
  private final int syncBlockRequestedSize;
  private final long remainNum;
  private final long syncChainRequestedMillis;
  private final long inactiveSeconds;
  private final int blockInProcess;

  ActivePeerInfo(String remoteAddress, long connectSeconds, long averageLatencyMillis,
      long lastKnownBlockNum, boolean needSyncFromPeer, boolean needSyncFromUs,
      int syncToFetchSize, long syncToFetchSizePeekNum, int syncBlockRequestedSize,
      long remainNum, long syncChainRequestedMillis, long inactiveSeconds, int blockInProcess) {
    this.remoteAddress = remoteAddress;
    this.connectSeconds = connectSeconds;
    this.averageLatencyMillis = averageLatencyMillis;
    this.lastKnownBlockNum = lastKnownBlockNum;
    this.needSyncFromPeer = needSyncFromPeer;
    this.needSyncFromUs = needSyncFromUs;
    this.syncToFetchSize = syncToFetchSize;
    this.syncToFetchSizePeekNum = syncToFetchSizePeekNum;
    this.syncBlockRequestedSize = syncBlockRequestedSize;
    this.remainNum = remainNum;
    this.syncChainRequestedMillis = syncChainRequestedMillis;
    this.inactiveSeconds = inactiveSeconds;
    this.blockInProcess = blockInProcess;
  }
}
