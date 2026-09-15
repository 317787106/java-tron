package org.tron.core.services.jsonrpc.filters;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.exception.jsonrpc.JsonRpcFilterOverflowException;

public class FilterResultTest {

  @Test
  public void exactLimitRemainsReadableInOrderAndCanBeReused() throws Exception {
    BlockFilterAndResult filter = new BlockFilterAndResult();
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < FilterResult.MAX_RESULT_SIZE; i++) {
      expected.add(Integer.toString(i));
    }
    filter.addAll(expected);
    filter.addAll(Collections.emptyList());
    Assert.assertEquals(FilterResult.MAX_RESULT_SIZE, filter.size());
    Assert.assertFalse(filter.isOverflowed());
    Assert.assertEquals(expected, filter.popAll());
    Assert.assertEquals(0, filter.size());
    Assert.assertTrue(filter.popAll().isEmpty());
    filter.add("next block");
    Assert.assertEquals(Collections.singletonList("next block"), filter.popAll());
  }

  @Test
  public void nextEntryInvalidatesFullFilterAndReleasesBacklog() throws Exception {
    BlockFilterAndResult filter = new BlockFilterAndResult();
    filter.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE, "block"));
    filter.add("overflow");
    assertOverflow(filter);
    filter.add("later");
    filter.addAll(Collections.singletonList("later batch"));
    assertOverflow(filter);
  }

  @Test
  public void batchCrossingLimitInvalidatesWholeFilter() throws Exception {
    BlockFilterAndResult filter = new BlockFilterAndResult();
    filter.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE - 50, "old"));
    filter.addAll(Collections.nCopies(100, "new"));
    assertOverflow(filter);
  }

  @Test
  public void oversizedFirstBatchIsRejected() throws Exception {
    BlockFilterAndResult filter = new BlockFilterAndResult();
    filter.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE + 1, "block"));
    assertOverflow(filter);
  }

  @Test
  public void overflowAndFailedPollsDoNotRenewDeadline() throws Exception {
    BlockFilterAndResult filter = new BlockFilterAndResult();
    long deadline = System.currentTimeMillis() + 60000;
    deadlineField().setLong(filter, deadline);
    filter.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE + 1, "block"));
    assertOverflow(filter);
    assertOverflow(filter);
    Assert.assertEquals(deadline, deadlineField().getLong(filter));

    deadlineField().setLong(filter, 0);
    Assert.assertTrue(filter.expire());
    Assert.assertThrows(ItemNotFoundException.class, filter::popAll);
    Assert.assertEquals(0, deadlineField().getLong(filter));
  }

  @Test
  public void successfulEmptyPollRenewsDeadline() throws Exception {
    BlockFilterAndResult filter = new BlockFilterAndResult();
    long oldDeadline = System.currentTimeMillis() + 60000;
    deadlineField().setLong(filter, oldDeadline);
    Assert.assertTrue(filter.popAll().isEmpty());
    Assert.assertTrue(deadlineField().getLong(filter) > oldDeadline);
  }

  @Test
  public void expiredFilterCannotBeRevivedByReadingOrAppending() throws Exception {
    BlockFilterAndResult filter = new BlockFilterAndResult();
    filter.add("old");
    deadlineField().setLong(filter, 0);
    Assert.assertThrows(ItemNotFoundException.class, filter::popAll);
    filter.add("late");
    Assert.assertEquals(0, filter.size());
    Assert.assertThrows(ItemNotFoundException.class, filter::checkValid);
  }

  @Test
  public void closingFilterClearsResultsAndRejectsStaleWriters() throws Exception {
    BlockFilterAndResult filter = new BlockFilterAndResult();
    filter.add("old");
    filter.close();
    filter.close();
    filter.add("late");
    Assert.assertEquals(0, filter.size());
    Assert.assertThrows(ItemNotFoundException.class, filter::popAll);
  }

  @Test(timeout = 15000)
  public void concurrentAppendsCannotExceedLimit() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 20; i++) {
        BlockFilterAndResult filter = new BlockFilterAndResult();
        filter.addAll(Collections.nCopies(FilterResult.MAX_RESULT_SIZE - 1, "old"));
        CountDownLatch start = new CountDownLatch(1);
        Future<?> first = executor.submit(() -> {
          start.await();
          filter.add("first");
          return null;
        });
        Future<?> second = executor.submit(() -> {
          start.await();
          filter.add("second");
          return null;
        });
        start.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertOverflow(filter);
      }
    } finally {
      executor.shutdownNow();
      Assert.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test(timeout = 15000)
  public void pollRacingWithOverflowGetsCompleteSnapshotOrError() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 20; i++) {
        BlockFilterAndResult filter = new BlockFilterAndResult();
        List<String> old = Collections.nCopies(FilterResult.MAX_RESULT_SIZE - 1, "old");
        List<String> added = Arrays.asList("new 1", "new 2");
        filter.addAll(old);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> producer = executor.submit(() -> {
          start.await();
          filter.addAll(added);
          return null;
        });
        Future<List<String>> consumer = executor.submit(() -> {
          start.await();
          try {
            return filter.popAll();
          } catch (JsonRpcFilterOverflowException expected) {
            return null;
          }
        });
        start.countDown();
        producer.get(5, TimeUnit.SECONDS);
        List<String> polled = consumer.get(5, TimeUnit.SECONDS);
        if (polled == null) {
          assertOverflow(filter);
        } else {
          Assert.assertEquals(old, polled);
          Assert.assertFalse(filter.isOverflowed());
          Assert.assertEquals(added, filter.popAll());
        }
      }
    } finally {
      executor.shutdownNow();
      Assert.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static void assertOverflow(FilterResult<?> filter) throws Exception {
    Assert.assertTrue(filter.isOverflowed());
    Assert.assertEquals(0, filter.size());
    JsonRpcFilterOverflowException error =
        Assert.assertThrows(JsonRpcFilterOverflowException.class, filter::popAll);
    Assert.assertEquals("filter result limit exceeded; filter invalidated", error.getMessage());
  }

  private static Field deadlineField() throws Exception {
    Field field = FilterResult.class.getDeclaredField("expireTimeStamp");
    field.setAccessible(true);
    return field;
  }
}
