package org.tron.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.apache.commons.collections4.CollectionUtils;
import org.rocksdb.RocksDBException;
import org.tron.common.bloom.Bloom;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.ByteUtil;
import org.tron.core.capsule.TransactionRetCapsule;
import org.tron.core.exception.BadItemException;
import org.tron.core.exception.EventBloomException;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "backfill-bloom")
@CommandLine.Command(name = "backfill-bloom",
    description = {
        "Backfill SectionBloom for historical blocks to enable eth_getLogs filtering.",
        "The same block range can be safely rerun after interruption."
    },
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "1:Internal error: exception occurred, please check toolkit.log"})
public class DbBackfillBloom implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;

  @CommandLine.Option(names = {"--database-directory", "-d"},
      defaultValue = "output-directory/database",
      description = "Database directory path. Default: ${DEFAULT-VALUE}", order = 1)
  private String databaseDirectory;

  @CommandLine.Option(names = {"--start-block", "-s"},
      description = "Start block number for backfill. Default: earliest block", order = 2)
  private long startBlock;

  @CommandLine.Option(names = {"--end-block", "-e"},
      description = "End block number for backfill, inclusive. Default: latest solidity block",
      order = 3)
  private long endBlock;

  // sames as SectionBloomStore.BLOCK_PER_SECTION
  private static final int BLOCKS_PER_SECTION = 2048;
  private static final long PROGRESS_LOG_INTERVAL = 10_000L;
  private static final String PROPERTIES_DB_NAME = "properties";
  private static final String TRANSACTION_RET_DB_NAME = "transactionRetStore";
  private static final String SECTION_BLOOM_DB_NAME = "section-bloom";
  private static final String LATEST_SOLIDIFIED_BLOCK_NUM = "LATEST_SOLIDIFIED_BLOCK_NUM";

  @CommandLine.Option(names = {"--max-concurrency", "-c"}, defaultValue = "8",
      description = "Maximum concurrency for processing. Default: ${DEFAULT-VALUE}. For SATA SSD "
          + "use 4–8; for NVMe SSD use 8–16; for HDD use 1–2.",
      order = 5)
  private int maxConcurrency;

  @CommandLine.Option(names = {"--help", "-h"}, help = true,
      description = "Display help message", order = 7)
  private boolean help;

  // Statistics
  // Number of blocks traversed (including failed ones)
  private final AtomicLong processedBlocks = new AtomicLong(0);
  // Number of successfully processed blocks
  private final AtomicLong successfulBlocks = new AtomicLong(0);
  // Number of blocks containing logs
  private final AtomicLong blocksWithLogs = new AtomicLong(0);
  // Number of failed blocks processed
  private final AtomicLong errorCount = new AtomicLong(0);
  // Total number of bloom writes
  private final AtomicLong totalBloomWrites = new AtomicLong(0);

  private DBInterface transactionRetDb;
  private DBInterface sectionBloomDb;

  private static class SectionRange {

    final long start;
    final long end;
    final long sectionId;

    SectionRange(long start, long end, long sectionId) {
      this.start = start;
      this.end = end;
      this.sectionId = sectionId;
    }

    @Override
    public String toString() {
      return String.format("Section %d: [%d-%d]", sectionId, start, end);
    }
  }

  @Override
  public Integer call() {
    if (help) {
      logger.info("Displaying backfill-bloom help");
      spec.commandLine().usage(System.out);
      return 0;
    }

    try {
      // Validate parameters
      if (!validateParameters()) {
        return 1;
      }

      // Initialize database connections
      if (!initializeDatabase()) {
        return 1;
      }

      // Determine end block if not specified
      long latestSolidityBlockNumber;
      try {
        latestSolidityBlockNumber = getLatestSolidityBlockNumber();
      } catch (Exception e) {
        printError(e, "Failed to read latest solidified block number");
        return 1;
      }
      if (latestSolidityBlockNumber < 0) {
        printError("Latest solidified block number does not exist");
        return 1;
      }
      if (endBlock == 0) {
        endBlock = latestSolidityBlockNumber;
      } else if (endBlock > latestSolidityBlockNumber) {
        printInfo("End block %d is larger than latest solidified block num %d; using %d instead.",
            endBlock, latestSolidityBlockNumber, latestSolidityBlockNumber);
        endBlock = latestSolidityBlockNumber;
      }

      long minBlockNumber;
      try {
        minBlockNumber = getMinBlockNumber();
      } catch (Exception e) {
        printError(e, "Failed to determine the first transaction result block");
        return 1;
      }
      if (minBlockNumber < 0) {
        printError("Transaction result database does not contain any non-zero block");
        return 1;
      }
      if (startBlock == 0) {
        startBlock = minBlockNumber;
      } else if (startBlock < minBlockNumber) {
        printInfo("Start block %d is earlier than the first available transaction result block %d; "
            + "using %d instead.", startBlock, minBlockNumber, minBlockNumber);
        startBlock = minBlockNumber;
      }

      // Validate block range
      if (endBlock < startBlock) {
        printError("End block %d must be greater than or equal to start block %d",
            endBlock, startBlock);
        return 1;
      }

      long totalBlocks = endBlock - startBlock + 1;
      printInfo("Starting SectionBloom backfill for block number %d to %d (%d blocks)",
          startBlock, endBlock, totalBlocks);

      // Process blocks with progress bar
      long startTime = System.currentTimeMillis();
      int result = processBlocks(startTime);
      long duration = (System.currentTimeMillis() - startTime) / 1000;

      // Print summary
      printSummary(duration);

      return result;

    } catch (Exception e) {
      printError(e, "Backfill failed");
      return 1;
    } finally {
      DbTool.close();
    }
  }

  private boolean validateParameters() {
    if (startBlock < 0) {
      printError("Start block must be >= zero, it is %d", startBlock);
      return false;
    }
    if (endBlock < 0) {
      printError("End block must be > zero, it is %d", endBlock);
      return false;
    }

    if (maxConcurrency <= 0 || maxConcurrency > 128) {
      printError("Max concurrency %d must be between 1 and 128", maxConcurrency);
      return false;
    }

    File dbDir = new File(databaseDirectory);
    if (!dbDir.exists() || !dbDir.isDirectory()) {
      printError("Database directory does not exist or is not a directory");
      return false;
    }
    if (!isDatabaseDirectory(dbDir, PROPERTIES_DB_NAME)) {
      printError("Required database '%s' does not exist", PROPERTIES_DB_NAME);
      return false;
    }
    if (!isDatabaseDirectory(dbDir, TRANSACTION_RET_DB_NAME)) {
      printError("Required database '%s' does not exist", TRANSACTION_RET_DB_NAME);
      return false;
    }

    return true;
  }

  private boolean isDatabaseDirectory(File databaseRoot, String databaseName) {
    return new File(databaseRoot, databaseName).isDirectory();
  }

  private boolean initializeDatabase() {
    try {
      // Open both DBs here, single-threaded, before any worker thread starts. DbTool.getDB
      // caches handles in a ConcurrentMap but its check-then-open is not atomic, so two
      // threads opening the same LevelDB dir concurrently would hit the exclusive-lock error.
      // Keep these handles for all worker threads instead of opening the same DB again.
      transactionRetDb = DbTool.getDB(databaseDirectory, TRANSACTION_RET_DB_NAME);
      sectionBloomDb = DbTool.getDB(databaseDirectory, SECTION_BLOOM_DB_NAME);

      printInfo("Database connections initialized successfully");
      return true;
    } catch (Exception e) {
      printError(e, "Failed to initialize database connections");
      return false;
    }
  }

  private long getLatestSolidityBlockNumber() throws IOException, RocksDBException {
    DBInterface propertiesDb = DbTool.getDB(databaseDirectory, PROPERTIES_DB_NAME);
    byte[] latestBlockKey = LATEST_SOLIDIFIED_BLOCK_NUM.getBytes(StandardCharsets.UTF_8);
    byte[] latestBlockBytes = propertiesDb.get(latestBlockKey);
    if (latestBlockBytes != null) {
      return ByteArray.toLong(latestBlockBytes);
    }
    return -1;
  }

  private long getMinBlockNumber() throws IOException {
    try (DBIterator iterator = transactionRetDb.iterator()) {
      iterator.seek(ByteArray.fromLong(1));
      if (iterator.hasNext()) {
        return ByteArray.toLong(iterator.getKey());
      }
    }
    return -1;
  }

  private int processBlocks(long startTime) {
    long totalBlocks = endBlock - startBlock + 1;
    // Calculate the section range to be processed
    List<SectionRange> sectionRanges = calculateSectionRanges(startBlock, endBlock);

    maxConcurrency = StrictMath.min(maxConcurrency, sectionRanges.size());
    ExecutorService executor =
        ExecutorServiceManager.newFixedThreadPool("backfill-bloom", maxConcurrency);
    List<CompletableFuture<Void>> futures = new ArrayList<>();

    try (ProgressBar pb = new ProgressBar("Backfill section-bloom", totalBlocks)) {
      printInfo("Processing %d sections with %d threads", sectionRanges.size(), maxConcurrency);
      // Submit all section tasks to the thread pool
      for (SectionRange range : sectionRanges) {
        final long finalSectionStart = range.start;
        final long finalSectionEnd = range.end;

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            processSection(finalSectionStart, finalSectionEnd, totalBlocks, startTime, pb);
          } catch (Exception e) {
            printError(e, "Error processing section %d to %d",
                finalSectionStart, finalSectionEnd);
          }
        }, executor);

        futures.add(future);
      }

      // Wait for all tasks to complete
      CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.toArray(
          new CompletableFuture[0]));

      try {
        allTasks.get();
        printInfo("All %d batch tasks completed", futures.size());
      } catch (Exception e) {
        printError(e, "Error waiting for backfill tasks to complete");
        return 1;
      }

    } catch (Exception e) {
      printError(e, "Error in progress tracking");
      return 1;
    } finally {
      ExecutorServiceManager.shutdownAndAwaitTermination(executor, "backfill-bloom");
    }

    return errorCount.get() > 0 ? 1 : 0;
  }

  /**
   * Calculate the section range to be processed to ensure each thread processes a complete section.
   * For example, startBlock=1000 and endBlock=4000 will generate:
   * - SectionRange 0: [1000-2047]
   * - SectionRange 1: [2048-4000]
   */
  private List<SectionRange> calculateSectionRanges(long startBlock, long endBlock) {
    List<SectionRange> ranges = new ArrayList<>();

    long currentBlock = startBlock;
    while (currentBlock <= endBlock) {
      // Calculate the section to which the current block belongs
      long sectionId = currentBlock / BLOCKS_PER_SECTION;

      // Calculate the boundaries of this section
      long sectionStart = sectionId * BLOCKS_PER_SECTION;
      long sectionEnd = sectionStart + BLOCKS_PER_SECTION - 1;

      // Adjust to the actual range that needs to be processed
      long rangeStart = StrictMath.max(currentBlock, sectionStart);
      long rangeEnd = StrictMath.min(endBlock, sectionEnd);

      ranges.add(new SectionRange(rangeStart, rangeEnd, sectionId));
      currentBlock = sectionEnd + 1;
    }

    return ranges;
  }

  private void processSection(long sectionStart, long sectionEnd, long totalBlocks, long startTime,
      ProgressBar pb) {
    for (long blockNum = sectionStart; blockNum <= sectionEnd; blockNum++) {
      try {
        processBlock(blockNum, transactionRetDb, sectionBloomDb);
        successfulBlocks.incrementAndGet();
      } catch (Exception e) {
        printError(e, "Error processing block %d", blockNum);
        errorCount.incrementAndGet();
      } finally {
        long processed = processedBlocks.incrementAndGet();
        logProgress(processed, totalBlocks, startTime);
        pb.step();
      }
    }
  }

  private void logProgress(long processed, long totalBlocks, long startTime) {
    if (processed % PROGRESS_LOG_INTERVAL != 0) {
      return;
    }

    long elapsedMillis =
        StrictMath.max(System.currentTimeMillis() - startTime, 1L);
    double progress = (double) processed / totalBlocks * 100;
    double blocksPerSecond = (double) processed * 1000 / elapsedMillis;
    long remainingSeconds = (long) ((totalBlocks - processed) / blocksPerSecond);
    logger.info(
        "Backfill progress: {}/{} blocks ({}%), elapsed={}, rate={} blocks/s, remaining={}",
        processed, totalBlocks, String.format(Locale.ROOT, "%.2f", progress),
        formatDuration(elapsedMillis / 1000),
        String.format(Locale.ROOT, "%.2f", blocksPerSecond),
        formatDuration(remainingSeconds));
  }

  private String formatDuration(long totalSeconds) {
    long hours = totalSeconds / 3600;
    long minutes = totalSeconds % 3600 / 60;
    long seconds = totalSeconds % 60;
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
  }

  private void processBlock(long blockNum, DBInterface transactionRetDb, DBInterface sectionBloomDb)
      throws BadItemException, EventBloomException {

    // Get transaction info for this block
    byte[] blockKey = ByteArray.fromLong(blockNum);
    byte[] transactionRetData = transactionRetDb.get(blockKey);

    if (transactionRetData == null) {
      return;
    }

    TransactionRetCapsule transactionRetCapsule = new TransactionRetCapsule(transactionRetData);

    // Create bloom filter for this block using the same logic as SectionBloomStore
    Bloom blockBloom = Bloom.createBloom(transactionRetCapsule);

    if (blockBloom != null) {
      // Extract bit positions from bloom filter
      List<Integer> bitList = extractBitPositions(blockBloom);

      if (!CollectionUtils.isEmpty(bitList)) {
        // Write to section bloom store using the same logic as SectionBloomStore.write
        writeSectionBloom(blockNum, bitList, sectionBloomDb);
        blocksWithLogs.incrementAndGet();
      }
    }
  }

  private List<Integer> extractBitPositions(Bloom blockBloom) {
    List<Integer> bitList = new ArrayList<>();
    BitSet bs = BitSet.valueOf(blockBloom.getData());
    for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
      // operate on index i here
      if (i == Integer.MAX_VALUE) {
        break; // or (i+1) would overflow
      }
      bitList.add(i);
    }
    return bitList;
  }

  private void writeSectionBloom(long blockNum, List<Integer> bitList, DBInterface sectionBloomDb)
      throws EventBloomException {

    int section = (int) (blockNum / BLOCKS_PER_SECTION);
    int blockNumOffset = (int) (blockNum % BLOCKS_PER_SECTION);

    for (int bitIndex : bitList) {
      // Get existing BitSet from database
      BitSet bitSet = getSectionBloomBitSet(section, bitIndex, sectionBloomDb);
      if (Objects.isNull(bitSet)) {
        bitSet = new BitSet(BLOCKS_PER_SECTION);
      }

      // Update the bit for this block
      bitSet.set(blockNumOffset);

      // Put back into database
      putSectionBloomBitSet(section, bitIndex, bitSet, sectionBloomDb);
      totalBloomWrites.incrementAndGet();
    }
  }

  private long combineKey(int section, int bitIndex) {
    return section * 1_000_000L + bitIndex;
  }

  private BitSet getSectionBloomBitSet(int section, int bitIndex, DBInterface sectionBloomDb)
      throws EventBloomException {
    long keyLong = combineKey(section, bitIndex);
    byte[] key = Long.toHexString(keyLong).getBytes();
    byte[] data = sectionBloomDb.get(key);

    if (data == null) {
      return null;
    }

    try {
      byte[] decompressedData = ByteUtil.decompress(data);
      return BitSet.valueOf(decompressedData);
    } catch (Exception e) {
      throw new EventBloomException("decompress byte failed");
    }
  }

  private void putSectionBloomBitSet(int section, int bitIndex, BitSet bitSet,
      DBInterface sectionBloomDb)
      throws EventBloomException {
    long keyLong = combineKey(section, bitIndex);
    byte[] key = Long.toHexString(keyLong).getBytes();

    try {
      byte[] compressedData = ByteUtil.compress(bitSet.toByteArray());
      sectionBloomDb.put(key, compressedData);
    } catch (Exception e) {
      throw new EventBloomException("compress byte failed");
    }
  }

  private void printSummary(long duration) {
    spec.commandLine().getOut().println();
    printInfo("=== Backfill Summary ===");

    printInfo("Total blocks scanned: %d", processedBlocks.get());
    printInfo("Successfully processed: %d", successfulBlocks.get());
    printInfo("Blocks with logs: %d", blocksWithLogs.get());
    printInfo("Errors encountered: %d", errorCount.get());
    printInfo("Duration: %d seconds", duration);

    // Success rate statistics
    if (processedBlocks.get() > 0) {
      double successRate = (double) successfulBlocks.get() / processedBlocks.get() * 100;
      double logRate = (double) blocksWithLogs.get() / processedBlocks.get() * 100;
      printInfo("Success rate: %.2f%% (%d/%d)",
          successRate, successfulBlocks.get(), processedBlocks.get());
      printInfo("Blocks with logs rate: %.2f%% (%d/%d)",
          logRate, blocksWithLogs.get(), processedBlocks.get());
    }

    // Performance statistics
    printInfo("Total bloom writes: %d", totalBloomWrites.get());
    printInfo("Max concurrency used: %d threads", maxConcurrency);
    printInfo("Section-based processing: No locks needed");

    if (duration > 0) {
      printInfo("Scanning rate: %.2f blocks/second", (double) processedBlocks.get() / duration);
      printInfo("Processing rate: %.2f blocks/second", (double) successfulBlocks.get() / duration);
      if (totalBloomWrites.get() > 0) {
        printInfo("Bloom write rate: %.2f writes/second",
            (double) totalBloomWrites.get() / duration);
      }
    }

    // Result judgment
    if (errorCount.get() == 0) {
      printInfo("✓ Backfill completed successfully!");
    } else {
      printWarning("⚠ Backfill completed with %d errors.", errorCount.get());
    }
  }

  private void printInfo(String format, Object... args) {
    String message = String.format(Locale.ROOT, format, args);
    logger.info(message);
    spec.commandLine().getOut().println(message);
  }

  private void printWarning(String format, Object... args) {
    String message = String.format(Locale.ROOT, format, args);
    logger.warn(message);
    spec.commandLine().getOut().println(message);
  }

  private void printError(String format, Object... args) {
    String message = String.format(Locale.ROOT, format, args);
    logger.error(message);
    spec.commandLine().getErr().println(message);
  }

  private void printError(Throwable cause, String format, Object... args) {
    String message = String.format(Locale.ROOT, format, args);
    logger.error(message, cause);
    spec.commandLine().getErr().println(message);
  }
}
