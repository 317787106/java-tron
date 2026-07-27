package org.tron.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.tron.common.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

public class DbBackfillBloomTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private String databaseDirectory;
  private CommandLine cli;
  private ByteArrayOutputStream outputStream;
  private ByteArrayOutputStream errorStream;
  private PrintStream originalOut;
  private PrintStream originalErr;
  private MockedStatic<DbTool> dbToolMock;

  @Before
  public void setUp() throws IOException {
    // Create temporary database directory
    databaseDirectory = temporaryFolder.newFolder().toString();
    assertTrue(new File(databaseDirectory, "properties").mkdir());
    assertTrue(new File(databaseDirectory, "transactionRetStore").mkdir());

    // Create the command line interface using Toolkit as the root command
    cli = new CommandLine(new Toolkit());

    // Capture output streams
    outputStream = new ByteArrayOutputStream();
    errorStream = new ByteArrayOutputStream();
    originalOut = System.out;
    originalErr = System.err;
    System.setOut(new PrintStream(outputStream));
    System.setErr(new PrintStream(errorStream));

    // Mock DbTool static methods
    dbToolMock = mockStatic(DbTool.class);
  }

  private DBIterator mockMinBlock(DBInterface transactionRetDb, long blockNumber) {
    DBIterator iterator = mock(DBIterator.class);
    when(transactionRetDb.iterator()).thenReturn(iterator);
    when(iterator.hasNext()).thenReturn(true);
    when(iterator.getKey()).thenReturn(ByteArray.fromLong(blockNumber));
    return iterator;
  }

  @After
  public void tearDown() {
    // Restore original streams
    System.setOut(originalOut);
    System.setErr(originalErr);

    // Close static mock
    if (dbToolMock != null) {
      dbToolMock.close();
    }
  }

  @Test
  public void testHelp() {
    String[] args = new String[] { "db", "backfill-bloom", "-h" };
    assertEquals(0, cli.execute(args));
    assertTrue(outputStream.toString().contains(
        "The same block range can be safely rerun after interruption."));
  }

  @Test
  public void testValidParametersWithMockedDatabase() throws Exception {
    // Mock database interfaces
    DBInterface transactionRetDb = mock(DBInterface.class);
    DBInterface sectionBloomDb = mock(DBInterface.class);
    DBInterface propertiesDb = mock(DBInterface.class);

    // Mock DbTool.getDB calls
    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenAnswer(invocation -> {
          String dbName = invocation.getArgument(1);
          switch (dbName) {
            case "transactionRetStore":
              return transactionRetDb;
            case "section-bloom":
              return sectionBloomDb;
            case "properties":
              return propertiesDb;
            default:
              return mock(DBInterface.class);
          }
        });

    // Mock latest block number
    when(propertiesDb.get(any(byte[].class)))
        .thenReturn(ByteArray.fromLong(1000L));
    mockMinBlock(transactionRetDb, 1L);

    // Mock empty transaction data (no transactions to process)
    when(transactionRetDb.get(any(byte[].class)))
        .thenReturn(null);

    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "100",
        "-e", "200",
        "-c", "2"
    };

    assertEquals(0, cli.execute(args));
  }

  @Test
  public void testSummaryReportsScannedBlocks() throws Exception {
    // Mock database interfaces
    DBInterface transactionRetDb = mock(DBInterface.class);
    DBInterface sectionBloomDb = mock(DBInterface.class);
    DBInterface propertiesDb = mock(DBInterface.class);

    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenAnswer(invocation -> {
          String dbName = invocation.getArgument(1);
          switch (dbName) {
            case "transactionRetStore":
              return transactionRetDb;
            case "section-bloom":
              return sectionBloomDb;
            case "properties":
              return propertiesDb;
            default:
              return mock(DBInterface.class);
          }
        });

    when(propertiesDb.get(any(byte[].class)))
        .thenReturn(ByteArray.fromLong(1000L));
    mockMinBlock(transactionRetDb, 1L);
    when(transactionRetDb.get(any(byte[].class)))
        .thenReturn(null);

    // Capture the command's output writer (summary is printed via
    // spec.commandLine().getOut()). Follow the repo pattern: set the writer on a
    // fresh root CommandLine, which picocli propagates to the subcommand on execute.
    StringWriter out = new StringWriter();
    CommandLine cmd = new CommandLine(new Toolkit());
    cmd.setOut(new PrintWriter(out));

    // Blocks 100..200 inclusive = 101 blocks scanned.
    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "100",
        "-e", "200",
        "-c", "2"
    };

    assertEquals(0, cmd.execute(args));

    // Guards against the regression where processedBlocks was never incremented,
    // which made the whole summary report 0 scanned blocks and skip the rates.
    String output = out.toString();
    assertTrue(output.contains("Total blocks scanned: 101"));
    assertTrue(output.contains("Success rate: 100.00%"));
  }

  @Test
  public void testLogsProgressEveryTenThousandBlocksWithoutWritingToCommandOutput()
      throws Exception {
    DBInterface transactionRetDb = mock(DBInterface.class);
    DBInterface sectionBloomDb = mock(DBInterface.class);
    DBInterface propertiesDb = mock(DBInterface.class);

    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenAnswer(invocation -> {
          String dbName = invocation.getArgument(1);
          switch (dbName) {
            case "transactionRetStore":
              return transactionRetDb;
            case "section-bloom":
              return sectionBloomDb;
            case "properties":
              return propertiesDb;
            default:
              return mock(DBInterface.class);
          }
        });
    when(propertiesDb.get(any(byte[].class)))
        .thenReturn(ByteArray.fromLong(10_000L));
    mockMinBlock(transactionRetDb, 1L);
    when(transactionRetDb.get(any(byte[].class))).thenReturn(null);

    StringWriter out = new StringWriter();
    CommandLine cmd = new CommandLine(new Toolkit());
    cmd.setOut(new PrintWriter(out));

    Logger progressLogger = (Logger) LoggerFactory.getLogger("backfill-bloom");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    progressLogger.addAppender(appender);
    try {
      String[] args = new String[] {
          "db", "backfill-bloom",
          "-d", databaseDirectory,
          "-s", "1",
          "-e", "10000",
          "-c", "1"
      };

      assertEquals(0, cmd.execute(args));

      long progressLogCount = appender.list.stream()
          .map(ILoggingEvent::getFormattedMessage)
          .filter(message -> message.startsWith(
              "Backfill progress: 10000/10000 blocks (100.00%)"))
          .count();
      assertEquals(1L, progressLogCount);
      assertFalse(out.toString().contains("Backfill progress:"));
    } finally {
      progressLogger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testInvalidStartBlock() {
    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "-1"
    };
    assertEquals(1, cli.execute(args));
  }

  @Test
  public void testInvalidConcurrency() {
    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "100",
        "-c", "0"
    };
    assertEquals(1, cli.execute(args));
  }

  @Test
  public void testInvalidConcurrencyTooHigh() {
    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "100",
        "-c", "200"
    };
    assertEquals(1, cli.execute(args));
  }

  @Test
  public void testNonExistentDatabaseDirectory() {
    String nonExistentDir = databaseDirectory + File.separator + UUID.randomUUID();
    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", nonExistentDir,
        "-s", "100"
    };
    assertEquals(1, cli.execute(args));
  }

  @Test
  public void testMissingPropertiesDatabaseIsRejectedBeforeOpeningDatabases() {
    File databaseRoot = temporaryFolder.getRoot();
    File transactionRetDirectory = new File(databaseRoot, "only-transaction-ret");
    assertTrue(transactionRetDirectory.mkdir());
    assertTrue(new File(transactionRetDirectory, "transactionRetStore").mkdir());

    StringWriter err = new StringWriter();
    CommandLine cmd = new CommandLine(new Toolkit());
    cmd.setErr(new PrintWriter(err));

    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", transactionRetDirectory.toString(),
        "-e", "100"
    };

    assertEquals(1, cmd.execute(args));
    assertTrue(err.toString().contains("Required database 'properties' does not exist"));
    dbToolMock.verify(() -> DbTool.close());
    dbToolMock.verifyNoMoreInteractions();
  }

  @Test
  public void testMissingTransactionRetDatabaseIsRejectedBeforeOpeningDatabases() {
    File databaseRoot = temporaryFolder.getRoot();
    File propertiesDirectory = new File(databaseRoot, "only-properties");
    assertTrue(propertiesDirectory.mkdir());
    assertTrue(new File(propertiesDirectory, "properties").mkdir());

    StringWriter err = new StringWriter();
    CommandLine cmd = new CommandLine(new Toolkit());
    cmd.setErr(new PrintWriter(err));

    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", propertiesDirectory.toString(),
        "-e", "100"
    };

    assertEquals(1, cmd.execute(args));
    assertTrue(err.toString().contains(
        "Required database 'transactionRetStore' does not exist"));
    dbToolMock.verify(() -> DbTool.close());
    dbToolMock.verifyNoMoreInteractions();
  }

  @Test
  public void testEndBlockLessThanStartBlock() throws Exception {
    // Mock database interfaces
    DBInterface transactionRetDb = mock(DBInterface.class);
    DBInterface sectionBloomDb = mock(DBInterface.class);
    DBInterface propertiesDb = mock(DBInterface.class);

    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenAnswer(invocation -> {
          String dbName = invocation.getArgument(1);
          switch (dbName) {
            case "transactionRetStore":
              return transactionRetDb;
            case "section-bloom":
              return sectionBloomDb;
            case "properties":
              return propertiesDb;
            default:
              return mock(DBInterface.class);
          }
        });
    when(propertiesDb.get(any(byte[].class)))
        .thenReturn(ByteArray.fromLong(1000L));
    mockMinBlock(transactionRetDb, 1L);

    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "200",
        "-e", "100"
    };
    assertEquals(1, cli.execute(args));
  }

  @Test
  public void testDatabaseInitializationFailure() {
    // Mock DbTool to throw exception
    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenThrow(new RuntimeException("Database connection failed"));

    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "100"
    };
    assertEquals(1, cli.execute(args));
  }

  @Test
  public void testAutoDetectEndBlock() throws Exception {
    // Mock database interfaces
    DBInterface transactionRetDb = mock(DBInterface.class);
    DBInterface sectionBloomDb = mock(DBInterface.class);
    DBInterface propertiesDb = mock(DBInterface.class);

    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenAnswer(invocation -> {
          String dbName = invocation.getArgument(1);
          switch (dbName) {
            case "transactionRetStore":
              return transactionRetDb;
            case "section-bloom":
              return sectionBloomDb;
            case "properties":
              return propertiesDb;
            default:
              return mock(DBInterface.class);
          }
        });

    // Mock latest block number
    when(propertiesDb.get(any(byte[].class)))
        .thenReturn(ByteArray.fromLong(5000L));
    mockMinBlock(transactionRetDb, 1L);

    // Mock empty transaction data
    when(transactionRetDb.get(any(byte[].class)))
        .thenReturn(null);

    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "100"
        // No end block specified - should auto-detect
    };

    assertEquals(0, cli.execute(args));
  }

  @Test
  public void testAdjustsStartBlockToMinimumNonZeroTransactionResultBlock() throws Exception {
    DBInterface transactionRetDb = mock(DBInterface.class);
    DBInterface sectionBloomDb = mock(DBInterface.class);
    DBInterface propertiesDb = mock(DBInterface.class);
    DBIterator iterator = mock(DBIterator.class);

    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenAnswer(invocation -> {
          String dbName = invocation.getArgument(1);
          switch (dbName) {
            case "transactionRetStore":
              return transactionRetDb;
            case "section-bloom":
              return sectionBloomDb;
            case "properties":
              return propertiesDb;
            default:
              return mock(DBInterface.class);
          }
        });

    when(propertiesDb.get(any(byte[].class)))
        .thenReturn(ByteArray.fromLong(12L));
    when(transactionRetDb.iterator()).thenReturn(iterator);
    when(iterator.hasNext()).thenReturn(true);
    when(iterator.getKey()).thenReturn(ByteArray.fromLong(10L));
    when(transactionRetDb.get(any(byte[].class))).thenReturn(null);

    StringWriter out = new StringWriter();
    CommandLine cmd = new CommandLine(new Toolkit());
    cmd.setOut(new PrintWriter(out));

    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "5",
        "-e", "12"
    };

    assertEquals(0, cmd.execute(args));
    assertTrue(out.toString().contains(
        "Start block 5 is earlier than the first available transaction result block 10"));
    assertTrue(out.toString().contains(
        "Starting SectionBloom backfill for block number 10 to 12 (3 blocks)"));
    verify(iterator).seek(aryEq(ByteArray.fromLong(1)));
    verify(iterator).close();
  }

  @Test
  public void testFailsWhenTransactionResultStoreIsEmpty() throws Exception {
    DBInterface transactionRetDb = mock(DBInterface.class);
    DBInterface sectionBloomDb = mock(DBInterface.class);
    DBInterface propertiesDb = mock(DBInterface.class);
    DBIterator iterator = mock(DBIterator.class);

    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenAnswer(invocation -> {
          String dbName = invocation.getArgument(1);
          switch (dbName) {
            case "transactionRetStore":
              return transactionRetDb;
            case "section-bloom":
              return sectionBloomDb;
            case "properties":
              return propertiesDb;
            default:
              return mock(DBInterface.class);
          }
        });

    when(propertiesDb.get(any(byte[].class)))
        .thenReturn(ByteArray.fromLong(6L));
    when(transactionRetDb.iterator()).thenReturn(iterator);
    when(iterator.hasNext()).thenReturn(false);

    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine cmd = new CommandLine(new Toolkit());
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));

    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "5",
        "-e", "6"
    };

    assertEquals(1, cmd.execute(args));
    assertTrue(err.toString().contains(
        "Transaction result database does not contain any non-zero block"));
    assertFalse(out.toString().contains("Starting SectionBloom backfill"));
    verify(iterator).seek(aryEq(ByteArray.fromLong(1)));
    verify(iterator).close();
  }

  @Test
  public void testFailsWhenMinimumBlockCannotBeRead() throws Exception {
    DBInterface transactionRetDb = mock(DBInterface.class);
    DBInterface sectionBloomDb = mock(DBInterface.class);
    DBInterface propertiesDb = mock(DBInterface.class);

    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenAnswer(invocation -> {
          String dbName = invocation.getArgument(1);
          switch (dbName) {
            case "transactionRetStore":
              return transactionRetDb;
            case "section-bloom":
              return sectionBloomDb;
            case "properties":
              return propertiesDb;
            default:
              return mock(DBInterface.class);
          }
        });

    when(propertiesDb.get(any(byte[].class)))
        .thenReturn(ByteArray.fromLong(6L));
    when(transactionRetDb.iterator())
        .thenThrow(new RuntimeException("iterator failed"));

    StringWriter err = new StringWriter();
    CommandLine cmd = new CommandLine(new Toolkit());
    cmd.setErr(new PrintWriter(err));

    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "1",
        "-e", "6"
    };

    assertEquals(1, cmd.execute(args));
    assertTrue(err.toString().contains(
        "Failed to determine the first transaction result block"));
  }

  @Test
  public void testProcessingErrorIsWrittenToStderr() throws Exception {
    DBInterface transactionRetDb = mock(DBInterface.class);
    DBInterface sectionBloomDb = mock(DBInterface.class);
    DBInterface propertiesDb = mock(DBInterface.class);

    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenAnswer(invocation -> {
          String dbName = invocation.getArgument(1);
          switch (dbName) {
            case "transactionRetStore":
              return transactionRetDb;
            case "section-bloom":
              return sectionBloomDb;
            case "properties":
              return propertiesDb;
            default:
              return mock(DBInterface.class);
          }
        });

    when(propertiesDb.get(any(byte[].class)))
        .thenReturn(ByteArray.fromLong(1L));
    mockMinBlock(transactionRetDb, 1L);
    when(transactionRetDb.get(any(byte[].class)))
        .thenThrow(new RuntimeException("read failed"));

    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine cmd = new CommandLine(new Toolkit());
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));

    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "1",
        "-e", "1"
    };

    assertEquals(1, cmd.execute(args));
    assertTrue(err.toString().contains("Error processing block 1"));
    assertFalse(out.toString().contains("Error processing block 1"));
  }

  @Test
  public void testMissingLatestSolidityBlockNumber() throws Exception {
    // Mock database interfaces
    DBInterface transactionRetDb = mock(DBInterface.class);
    DBInterface sectionBloomDb = mock(DBInterface.class);
    DBInterface propertiesDb = mock(DBInterface.class);

    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenAnswer(invocation -> {
          String dbName = invocation.getArgument(1);
          switch (dbName) {
            case "transactionRetStore":
              return transactionRetDb;
            case "section-bloom":
              return sectionBloomDb;
            case "properties":
              return propertiesDb;
            default:
              return mock(DBInterface.class);
          }
        });

    // Mock null latest block number (failed to get)
    when(propertiesDb.get(any(byte[].class)))
        .thenReturn(null);

    StringWriter err = new StringWriter();
    CommandLine cmd = new CommandLine(new Toolkit());
    cmd.setErr(new PrintWriter(err));

    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "100"
        // No end block specified - should fail to auto-detect
    };
    assertEquals(1, cmd.execute(args));
    assertTrue(err.toString().contains("Latest solidified block number does not exist"));
    verify(propertiesDb).get(aryEq(
        "LATEST_SOLIDIFIED_BLOCK_NUM".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void testFailsWhenLatestSolidityBlockNumberCannotBeRead() {
    DBInterface transactionRetDb = mock(DBInterface.class);
    DBInterface sectionBloomDb = mock(DBInterface.class);
    DBInterface propertiesDb = mock(DBInterface.class);

    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenAnswer(invocation -> {
          String dbName = invocation.getArgument(1);
          switch (dbName) {
            case "transactionRetStore":
              return transactionRetDb;
            case "section-bloom":
              return sectionBloomDb;
            case "properties":
              return propertiesDb;
            default:
              return mock(DBInterface.class);
          }
        });
    when(propertiesDb.get(any(byte[].class)))
        .thenThrow(new RuntimeException("read failed"));

    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine cmd = new CommandLine(new Toolkit());
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));

    String[] args = new String[] {
        "db", "backfill-bloom",
        "-d", databaseDirectory,
        "-s", "1",
        "-e", "6"
    };

    assertEquals(1, cmd.execute(args));
    assertTrue(err.toString().contains("Failed to read latest solidified block number"));
    assertFalse(out.toString().contains("using -1 instead"));
  }

  @Test
  public void testDefaultParameters() throws Exception {
    // Mock database interfaces
    DBInterface transactionRetDb = mock(DBInterface.class);
    DBInterface sectionBloomDb = mock(DBInterface.class);
    DBInterface propertiesDb = mock(DBInterface.class);

    dbToolMock.when(() -> DbTool.getDB(anyString(), anyString()))
        .thenAnswer(invocation -> {
          String dbName = invocation.getArgument(1);
          switch (dbName) {
            case "transactionRetStore":
              return transactionRetDb;
            case "section-bloom":
              return sectionBloomDb;
            case "properties":
              return propertiesDb;
            default:
              return mock(DBInterface.class);
          }
        });

    // Mock latest block number
    when(propertiesDb.get(any(byte[].class)))
        .thenReturn(ByteArray.fromLong(1000L));

    // Mock empty transaction data
    when(transactionRetDb.get(any(byte[].class)))
        .thenReturn(null);

    // Test with default database directory
    String[] args = new String[] {
        "db", "backfill-bloom",
        "-s", "100",
        "-e", "200"
    };

    // This should fail because default directory doesn't exist
    assertEquals(1, cli.execute(args));
  }
}
