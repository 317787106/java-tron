package org.tron.core.net.services;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Collections;
import javax.annotation.Resource;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.common.utils.PublicMethod;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.config.args.Args;
import org.tron.core.net.TronNetDelegate;
import org.tron.core.net.TronNetService;
import org.tron.core.net.service.effective.EffectiveCheckService;
import org.tron.p2p.P2pConfig;
import org.tron.p2p.P2pService;
import org.tron.p2p.discover.Node;

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

  @Test
  public void testUnscheduledConnectionClearsCurrentCandidate() {
    EffectiveCheckService effectiveCheckService = new EffectiveCheckService();
    TronNetDelegate tronNetDelegate = Mockito.mock(TronNetDelegate.class);
    Mockito.when(tronNetDelegate.getActivePeer()).thenReturn(Collections.emptyList());
    ReflectUtils.setFieldValue(effectiveCheckService, "tronNetDelegate", tronNetDelegate);

    InetSocketAddress address = new InetSocketAddress("192.0.2.20", 18888);
    Node node = Mockito.mock(Node.class);
    Mockito.when(node.getPreferInetSocketAddress()).thenReturn(address);
    P2pService p2pService = Mockito.mock(P2pService.class);
    Mockito.when(p2pService.getConnectableNodes()).thenReturn(Collections.singletonList(node));
    Mockito.when(p2pService.connect(Mockito.eq(node), Mockito.any())).thenReturn(null);
    P2pConfig p2pConfig = new P2pConfig();

    try (MockedStatic<TronNetService> tronNetService = Mockito.mockStatic(TronNetService.class)) {
      tronNetService.when(TronNetService::getP2pService).thenReturn(p2pService);
      tronNetService.when(TronNetService::getP2pConfig).thenReturn(p2pConfig);

      ReflectUtils.invokeMethod(effectiveCheckService, "findEffectiveNode");

      Assert.assertNull(effectiveCheckService.getCur());
      Mockito.verify(p2pService).connect(Mockito.eq(node), Mockito.any());
    }
  }
}
