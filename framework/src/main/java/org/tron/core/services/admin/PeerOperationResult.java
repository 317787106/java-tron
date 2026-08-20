package org.tron.core.services.admin;

import lombok.Getter;

@Getter
public class PeerOperationResult {

  private final boolean success;
  private final boolean changed;
  private final int disconnectedCount;
  private final String message;

  public PeerOperationResult(boolean success, boolean changed, int disconnectedCount,
      String message) {
    this.success = success;
    this.changed = changed;
    this.disconnectedCount = disconnectedCount;
    this.message = message;
  }
}
