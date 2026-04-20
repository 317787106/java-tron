package org.tron.tool;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.tron.common.crypto.ECKey;
import org.tron.common.crypto.Rsv;
import org.tron.common.crypto.SignUtils;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;

/**
 * Replay tool: fetch a block via TronGrid /wallet/getblockbynum, then for every
 * transaction in it run ECDSA signature-to-address recovery and time how long
 * the verification takes. No DB writes, no execution — signature verification only.
 *
 * Usage:
 * ./gradlew :framework:runSigReplay -PblockNum=81993268
 * # or after a standard build:
 * java -cp framework/build/libs/FullNode.jar \
 * org.tron.tool.BlockSigVerifyReplay 81993268 [https://api.trongrid.io]
 */
public class BlockSigVerifyReplay {

  private static final String DEFAULT_ENDPOINT = "https://api.trongrid.io";

  public static void main(String[] args) throws Exception {
    long blockNum = args.length > 0 ? Long.parseLong(args[0]) : 81993268L;
    String endpoint = args.length > 1 ? args[1] : DEFAULT_ENDPOINT;

    System.out.printf("Fetching block %d from %s%n", blockNum, endpoint);
    JSONObject block = fetchBlock(endpoint, blockNum);
    JSONArray txs = block.getJSONArray("transactions");
    if (txs == null || txs.isEmpty()) {
      System.out.println("Block has no transactions.");
      return;
    }
    System.out.printf("Got %d transactions%n%n", txs.size());

    // Warm-up so JIT / BouncyCastle init does not skew the first few timings.
    warmup(txs.getJSONObject(0));

    List<Result> results = new ArrayList<>(txs.size());
    long blockStartNs = System.nanoTime();
    for (int i = 0; i < txs.size(); i++) {
      results.add(verifyOneTx(i, txs.getJSONObject(i)));
    }
    long blockElapsedNs = System.nanoTime() - blockStartNs;

    printReport(blockNum, results, blockElapsedNs);
  }

  private static Result verifyOneTx(int index, JSONObject tx) {
    String txId = tx.getString("txID");

    String rawHex = tx.getString("raw_data_hex");
    byte[] rawData = ByteArray.fromHexString(rawHex);
    byte[] hash = Sha256Hash.hash(true, rawData); // SHA-256 over raw_data

    JSONArray sigs = tx.getJSONArray("signature");
    int sigCount = sigs == null ? 0 : sigs.size();
    List<String> recoveredAddresses = new ArrayList<>(sigCount);

    if (txId.equalsIgnoreCase("73350db08350056f128734ec26444ea549299256ea99e0aaab7f5ad60d0d552a")) {
      System.out.println("raw:" + rawHex);
      System.out.println("count:" + sigCount);
      System.out.println("sig:" + sigs.getString(0));
    }
    long txStartNs = System.nanoTime();
    boolean ok = true;
    String err = null;
    try {
      for (int s = 0; s < sigCount; s++) {
        // Wire signature is 65 bytes: r(32) || s(32) || v(1, revId 0/1).
        // SignUtils.signatureToAddress expects base64 of v(1, 27+) || r(32) || s(32),
        // matching ECKey.ECDSASignature#toBase64(). Convert via the same path that
        // TransactionCapsule.getBase64FromByteString uses.
        byte[] sigBytes = ByteArray.fromHexString(sigs.getString(s));
        Rsv rsv = Rsv.fromSignature(sigBytes);
        String sigBase64 = ECKey.ECDSASignature
            .fromComponents(rsv.getR(), rsv.getS(), rsv.getV()).toBase64();
        // true = ECKey (secp256k1). Set to false only if the chain uses SM2.
        byte[] recovered = SignUtils.signatureToAddress(hash, sigBase64, true);
        recoveredAddresses.add(ByteArray.toHexString(recovered));
      }
    } catch (Throwable t) {
      ok = false;
      err = t.getClass().getSimpleName() + ": " + t.getMessage();
    }
    long txElapsedNs = System.nanoTime() - txStartNs;

    String contractType = extractContractType(tx);
    return new Result(index, txId, sigCount, contractType, ok, err,
        txElapsedNs, recoveredAddresses);
  }

  private static String extractContractType(JSONObject tx) {
    try {
      JSONObject rawData = tx.getJSONObject("raw_data");
      if (rawData == null) {
        return "?";
      }
      JSONArray contracts = rawData.getJSONArray("contract");
      if (contracts == null || contracts.isEmpty()) {
        return "?";
      }
      return contracts.getJSONObject(0).getString("type");
    } catch (Exception e) {
      return "?";
    }
  }

  private static void warmup(JSONObject tx) {
    try {
      for (int i = 0; i < 20; i++) {
        verifyOneTx(-1, tx);
      }
    } catch (Throwable ignored) {
      // warm-up errors are non-fatal
    }
  }

  private static void printReport(long blockNum, List<Result> results, long blockElapsedNs) {
    long totalSigNs = 0;
    int totalSigs = 0;
    int ok = 0;
    for (Result r : results) {
      totalSigNs += r.elapsedNs;
      totalSigs += r.sigCount;
      if (r.ok) {
        ok++;
      }
    }

    System.out.println("=== per-tx signature verification ===");
    System.out.printf("%-4s  %-64s  %-30s  %5s  %9s  %9s  %s%n",
        "idx", "txID", "contractType", "sigs", "tx(us)", "per-sig", "status");
    for (Result r : results) {
      System.out.printf("%-4d  %-64s  %-30s  %5d  %9.1f  %9.1f  %s%n",
          r.index, r.txId, r.contractType, r.sigCount,
          r.elapsedNs / 1_000.0,
          r.sigCount == 0 ? 0.0 : r.elapsedNs / 1_000.0 / r.sigCount,
          r.ok ? "OK" : "FAIL " + r.err);
    }

    System.out.println();
    System.out.println("=== top-10 slowest tx ===");
    List<Result> byTime = new ArrayList<>(results);
    byTime.sort(Comparator.<Result>comparingLong(r -> r.elapsedNs).reversed());
    for (int i = 0; i < Math.min(10, byTime.size()); i++) {
      Result r = byTime.get(i);
      System.out.printf("#%-2d %s  %-30s  %7.2f us (%d sigs)%n",
          i + 1, r.txId, r.contractType, r.elapsedNs / 1_000.0, r.sigCount);
    }

    System.out.println();
    System.out.println("=== summary ===");
    System.out.printf("block number        : %d%n", blockNum);
    System.out.printf("tx count            : %d (%d OK, %d FAIL)%n",
        results.size(), ok, results.size() - ok);
    System.out.printf("signature count     : %d%n", totalSigs);
    System.out.printf("sum of per-tx time  : %.3f ms%n", totalSigNs / 1_000_000.0);
    System.out.printf("wall-clock loop     : %.3f ms%n", blockElapsedNs / 1_000_000.0);
    System.out.printf("avg per tx          : %.1f us%n",
        totalSigNs / 1_000.0 / Math.max(1, results.size()));
    System.out.printf("avg per signature   : %.1f us%n",
        totalSigNs / 1_000.0 / Math.max(1, totalSigs));
  }

  private static JSONObject fetchBlock(String endpoint, long blockNum) throws Exception {
    URL url = new URL(endpoint + "/wallet/getblockbynum");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setConnectTimeout(10_000);
    conn.setReadTimeout(30_000);
    conn.setRequestProperty("content-type", "application/json");
    conn.setRequestProperty("accept", "application/json");
    String payload = "{\"num\":" + blockNum + ",\"visible\":false}";
    try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
      out.write(payload.getBytes(StandardCharsets.UTF_8));
    }
    StringBuilder sb = new StringBuilder();
    try (BufferedReader in = new BufferedReader(
        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        sb.append(line);
      }
    }
    return JSON.parseObject(sb.toString());
  }

  private static final class Result {

    final int index;
    final String txId;
    final int sigCount;
    final String contractType;
    final boolean ok;
    final String err;
    final long elapsedNs;
    final List<String> recovered;

    Result(int index, String txId, int sigCount, String contractType,
        boolean ok, String err, long elapsedNs, List<String> recovered) {
      this.index = index;
      this.txId = txId;
      this.sigCount = sigCount;
      this.contractType = contractType;
      this.ok = ok;
      this.err = err;
      this.elapsedNs = elapsedNs;
      this.recovered = recovered;
    }
  }
}