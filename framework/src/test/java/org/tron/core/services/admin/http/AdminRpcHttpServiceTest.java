package org.tron.core.services.admin.http;

import org.junit.Assert;
import org.junit.Test;

public class AdminRpcHttpServiceTest {

  @Test
  public void testLoopbackListenAddressesAreRecognized() {
    Assert.assertTrue(AdminRpcHttpService.isLoopbackListenAddress("127.0.0.1"));
    Assert.assertTrue(AdminRpcHttpService.isLoopbackListenAddress("::1"));
    Assert.assertTrue(AdminRpcHttpService.isLoopbackListenAddress("localhost"));
  }

  @Test
  public void testNonLoopbackListenAddressesAreRejected() {
    Assert.assertFalse(AdminRpcHttpService.isLoopbackListenAddress(null));
    Assert.assertFalse(AdminRpcHttpService.isLoopbackListenAddress("0.0.0.0"));
    Assert.assertFalse(AdminRpcHttpService.isLoopbackListenAddress("192.0.2.1"));
  }
}
