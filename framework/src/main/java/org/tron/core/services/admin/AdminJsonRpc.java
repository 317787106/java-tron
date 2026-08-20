package org.tron.core.services.admin;

import com.googlecode.jsonrpc4j.JsonRpcError;
import com.googlecode.jsonrpc4j.JsonRpcErrors;
import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.googlecode.jsonrpc4j.JsonRpcParam;
import java.util.List;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;

public interface AdminJsonRpc {

  @JsonRpcMethod("admin_addPeer")
  @JsonRpcErrors({
      @JsonRpcError(exception = JsonRpcInvalidParamsException.class, code = -32602, data = "{}"),
      @JsonRpcError(exception = JsonRpcInternalException.class, code = -32000, data = "{}"),
  })
  PeerOperationResult addPeer(@JsonRpcParam("endpoint") String endpoint)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;

  @JsonRpcMethod("admin_removePeer")
  @JsonRpcErrors({
      @JsonRpcError(exception = JsonRpcInvalidParamsException.class, code = -32602, data = "{}"),
      @JsonRpcError(exception = JsonRpcInternalException.class, code = -32000, data = "{}"),
  })
  PeerOperationResult removePeer(@JsonRpcParam("endpoint") String endpoint)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;

  @JsonRpcMethod("admin_disconnectPeer")
  @JsonRpcErrors({
      @JsonRpcError(exception = JsonRpcInvalidParamsException.class, code = -32602, data = "{}"),
      @JsonRpcError(exception = JsonRpcInternalException.class, code = -32000, data = "{}"),
  })
  PeerOperationResult disconnectPeer(@JsonRpcParam("endpoint") String endpoint)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;

  @JsonRpcMethod("admin_listActivePeers")
  ActivePeerListResult listActivePeers();

  @JsonRpcMethod("admin_blockIp")
  @JsonRpcErrors({
      @JsonRpcError(exception = JsonRpcInvalidParamsException.class, code = -32602, data = "{}"),
      @JsonRpcError(exception = JsonRpcInternalException.class, code = -32000, data = "{}"),
  })
  PeerOperationResult blockIp(@JsonRpcParam("ip") String ip)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;

  @JsonRpcMethod("admin_unblockIp")
  @JsonRpcErrors({
      @JsonRpcError(exception = JsonRpcInvalidParamsException.class, code = -32602, data = "{}"),
      @JsonRpcError(exception = JsonRpcInternalException.class, code = -32000, data = "{}"),
  })
  PeerOperationResult unblockIp(@JsonRpcParam("ip") String ip)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;

  @JsonRpcMethod("admin_listBlockedIps")
  List<String> listBlockedIps();
}
