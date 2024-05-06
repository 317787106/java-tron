package org.tron.core.db;

import static org.fusesource.leveldbjni.JniDBFactory.factory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.junit.Test;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Commons;
import org.tron.core.capsule.AccountCapsule;

@Slf4j
public class ReadDBTest {

  private org.iq80.leveldb.Options newDefaultLevelDbOptions() {
    org.iq80.leveldb.Options dbOptions = new org.iq80.leveldb.Options();
    dbOptions.createIfMissing(true);
    dbOptions.paranoidChecks(true);
    dbOptions.verifyChecksums(true);
    dbOptions.compressionType(CompressionType.SNAPPY);
    dbOptions.blockSize(4 * 1024);
    dbOptions.writeBufferSize(10 * 1024 * 1024);
    dbOptions.cacheSize(10 * 1024 * 1024L);
    dbOptions.maxOpenFiles(1000);
    return dbOptions;
  }

  private DB newLevelDb(Path db) throws IOException {
    File file = db.toFile();
    org.iq80.leveldb.Options dbOptions = newDefaultLevelDbOptions();
    return factory.open(file, dbOptions);
  }

  @Test
  public void readTest() throws IOException {
    String PATH =
        "/Users/jiangyuanshu/workspace/java-tron-317787106/output-directory/database/account";

    DB level = newLevelDb(Paths.get(PATH));
    byte[] data = level.get(Commons.decodeFromBase58Check("TTehfXyQUtynDLyvAkiLVgUKpDoPfEY7vk"));
    if (data != null) {
      AccountCapsule accountCapsule = new AccountCapsule(data);
      System.out.println(accountCapsule.getBalance());
    }
  }

  @Test
  public void iteratorTest() {
    String PATH =
        "/Users/jiangyuanshu/workspace/java-tron-317787106/output-directory/database/account";
    try (
        DB level = newLevelDb(Paths.get(PATH));
        DBIterator levelIterator = level.iterator(
            new org.iq80.leveldb.ReadOptions().fillCache(false))) {
      levelIterator.seekToFirst();

      while (levelIterator.hasNext()) {
        Map.Entry<byte[], byte[]> entry = levelIterator.next();
        byte[] key = entry.getKey();
        byte[] value = entry.getValue();
        System.out.println("key:" + ByteArray.toHexString(key));
        System.out.println("val:" + ByteArray.toHexString(value));
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

}
