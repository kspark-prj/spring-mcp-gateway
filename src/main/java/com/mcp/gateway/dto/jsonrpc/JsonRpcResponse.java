package com.mcp.gateway.dto.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard JSON-RPC 2.0 Response DTO.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcResponse {

    private String jsonrpc = "2.0";
    private Object id;
    private Object result;
    private JsonRpcError error;

    public JsonRpcResponse() {
    }

    public static JsonRpcResponse success(Object id, Object result) {
        JsonRpcResponse resp = new JsonRpcResponse();
        resp.setId(id);
        resp.setResult(result);
        return resp;
    }

    public static JsonRpcResponse error(Object id, int errorCode, String errorMessage) {
        JsonRpcResponse resp = new JsonRpcResponse();
        resp.setId(id);
        resp.setError(new JsonRpcError(errorCode, errorMessage));
        return resp;
    }

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public JsonRpcError getError() {
        return error;
    }

    public void setError(JsonRpcError error) {
        this.error = error;
    }
}
