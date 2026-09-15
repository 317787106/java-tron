package org.tron.core.services.jsonrpc.filters;

import lombok.Getter;
import org.tron.core.Wallet;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.services.jsonrpc.TronJsonRpc.FilterRequest;
import org.tron.core.services.jsonrpc.TronJsonRpc.LogFilterElement;

public class LogFilterAndResult extends FilterResult<LogFilterElement> {

  @Getter
  private final LogFilterWrapper logFilterWrapper;

  public LogFilterAndResult(FilterRequest fr, long currentMaxBlockNum, Wallet wallet)
      throws JsonRpcInvalidParamsException {
    // eth_newFilter, no need to check block range
    this.logFilterWrapper = new LogFilterWrapper(fr, currentMaxBlockNum, wallet, false);
  }
}
