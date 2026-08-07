package org.tron.common.parameter;

import com.google.common.annotations.VisibleForTesting;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.tron.common.args.GenesisBlock;
import org.tron.common.cron.CronExpression;
import org.tron.common.logsfilter.EventPluginConfig;
import org.tron.common.logsfilter.FilterQuery;
import org.tron.common.setting.RocksDbSettings;
import org.tron.core.Constant;
import org.tron.core.config.args.SeedNode;
import org.tron.core.config.args.Storage;
import org.tron.p2p.P2pConfig;
import org.tron.p2p.dns.update.PublishConfig;

public class CommonParameter {

  // Install the JUL->SLF4J bridge early so that JUL log records emitted during
  // static init of grpc classes (or from unit tests that don't invoke
  // LogService.load()) still reach Logback.
  // removeHandlersForRootLogger() strips JUL's default ConsoleHandler so the
  // same record is not emitted twice (once by JUL's own console output and
  // once via the bridge to Logback).
  static {
    SLF4JBridgeHandler.removeHandlersForRootLogger();
    if (!SLF4JBridgeHandler.isInstalled()) {
      SLF4JBridgeHandler.install();
    }
  }

  protected static CommonParameter PARAMETER = new CommonParameter();

  // Runtime chain state: set by VMConfig.initVmHardFork()
  // when the energy-limit governance proposal is activated.
  // Legacy: should belong to VMConfig, not here.
  @Setter
  @Exportable
  public static boolean ENERGY_LIMIT_HARD_FORK = false;

  // -- Startup parameters --
  @Getter
  @Exportable
  public String outputDirectory = "output-directory";
  @Getter
  @Exportable
  public String logbackPath = "";
  // -- Flags (CLI + Config) --
  @Getter
  @Setter
  @Exportable
  public boolean witness = false;
  @Getter
  @Setter
  @Exportable
  public boolean supportConstant = false;
  @Getter
  @Setter
  @Exportable
  public long maxEnergyLimitForConstant = 100_000_000L;
  @Getter
  @Setter
  @Exportable
  public int lruCacheSize = 500;
  @Getter
  @Setter
  @Exportable
  public boolean debug = false;
  @Getter
  @Setter
  @Exportable
  public double minTimeRatio = 0.0;
  @Getter
  @Setter
  @Exportable
  public double maxTimeRatio = calcMaxTimeRatio();
  /**
   * Max TVM execution time (ms) for constant calls — covers
   * triggerconstantcontract, triggersmartcontract dispatched to view/pure
   * functions, estimateenergy, eth_call, eth_estimateGas, and any other
   * RPC routed through Wallet#callConstantContract. 0 = use the same
   * deadline as block processing (current behaviour). When operators set
   * this in config the value must be positive and fit VM deadline conversion;
   * validated at config-load in VmConfig.
   */
  @Getter
  @Setter
  @Exportable
  public long constantCallTimeoutMs = 0L;
  @Getter
  @Setter
  @Exportable
  public boolean saveInternalTx;
  @Getter
  @Setter
  @Exportable
  public boolean saveFeaturedInternalTx;
  @Getter
  @Setter
  @Exportable
  public boolean saveCancelAllUnfreezeV2Details;
  @Getter
  @Setter
  @Exportable
  public int longRunningTime = 10;
  @Getter
  @Setter
  @Exportable
  public int maxHttpConnectNumber = 50;
  @Getter
  public List<String> seedNodes = new ArrayList<>();
  @Getter
  @Exportable
  public boolean fastForward = false;
  // -- Network / P2P --
  @Getter
  @Setter
  @Exportable
  public String chainId;
  @Getter
  @Setter
  @Exportable
  public boolean needSyncCheck;
  @Getter
  @Setter
  @Exportable
  public boolean nodeDiscoveryEnable;
  @Getter
  @Setter
  @Exportable
  public boolean nodeDiscoveryPersist;
  @Getter
  @Setter
  @Exportable
  public boolean nodeEffectiveCheckEnable;
  @Getter
  @Setter
  @Exportable
  public int fetchBlockTimeout;
  @Getter
  @Setter
  @Exportable
  public int maxConnections = 30; // from clearParam(), consistent with mainnet.conf
  @Getter
  @Setter
  @Exportable
  public int minConnections = 8; // from clearParam(), consistent with mainnet.conf
  @Getter
  @Setter
  @Exportable
  public int minActiveConnections = 3; // from clearParam(), consistent with mainnet.conf
  @Getter
  @Setter
  @Exportable
  public int maxConnectionsWithSameIp = 2; // from clearParam(), consistent with mainnet.conf
  @Getter
  @Setter
  @Exportable
  public int maxTps; // clearParam: 1000
  @Getter
  @Setter
  @Exportable
  public int maxBlockInvPerSecond = 10; // default: 10 block inv hashes/s per peer
  @Getter
  @Setter
  @Exportable
  public int minParticipationRate;
  @Getter
  @Exportable
  public P2pConfig p2pConfig;
  @Getter
  @Setter
  @Exportable
  public int nodeListenPort;
  @Getter
  @Setter
  @Exportable
  public String nodeLanIp;
  @Getter
  @Setter
  @Exportable
  public String nodeExternalIp;
  @Getter
  @Setter
  @Exportable
  public int nodeP2pVersion;
  @Getter
  @Setter
  @Exportable
  public boolean nodeEnableIpv6 = false;
  @Getter
  @Setter
  @Exportable
  public List<String> dnsTreeUrls; // clearParam: new ArrayList<>()
  @Getter
  @Setter
  public PublishConfig dnsPublishConfig;
  @Getter
  @Setter
  @Exportable
  public long syncFetchBatchNum; // clearParam: 2000
  @Getter
  @Setter
  @Exportable
  public int maxPendingBlockSize;

  // If you are running a solidity node for java tron,
  // this flag is set to true
  @Getter
  @Setter
  @Exportable
  public boolean solidityNode = false;

  // If you are running KeystoreFactory,
  // this flag is set to true
  @Getter
  @Setter
  @Exportable
  public boolean keystoreFactory = false;

  // -- RPC / HTTP --
  @Getter
  @Setter
  @Exportable
  public int rpcPort;
  @Getter
  @Setter
  @Exportable
  public int rpcOnSolidityPort;
  @Getter
  @Setter
  @Exportable
  public int fullNodeHttpPort;
  @Getter
  @Setter
  @Exportable
  public int solidityHttpPort;
  @Getter
  @Setter
  @Exportable
  public int jsonRpcHttpFullNodePort;
  @Getter
  @Setter
  @Exportable
  public int jsonRpcHttpSolidityPort;
  @Getter
  @Setter
  @Exportable
  public int jsonRpcHttpPBFTPort;
  @Getter
  @Setter
  @Exportable
  public int rpcThreadNum;
  @Getter
  @Setter
  @Exportable
  public int solidityThreads;
  @Getter
  @Setter
  @Exportable
  public int maxConcurrentCallsPerConnection;
  @Getter
  @Setter
  @Exportable
  public int flowControlWindow;
  @Getter
  @Setter
  @Exportable
  public int rpcMaxRstStream;
  @Getter
  @Setter
  @Exportable
  public int rpcSecondsPerWindow;
  @Getter
  @Setter
  @Exportable
  public long maxConnectionIdleInMillis;
  @Getter
  @Setter
  @Exportable
  public int blockProducedTimeOut;
  @Getter
  @Setter
  @Exportable
  public long netMaxTrxPerSecond;
  @Getter
  @Setter
  @Exportable
  public long maxConnectionAgeInMillis;
  // Refers to RPC (gRPC) max message size; see httpMaxMessageSize / jsonRpcMaxMessageSize
  // below for the HTTP / JSON-RPC counterparts.
  @Getter
  @Setter
  @Exportable
  public int maxMessageSize;
  @Getter
  @Setter
  @Exportable
  public long httpMaxMessageSize;
  @Getter
  @Setter
  @Exportable
  public long jsonRpcMaxMessageSize;
  @Getter
  @Setter
  @Exportable
  public int maxHeaderListSize;
  @Getter
  @Setter
  @Exportable
  public boolean isRpcReflectionServiceEnable;
  @Getter
  @Setter
  @Exportable
  public int validateSignThreadNum;
  @Getter
  @Setter
  @Exportable
  public long maintenanceTimeInterval;
  @Getter
  @Setter
  @Exportable
  public long proposalExpireTime;
  @Getter
  @Setter
  @Exportable
  public int checkFrozenTime; // clearParam: 1

  // -- Committee parameters --
  @Getter
  @Setter
  public long allowCreationOfContracts;
  @Getter
  @Setter
  public long allowAdaptiveEnergy;
  @Getter
  @Setter
  public long allowDelegateResource;
  @Getter
  @Setter
  public long allowSameTokenName;
  @Getter
  @Setter
  public long allowTvmTransferTrc10;
  @Getter
  @Setter
  public long allowTvmConstantinople;
  @Getter
  @Setter
  public long allowTvmSolidity059;
  @Getter
  @Setter
  public long forbidTransferToContract;

  @Getter
  @Setter
  @Exportable
  public String trustNodeAddr; // clearParam: ""
  @Getter
  @Setter
  @Exportable
  public boolean walletExtensionApi;
  @Getter
  @Setter
  @Exportable
  public boolean estimateEnergy;
  @Getter
  @Setter
  @Exportable
  public int estimateEnergyMaxRetry = 3; // from clearParam(), consistent with mainnet.conf
  @Getter
  @Setter
  @Exportable
  public int backupPriority;
  @Getter
  @Setter
  @Exportable
  public int backupPort;
  @Getter
  @Setter
  @Exportable
  public int keepAliveInterval;
  @Getter
  @Setter
  @Exportable
  public List<String> backupMembers;
  @Getter
  @Setter
  @Exportable
  public boolean isOpenFullTcpDisconnect;
  @Getter
  @Setter
  @Exportable
  public int inactiveThreshold = 600; // from clearParam(), consistent with mainnet.conf
  @Getter
  @Setter
  @Exportable
  public boolean nodeDetectEnable;
  @Getter
  @Setter
  public int allowMultiSign;
  @Getter
  @Setter
  @Exportable
  public boolean vmTrace;
  @Getter
  @Setter
  @Exportable
  public boolean needToUpdateAsset;
  @Getter
  @Setter
  @Exportable
  public String trxReferenceBlock;
  @Getter
  @Setter
  @Exportable
  public int minEffectiveConnection;
  @Getter
  @Setter
  @Exportable
  public boolean trxCacheEnable;
  @Getter
  @Setter
  public long allowMarketTransaction;
  @Getter
  @Setter
  public long allowTransactionFeePool;
  @Getter
  @Setter
  public long allowBlackHoleOptimization;
  @Getter
  @Setter
  public long allowNewResourceModel;

  @Getter
  @Setter
  @Exportable
  public boolean allowShieldedTransactionApi; // clearParam: false
  @Getter
  @Setter
  @Exportable
  public long blockNumForEnergyLimit;
  @Getter
  @Setter
  @Exportable
  public boolean eventSubscribe = false;
  @Getter
  @Setter
  @Exportable
  public long trxExpirationTimeInMilliseconds;

  // -- Shielded / ZK --
  @Getter
  @Setter
  @Exportable
  public String zenTokenId; // clearParam: "000000"
  @Getter
  @Setter
  public long allowProtoFilterNum;
  @Getter
  @Setter
  public long allowAccountStateRoot;
  @Getter
  @Setter
  @Exportable
  public int validContractProtoThreadNum = 1;
  @Getter
  @Setter
  @Exportable
  public int shieldedTransInPendingMaxCounts; // clearParam: 10
  @Getter
  @Setter
  public long changedDelegation;
  @Getter
  @Setter
  @Exportable
  public RateLimiterInitialization rateLimiterInitialization;
  @Getter
  @Setter
  @Exportable
  public int rateLimiterGlobalQps = 50000; // from clearParam(), consistent with mainnet.conf
  @Getter
  @Setter
  @Exportable
  public int rateLimiterGlobalIpQps = 10000; // from clearParam(), consistent with mainnet.conf
  @Getter
  @Exportable
  public int rateLimiterGlobalApiQps = 1000; // from clearParam(), consistent with mainnet.conf
  @Getter
  @Setter
  @Exportable
  public double rateLimiterSyncBlockChain; // clearParam: 3.0
  @Getter
  @Setter
  @Exportable
  public double rateLimiterFetchInvData; // clearParam: 3.0
  @Getter
  @Setter
  @Exportable
  public double rateLimiterDisconnect; // clearParam: 1.0
  @Getter
  @Setter
  @Exportable
  public boolean rateLimiterApiNonBlocking = false;
  @Getter
  @Exportable
  public RocksDbSettings rocksDBCustomSettings;
  @Getter
  @Exportable
  public GenesisBlock genesisBlock;
  @Getter
  @Setter
  @Exportable
  public boolean p2pDisable = false;
  @Getter
  @Setter
  // from clearParam(), consistent with mainnet.conf
  @Exportable
  public List<InetSocketAddress> activeNodes = new ArrayList<>();
  @Getter
  @Setter
  // from clearParam(), consistent with mainnet.conf
  @Exportable
  public List<InetAddress> passiveNodes = new ArrayList<>();
  @Getter
  @Exportable
  public List<InetSocketAddress> fastForwardNodes; // clearParam: new ArrayList<>()
  @Getter
  @Exportable
  public int maxFastForwardNum; // clearParam: 4
  @Getter
  @Exportable
  public Storage storage;
  @Getter
  @Exportable
  public SeedNode seedNode;
  @Getter
  @Exportable
  public EventPluginConfig eventPluginConfig;
  @Getter
  @Exportable
  public FilterQuery eventFilter;
  @Getter
  @Setter
  @Exportable
  public String cryptoEngine = Constant.ECKey_ENGINE;

  @Getter
  @Setter
  @Exportable
  public boolean rpcEnable = true;
  @Getter
  @Setter
  @Exportable
  public boolean rpcSolidityEnable = true;
  @Getter
  @Setter
  @Exportable
  public boolean rpcPBFTEnable = true;
  @Getter
  @Setter
  @Exportable
  public boolean fullNodeHttpEnable = true;
  @Getter
  @Setter
  @Exportable
  public boolean solidityNodeHttpEnable = true;
  @Getter
  @Setter
  @Exportable
  public boolean pBFTHttpEnable = true;
  @Getter
  @Setter
  @Exportable
  public boolean jsonRpcHttpFullNodeEnable = false;
  @Getter
  @Setter
  @Exportable
  public boolean jsonRpcHttpSolidityNodeEnable = false;
  @Getter
  @Setter
  @Exportable
  public boolean jsonRpcHttpPBFTNodeEnable = false;
  @Getter
  @Setter
  @Exportable
  public int jsonRpcMaxBlockRange = 5000;
  @Getter
  @Setter
  @Exportable
  public int jsonRpcMaxSubTopics = 1000;
  @Getter
  @Setter
  @Exportable
  public int jsonRpcMaxBlockFilterNum = 50000;
  @Getter
  @Setter
  @Exportable
  public int jsonRpcMaxBatchSize = 100;
  @Getter
  @Setter
  @Exportable
  public int jsonRpcMaxResponseSize = 25 * 1024 * 1024;
  @Getter
  @Setter
  @Exportable
  public int jsonRpcMaxAddressSize = 1000;
  @Getter
  @Setter
  @Exportable
  public int jsonRpcMaxLogFilterNum = 20000;
  @Getter
  @Setter
  @Exportable
  public boolean adminRpcEnable = false;
  @Getter
  @Setter
  @Exportable
  public String adminListenAddress = Constant.LOCAL_HOST;
  @Getter
  @Setter
  @Exportable
  public int adminListenPort = 8575;
  @Getter
  @Setter
  @Exportable
  public boolean ipcEnable = false;
  @Getter
  @Setter
  @Exportable
  public int maxTransactionPendingSize;
  @Getter
  @Setter
  @Exportable
  public long pendingTransactionTimeout;
  @Getter
  @Setter
  @Exportable
  public int maxTrxCacheSize;
  @Getter
  @Setter
  @Exportable
  public boolean nodeMetricsEnable = false;
  @Getter
  @Setter
  @Exportable
  public boolean metricsPrometheusEnable = false;
  @Getter
  @Setter
  @Exportable
  public int metricsPrometheusPort;
  @Getter
  @Setter
  @Exportable
  public int agreeNodeCount;
  @Getter
  @Setter
  public long allowPBFT;
  @Getter
  @Setter
  @Exportable
  public int rpcOnPBFTPort;
  @Getter
  @Setter
  @Exportable
  public int pBFTHttpPort;

  @Getter
  @Setter
  public long pBFTExpireNum; // clearParam: 20
  @Getter
  @Setter
  @Exportable
  public long oldSolidityBlockNum = -1;

  @Getter
  @Setter
  public long allowShieldedTRC20Transaction;
  @Getter
  @Setter
  public long allowTvmIstanbul;
  @Getter
  @Setter
  public long allowTvmFreeze;
  @Getter
  @Setter
  public long allowTvmVote;
  @Getter
  @Setter
  public long allowTvmLondon;
  @Getter
  @Setter
  public long allowTvmCompatibleEvm;
  @Getter
  @Setter
  public long allowHigherLimitForMaxCpuTimeOfOneTx;
  @Getter
  @Setter
  @Exportable
  public boolean openHistoryQueryWhenLiteFN = false;
  @Getter
  @Setter
  @Exportable
  public boolean historyBalanceLookup = false;
  @Getter
  @Setter
  @Exportable
  public boolean openPrintLog = true;
  @Getter
  @Setter
  @Exportable
  public boolean openTransactionSort = false;
  @Getter
  @Setter
  public long allowAccountAssetOptimization;
  @Getter
  @Setter
  public long allowAssetOptimization;
  @Getter
  @Setter
  @Exportable
  public List<String> disabledApiList; // clearParam: Collections.emptyList()
  @Getter
  @Setter
  @Exportable
  public CronExpression shutdownBlockTime = null;
  @Getter
  @Setter
  @Exportable
  public long shutdownBlockHeight = -1;
  @Getter
  @Setter
  @Exportable
  public long shutdownBlockCount = -1;
  @Getter
  @Setter
  @Exportable
  public long blockCacheTimeout = 60;
  @Getter
  @Setter
  public long allowNewRewardAlgorithm;
  @Getter
  @Setter
  public long allowNewReward = 0L;
  @Getter
  @Setter
  public long memoFee = 0L;
  @Getter
  @Setter
  public long allowDelegateOptimization = 0L;
  @Getter
  @Setter
  public long unfreezeDelayDays = 0L;
  @Getter
  @Setter
  public long allowOptimizedReturnValueOfChainId = 0L;
  @Getter
  @Setter
  public long allowDynamicEnergy = 0L;
  @Getter
  @Setter
  public long dynamicEnergyThreshold = 0L;
  @Getter
  @Setter
  public long dynamicEnergyIncreaseFactor = 0L;
  @Getter
  @Setter
  public long dynamicEnergyMaxFactor = 0L;
  @Getter
  @Setter
  @Exportable
  public boolean dynamicConfigEnable;
  @Getter
  @Setter
  @Exportable
  public long dynamicConfigCheckInterval; // clearParam: 600
  @Getter
  @Setter
  public long allowTvmShangHai;
  @Getter
  @Setter
  @Exportable
  public long allowCancelAllUnfreezeV2;
  @Getter
  @Setter
  @Exportable
  public boolean unsolidifiedBlockCheck;
  @Getter
  @Setter
  @Exportable
  public int maxUnsolidifiedBlocks; // clearParam: 54
  @Getter
  @Setter
  public long allowOldRewardOpt;
  @Getter
  @Setter
  public long allowEnergyAdjustment;
  @Getter
  @Setter
  @Exportable
  public long maxCreateAccountTxSize = 1000L;
  @Getter
  @Setter
  public long allowStrictMath;
  @Getter
  @Setter
  public long consensusLogicOptimization;
  @Getter
  @Setter
  public long allowTvmCancun;
  @Getter
  @Setter
  public long allowTvmBlob;

  private static double calcMaxTimeRatio() {
    return 5.0;
  }

  public static CommonParameter getInstance() {
    return PARAMETER;
  }

  /**
   * Reset to a fresh instance. Test-only.
   */
  @VisibleForTesting
  public static void reset() {
    if (PARAMETER.storage != null) {
      PARAMETER.storage.deleteAllStoragePaths();
    }
    PARAMETER = new CommonParameter();
  }

  public boolean isECKeyCryptoEngine() {
    return cryptoEngine.equalsIgnoreCase(Constant.ECKey_ENGINE);
  }

  public boolean isJsonRpcFilterEnabled() {
    return jsonRpcHttpFullNodeEnable
        || jsonRpcHttpSolidityNodeEnable;
  }

  public int getSafeLruCacheSize() {
    return lruCacheSize < 1 ? 500 : lruCacheSize;
  }
}
