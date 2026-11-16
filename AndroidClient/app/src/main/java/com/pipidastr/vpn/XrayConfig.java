package com.pipidastr.vpn;

import org.json.JSONArray;
import org.json.JSONObject;

import libXray.LibXray;

/** Converts a validated share link into an Xray configuration with Android TUN. */
final class XrayConfig {
    private XrayConfig() { }

    static String build(SubscriptionParser.Server server) throws Exception {
        JSONObject request = new JSONObject()
                .put("apiVersion", 3)
                .put("method", "convertShareLinksToXrayJson")
                .put("payload", new JSONObject().put("text", server.link));
        JSONObject response = new JSONObject(LibXray.invoke(request.toString()));
        if (!response.optBoolean("success"))
            throw new IllegalArgumentException("Xray не поддерживает параметры этого сервера.");
        JSONArray outbounds = response.getJSONObject("data").getJSONArray("outbounds");
        if (outbounds.length() != 1)
            throw new IllegalArgumentException("Ссылка не содержит одного сервера Xray.");
        JSONObject outbound = outbounds.getJSONObject(0).put("tag", "proxy");
        JSONObject tun = new JSONObject()
                .put("tag", "tun-in")
                .put("port", 0)
                .put("protocol", "tun")
                .put("settings", new JSONObject().put("name", "tun0").put("mtu", 1500));
        return new JSONObject()
                .put("log", new JSONObject().put("loglevel", "warning"))
                .put("inbounds", new JSONArray().put(tun))
                .put("outbounds", new JSONArray().put(outbound))
                .toString();
    }

    static String withTunFd(String config, int fd) throws Exception {
        return new JSONObject(config)
                .put("env", new JSONObject().put("xray.tun.fd", Integer.toString(fd)))
                .toString();
    }
}
