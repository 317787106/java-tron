package org.tron.core.net.messagehandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.ChainBaseManager;
import org.tron.core.config.args.Args;
import org.tron.core.exception.BadItemException;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.exception.P2pException;
import org.tron.core.exception.P2pException.TypeEnum;
import org.tron.core.exception.TransactionExpirationException;
import org.tron.core.net.TronNetDelegate;
import org.tron.core.net.message.TronMessage;
import org.tron.core.net.message.adv.TransactionMessage;
import org.tron.core.net.message.adv.TransactionsMessage;
import org.tron.core.net.peer.Item;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.service.adv.AdvService;
import org.tron.protos.Protocol.Inventory.InventoryType;
import org.tron.protos.Protocol.ReasonCode;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;

@Slf4j(topic = "net")
@Component
public class TransactionsMsgHandler implements TronMsgHandler {

  private static int MAX_TRX_SIZE = 50_000;
  private static int MAX_SMART_CONTRACT_SUBMIT_SIZE = 100;
  @Autowired
  private TronNetDelegate tronNetDelegate;
  @Autowired
  private AdvService advService;
  @Autowired
  private ChainBaseManager chainBaseManager;

  private BlockingQueue<TrxEvent> smartContractQueue = new LinkedBlockingQueue(MAX_TRX_SIZE);

  private BlockingQueue<Runnable> queue = new LinkedBlockingQueue();

  private int threadNum = Args.getInstance().getValidateSignThreadNum();
  private final String trxEsName = "trx-msg-handler";
  private ExecutorService trxHandlePool = ExecutorServiceManager.newThreadPoolExecutor(
      threadNum, threadNum, 0L,
      TimeUnit.MILLISECONDS, queue, trxEsName);
  private final String smartEsName = "contract-msg-handler";
  private final ScheduledExecutorService smartContractExecutor = ExecutorServiceManager
      .newSingleThreadScheduledExecutor(smartEsName);

  public void init() {
    handleSmartContract();
    new Thread(() -> {
      try {
        loadAndBroadCastTx();
      } catch (Exception e) {
        logger.error("", e);
      }
    }).start();
  }

  public void close() {
    ExecutorServiceManager.shutdownAndAwaitTermination(trxHandlePool, trxEsName);
    ExecutorServiceManager.shutdownAndAwaitTermination(smartContractExecutor, smartEsName);
  }

  public boolean isBusy() {
    return queue.size() + smartContractQueue.size() > MAX_TRX_SIZE;
  }

  private int getBroadCastPeerCount() {
    return (int) tronNetDelegate.getActivePeer().stream()
        .filter(p -> !p.isNeedSyncFromPeer() && !p.isNeedSyncFromUs())
        .count();
  }

  private void loadAndBroadCastTx() throws IOException, InterruptedException {
    while (getBroadCastPeerCount() == 0) {
      logger.info("No available peers to broadcast, please wait");
      Thread.sleep(10_000);
    }

    String path = CommonParameter.getInstance().outputDirectory + "/sample.dat";
    File f = new File(path);
    FileInputStream fis;
    try {
      fis = new FileInputStream(f);
    } catch (FileNotFoundException e) {
      logger.error("File {} not exist, skip load and broadcast tx", path);
      return;
    }

    long startNum = tronNetDelegate.getHeadBlockId().getNum();
    long startTime;
    try {
      startTime = chainBaseManager.getBlockByNum(startNum).getTimeStamp();
    } catch (Exception e) {
      return;
    }
    int stressTps = 2000; //read from file

    logger.info("Begin to broadcast transactions from {}, tps {}", path, stressTps);
    int total = 0;
    int count = 0;
    Transaction transaction;
    while ((transaction = Transaction.parseDelimitedFrom(fis)) != null) {
      total += 1;
      if (total % stressTps == 0) {
        logger.info("Load tx {}", total);
      }
      TransactionMessage trx = new TransactionMessage(transaction);
      advService.broadcast(trx);
      if (count % stressTps == 0) {
        logger.info("Broadcast tx {}", total);
        Thread.sleep(500);
      }
      count += 1;
    }
    logger.info("Load tx {}, broadcast tx {}", total, count);

    fis.close();

    Thread.sleep(10_000);
    try {
      reportStress(startNum, startTime);
    } catch (Exception e) {
      logger.error("", e);
    }
  }

  private void reportStress(long startNum, long startTime)
      throws BadItemException, ItemNotFoundException {
    //get last non-empty block
    long endNum = chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber();
    while (chainBaseManager.getBlockByNum(endNum).getInstance().getTransactionsCount() == 0
        && endNum >= startNum) {
      endNum -= 1;
    }
    long endTime = chainBaseManager.getBlockByNum(endNum).getTimeStamp();

    int total = 0;
    int max = 0;
    int min = Integer.MAX_VALUE;
    for (long i = startNum + 1; i <= endNum; i++) {
      int txSize = chainBaseManager.getBlockByNum(i).getInstance().getTransactionsCount();
      total += txSize;
      max = Math.max(max, txSize);
      min = Math.min(min, txSize);
    }

    int timeCost = (int) (endTime - startTime) / 1000;
    int tps = timeCost == 0 ? 0 : total / timeCost;
    int shouldGenerateBlockCount = (int) (endTime - startTime) / 3;
    int missBlock = shouldGenerateBlockCount - (int) (endNum - startNum);
    float missBlockRate = missBlock * 100 / (float) shouldGenerateBlockCount;

    logger.info("Total transactions: {}}, cost time: {}, max block size : {}, min block size : {}, "
            + "push block average tps: {}/s, MissBlockRate: {}%", total, timeCost, max, min, tps,
        String.format("%.1f", missBlockRate));
  }

  @Override
  public void processMessage(PeerConnection peer, TronMessage msg) throws P2pException {
    TransactionsMessage transactionsMessage = (TransactionsMessage) msg;
    check(peer, transactionsMessage);
    int smartContractQueueSize = 0;
    int trxHandlePoolQueueSize = 0;
    int dropSmartContractCount = 0;
    for (Transaction trx : transactionsMessage.getTransactions().getTransactionsList()) {
      int type = trx.getRawData().getContract(0).getType().getNumber();
      if (type == ContractType.TriggerSmartContract_VALUE
          || type == ContractType.CreateSmartContract_VALUE) {
        if (!smartContractQueue.offer(new TrxEvent(peer, new TransactionMessage(trx)))) {
          smartContractQueueSize = smartContractQueue.size();
          trxHandlePoolQueueSize = queue.size();
          dropSmartContractCount++;
        }
      } else {
        trxHandlePool.submit(() -> handleTransaction(peer, new TransactionMessage(trx)));
      }
    }

    if (dropSmartContractCount > 0) {
      logger.warn("Add smart contract failed, drop count: {}, queueSize {}:{}",
          dropSmartContractCount, smartContractQueueSize, trxHandlePoolQueueSize);
    }
  }

  private void check(PeerConnection peer, TransactionsMessage msg) throws P2pException {
    for (Transaction trx : msg.getTransactions().getTransactionsList()) {
      Item item = new Item(new TransactionMessage(trx).getMessageId(), InventoryType.TRX);
      if (!peer.getAdvInvRequest().containsKey(item)) {
        throw new P2pException(TypeEnum.BAD_MESSAGE,
            "trx: " + msg.getMessageId() + " without request.");
      }
      peer.getAdvInvRequest().remove(item);
    }
  }

  private void handleSmartContract() {
    smartContractExecutor.scheduleWithFixedDelay(() -> {
      try {
        while (queue.size() < MAX_SMART_CONTRACT_SUBMIT_SIZE && smartContractQueue.size() > 0) {
          TrxEvent event = smartContractQueue.take();
          trxHandlePool.submit(() -> handleTransaction(event.getPeer(), event.getMsg()));
        }
      } catch (InterruptedException e) {
        logger.warn("Handle smart server interrupted");
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        logger.error("Handle smart contract exception", e);
      }
    }, 1000, 20, TimeUnit.MILLISECONDS);
  }

  private void handleTransaction(PeerConnection peer, TransactionMessage trx) {
    if (peer.isBadPeer()) {
      logger.warn("Drop trx {} from {}, peer is bad peer", trx.getMessageId(),
          peer.getInetAddress());
      return;
    }

    if (advService.getMessage(new Item(trx.getMessageId(), InventoryType.TRX)) != null) {
      return;
    }

    try {
      trx.getTransactionCapsule().checkExpiration(tronNetDelegate.getNextBlockSlotTime());
      tronNetDelegate.pushTransaction(trx.getTransactionCapsule());
      advService.broadcast(trx);
    } catch (P2pException e) {
      logger.warn("Trx {} from peer {} process failed. type: {}, reason: {}",
          trx.getMessageId(), peer.getInetAddress(), e.getType(), e.getMessage());
      if (e.getType().equals(TypeEnum.BAD_TRX)) {
        peer.setBadPeer(true);
        peer.disconnect(ReasonCode.BAD_TX);
      }
    } catch (TransactionExpirationException e) {
      logger.warn("{}. trx: {}, peer: {}",
          e.getMessage(), trx.getMessageId(), peer.getInetAddress());
    } catch (Exception e) {
      logger.error("Trx {} from peer {} process failed", trx.getMessageId(), peer.getInetAddress(),
          e);
    }
  }

  class TrxEvent {

    @Getter
    private PeerConnection peer;
    @Getter
    private TransactionMessage msg;
    @Getter
    private long time;

    public TrxEvent(PeerConnection peer, TransactionMessage msg) {
      this.peer = peer;
      this.msg = msg;
      this.time = System.currentTimeMillis();
    }
  }
}