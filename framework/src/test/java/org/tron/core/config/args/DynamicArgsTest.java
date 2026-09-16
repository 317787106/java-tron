package org.tron.core.config.args;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Assert;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.TestConstants;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.net.TronNetService;
import org.tron.p2p.P2pConfig;

public class DynamicArgsTest extends BaseMethodTest {
  private DynamicArgs dynamicArgs;

  @Override
  protected void afterInit() {
    dynamicArgs = context.getBean(DynamicArgs.class);
  }

  @Test
  public void start() {
    CommonParameter parameter = Args.getInstance();
    Assert.assertEquals(TestConstants.TEST_CONF, Args.getConfigFilePath());
    Assert.assertTrue(parameter.isDynamicConfigEnable());
    Assert.assertEquals(600, parameter.getDynamicConfigCheckInterval());

    dynamicArgs.init();
    File configFile = (File) ReflectUtils.getFieldObject(dynamicArgs, "configFile");
    Assert.assertNotNull(configFile);
    Assert.assertEquals(TestConstants.TEST_CONF, configFile.getName());
    Assert.assertEquals(0, (long) ReflectUtils.getFieldObject(dynamicArgs, "lastModified"));

    TronNetService tronNetService = context.getBean(TronNetService.class);
    ReflectUtils.setFieldValue(tronNetService, "p2pConfig", new P2pConfig());
    File config = new File(Args.getConfigFilePath());
    if (!config.exists()) {
      try {
        config.createNewFile();
      } catch (Exception e) {
        return;
      }
      dynamicArgs.run();
      try {
        config.delete();
      } catch (Exception e) {
        return;
      }
    }
    try {
      dynamicArgs.reload();
    } catch (Exception e) {
      // no need to deal with
    }

    dynamicArgs.close();
  }

  @Test
  public void testReloadReplacesP2pNodeListsAtomically() {
    CommonParameter parameter = Args.getInstance();
    List<InetSocketAddress> originalActiveNodes = parameter.getActiveNodes();
    List<InetAddress> originalPassiveNodes = parameter.getPassiveNodes();
    List<InetSocketAddress> originalFastForwardNodes = parameter.getFastForwardNodes();
    TronNetService tronNetService = context.getBean(TronNetService.class);
    P2pConfig originalP2pConfig = TronNetService.getP2pConfig();
    P2pConfig p2pConfig = new P2pConfig();
    ReflectUtils.setFieldValue(tronNetService, "p2pConfig", p2pConfig);
    parameter.fastForwardNodes = new ArrayList<>();

    NodeConfig nodeConfig = new NodeConfig();
    nodeConfig.setActive(Collections.singletonList("192.0.2.1:18889"));
    nodeConfig.setPassive(Arrays.asList("127.0.0.2:18888", "127.0.0.3:18888"));
    try {
      ReflectUtils.invokeMethod(dynamicArgs, "updateActiveNodes",
          new Class[] {NodeConfig.class}, nodeConfig);
      ReflectUtils.invokeMethod(dynamicArgs, "updateTrustNodes",
          new Class[] {NodeConfig.class}, nodeConfig);

      Assert.assertTrue(p2pConfig.getActiveNodes() instanceof CopyOnWriteArrayList);
      Assert.assertTrue(p2pConfig.getTrustNodes() instanceof CopyOnWriteArrayList);
      Assert.assertEquals(1, p2pConfig.getActiveNodes().size());
      Assert.assertEquals(3, p2pConfig.getTrustNodes().size());
    } finally {
      parameter.setActiveNodes(originalActiveNodes);
      parameter.setPassiveNodes(originalPassiveNodes);
      parameter.fastForwardNodes = originalFastForwardNodes;
      ReflectUtils.setFieldValue(tronNetService, "p2pConfig", originalP2pConfig);
    }
  }
}
