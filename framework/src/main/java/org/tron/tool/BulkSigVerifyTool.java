package org.tron.tool;

import com.google.protobuf.ByteString;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.crypto.ECKey;
import org.tron.common.crypto.Rsv;
import org.tron.common.crypto.SignUtils;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;

/**
 * Reads up to 1M blocks backwards from the chain head, verifies every transaction signature,
 * and reports the top-K slowest transactions.
 *
 * <p>Usage:
 * <pre>
 *   java BulkSigVerifyTool --output-directory /path/to/db [--topK 20]
 * </pre>
 */
@Slf4j(topic = "tool")
public class BulkSigVerifyTool {

  private static final long MAX_BLOCKS = 20_000L;
  private static final int DEFAULT_TOP_K = 100;

  private static class TxRecord {
    final long elapsedMs;
    final String txId;
    final long blockNum;

    TxRecord(long elapsedMs, String txId, long blockNum) {
      this.elapsedMs = elapsedMs;
      this.txId = txId;
      this.blockNum = blockNum;
    }
  }

  public static void main(String[] args) throws Exception {
    int topK = DEFAULT_TOP_K;
    List<String> passThrough = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      if ("--topK".equals(args[i]) && i + 1 < args.length) {
        topK = Integer.parseInt(args[++i]);
      } else {
        passThrough.add(args[i]);
      }
    }

    Args.setParam(passThrough.toArray(new String[0]), "config.conf");

    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    TronApplicationContext context = new TronApplicationContext(beanFactory);
    context.register(DefaultConfig.class);
    context.refresh();

    ChainBaseManager manager = ChainBaseManager.getInstance();
    boolean isECKey = CommonParameter.getInstance().isECKeyCryptoEngine();

    long headBlockNum = manager.getHeadBlockNum();
    long endBlock = Math.max(1L, headBlockNum - MAX_BLOCKS + 1);
    logger.info("Bulk sig verify: blocks [{} -> {}] topK={}", headBlockNum, endBlock, topK);

    // min-heap on elapsedMs — so poll() removes the smallest, keeping the K largest
    final int k = topK;
    PriorityQueue<TxRecord> topKHeap = new PriorityQueue<>(
        k + 1, Comparator.comparingLong(r -> r.elapsedMs));

    long totalTxs = 0;
    long totalFails = 0;

    for (long num = headBlockNum; num >= endBlock; num--) {
      BlockCapsule block;
      try {
        block = manager.getBlockByNum(num);
      } catch (Exception e) {
        logger.error("Cannot read block {}: {}", num, e.getMessage());
        continue;
      }

      List<TransactionCapsule> txs = block.getTransactions();
      long blockStart = System.currentTimeMillis();

      for (TransactionCapsule tx : txs) {
        byte[] hash = tx.getTransactionId().getBytes();
        String txId = tx.getTransactionId().toString();
        List<ByteString> sigs = tx.getInstance().getSignatureList();

        long txStart = System.currentTimeMillis();

        for (ByteString sig : sigs) {
          try {
            Rsv rsv = Rsv.fromSignature(sig.toByteArray());
            String sigBase64 = ECKey.ECDSASignature
                .fromComponents(rsv.getR(), rsv.getS(), rsv.getV()).toBase64();
            byte[] recovered = SignUtils.signatureToAddress(hash, sigBase64, isECKey);
          } catch (SignatureException e) {
            logger.error("Verify failed: block={} txId={} error={}", num, txId, e.getMessage());
            totalFails++;
          }
        }

        long txElapsed = System.currentTimeMillis() - txStart;
        if (topKHeap.size() < k) {
          topKHeap.offer(new TxRecord(txElapsed, txId, num));
        } else if (topKHeap.peek().elapsedMs < txElapsed) {
          topKHeap.poll();
          topKHeap.offer(new TxRecord(txElapsed, txId, num));
        }
      }

      long blockElapsed = System.currentTimeMillis() - blockStart;
      logger.info("Block {} txCount={} verifyTime={}ms", num, txs.size(), blockElapsed);
      totalTxs += txs.size();
    }

    logger.info("Done: totalTxs={} totalFails={}", totalTxs, totalFails);

    List<TxRecord> topList = new ArrayList<>(topKHeap);
    topList.sort((a, b) -> Long.compare(b.elapsedMs, a.elapsedMs));
    logger.info("=== Top {} slowest transactions ===", topList.size());
    for (int i = 0; i < topList.size(); i++) {
      TxRecord r = topList.get(i);
      logger.info("  #{} txId={} block={} elapsedMs={}", i + 1, r.txId, r.blockNum, r.elapsedMs);
    }

    context.close();
  }
}
