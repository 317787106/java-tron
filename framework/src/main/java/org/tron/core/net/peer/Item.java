package org.tron.core.net.peer;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import java.security.SecureRandom;
import lombok.Getter;
import org.tron.common.utils.Sha256Hash;
import org.tron.protos.Protocol.Inventory.InventoryType;

public class Item {

  // This key is used only by P2P in-memory collections, never for consensus or wire IDs.
  private static final HashFunction HASH_FUNCTION = createHashFunction();

  private final Sha256Hash hash;
  @Getter
  private final InventoryType type;
  @Getter
  private final long time;
  private final int cachedHashCode;


  public Item(Sha256Hash hash, InventoryType type) {
    this.hash = Sha256Hash.wrap(hash.getBytes().clone());
    this.type = type;
    this.time = System.currentTimeMillis();
    this.cachedHashCode = HASH_FUNCTION.newHasher()
        .putBytes(this.hash.getBytes()).putInt(type.ordinal()).hash().asInt();
  }

  private static HashFunction createHashFunction() {
    SecureRandom random = new SecureRandom();
    return Hashing.sipHash24(random.nextLong(), random.nextLong());
  }

  public Sha256Hash getHash() {
    // Sha256Hash exposes its backing array; keep the collection key immutable.
    return Sha256Hash.wrap(hash.getBytes().clone());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || this.getClass() != o.getClass()) {
      return false;
    }
    Item item = (Item) o;
    return hash.equals(item.hash)
        && type.equals(item.type);
  }

  @Override
  public int hashCode() {
    return cachedHashCode;
  }

  @Override
  public String toString() {
    return type + ":" + hash;
  }
}
