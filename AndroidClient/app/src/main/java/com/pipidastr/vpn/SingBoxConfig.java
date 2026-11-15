package com.pipidastr.vpn;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.util.Locale;

/** Builds a mobile TUN configuration for protocols supported by the bundled libbox. */
final class SingBoxConfig {
    private SingBoxConfig() { }

    static String build(SubscriptionParser.Server server) throws Exception {
        URI uri = URI.create(server.link);
        JSONObject proxy = new JSONObject()
                .put("tag", "proxy")
                .put("server", server.host)
                .put("server_port", server.port);
        if ("vless".equalsIgnoreCase(uri.getScheme())) {
            vless(proxy, uri);
        } else if ("hysteria2".equalsIgnoreCase(uri.getScheme())) {
            hysteria2(proxy, uri);
        } else {
            throw new IllegalArgumentException("Этот протокол пока не поддерживается на Android.");
        }

        JSONObject tun = new JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("address", new JSONArray().put("172.19.0.1/30"))
                .put("mtu", 1500)
                .put("auto_route", true)
                .put("dns_mode", "hijack")
                .put("stack", "mixed");
        JSONObject dnsServer = new JSONObject()
                .put("type", "https")
                .put("tag", "vpn-dns")
                .put("server", "1.1.1.1")
                .put("path", "/dns-query")
                .put("tls", new JSONObject().put("enabled", true)
                        .put("server_name", "cloudflare-dns.com"))
                .put("detour", "proxy");
        JSONObject dns = new JSONObject()
                .put("servers", new JSONArray().put(dnsServer))
                .put("final", "vpn-dns")
                .put("strategy", "ipv4_only");
        JSONObject route = new JSONObject()
                .put("auto_detect_interface", true)
                .put("final", "proxy");
        JSONObject log = new JSONObject().put("level", "warn");
        return new JSONObject()
                .put("log", log)
                .put("dns", dns)
                .put("inbounds", new JSONArray().put(tun))
                .put("outbounds", new JSONArray().put(proxy)
                        .put(new JSONObject().put("type", "direct").put("tag", "direct")))
                .put("route", route)
                .toString();
    }

    private static void vless(JSONObject proxy, URI uri) throws Exception {
        if (!param(uri, "fm").isEmpty() || !param(uri, "pcs").isEmpty())
            throw new IllegalArgumentException("Этот сервер использует параметры Xray, которых пока нет в Android-ядре.");
        String encryption = param(uri, "encryption");
        if (!encryption.isEmpty() && !"none".equalsIgnoreCase(encryption))
            throw new IllegalArgumentException("Шифрованный VLESS этого сервера пока не поддерживается на Android.");
        String security = param(uri, "security").toLowerCase(Locale.ROOT);
        if (!"tls".equals(security) && !"reality".equals(security))
            throw new IllegalArgumentException("Для Android нужен сервер VLESS с TLS или Reality.");
        proxy.put("type", "vless").put("uuid", uri.getRawUserInfo());
        String flow = param(uri, "flow");
        if (!flow.isEmpty()) proxy.put("flow", flow);

        JSONObject tls = new JSONObject().put("enabled", true);
        String sni = param(uri, "sni");
        tls.put("server_name", sni.isEmpty() ? uri.getHost() : sni);
        String alpn = param(uri, "alpn");
        if (!alpn.isEmpty()) {
            JSONArray values = new JSONArray();
            for (String item : alpn.split(",")) if (!item.trim().isEmpty()) values.put(item.trim());
            tls.put("alpn", values);
        }
        String fingerprint = param(uri, "fp");
        if (!fingerprint.isEmpty())
            tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fingerprint));
        if ("reality".equals(security)) {
            String publicKey = param(uri, "pbk");
            if (publicKey.isEmpty()) throw new IllegalArgumentException("В сервере Reality нет публичного ключа.");
            tls.put("reality", new JSONObject().put("enabled", true)
                    .put("public_key", publicKey).put("short_id", param(uri, "sid")));
        }
        proxy.put("tls", tls);
        String transport = param(uri, "type").toLowerCase(Locale.ROOT);
        if ("grpc".equals(transport))
            proxy.put("transport", new JSONObject().put("type", "grpc")
                    .put("service_name", param(uri, "serviceName")));
        else if (!transport.isEmpty() && !"tcp".equals(transport) && !"raw".equals(transport))
            throw new IllegalArgumentException("Транспорт VLESS пока не поддерживается на Android.");
    }

    private static void hysteria2(JSONObject proxy, URI uri) throws Exception {
        if (!param(uri, "fm").isEmpty())
            throw new IllegalArgumentException("Этот сервер использует Xray finalmask, которого нет в Android-ядре.");
        if (!param(uri, "pinSHA256").isEmpty() || "1".equals(param(uri, "insecure")) ||
                "true".equalsIgnoreCase(param(uri, "insecure")))
            throw new IllegalArgumentException("Проверка сертификата этого Hysteria2-сервера пока не поддерживается.");
        proxy.put("type", "hysteria2")
                .put("password", decode(uri.getRawUserInfo()));
        String sni = param(uri, "sni");
        proxy.put("tls", new JSONObject().put("enabled", true)
                .put("server_name", sni.isEmpty() ? uri.getHost() : sni));
        String obfs = param(uri, "obfs");
        if (!obfs.isEmpty()) {
            if (!"salamander".equals(obfs))
                throw new IllegalArgumentException("Маскировка Hysteria2 пока не поддерживается.");
            proxy.put("obfs", new JSONObject().put("type", "salamander")
                    .put("password", param(uri, "obfs-password")));
        }
    }

    private static String param(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) return "";
        for (String item : query.split("&")) {
            String[] pair = item.split("=", 2);
            if (decode(pair[0]).equalsIgnoreCase(name)) return pair.length == 2 ? decode(pair[1]) : "";
        }
        return "";
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8"); }
        catch (UnsupportedEncodingException ex) { throw new AssertionError(ex); }
    }
}
