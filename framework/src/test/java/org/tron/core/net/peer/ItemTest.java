package org.tron.core.net.peer;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.net.service.adv.AdvService;
import org.tron.protos.Protocol.Inventory.InventoryType;

public class ItemTest {

  @Test
  public void equalityUsesCompleteIdAndType() {
    byte[] bytes = Sha256Hash.hash("inventory-key".getBytes(StandardCharsets.UTF_8));
    Item first = new Item(Sha256Hash.wrap(bytes), InventoryType.TRX);
    Item equal = new Item(Sha256Hash.wrap(Arrays.copyOf(bytes, bytes.length)), InventoryType.TRX);
    Item block = new Item(Sha256Hash.wrap(bytes), InventoryType.BLOCK);
    byte[] different = Arrays.copyOf(bytes, bytes.length);
    different[0] ^= 1;
    Item other = new Item(Sha256Hash.wrap(different), InventoryType.TRX);

    Assert.assertEquals(first, equal);
    Assert.assertEquals(equal, first);
    Assert.assertEquals(first.hashCode(), equal.hashCode());
    Assert.assertNotEquals(first, block);
    Assert.assertNotEquals(first, other);
    Assert.assertNotEquals(first, null);
    Assert.assertNotEquals(first, first.getHash());

    Cache<Item, String> cache = CacheBuilder.newBuilder().build();
    cache.put(first, "transaction");
    cache.put(block, "block");
    Assert.assertEquals("transaction", cache.getIfPresent(equal));
    Assert.assertEquals("block", cache.getIfPresent(block));
    Assert.assertEquals(2, cache.size());
  }

  @Test
  public void blockIdAndPlainHashRemainInterchangeable() {
    BlockId blockId = new BlockId(Sha256Hash.ZERO_HASH, 1L);
    Item block = new Item(blockId, InventoryType.BLOCK);
    Item plain = new Item(Sha256Hash.wrap(Arrays.copyOf(blockId.getBytes(), Sha256Hash.LENGTH)),
        InventoryType.BLOCK);

    Assert.assertEquals(block, plain);
    Assert.assertEquals(plain, block);
    Assert.assertEquals(block.hashCode(), plain.hashCode());
    Assert.assertArrayEquals(blockId.getBytes(), block.getHash().getBytes());
    Assert.assertEquals(InventoryType.BLOCK + ":" + blockId, block.toString());
  }

  @Test
  public void cacheKeySurvivesSourceAndGetterMutations() {
    byte[] bytes = Sha256Hash.hash("immutable-inventory-key".getBytes(StandardCharsets.UTF_8));
    Sha256Hash source = Sha256Hash.wrap(bytes);
    Item key = new Item(source, InventoryType.TRX);
    Item equal = new Item(Sha256Hash.wrap(Arrays.copyOf(bytes, bytes.length)), InventoryType.TRX);
    int originalHashCode = key.hashCode();
    Cache<Item, String> cache = CacheBuilder.newBuilder().build();
    cache.put(key, "cached");

    bytes[0] ^= 1;
    source.getBytes()[1] ^= 1;
    key.getHash().getBytes()[2] ^= 1;

    Assert.assertEquals(equal, key);
    Assert.assertEquals(key, equal);
    Assert.assertEquals(originalHashCode, key.hashCode());
    Assert.assertEquals("cached", cache.getIfPresent(equal));
    Assert.assertArrayEquals(equal.getHash().getBytes(), key.getHash().getBytes());
  }

  @Test
  public void hashCodeUsesTheCompleteId() {
    for (int position = 0; position < Sha256Hash.LENGTH; position++) {
      Set<Integer> hashCodes = new HashSet<>();
      for (int value = 0; value < 64; value++) {
        byte[] bytes = new byte[Sha256Hash.LENGTH];
        bytes[position] = (byte) value;
        hashCodes.add(new Item(Sha256Hash.wrap(bytes), InventoryType.TRX).hashCode());
      }
      // Allow ordinary int hash collisions without accepting a constant hash code.
      Assert.assertTrue("ID byte does not contribute to key distribution: " + position,
          hashCodes.size() > 32);
    }
  }

  @Test
  public void hashingDoesNotChangeTheSuppliedId() {
    byte[] bytes = Sha256Hash.hash("preserve-inventory-id".getBytes(StandardCharsets.UTF_8));
    byte[] expected = Arrays.copyOf(bytes, bytes.length);
    Sha256Hash source = Sha256Hash.wrap(bytes);
    int originalHashCode = source.hashCode();

    Item item = new Item(source, InventoryType.TRX);
    item.hashCode();

    Assert.assertArrayEquals(expected, source.getBytes());
    Assert.assertEquals(originalHashCode, source.hashCode());
    Assert.assertArrayEquals(expected, item.getHash().getBytes());
  }

  @Test(timeout = 20_000)
  public void cacheProcessingAvoidsExcessiveKeyComparisons() {
    AdvService service = new AdvService();
    ReflectUtils.setFieldValue(service, "fastForward", false);
    Cache<Item, Long> peerCache = new PeerConnection().getAdvInvReceive();
    CountingItem.comparisons.set(0);
    int count = 512;
    try {
      for (int i = 0; i < count; i++) {
        CountingItem item = countingItem(i);
        peerCache.put(item, 1L);
        Assert.assertTrue(service.addInv(item));
      }
      for (int i = 0; i < count; i++) {
        CountingItem equal = countingItem(i);
        Assert.assertEquals(Long.valueOf(1L), peerCache.getIfPresent(equal));
        Assert.assertFalse(service.addInv(equal));
      }
      // Check collection work rather than elapsed time, which depends on the host.
      Assert.assertTrue("Inventory caches performed excessive key comparisons",
          CountingItem.comparisons.get() < count * 32);
    } finally {
      service.close();
    }
  }

  private static CountingItem countingItem(int index) {
    byte[] bytes = new byte[Sha256Hash.LENGTH];
    bytes[0] = (byte) (index >>> 8);
    bytes[1] = (byte) index;
    return new CountingItem(Sha256Hash.wrap(bytes));
  }

  private static class CountingItem extends Item {
    private static final AtomicInteger comparisons = new AtomicInteger();

    CountingItem(Sha256Hash hash) {
      super(hash, InventoryType.TRX);
    }

    @Override
    public boolean equals(Object other) {
      comparisons.incrementAndGet();
      return super.equals(other);
    }

    @Override
    public int hashCode() {
      return super.hashCode();
    }
  }
}
