package org.tron.tool;

import java.security.SignatureException;
import org.tron.common.crypto.ECKey;
import org.tron.common.crypto.Rsv;
import org.tron.common.crypto.SignUtils;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;

public class VerifyOneSignature {

  public static void main(String[] args) throws SignatureException {
    for (int i = 0; i < 50; i++) {
      long t0 = System.currentTimeMillis();
      byte[] rawData = ByteArray.fromHexString(
          "0a021e332208cd217ab69a62384940e0c0d0c3da335a68080112640a2d747970652e676f6f676c65617"
              + "069732e636f6d2f70726f746f636f6c2e5472616e73666572436f6e747261637412330a15416554da5"
              + "79ea967a38dc1972adf49abd0bc0b49781215411f8e5b7807c7bc4515e83bd042b3f22ae5c004fa188"
              + "0b6dc057080ecccc3da33");
      long t1 = System.currentTimeMillis();
      System.out.println("[1] fromHexString(rawData): " + (t1 - t0) + " ms");

      t0 = System.currentTimeMillis();
      byte[] hash = Sha256Hash.hash(true, rawData);
      t1 = System.currentTimeMillis();
      System.out.println("[2] Sha256Hash.hash: " + (t1 - t0) + " ms");

      t0 = System.currentTimeMillis();
      byte[] sigBytes = ByteArray.fromHexString(
          "6f9ef9d226dc87bceb571c859614fa7dcdbe0be6e1dfea54fb99cb9970fa09af91a945e3b0eb1eea559"
              + "c89cc4bd16932bfabcf0e63a0e0848fbb7fa0db4dcfd600");
      t1 = System.currentTimeMillis();
      System.out.println("[3] fromHexString(sigBytes): " + (t1 - t0) + " ms");

      t0 = System.currentTimeMillis();
      Rsv rsv = Rsv.fromSignature(sigBytes);
      t1 = System.currentTimeMillis();
      System.out.println("[4] Rsv.fromSignature: " + (t1 - t0) + " ms");

      t0 = System.currentTimeMillis();
      byte[] s = new byte[32];
      String sigBase64 = ECKey.ECDSASignature
          .fromComponents(rsv.getR(), s, rsv.getV()).toBase64();
      //.fromComponents(rsv.getR(), rsv.getS(), rsv.getV()).toBase64();
      t1 = System.currentTimeMillis();
      System.out.println("[5] ECDSASignature.fromComponents+toBase64: " + (t1 - t0) + " ms");

      // true = ECKey (secp256k1). Set to false only if the chain uses SM2.
      t0 = System.currentTimeMillis();
      byte[] recovered = SignUtils.signatureToAddress(hash, sigBase64, true);
      t1 = System.currentTimeMillis();
      System.out.println("[6] SignUtils.signatureToAddress: " + (t1 - t0) + " ms");
    }
  }
}
