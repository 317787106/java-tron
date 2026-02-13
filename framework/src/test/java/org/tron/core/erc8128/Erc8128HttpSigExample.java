package org.tron.core.erc8128;

import static org.tron.common.utils.Commons.decodeFromBase58Check;

import com.google.protobuf.ByteString;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.tron.common.crypto.ECKey;
import org.tron.common.crypto.ECKey.ECDSASignature;
import org.tron.common.crypto.Hash;
import org.tron.common.utils.ByteArray;

public class Erc8128HttpSigExample {

  private static final String PRIVATE_KEY =
      "bc48b94db7a8ba3f9025eeff7c9fc09f57fbcf46cf422ea33d64492f292d2215";
  //nile address
  private static final String ADDRESS = "TEPRbQxXQEpHpeEx8tK5xHVs7NWudAAZgu";
  //nile chainId from eth_chainId
  private static final long CHAINID = ByteArray.toLong(ByteArray.fromHexString("0xcd8690dc"));

  // specify authority, without http:// or https://
  private static final String authority = "localhost:8090"; //only use for test
  //private static final String authority = "api.trongrid.io"; //use for production
  private static final String PATH = "/wallet/getaccount";

  public static void main(String[] args) throws NoSuchAlgorithmException, IOException {

    // post uses json
    // get uses "?key1=k1&value1=v1", carry with ? prefix
    String jsonBody = String.format("{\"address\":\"%s\",\"visible\":true}", ADDRESS);

    // get the sha256 of jsonBody
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] bodyHash = digest.digest(jsonBody.getBytes(StandardCharsets.UTF_8));
    String contentDigest = Base64.getEncoder().encodeToString(bodyHash);

    long createTimestamp = System.currentTimeMillis() / 1000; //seconds
    long expireTimestamp = createTimestamp + 60; //seconds

    // build keyid with tip-8128
    byte[] addressHex = new byte[20];
    System.arraycopy(decodeFromBase58Check(ADDRESS), 1, addressHex, 0, 20);
    String keyId = "tip8128:" + CHAINID + ":" + ByteArray.toHexString(addressHex);

    // construct signingString, with the order of tip-8128.
    // only add @query to signingString if method GET, don't add it in POST
    String signingString = "\"@method\": POST\n" +
        "\"@authority\": " + authority + "\n" +
        "\"@path\": " + PATH + "\n" +
        //"\"@query\": " + jsonBody + "\n" +
        "\"content-digest\": sha-256=:" + contentDigest + ":\n" +
        "\"@signature-params\": (\"@method\" \"@authority\" \"@path\" \"content-digest\")" +
        ";created=" + createTimestamp +
        ";expires=" + expireTimestamp +
        ";keyid=\"" + keyId + "\"" +
        ";alg=\"eth_personalSign\"";

    signingString = "\u0019TRON Signed Message:\n" + signingString.length() + signingString;
    System.out.println(signingString);

    // sign the messageHash of signingString with PRIVATE_KEY
    byte[] messageHash = Hash.sha3(signingString.getBytes(StandardCharsets.UTF_8));
    ECKey ecKey = ECKey.fromPrivate(ByteArray.fromHexString(PRIVATE_KEY));
    ECDSASignature signature = ecKey.sign(messageHash);
    ByteString sign = ByteString.copyFrom(signature.toByteArray());

    // 基于 Base64
    String sigB64 = Base64.getEncoder().encodeToString(sign.toByteArray());

    // build Signature-Input & Signature for request HEADER。@ represetns placeholder
    String signatureInput =
        "tron=(\"@method\" \"@authority\" \"@path\" \"content-digest\");" +
            "created=" + createTimestamp + ";expires=" + expireTimestamp +
            ";keyid=\"" + keyId + "\";alg=\"eth_personalSign\"";

    String signatureHeader = "tron=:" + sigB64 + ":";

    URL url = new URL("http://" + authority + PATH);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.addRequestProperty("Content-Type", "application/json");

    conn.addRequestProperty("Signature-Input", signatureInput);
    conn.addRequestProperty("Signature", signatureHeader);
    conn.addRequestProperty("Content-Digest", "sha-256=" + contentDigest);
    conn.addRequestProperty("Signature-Date", String.valueOf(createTimestamp));
    conn.setDoOutput(true);
    conn.setDoInput(true);
    conn.setInstanceFollowRedirects(true);

    conn.setRequestProperty("Content-Length",
        String.valueOf(jsonBody.getBytes(StandardCharsets.UTF_8).length));
    DataOutputStream out = new DataOutputStream(conn.getOutputStream());
    out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
    out.flush();
    out.close();

    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
    String inputLine;
    StringBuilder content = new StringBuilder();
    while ((inputLine = in.readLine()) != null) {
      content.append(inputLine);
    }
    in.close();

    System.out.println("\nStatus: " + conn.getResponseCode());
    System.out.println("\nResponse: " + content);
    conn.disconnect();
  }
}
