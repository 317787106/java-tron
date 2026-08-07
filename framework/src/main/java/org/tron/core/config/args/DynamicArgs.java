package org.tron.core.config.args;

import com.typesafe.config.Config;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.config.Configuration;
import org.tron.core.net.TronNetService;


@Slf4j(topic = "app")
@Component
public class DynamicArgs {
  private final CommonParameter parameter = Args.getInstance();

  private File configFile;
  private long lastModified = 0;

  private ScheduledExecutorService reloadExecutor;
  private final String esName = "dynamic-reload";

  @PostConstruct
  public void init() {
    if (parameter.isDynamicConfigEnable()) {
      reloadExecutor = ExecutorServiceManager.newSingleThreadScheduledExecutor(esName);
      logger.info("Start the dynamic loading configuration service");
      long checkInterval = parameter.getDynamicConfigCheckInterval();
      configFile = new File(Args.getConfigFilePath());
      if (!configFile.exists()) {
        logger.warn("Configuration path is required! No such file {}", configFile);
        return;
      }
      lastModified = configFile.lastModified();
      reloadExecutor.scheduleWithFixedDelay(() -> {
        try {
          run();
        } catch (Exception e) {
          logger.error("Exception caught when reloading configuration", e);
        }
      }, 10, checkInterval, TimeUnit.SECONDS);
    }
  }

  public void run() {
    long lastModifiedTime = configFile.lastModified();
    if (lastModifiedTime > lastModified) {
      reload();
      lastModified = lastModifiedTime;
    }
  }

  public void reload() {
    logger.debug("Reloading ... ");
    Config config = Configuration.getByFileName(Args.getConfigFilePath());
    NodeConfig nodeConfig = NodeConfig.fromConfig(config);

    updateActiveNodes(nodeConfig);

    updateTrustNodes(nodeConfig);
  }

  /**
   * Builds the complete active-node list before replacing the shared reference atomically. Using
   * {@code clear()} followed by {@code addAll()} would let concurrent readers observe a transient
   * empty or partially updated list.
   */
  private void updateActiveNodes(NodeConfig nodeConfig) {
    List<InetSocketAddress> newActiveNodes =
        Args.filterInetSocketAddress(nodeConfig.getActive(), true);
    parameter.setActiveNodes(newActiveNodes);
    List<InetSocketAddress> activeNodes = new CopyOnWriteArrayList<>(newActiveNodes);
    TronNetService.getP2pConfig().setActiveNodes(activeNodes);
    logger.debug("p2p active nodes : {}", activeNodes);
  }

  /**
   * Builds the complete trust-node list before replacing the shared reference atomically, so
   * concurrent configuration exports and network readers see either the old or the new snapshot.
   */
  private void updateTrustNodes(NodeConfig nodeConfig) {
    List<InetAddress> newPassiveNodes = new ArrayList<>();
    for (InetSocketAddress sa : Args.filterInetSocketAddress(nodeConfig.getPassive(), false)) {
      newPassiveNodes.add(sa.getAddress());
    }
    parameter.setPassiveNodes(newPassiveNodes);
    List<InetAddress> newTrustNodes = new ArrayList<>(newPassiveNodes);
    parameter.getActiveNodes().forEach(n -> newTrustNodes.add(n.getAddress()));
    parameter.getFastForwardNodes().forEach(f -> newTrustNodes.add(f.getAddress()));
    List<InetAddress> trustNodes = new CopyOnWriteArrayList<>(newTrustNodes);
    TronNetService.getP2pConfig().setTrustNodes(trustNodes);
    logger.debug("p2p trust nodes : {}", trustNodes);
  }

  @PreDestroy
  public void close() {
    ExecutorServiceManager.shutdownAndAwaitTermination(reloadExecutor, esName);
  }
}
