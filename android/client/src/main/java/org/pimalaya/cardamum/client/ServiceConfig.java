package org.pimalaya.cardamum.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One discovered way to use one service: where to connect, how to
 * authenticate, and which mechanism found it. Produced by
 * {@link CardamumClient#searchAll} and {@link CardamumClient#searchFirst}.
 */
public final class ServiceConfig {
    /** Service kind, e.g. {@code carddav}. */
    public final String service;

    /** HTTP endpoint URL; null for TCP endpoints. */
    public final String url;

    /** TCP host; null for HTTP endpoints. */
    public final String host;

    /** TCP port; 0 for HTTP endpoints. */
    public final int port;

    /**
     * TCP security ({@code plain}, {@code starttls}, {@code tls});
     * null for HTTP endpoints.
     */
    public final String security;

    /** Login to present, when the mechanism advertises one. */
    public final String username;

    /** Authentication methods the service accepts. */
    public final List<AuthMethod> auth;

    /**
     * Mechanism that produced this config: {@code provider:google},
     * {@code provider:microsoft}, {@code pacc}, {@code dav}, ...
     */
    public final String source;

    private ServiceConfig(
            String service,
            String url,
            String host,
            int port,
            String security,
            String username,
            List<AuthMethod> auth,
            String source) {
        this.service = service;
        this.url = url;
        this.host = host;
        this.port = port;
        this.security = security;
        this.username = username;
        this.auth = auth;
        this.source = source;
    }

    /** Parses one config from the bridge's JSON shape. */
    static ServiceConfig from(JSONObject json) {
        try {
            String url = null;
            String host = null;
            int port = 0;
            String security = null;

            JSONObject endpoint = json.getJSONObject("endpoint");
            if (endpoint.has("http")) {
                url = endpoint.getString("http");
            } else {
                JSONObject tcp = endpoint.getJSONObject("tcp");
                host = tcp.getString("host");
                port = tcp.getInt("port");
                security = tcp.getString("security");
            }

            List<AuthMethod> auth = new ArrayList<>();
            JSONArray methods = json.getJSONArray("auth");
            for (int index = 0; index < methods.length(); index++) {
                auth.add(AuthMethod.from(methods.get(index)));
            }

            return new ServiceConfig(
                    json.getString("service"),
                    url,
                    host,
                    port,
                    security,
                    json.isNull("username") ? null : json.getString("username"),
                    auth,
                    source(json.get("source")));
        } catch (JSONException error) {
            throw new CardamumException("Unreadable service config: " + error.getMessage());
        }
    }

    /**
     * Flattens the source tag: plain mechanisms come as strings,
     * fixed provider rules as {@code {"provider": ".."}} objects.
     */
    private static String source(Object source) throws JSONException {
        if (source instanceof JSONObject) {
            return "provider:" + ((JSONObject) source).getString("provider");
        }
        return source.toString();
    }
}
