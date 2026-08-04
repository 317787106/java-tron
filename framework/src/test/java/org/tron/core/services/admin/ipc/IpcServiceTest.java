package org.tron.core.services.admin.ipc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Test;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.config.args.Args;
import org.tron.core.services.admin.AdminJsonRpcImpl;
import org.tron.core.services.admin.CommonParameterExporter;

public class IpcServiceTest {

  @Test
  public void testResolveSocketFilePathUsesOutputDirectory() {
    CommonParameter parameter = new CommonParameter();
    parameter.outputDirectory = "node-output";

    Path socketFilePath = IpcService.resolveSocketFilePath(parameter, "1234");

    Assert.assertEquals(
        Paths.get("node-output", "java-tron.1234.sock"),
        socketFilePath);
  }

  @Test
  public void testCreateParentDirectoriesWithNoParent() throws IOException {
    Path socketFilePath = Paths.get("java-tron.1234.sock");
    Assert.assertNull(socketFilePath.getParent());

    IpcService.createParentDirectories(socketFilePath);
  }

  @Test(timeout = 10_000)
  public void testStopClosesActiveClientSocket() throws Exception {
    CommonParameter parameter = Args.getInstance();
    String originalOutputDirectory = parameter.outputDirectory;
    Path outputDirectory = Files.createTempDirectory("ipc-test-");
    IpcService service = new IpcService(
        new AdminJsonRpcImpl(new CommonParameterExporter()));
    boolean started = false;
    try {
      parameter.outputDirectory = outputDirectory.toString();
      service.innerStart();
      started = true;

      File socketFile = IpcService.resolveSocketFilePath(parameter, IpcService.getPid()).toFile();
      AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile);
      try (AFUNIXSocket client = AFUNIXSocket.newInstance()) {
        client.connect(address);
        client.setSoTimeout(5_000);
        try (BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
          writer.write("{\"jsonrpc\":\"2.0\",\"method\":\"admin_example\","
              + "\"params\":[\"a\",\"b\"],\"id\":1}");
          writer.newLine();
          writer.flush();
          Assert.assertNotNull(reader.readLine());

          long startNanos = System.nanoTime();
          service.innerStop();
          started = false;
          long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
          Assert.assertTrue("IPC service shutdown took " + elapsedMillis + " ms",
              elapsedMillis < 5_000);
        }
      }
    } finally {
      if (started) {
        service.innerStop();
      }
      parameter.outputDirectory = originalOutputDirectory;
      Files.deleteIfExists(outputDirectory);
    }
  }
}
