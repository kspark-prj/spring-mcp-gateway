package com.mcp.gateway.dto.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Standard JSON-RPC 2.0 Request DTO for Model Context Protocol (MCP) communications.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcRequest {

    private String jsonrpc = "2.0";
    private Object id;
    private String method;
    private Map<String, Object> params;

    public JsonRpcRequest() {
    }

    public JsonRpcRequest(Object id, String method, Map<String, Object> params) {
        this.jsonrpc = "2.0";
        this.id = id;
        this.method = method;
        this.params = params;
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

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
}
