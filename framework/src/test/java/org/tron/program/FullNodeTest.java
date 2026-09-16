package org.tron.program;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.tron.common.arch.Arch;
import org.tron.common.exit.ExitManager;
import org.tron.common.log.LogService;
import org.tron.core.config.args.Args;
import org.tron.core.services.admin.ipc.IpcClient;

public class FullNodeTest {

  @Test
  public void testAttachStartsBeforeLogService() {
    AtomicBoolean logServiceLoaded = new AtomicBoolean(false);
    try (MockedStatic<ExitManager> exitManager = Mockito.mockStatic(ExitManager.class);
        MockedStatic<Arch> arch = Mockito.mockStatic(Arch.class);
        MockedStatic<Args> args = Mockito.mockStatic(Args.class);
        MockedStatic<LogService> logService = Mockito.mockStatic(LogService.class);
        MockedStatic<IpcClient> ipcClient = Mockito.mockStatic(IpcClient.class)) {
      args.when(Args::getIpcSocketFile).thenReturn("/tmp/java-tron.sock");
      args.when(Args::getIpcExecCommand).thenReturn(null);
      logService.when(() -> LogService.load(Mockito.anyString()))
          .thenAnswer(invocation -> {
            logServiceLoaded.set(true);
            return null;
          });
      ipcClient.when(() -> IpcClient.start("/tmp/java-tron.sock", null))
          .thenAnswer(invocation -> {
            Assert.assertFalse("Attach initialized node logging", logServiceLoaded.get());
            return 0;
          });

      FullNode.main(new String[] {"--attach", "/tmp/java-tron.sock"});

      ipcClient.verify(() -> IpcClient.start("/tmp/java-tron.sock", null));
      logService.verifyNoInteractions();
    }
  }
}
