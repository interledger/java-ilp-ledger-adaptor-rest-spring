package org.interledger.ilp.ledger.adaptor.ws.jsonrpc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Defines the JSON-RPC call to subscribe to account notifications.
 */
@JsonDeserialize(as = JsonRpcSubscribeAccountRequest.class)
public class JsonRpcSubscribeAccountRequest extends JsonRpcRequestMessage {

  private JsonRpcSubscribeAccountRequestParams params;

  public JsonRpcSubscribeAccountRequest() {
    setMethod("subscribe_account");
  }

  @JsonProperty(value = "params")
  public JsonRpcSubscribeAccountRequestParams getParams() {
    return this.params;
  }

  public void setParams(JsonRpcSubscribeAccountRequestParams params) {
    this.params = params;
  }
}
