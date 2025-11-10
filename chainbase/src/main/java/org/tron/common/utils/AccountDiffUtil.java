package org.tron.common.utils;


import com.google.protobuf.InvalidProtocolBufferException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.AccountDiff;
import org.tron.protos.Protocol.BoolValue;
import org.tron.protos.Protocol.BytesValue;
import org.tron.protos.Protocol.FreezeV2List;
import org.tron.protos.Protocol.FrozenList;
import org.tron.protos.Protocol.Int64Value;
import org.tron.protos.Protocol.MapStringInt64;
import org.tron.protos.Protocol.PermissionList;
import org.tron.protos.Protocol.UnFreezeV2List;
import org.tron.protos.Protocol.VoteList;

public class AccountDiffUtil {

  /**
   * compute the diff between newAccount and oldAccount
   */
  public static AccountDiff computeDiff(Account oldAccount, Account newAccount) {
    if (oldAccount == null) {
      throw new IllegalArgumentException("oldAccount cannot be null");
    }
    if (newAccount == null) {
      throw new IllegalArgumentException("newAccount cannot be null");
    }

    AccountDiff.Builder diff = AccountDiff.newBuilder();

    // --- 简单字段 (int64, bytes, bool, enum) ---
    diffFieldIfChanged(diff, 1, oldAccount.getAccountName(), newAccount.getAccountName(),
        (b, v) -> b.putBytesFields(1, BytesValue.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 2, oldAccount.getType(), newAccount.getType(),
        (b, v) -> b.putEnumFields(2, v));
    diffFieldIfChanged(diff, 3, oldAccount.getAddress(), newAccount.getAddress(),
        (b, v) -> b.putBytesFields(3, BytesValue.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 4, oldAccount.getBalance(), newAccount.getBalance(),
        (b, v) -> b.putInt64Fields(4, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 8, oldAccount.getNetUsage(), newAccount.getNetUsage(),
        (b, v) -> b.putInt64Fields(8, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 9, oldAccount.getCreateTime(), newAccount.getCreateTime(),
        (b, v) -> b.putInt64Fields(9, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 10, oldAccount.getLatestOprationTime(),
        newAccount.getLatestOprationTime(),
        (b, v) -> b.putInt64Fields(10, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 11, oldAccount.getAllowance(), newAccount.getAllowance(),
        (b, v) -> b.putInt64Fields(11, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 12, oldAccount.getLatestWithdrawTime(),
        newAccount.getLatestWithdrawTime(),
        (b, v) -> b.putInt64Fields(12, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 13, oldAccount.getCode(), newAccount.getCode(),
        (b, v) -> b.putBytesFields(13, BytesValue.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 14, oldAccount.getIsWitness(), newAccount.getIsWitness(),
        (b, v) -> b.putBoolFields(14, BoolValue.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 15, oldAccount.getIsCommittee(), newAccount.getIsCommittee(),
        (b, v) -> b.putBoolFields(15, BoolValue.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 17, oldAccount.getAssetIssuedName(), newAccount.getAssetIssuedName(),
        (b, v) -> b.putBytesFields(17, BytesValue.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 19, oldAccount.getFreeNetUsage(), newAccount.getFreeNetUsage(),
        (b, v) -> b.putInt64Fields(19, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 21, oldAccount.getLatestConsumeTime(),
        newAccount.getLatestConsumeTime(),
        (b, v) -> b.putInt64Fields(21, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 22, oldAccount.getLatestConsumeFreeTime(),
        newAccount.getLatestConsumeFreeTime(),
        (b, v) -> b.putInt64Fields(22, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 23, oldAccount.getAccountId(), newAccount.getAccountId(),
        (b, v) -> b.putBytesFields(23, BytesValue.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 24, oldAccount.getNetWindowSize(), newAccount.getNetWindowSize(),
        (b, v) -> b.putInt64Fields(24, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 25, oldAccount.getNetWindowOptimized(),
        newAccount.getNetWindowOptimized(),
        (b, v) -> b.putBoolFields(25, BoolValue.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 30, oldAccount.getCodeHash(), newAccount.getCodeHash(),
        (b, v) -> b.putBytesFields(30, BytesValue.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 36, oldAccount.getDelegatedFrozenV2BalanceForBandwidth(),
        newAccount.getDelegatedFrozenV2BalanceForBandwidth(),
        (b, v) -> b.putInt64Fields(36, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 37, oldAccount.getAcquiredDelegatedFrozenV2BalanceForBandwidth(),
        newAccount.getAcquiredDelegatedFrozenV2BalanceForBandwidth(),
        (b, v) -> b.putInt64Fields(37, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 41, oldAccount.getAcquiredDelegatedFrozenBalanceForBandwidth(),
        newAccount.getAcquiredDelegatedFrozenBalanceForBandwidth(),
        (b, v) -> b.putInt64Fields(41, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 42, oldAccount.getDelegatedFrozenBalanceForBandwidth(),
        newAccount.getDelegatedFrozenBalanceForBandwidth(),
        (b, v) -> b.putInt64Fields(42, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 46, oldAccount.getOldTronPower(), newAccount.getOldTronPower(),
        (b, v) -> b.putInt64Fields(46, Int64Value.newBuilder().setValue(v).build()));
    diffFieldIfChanged(diff, 60, oldAccount.getAssetOptimized(), newAccount.getAssetOptimized(),
        (b, v) -> b.putBoolFields(60, BoolValue.newBuilder().setValue(v).build()));

    // --- 嵌套 message 字段 ---
    if (!Objects.equals(oldAccount.getAccountResource(), newAccount.getAccountResource())) {
      diff.putAccountResourceFields(26, newAccount.getAccountResource());
    }
    if (!Objects.equals(oldAccount.getTronPower(), newAccount.getTronPower())) {
      diff.putFrozenMessageFields(47, newAccount.getTronPower());
    }
    if (!Objects.equals(oldAccount.getOwnerPermission(), newAccount.getOwnerPermission())) {
      diff.putPermissionFields(31, newAccount.getOwnerPermission());
    }
    if (!Objects.equals(oldAccount.getWitnessPermission(), newAccount.getWitnessPermission())) {
      diff.putPermissionFields(32, newAccount.getWitnessPermission());
    }

    // --- repeated 字段（全量替换）---
    if (!oldAccount.getFrozenList().equals(newAccount.getFrozenList())) {
      diff.putRepeatedFrozen(7,
          FrozenList.newBuilder().addAllValues(newAccount.getFrozenList()).build());
    }
    if (!oldAccount.getFrozenSupplyList().equals(newAccount.getFrozenSupplyList())) {
      diff.putRepeatedFrozen(16,
          FrozenList.newBuilder().addAllValues(newAccount.getFrozenSupplyList()).build());
    }
    if (!oldAccount.getVotesList().equals(newAccount.getVotesList())) {
      diff.putRepeatedVotes(5,
          VoteList.newBuilder().addAllValues(newAccount.getVotesList()).build());
    }
    if (!oldAccount.getFrozenV2List().equals(newAccount.getFrozenV2List())) {
      diff.putRepeatedFrozenV2(34,
          FreezeV2List.newBuilder().addAllValues(newAccount.getFrozenV2List()).build());
    }
    if (!oldAccount.getUnfrozenV2List().equals(newAccount.getUnfrozenV2List())) {
      diff.putRepeatedUnfrozenV2(35,
          UnFreezeV2List.newBuilder().addAllValues(newAccount.getUnfrozenV2List()).build());
    }
    if (!oldAccount.getActivePermissionList().equals(newAccount.getActivePermissionList())) {
      diff.putRepeatedActivePermission(33,
          PermissionList.newBuilder().addAllValues(newAccount.getActivePermissionList()).build());
    }

    // --- map 字段 ---
    putMapDiffIfChanged(diff, 6, oldAccount.getAssetMap(), newAccount.getAssetMap());
    putMapDiffIfChanged(diff, 56, oldAccount.getAssetV2Map(), newAccount.getAssetV2Map());
    putMapDiffIfChanged(diff, 18, oldAccount.getLatestAssetOperationTimeMap(),
        newAccount.getLatestAssetOperationTimeMap());
    putMapDiffIfChanged(diff, 58, oldAccount.getLatestAssetOperationTimeV2Map(),
        newAccount.getLatestAssetOperationTimeV2Map());
    putMapDiffIfChanged(diff, 20, oldAccount.getFreeAssetNetUsageMap(),
        newAccount.getFreeAssetNetUsageMap());
    putMapDiffIfChanged(diff, 59, oldAccount.getFreeAssetNetUsageV2Map(),
        newAccount.getFreeAssetNetUsageV2Map());

    return diff.build();
  }

  @FunctionalInterface
  private interface FieldDiffSetter<T> {

    void set(AccountDiff.Builder builder, T value);
  }

  private static <T> void diffFieldIfChanged(
      AccountDiff.Builder builder,
      int fieldNumber,
      T oldValue,
      T newValue,
      FieldDiffSetter<T> setter) {
    if (!Objects.equals(oldValue, newValue)) {
      setter.set(builder, newValue);
    }
  }

  private static void putMapDiffIfChanged(
      AccountDiff.Builder builder,
      int fieldNumber,
      Map<String, Long> oldMap,
      Map<String, Long> newMap) {
    Map<String, Long> diff = computeMapDiff(oldMap, newMap);
    if (!diff.isEmpty()) {
      builder.putMapStringInt64(fieldNumber,
          MapStringInt64.newBuilder().putAllEntries(diff).build());
    }
  }

  private static Map<String, Long> computeMapDiff(
      Map<String, Long> oldMap,
      Map<String, Long> newMap) {
    Map<String, Long> diff = new HashMap<>();
    for (Map.Entry<String, Long> entry : newMap.entrySet()) {
      String key = entry.getKey();
      long newValue = entry.getValue();
      long oldValue = oldMap.getOrDefault(key, 0L);
      if (newValue != oldValue) {
        diff.put(key, newValue);
      }
    }
    // 注意：当前逻辑不支持显式删除（value=0 可能是合法值）
    // 如需支持删除，应添加 deleted_keys 列表或使用特殊标记值
    return diff;
  }

  /**
   * 应用 Diff 到旧 Account，生成新 Account
   */
  public static Account applyDiff(Account oldAccount, AccountDiff diff) {
    Account.Builder builder = oldAccount.toBuilder();

    // 简单字段
    if (diff.getInt64FieldsMap().containsKey(4)) {
      builder.setBalance(diff.getInt64FieldsMap().get(4).getValue());
    }
    if (diff.getBytesFieldsMap().containsKey(1)) {
      builder.setAccountName(diff.getBytesFieldsMap().get(1).getValue());
    }
    if (diff.getEnumFieldsMap().containsKey(2)) {
      builder.setType(diff.getEnumFieldsMap().get(2));
    }
    if (diff.getBytesFieldsMap().containsKey(3)) {
      builder.setAddress(diff.getBytesFieldsMap().get(3).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(8)) {
      builder.setNetUsage(diff.getInt64FieldsMap().get(8).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(9)) {
      builder.setCreateTime(diff.getInt64FieldsMap().get(9).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(10)) {
      builder.setLatestOprationTime(diff.getInt64FieldsMap().get(10).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(11)) {
      builder.setAllowance(diff.getInt64FieldsMap().get(11).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(12)) {
      builder.setLatestWithdrawTime(diff.getInt64FieldsMap().get(12).getValue());
    }
    if (diff.getBytesFieldsMap().containsKey(13)) {
      builder.setCode(diff.getBytesFieldsMap().get(13).getValue());
    }
    if (diff.getBoolFieldsMap().containsKey(14)) {
      builder.setIsWitness(diff.getBoolFieldsMap().get(14).getValue());
    }
    if (diff.getBoolFieldsMap().containsKey(15)) {
      builder.setIsCommittee(diff.getBoolFieldsMap().get(15).getValue());
    }
    if (diff.getBytesFieldsMap().containsKey(17)) {
      builder.setAssetIssuedName(diff.getBytesFieldsMap().get(17).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(19)) {
      builder.setFreeNetUsage(diff.getInt64FieldsMap().get(19).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(21)) {
      builder.setLatestConsumeTime(diff.getInt64FieldsMap().get(21).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(22)) {
      builder.setLatestConsumeFreeTime(diff.getInt64FieldsMap().get(22).getValue());
    }
    if (diff.getBytesFieldsMap().containsKey(23)) {
      builder.setAccountId(diff.getBytesFieldsMap().get(23).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(24)) {
      builder.setNetWindowSize(diff.getInt64FieldsMap().get(24).getValue());
    }
    if (diff.getBoolFieldsMap().containsKey(25)) {
      builder.setNetWindowOptimized(diff.getBoolFieldsMap().get(25).getValue());
    }
    if (diff.getBytesFieldsMap().containsKey(30)) {
      builder.setCodeHash(diff.getBytesFieldsMap().get(30).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(36)) {
      builder.setDelegatedFrozenV2BalanceForBandwidth(diff.getInt64FieldsMap().get(36).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(37)) {
      builder.setAcquiredDelegatedFrozenV2BalanceForBandwidth(
          diff.getInt64FieldsMap().get(37).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(41)) {
      builder.setAcquiredDelegatedFrozenBalanceForBandwidth(
          diff.getInt64FieldsMap().get(41).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(42)) {
      builder.setDelegatedFrozenBalanceForBandwidth(diff.getInt64FieldsMap().get(42).getValue());
    }
    if (diff.getInt64FieldsMap().containsKey(46)) {
      builder.setOldTronPower(diff.getInt64FieldsMap().get(46).getValue());
    }
    if (diff.getBoolFieldsMap().containsKey(60)) {
      builder.setAssetOptimized(diff.getBoolFieldsMap().get(60).getValue());
    }

    // 嵌套 message
    if (diff.getAccountResourceFieldsMap().containsKey(26)) {
      builder.setAccountResource(diff.getAccountResourceFieldsMap().get(26));
    }
    if (diff.getFrozenMessageFieldsMap().containsKey(47)) {
      builder.setTronPower(diff.getFrozenMessageFieldsMap().get(47));
    }
    if (diff.getPermissionFieldsMap().containsKey(31)) {
      builder.setOwnerPermission(diff.getPermissionFieldsMap().get(31));
    }
    if (diff.getPermissionFieldsMap().containsKey(32)) {
      builder.setWitnessPermission(diff.getPermissionFieldsMap().get(32));
    }

    // repeated 字段
    if (diff.getRepeatedFrozenMap().containsKey(7)) {
      builder.clearFrozen().addAllFrozen(
          diff.getRepeatedFrozenMap().get(7).getValuesList());
    }
    if (diff.getRepeatedFrozenMap().containsKey(16)) {
      builder.clearFrozenSupply().addAllFrozenSupply(
          diff.getRepeatedFrozenMap().get(16).getValuesList());
    }
    if (diff.getRepeatedVotesMap().containsKey(5)) {
      builder.clearVotes().addAllVotes(
          diff.getRepeatedVotesMap().get(5).getValuesList());
    }
    if (diff.getRepeatedFrozenV2Map().containsKey(34)) {
      builder.clearFrozenV2().addAllFrozenV2(
          diff.getRepeatedFrozenV2Map().get(34).getValuesList());
    }
    if (diff.getRepeatedUnfrozenV2Map().containsKey(35)) {
      builder.clearUnfrozenV2().addAllUnfrozenV2(
          diff.getRepeatedUnfrozenV2Map().get(35).getValuesList());
    }
    if (diff.getRepeatedActivePermissionMap().containsKey(33)) {
      builder.clearActivePermission().addAllActivePermission(
          diff.getRepeatedActivePermissionMap().get(33).getValuesList());
    }

    // map 字段
    applyMapDiff(builder, 6, diff);
    applyMapDiff(builder, 56, diff);
    applyMapDiff(builder, 18, diff);
    applyMapDiff(builder, 58, diff);
    applyMapDiff(builder, 20, diff);
    applyMapDiff(builder, 59, diff);

    return builder.build();
  }

  private static void applyMapDiff(Account.Builder builder, int fieldNumber, AccountDiff diff) {
    if (!diff.getMapStringInt64Map().containsKey(fieldNumber)) {
      return;
    }

    Map<String, Long> entries = diff.getMapStringInt64Map().get(fieldNumber).getEntriesMap();
    switch (fieldNumber) {
      case 6:
        builder.putAllAsset(entries);
        break;
      case 56:
        builder.putAllAssetV2(entries);
        break;
      case 18:
        builder.putAllLatestAssetOperationTime(entries);
        break;
      case 58:
        builder.putAllLatestAssetOperationTimeV2(entries);
        break;
      case 20:
        builder.putAllFreeAssetNetUsage(entries);
        break;
      case 59:
        builder.putAllFreeAssetNetUsageV2(entries);
        break;
      default:
        throw new IllegalArgumentException("Unknown map field: " + fieldNumber);
    }
  }

  // 工具方法：序列化/反序列化
  public static byte[] toBytes(AccountDiff diff) {
    return diff.toByteArray();
  }

  public static AccountDiff fromBytes(byte[] bytes)
      throws InvalidProtocolBufferException {
    return AccountDiff.parseFrom(bytes);
  }

}
