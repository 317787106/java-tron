package org.tron.core.services.jsonrpc.filters;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.exception.jsonrpc.JsonRpcFilterOverflowException;
import org.tron.core.services.jsonrpc.TronJsonRpcImpl;

public abstract class FilterResult<T> {

  public static final int MAX_RESULT_SIZE = 20_000;

  private enum State {
    ACTIVE, OVERFLOWED, CLOSED
  }

  private long expireTimeStamp =
      System.currentTimeMillis() + TronJsonRpcImpl.EXPIRE_SECONDS * 1000L;
  private final BlockingQueue<T> result = new LinkedBlockingQueue<>(MAX_RESULT_SIZE);
  private State state = State.ACTIVE;

  public final void add(T element) {
    addAll(Collections.singletonList(element));
  }

  /**
   * Append a whole batch or invalidate the filter. Never block the event dispatcher on a reader.
   */
  public final synchronized void addAll(List<? extends T> elements) {
    if (expire() || state == State.OVERFLOWED) {
      return;
    }
    if (elements.size() > MAX_RESULT_SIZE - result.size()) {
      state = State.OVERFLOWED;
      result.clear();
      // Retain the original deadline: failed polls must not keep this record alive.
      return;
    }
    result.addAll(elements);
  }

  public final synchronized List<T> popAll()
      throws ItemNotFoundException, JsonRpcFilterOverflowException {
    checkValid();
    List<T> elements = new ArrayList<>(result.size());
    result.drainTo(elements);
    expireTimeStamp = System.currentTimeMillis() + TronJsonRpcImpl.EXPIRE_SECONDS * 1000L;
    return elements;
  }

  public final synchronized void checkValid()
      throws ItemNotFoundException, JsonRpcFilterOverflowException {
    if (expire()) {
      throw new ItemNotFoundException("filter not found");
    }
    if (state == State.OVERFLOWED) {
      throw new JsonRpcFilterOverflowException(MAX_RESULT_SIZE);
    }
  }

  /**
   * Expiration and closing are atomic with polling, so a concurrent renewal cannot be lost.
   */
  public final synchronized boolean expire() {
    if (state == State.CLOSED || expireTimeStamp < System.currentTimeMillis()) {
      close();
      return true;
    }
    return false;
  }

  public final synchronized boolean isOverflowed() {
    return state == State.OVERFLOWED;
  }

  public final synchronized void close() {
    state = State.CLOSED;
    result.clear();
  }

  @VisibleForTesting
  public final synchronized int size() {
    return result.size();
  }
}
