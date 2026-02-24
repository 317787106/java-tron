package org.tron.core.erc8128;

import static org.tron.common.utils.Commons.decodeFromBase58Check;

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
import org.tron.common.utils.ByteArray;
import org.tron.keystore.Wallet;

public class Erc8128HttpSigExample {

  private static final String PRIVATE_KEY =
      "private_key_of_ADDRESS";
  //nile address
  private static final String ADDRESS = "TEPRbQxXQEpHpeEx8tK5xHVs7NWudAAZgu";
  //nile chainId from eth_chainId
  private static final long CHAINID = ByteArray.toLong(ByteArray.fromHexString("0xcd8690dc"));

  // specify authority, without http:// or https://
  private static final String authority = "api.trongrid.io"; //only use for test
  //private static final String authority = "api.trongrid.io"; //use for production
  private static final String PATH = "/wallet/getaccount";
  private static final String ERCLABEL = "tron";
  private static final String noncePrefix = "b64url_";

  public static void main(String[] args) throws NoSuchAlgorithmException, IOException {

    // POST uses json
    String jsonBody = String.format("{\"address\":\"%s\",\"visible\":true}", ADDRESS);
    System.out.println("jsonBody: " + jsonBody);

    // get the sha256 of jsonBody, only used in POST
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] bodyHash = digest.digest(jsonBody.getBytes(StandardCharsets.UTF_8));
    String contentDigest = Base64.getEncoder().encodeToString(bodyHash);

    long createTimestamp = System.currentTimeMillis() / 1000; //seconds
    long expireTimestamp = createTimestamp + 60; //seconds

    String nonce = noncePrefix + Base64.getEncoder().encodeToString(Wallet.generateRandomBytes(64));
    System.out.println("nonce: " + nonce);

    // build keyid with tip-8128
    byte[] addressHex = new byte[20];
    System.arraycopy(decodeFromBase58Check(ADDRESS), 1, addressHex, 0, 20);
    String keyId = "tip8128:" + CHAINID + ":" + ByteArray.toHexString(addressHex);

    //only used in GET
    //String queryOfGet = "?" + "address=" + ADDRESS + "&visible=true";

    // construct signingString, with the order of tip-8128.
    // @query only belongs to method GET, don't add it in POST.
    // content-digest only belongs to method POST. if length=0,ignore
    // nonce parameter MUST be included in Non-Replayable signatures
    String signingString = "\"@method\": POST\n"  //must use uppercase and line
        + "\"@authority\": " + authority + "\n"
        + "\"@path\": " + PATH + "\n"
        //+ "\"@query\": " + queryOfGet + "\n"  //only used in GET
        + "\"content-digest\": sha-256=:" + contentDigest + ":\n"; //only used in POST

    //this part is not included in http header signatureInput, only used in client
    signingString +=
        "\"@signature-params\": (\"@method\" \"@authority\" \"@path\" \"content-digest\");";

    signingString += "created=" + createTimestamp
        + ";expires=" + expireTimestamp
        + ";nonce=\"" + nonce + "\""
        + ";keyid=\"" + keyId + "\""
        + ";alg=\"eth_personalSign\"";

    byte[] singedBytes = signingString.getBytes(StandardCharsets.UTF_8);
    signingString = "\u0019TRON Signed Message:\n" + singedBytes.length + signingString;
    System.out.println(signingString);

    // sign the messageHash of signingString with PRIVATE_KEY
    byte[] messageHash = digest.digest(signingString.getBytes(StandardCharsets.UTF_8));
    System.out.println("messageHash: " + ByteArray.toHexString(messageHash));
    ECKey ecKey = ECKey.fromPrivate(ByteArray.fromHexString(PRIVATE_KEY));
    String sigB64 = ecKey.signHash(messageHash);
    System.out.println("signatureBase64: " + sigB64);

    // build Signature-Input & Signature for request HEADER。@ represetns placeholder
    String signatureInput = ERCLABEL
        + "=(\"@method\" \"@authority\" \"@path\" \"content-digest\")"
        + ";created=" + createTimestamp
        + ";expires=" + expireTimestamp
        + ";nonce=\"" + nonce + "\""
        + ";keyid=\"" + keyId + "\""
        + ";alg=\"eth_personalSign\"";

    String signatureHeader = ERCLABEL + "=:" + sigB64 + ":";

    URL url = new URL("http://localhost:8090" + PATH);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.addRequestProperty("Content-Type", "application/json");

    conn.addRequestProperty("Signature-Input", signatureInput);
    conn.addRequestProperty("Signature", signatureHeader);
    //When client POST, Content-Digest is append to http header
    //Server use request.getInputStream.readAllBytes(), so ignore the order of key
    conn.addRequestProperty("Content-Digest", "sha-256=:" + contentDigest + ":");
    conn.addRequestProperty("authority", authority);
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
