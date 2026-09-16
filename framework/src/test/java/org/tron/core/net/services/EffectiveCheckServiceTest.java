package org.tron.core.net.services;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Resource;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.PublicMethod;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.config.args.Args;
import org.tron.core.net.TronNetService;
import org.tron.core.net.service.effective.EffectiveCheckService;
import org.tron.core.net.service.nodepersist.NodePersistService;
import org.tron.p2p.P2pConfig;

public class EffectiveCheckServiceTest extends BaseTest {

  @Resource
  private EffectiveCheckService service;
  @Resource
  private TronNetService tronNetService;

  @BeforeClass
  public static void init() {
    Args.setParam(new String[] {"--output-directory", dbPath(), "--debug"},
        TestConstants.TEST_CONF);
  }

  @Test
  public void testNoIpv4() throws Exception {
    Method privateMethod = tronNetService.getClass()
        .getDeclaredMethod("updateConfig", P2pConfig.class);
    privateMethod.setAccessible(true);
    P2pConfig config = new P2pConfig();
    config.setIp(null);
    P2pConfig newConfig = (P2pConfig) privateMethod.invoke(tronNetService, config);
    Assert.assertNotNull(newConfig.getIp());
  }

  @Test
  public void testUpdateConfigDoesNotMutateConfiguredSeedNodes() throws Exception {
    CommonParameter parameter = Args.getInstance();
    List<InetSocketAddress> originalSeedNodes = parameter.getSeedNode().getAddressList();
    NodePersistService originalNodePersistService =
        ReflectUtils.getFieldValue(tronNetService, "nodePersistService");
    InetSocketAddress configuredSeed =
        InetSocketAddress.createUnresolved("seed.example.org", 18888);
    InetSocketAddress persistedPeer =
        InetSocketAddress.createUnresolved("persisted.example.org", 18888);
    NodePersistService nodePersistService = Mockito.mock(NodePersistService.class);
    Mockito.when(nodePersistService.dbRead()).thenReturn(Arrays.asList(persistedPeer));
    try {
      parameter.getSeedNode().setAddressList(
          new ArrayList<>(Arrays.asList(configuredSeed)));
      ReflectUtils.setFieldValue(tronNetService, "nodePersistService", nodePersistService);
      Method updateConfig = tronNetService.getClass()
          .getDeclaredMethod("updateConfig", P2pConfig.class);
      updateConfig.setAccessible(true);

      P2pConfig updated = (P2pConfig) updateConfig.invoke(tronNetService, new P2pConfig());

      Assert.assertEquals(Arrays.asList(configuredSeed),
          parameter.getSeedNode().getAddressList());
      Assert.assertTrue(updated.getSeedNodes().contains(configuredSeed));
      Assert.assertTrue(updated.getSeedNodes().contains(persistedPeer));
    } finally {
      parameter.getSeedNode().setAddressList(originalSeedNodes);
      ReflectUtils.setFieldValue(tronNetService, "nodePersistService", originalNodePersistService);
    }
  }

  @Test
  public void testFind() {
    int port = PublicMethod.chooseRandomPort();
    P2pConfig p2pConfig = new P2pConfig();
    p2pConfig.setIp("127.0.0.1");
    p2pConfig.setPort(port);
    ReflectUtils.setFieldValue(tronNetService, "p2pConfig", p2pConfig);
    TronNetService.getP2pService().start(p2pConfig);

    service.triggerNext();
    Assert.assertNull(service.getCur());

    ReflectUtils.invokeMethod(service, "resetCount");
    InetSocketAddress cur = new InetSocketAddress("192.168.0.1", port);
    service.setCur(cur);
    service.onDisconnect(cur);
  }
}
