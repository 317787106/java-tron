package org.tron.core.services.admin.http;

import com.google.common.net.InetAddresses;
import com.googlecode.jsonrpc4j.HttpStatusCodeProvider;
import com.googlecode.jsonrpc4j.JsonRpcInterceptor;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import com.googlecode.jsonrpc4j.ProxyUtil;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.services.admin.AdminJsonRpc;
import org.tron.core.services.http.RateLimiterServlet;
import org.tron.core.services.jsonrpc.JsonRpcErrorResolver;
import org.tron.core.services.jsonrpc.JsonRpcMapper;
import org.tron.core.services.jsonrpc.JsonRpcMediaType;

@Component
@Slf4j(topic = "API")
public class AdminRpcServlet extends RateLimiterServlet {

  private static final long serialVersionUID = 0L;

  private JsonRpcServer rpcServer = null;
  private Set<String> virtualHosts = Collections.emptySet();

  @Autowired
  private AdminJsonRpc adminJsonRpc;

  @Autowired
  private JsonRpcInterceptor interceptor;

  @Override
  public void init(ServletConfig config) throws ServletException {
    super.init(config);

    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    Object compositeService = ProxyUtil.createCompositeServiceProxy(cl,
        new Object[] {adminJsonRpc},
        new Class[] {AdminJsonRpc.class},
        true);

    rpcServer = new JsonRpcServer(JsonRpcMapper.create(), compositeService);
    rpcServer.setErrorResolver(JsonRpcErrorResolver.INSTANCE);

    HttpStatusCodeProvider httpStatusCodeProvider = new HttpStatusCodeProvider() {
      @Override
      public int getHttpStatusCode(int resultCode) {
        return 200;
      }

      @Override
      public Integer getJsonRpcCode(int httpStatusCode) {
        return null;
      }
    };
    rpcServer.setHttpStatusCodeProvider(httpStatusCodeProvider);

    rpcServer.setShouldLogInvocationErrors(false);
    if (CommonParameter.getInstance().isMetricsPrometheusEnable()) {
      rpcServer.setInterceptorList(Collections.singletonList(interceptor));
    }
    virtualHosts = normalizeVirtualHosts(CommonParameter.getInstance().getAdminVirtualHosts());
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (!isAllowedHost(req.getHeader("Host"))) {
      resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid Host header");
      return;
    }
    if (!JsonRpcMediaType.isSupported(req.getContentType())) {
      resp.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
      resp.setContentLength(0);
      return;
    }
    rpcServer.handle(req, resp);
  }

  private boolean isAllowedHost(String hostHeader) {
    if (hostHeader == null || hostHeader.isEmpty()) {
      // A browser always sends Host. Preserve compatibility for non-browser HTTP/1.0 clients.
      return true;
    }
    String host = extractHost(hostHeader);
    if (host == null) {
      return false;
    }
    if (InetAddresses.isInetAddress(host)) {
      return true;
    }
    return virtualHosts.contains("*")
        || virtualHosts.contains(host.toLowerCase(Locale.ROOT));
  }

  private String extractHost(String hostHeader) {
    // IPv6
    if (hostHeader.startsWith("[")) {
      int closingBracket = hostHeader.indexOf(']');
      if (closingBracket <= 1) {
        return null;
      }
      String suffix = hostHeader.substring(closingBracket + 1);
      if (!suffix.isEmpty() && !isPortSuffix(suffix)) {
        return null;
      }
      return hostHeader.substring(1, closingBracket);
    }

    // IPv4
    int firstColon = hostHeader.indexOf(':');
    if (firstColon < 0) {
      return hostHeader;
    }
    if (firstColon != hostHeader.lastIndexOf(':')) {
      return hostHeader;
    }
    String suffix = hostHeader.substring(firstColon);
    if (!isPortSuffix(suffix)) {
      return null;
    }
    return hostHeader.substring(0, firstColon);
  }

  private boolean isPortSuffix(String suffix) {
    if (suffix.length() <= 1 || suffix.charAt(0) != ':') {
      return false;
    }
    for (int i = 1; i < suffix.length(); i++) {
      if (!Character.isDigit(suffix.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private Set<String> normalizeVirtualHosts(List<String> configuredHosts) {
    Set<String> normalizedHosts = new HashSet<>();
    if (configuredHosts == null) {
      return normalizedHosts;
    }
    for (String configuredHost : configuredHosts) {
      if (configuredHost != null && !configuredHost.trim().isEmpty()) {
        normalizedHosts.add(configuredHost.trim().toLowerCase(Locale.ROOT));
      }
    }
    return normalizedHosts;
  }
}
