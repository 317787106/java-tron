package org.tron.common.entity;

import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.tron.common.entity.NodeInfo.MachineInfo.DeadLockThreadInfo;
import org.tron.common.entity.NodeInfo.MachineInfo.MemoryDescInfo;
import org.tron.protos.Protocol;

public class NodeInfo {

  /*block information*/
  @Getter
  @Setter
  private long beginSyncNum;
  @Getter
  @Setter
  private String block;
  @Setter
  @Getter
  private String solidityBlock;

  /*connect information*/
  @Setter
  @Getter
  private int currentConnectCount;
  @Setter
  @Getter
  private int activeConnectCount;
  @Getter
  @Setter
  private int passiveConnectCount;
  @Getter
  @Setter
  private long totalFlow;
  @Getter
  @Setter
  private List<PeerInfo> peerList = new ArrayList<>();

  /*node config information*/
  @Setter
  @Getter
  private ConfigNodeInfo configNodeInfo;
  /*machine information*/
  @Setter
  @Getter
  private MachineInfo machineInfo;

  @Getter
  @Setter
  private Map<String, String> cheatWitnessInfoMap = new HashMap<>();

  @Setter
  @Getter
  private JSONObject runtimeConfig ;

  public Protocol.NodeInfo transferToProtoEntity() {
    Protocol.NodeInfo.Builder builder = Protocol.NodeInfo.newBuilder();
    builder.setBeginSyncNum(getBeginSyncNum());
    builder.setBlock(getBlock());
    builder.setSolidityBlock(getSolidityBlock());
    builder.setCurrentConnectCount(getCurrentConnectCount());
    builder.setActiveConnectCount(getActiveConnectCount());
    builder.setPassiveConnectCount(getPassiveConnectCount());
    builder.setTotalFlow(getTotalFlow());
    builder.putAllCheatWitnessInfoMap(getCheatWitnessInfoMap());
    for (PeerInfo peerInfo : getPeerList()) {
      Protocol.NodeInfo.PeerInfo.Builder peerInfoBuilder = Protocol.NodeInfo.PeerInfo.newBuilder();
      peerInfoBuilder.setLastSyncBlock(peerInfo.getLastSyncBlock());
      peerInfoBuilder.setRemainNum(peerInfo.getRemainNum());
      peerInfoBuilder.setLastBlockUpdateTime(peerInfo.getLastBlockUpdateTime());
      peerInfoBuilder.setSyncFlag(peerInfo.isSyncFlag());
      peerInfoBuilder.setHeadBlockTimeWeBothHave(peerInfo.getHeadBlockTimeWeBothHave());
      peerInfoBuilder.setNeedSyncFromPeer(peerInfo.isSyncFlag());
      peerInfoBuilder.setNeedSyncFromUs(peerInfo.isNeedSyncFromUs());
      peerInfoBuilder.setHost(peerInfo.getHost());
      peerInfoBuilder.setPort(peerInfo.getPort());
      peerInfoBuilder.setNodeId(peerInfo.getNodeId());
      peerInfoBuilder.setConnectTime(peerInfo.getConnectTime());
      peerInfoBuilder.setAvgLatency(peerInfo.getAvgLatency());
      peerInfoBuilder.setSyncToFetchSize(peerInfo.getSyncToFetchSize());
      peerInfoBuilder.setSyncToFetchSizePeekNum(peerInfo.getSyncToFetchSizePeekNum());
      peerInfoBuilder.setSyncBlockRequestedSize(peerInfo.getSyncBlockRequestedSize());
      peerInfoBuilder.setUnFetchSynNum(peerInfo.getUnFetchSynNum());
      peerInfoBuilder.setBlockInPorcSize(peerInfo.getBlockInPorcSize());
      peerInfoBuilder.setHeadBlockWeBothHave(peerInfo.getHeadBlockWeBothHave());
      peerInfoBuilder.setIsActive(peerInfo.isActive());
      peerInfoBuilder.setScore(peerInfo.getScore());
      peerInfoBuilder.setNodeCount(peerInfo.getNodeCount());
      peerInfoBuilder.setInFlow(peerInfo.getInFlow());
      peerInfoBuilder.setDisconnectTimes(peerInfo.getDisconnectTimes());
      peerInfoBuilder.setLocalDisconnectReason(peerInfo.getLocalDisconnectReason());
      peerInfoBuilder.setRemoteDisconnectReason(peerInfo.getRemoteDisconnectReason());
      builder.addPeerInfoList(peerInfoBuilder.build());
    }
    ConfigNodeInfo configNodeInfo = getConfigNodeInfo();
    if (configNodeInfo != null) {
      Protocol.NodeInfo.ConfigNodeInfo.Builder configBuilder = Protocol.NodeInfo.ConfigNodeInfo
          .newBuilder();
      configBuilder.setCodeVersion(configNodeInfo.getCodeVersion());
      configBuilder.setP2PVersion(configNodeInfo.getP2pVersion());
      configBuilder.setListenPort(configNodeInfo.getListenPort());
      configBuilder.setDiscoverEnable(configNodeInfo.isDiscoverEnable());
      configBuilder.setActiveNodeSize(configNodeInfo.getActiveNodeSize());
      configBuilder.setPassiveNodeSize(configNodeInfo.getPassiveNodeSize());
      configBuilder.setSendNodeSize(configNodeInfo.getSendNodeSize());
      configBuilder.setMaxConnectCount(configNodeInfo.getMaxConnectCount());
      configBuilder.setSameIpMaxConnectCount(configNodeInfo.getSameIpMaxConnectCount());
      configBuilder.setBackupListenPort(configNodeInfo.getBackupListenPort());
      configBuilder.setBackupMemberSize(configNodeInfo.getBackupMemberSize());
      configBuilder.setBackupPriority(configNodeInfo.getBackupPriority());
      configBuilder.setDbVersion(configNodeInfo.getDbVersion());
      configBuilder.setMinParticipationRate(configNodeInfo.getMinParticipationRate());
      configBuilder.setSupportConstant(configNodeInfo.isSupportConstant());
      configBuilder.setMinTimeRatio(configNodeInfo.getMinTimeRatio());
      configBuilder.setMaxTimeRatio(configNodeInfo.getMaxTimeRatio());
      configBuilder.setAllowCreationOfContracts(configNodeInfo.getAllowCreationOfContracts());
      configBuilder.setAllowAdaptiveEnergy(configNodeInfo.getAllowAdaptiveEnergy());
      builder.setConfigNodeInfo(configBuilder.build());
    }
    MachineInfo machineInfo = getMachineInfo();
    if (machineInfo != null) {
      Protocol.NodeInfo.MachineInfo.Builder machineBuilder = Protocol.NodeInfo.MachineInfo
          .newBuilder();
      machineBuilder.setThreadCount(machineInfo.getThreadCount());
      machineBuilder.setDeadLockThreadCount(machineInfo.getDeadLockThreadCount());
      machineBuilder.setCpuCount(machineInfo.getCpuCount());
      machineBuilder.setTotalMemory(machineInfo.getTotalMemory());
      machineBuilder.setFreeMemory(machineInfo.getFreeMemory());
      machineBuilder.setCpuRate(machineInfo.getCpuRate());
      machineBuilder.setJavaVersion(machineInfo.getJavaVersion());
      machineBuilder.setOsName(machineInfo.getOsName());
      machineBuilder.setJvmTotalMemory(machineInfo.getJvmTotalMemory());
      machineBuilder.setJvmFreeMemory(machineInfo.getJvmFreeMemory());
      machineBuilder.setProcessCpuRate(machineInfo.getProcessCpuRate());
      for (MemoryDescInfo memoryDescInfo : machineInfo.getMemoryDescInfoList()) {
        Protocol.NodeInfo.MachineInfo.MemoryDescInfo.Builder descBuilder = Protocol.NodeInfo.MachineInfo.MemoryDescInfo
            .newBuilder();
        descBuilder.setName(memoryDescInfo.getName());
        descBuilder.setInitSize(memoryDescInfo.getInitSize());
        descBuilder.setUseSize(memoryDescInfo.getUseSize());
        descBuilder.setMaxSize(memoryDescInfo.getMaxSize());
        descBuilder.setUseRate(memoryDescInfo.getUseRate());
        machineBuilder.addMemoryDescInfoList(descBuilder.build());
      }
      for (DeadLockThreadInfo deadLockThreadInfo : machineInfo.getDeadLockThreadInfoList()) {
        Protocol.NodeInfo.MachineInfo.DeadLockThreadInfo.Builder deadBuilder = Protocol.NodeInfo.MachineInfo.DeadLockThreadInfo
            .newBuilder();
        deadBuilder.setName(deadLockThreadInfo.getName());
        deadBuilder.setLockName(deadLockThreadInfo.getLockName());
        deadBuilder.setLockOwner(deadLockThreadInfo.getLockOwner());
        deadBuilder.setState(deadLockThreadInfo.getState());
        deadBuilder.setBlockTime(deadLockThreadInfo.getBlockTime());
        deadBuilder.setWaitTime(deadLockThreadInfo.getWaitTime());
        deadBuilder.setStackTrace(deadLockThreadInfo.getStackTrace());
        machineBuilder.addDeadLockThreadInfoList(deadBuilder.build());
      }
      builder.setMachineInfo(machineBuilder.build());
    }

    return builder.build();
  }

  @Setter
  @Getter
  public static class MachineInfo {

    /*machine information*/
    private int threadCount;
    private int deadLockThreadCount;
    private int cpuCount;
    private long totalMemory;
    private long freeMemory;
    private double cpuRate;
    private String javaVersion;
    private String osName;
    private long jvmTotalMemory;
    private long jvmFreeMemory;
    private double processCpuRate;
    private List<MemoryDescInfo> memoryDescInfoList = new ArrayList<>();
    private List<DeadLockThreadInfo> deadLockThreadInfoList = new ArrayList<>();

    @Setter
    @Getter
    public static class MemoryDescInfo {

      private String name;
      private long initSize;
      private long useSize;
      private long maxSize;
      private double useRate;
    }

    @Setter
    @Getter
    public static class DeadLockThreadInfo {

      private String name;
      private String lockName;
      private String lockOwner;
      private String state;
      private long blockTime;
      private long waitTime;
      private String stackTrace;
    }
  }

  @Setter
  @Getter
  public static class ConfigNodeInfo {

    /*node information*/
    private String codeVersion;
    private String versionNum;
    private String p2pVersion;
    private int listenPort;
    private boolean discoverEnable;
    private int activeNodeSize;
    private int passiveNodeSize;
    private int sendNodeSize;
    private int maxConnectCount;
    private int sameIpMaxConnectCount;
    private int backupListenPort;
    private int backupMemberSize;
    private int backupPriority;
    private int dbVersion;
    private int minParticipationRate;
    private boolean supportConstant;
    private double minTimeRatio;
    private double maxTimeRatio;
    private long allowCreationOfContracts;
    private long allowAdaptiveEnergy;
  }
}
