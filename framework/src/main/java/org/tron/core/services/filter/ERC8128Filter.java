package org.tron.core.services.filter;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.tron.common.crypto.Hash;
import org.tron.common.crypto.SignUtils;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.ByteUtil;
import org.tron.common.utils.DecodeUtil;

@Component
@Slf4j
public class ERC8128Filter implements Filter {

  public static String signatureInputHeader = "Signature-Input";
  public static String signatureHeader = "Signature";
  public static String contentDigestHeader = "Content-Digest";

  private final Cache<String, Boolean> replayKeyCache = CacheBuilder
      .newBuilder().maximumSize(10000)
      .expireAfterWrite(10, TimeUnit.MINUTES).recordStats().build();

  // todo: fix later using real chainid
  private static final long CHAINID = ByteArray.toLong(ByteArray.fromHexString("0xcd8690dc"));

  private static final String ERCLABEL = "tron";

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response,
      FilterChain filterChain) throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse resp = (HttpServletResponse) response;

    CachedBodyHttpServletRequest wrapped =
        new CachedBodyHttpServletRequest(req);

    try {
      verifyHttpSignature(wrapped);
    } catch (Exception e) {
      logger.error("", e);
      resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      resp.getWriter().write(e.getMessage());
      //resp.getWriter().write("Signature verification error");
      return;
    }

    filterChain.doFilter(wrapped, response);
  }

  private void verifyHttpSignature(CachedBodyHttpServletRequest request) throws Exception {

    // 1. read header
    String signatureInput = request.getHeader(signatureInputHeader);
    String signature = request.getHeader(signatureHeader);
    String contentDigest = request.getHeader(contentDigestHeader);

    //logger.info("signatureInput: {}", signatureInput);
    //logger.info("signature: {}", signature);
    //logger.info("contentDigest: {}", contentDigest);
    if (signatureInput == null || signature == null) {
      //no need to verify http signature if anyone is null
      return;
    }

    // 2. parse SignatureInput as Structured Field Dictionary.
    // should not split with semicolon as value may contains semicolon
    // Dictionary {
    //   key: inner-list + parameters
    // }
    int index = signatureInput.indexOf('=');
    if (index == -1) {
      throw new Exception("Invalid components part of " + signatureInputHeader);
    }
    int restPart = index;
    String label = signatureInput.substring(0, index).trim();
    if (!ERCLABEL.equals(label)) {
      throw new Exception("Label " + ERCLABEL + " not found in " + signatureInputHeader);
    }
    int open = signatureInput.indexOf('(');
    int close = signatureInput.indexOf(')');
    if (open == -1 || close == -1 || open > close + 1) {
      throw new Exception("Invalid components part of " + signatureInputHeader);
    }
    String placeholders = signatureInput.substring(open + 1, close);
    String paramPart = signatureInput.substring(close + 2).trim();

    Map<String, String> inputParams = new HashMap<>();
    for (String item : paramPart.split(";")) {
      index = item.indexOf("="); //first index of =
      if (index == -1) {
        throw new Exception("Invalid param part of " + signatureInputHeader);
      }
      String key = item.substring(0, index);
      String value = StringUtils.strip(item.substring(index + 1), "\"");
      inputParams.put(key, value);
    }

    // check mandatory componenets
    if (!placeholders.contains("\"@method\"")
        || !placeholders.contains("\"@authority\"")
        || !placeholders.contains("\"@path\"")) {
      throw new Exception("Componenets @method or @authority or @path not found");
    }
    // check signature
    index = signature.indexOf("=:");
    if (index == -1 || !signature.endsWith(":")) {
      throw new Exception("Invalid http header " + signatureHeader);
    }
    label = signature.substring(0, index).trim();
    if (!ERCLABEL.equals(label)) {
      throw new Exception("Label " + ERCLABEL + " not found in " + signatureHeader);
    }
    String signatureBase64 = StringUtils.strip(signature.substring(index + 2), ":");

    // 3. verify created and expires (Mandatory Parameters)
    if (!inputParams.containsKey("created") || !inputParams.containsKey("expires")) {
      throw new Exception("Key created or expires not found");
    }
    long now = System.currentTimeMillis() / 1000;
    long expires = Long.parseLong(inputParams.get("expires"));
    if (expires <= now) {
      throw new Exception("Signature expired");
    }
    long created = Long.parseLong(inputParams.get("created"));
    if (created > now) {
      throw new Exception("Signature make sure created <= now");
    }
    if (expires - created > 60) {
      throw new Exception("Signature make sure expires - created <= 60 seconds");
    }

    // 4. Check nonce, Only Non-Replayable requests supported
    String nonce = inputParams.get("nonce");
    if (StringUtils.isEmpty(nonce)) {
      throw new Exception("Key nonce is not found or invalid");
    }

    // 5. Check if the nonce has already been used.
    String keyid = inputParams.get("keyid");
    if (StringUtils.isEmpty(keyid)) { // Mandatory Parameters
      throw new Exception("Key keyid is not found or invalid");
    }

    // 6. Check if use right  CHAINID
    String[] items = keyid.split(":");
    if (items.length != 3) {
      throw new Exception("Invalid keyid");
    }
    if (!items[0].equals("tip8128")) {
      throw new Exception("Key keyid must begin with tip8128");
    }
    if (Long.parseLong(items[1]) != CHAINID) {
      throw new Exception("Key keyid contains invalid chainid");
    }
    byte[] sigaddressBytes = ByteArray.fromHexString(items[2]);

    // 7. Check if the replayKey has already been used. "abc" + "123" is not same as "ab" + "c123"
    String replayKey = keyid + ":" + nonce;
    if (Boolean.TRUE.equals(replayKeyCache.getIfPresent(replayKey))) {
      throw new Exception("Replay key already exists");
    }

    // 8. Request integrity checks
    // (at minimum: if `content-digest` is covered, recompute and compare it).
    // only sha-256 is supported. Format: sha-256=:xxxx:
    boolean covered = placeholders.contains("content-digest");
    if (covered && StringUtils.isNoneEmpty(contentDigest)) {
      index = contentDigest.indexOf("="); // first index of =
      String key = contentDigest.substring(0, index);
      String value = contentDigest.substring(index + 1);
      if (!key.equals("sha-256")) {
        throw new Exception("ContentDigest must begin with sha-256");
      }
      if (!value.startsWith(":") || !value.endsWith(":")) {
        throw new Exception("ContentDigest' value must begin or end with semicolon");
      }

      // recompute and compare it
      value = StringUtils.strip(value, ":");
      byte[] contentDigestBytes = Base64.getDecoder().decode(value);

      byte[] body = request.getCachedBody();
      //logger.info("body: {}", new String(body, StandardCharsets.UTF_8));
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bodyHash = digest.digest(body);

      if (!Arrays.equals(contentDigestBytes, bodyHash)) {
        throw new Exception("Check content digest failed");
      }
    }

    // 9. construct to be singed string
    String method = request.getMethod();
    String authority = request.getHeader("Host");
    String path = request.getRequestURI();
    String query = request.getQueryString();

    StringBuilder sb = new StringBuilder();
    Pattern p = Pattern.compile("\"([^\"]+)\"");
    Matcher m = p.matcher(placeholders);
    List<String> components = new ArrayList<>();
    while (m.find()) {
      components.add(m.group(1));
    }
    for (String component : components) {
      if (component.equals("@method")) {
        sb.append("\"@method\": ").append(method).append("\n");
      }
      if (component.equals("@authority")) {
        sb.append("\"@authority\": ").append(authority).append("\n");
      }
      if (component.equals("@path")) {
        sb.append("\"@path\": ").append(path).append("\n");
      }
      if (component.equals("@query")) {
        String queryValue = query == null ? "" : query;
        sb.append("\"@query\": ").append(queryValue).append("\n");
      }
      if (component.equals("content-digest") && StringUtils.isNotEmpty(contentDigest)) {
        sb.append("\"content-digest\": ").append(contentDigest).append("\n");
      }
    }

    sb.append("\"@signature-params\": ");
    //user may add whitespace, but we should remove it by RFC9421
    sb.append(signatureInput.substring(restPart + 1).trim());

    byte[] signingBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
    String prefix = "\u0019TRON Signed Message:\n" + signingBytes.length;
    String finalMessage = prefix + sb;

    // 10. verify signature
    //logger.info("finalMessage: {}", finalMessage);
    byte[] messageHash = Hash.sha3(finalMessage.getBytes(StandardCharsets.UTF_8));
    //logger.info("messageHash: {}", ByteArray.toHexString(messageHash));
    //logger.info("signatureBase64: {}", signatureBase64);

    sigaddressBytes = ByteUtil.merge(new byte[] {DecodeUtil.addressPreFixByte}, sigaddressBytes);
    byte[] sigAddress = verifyECDSA(messageHash, signatureBase64); // has 0x41 prefix already
    //logger.info("src address: {}", ByteArray.toHexString(sigaddressBytes));
    //logger.info("cal address: {}", ByteArray.toHexString(sigAddress));
    if (!Arrays.equals(sigaddressBytes, sigAddress)) {
      throw new Exception("Signature verification failed");
    }

    // 11. find sigAddress if exist or not
    //todo

    // 12. restrict this sigAddress with access speed
    //todo

    replayKeyCache.put(replayKey, true);
  }

  private byte[] verifyECDSA(byte[] hash, String signatureBase64) throws SignatureException {
    return SignUtils.signatureToAddress(hash, signatureBase64, true);
  }

  @Override
  public void destroy() {
  }
}
