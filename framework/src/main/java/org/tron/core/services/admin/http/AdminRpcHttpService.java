package org.tron.core.services.admin.http;

import java.util.EnumSet;
import javax.servlet.DispatcherType;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.application.HttpService;
import org.tron.core.config.args.Args;
import org.tron.core.services.filter.HttpInterceptor;

@Component
@Slf4j(topic = "API")
public class AdminRpcHttpService extends HttpService {

  @Autowired
  private AdminRpcServlet adminRpcServlet;

  public AdminRpcHttpService() {
    enable = isFullNode() && Args.getInstance().isAdminRpcEnable();
    listenAddress = Args.getInstance().getAdminListenAddress();
    port = Args.getInstance().getAdminListenPort();
    contextPath = "/";
  }

  @Override
  protected void addServlet(ServletContextHandler context) {
    context.addServlet(new ServletHolder(adminRpcServlet), "/admin");
  }

  @Override
  protected void addFilter(ServletContextHandler context) {
    // filter
    ServletHandler handler = new ServletHandler();
    FilterHolder fh = handler
        .addFilterWithMapping(HttpInterceptor.class, "/*",
            EnumSet.of(DispatcherType.REQUEST));
    context.addFilter(fh, "/*", EnumSet.of(DispatcherType.REQUEST));
  }
}
