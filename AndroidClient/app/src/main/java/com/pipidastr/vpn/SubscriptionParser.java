package com.pipidastr.vpn;

import java.net.URI;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Parses the same subscription line formats as the Windows client. */
public final class SubscriptionParser {
    private SubscriptionParser() { }

    public static final class Server {
        public final String name;
        public final String host;
        public final int port;
        public final String protocol;
        public final String link;

        Server(String name, String host, int port, String protocol, String link) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.protocol = protocol;
            this.link = link;
        }

        @Override public String toString() {
            return name + "\n" + host + ":" + port + " · " + protocol;
        }
    }

    public static final class Result {
        public final List<Server> servers;
        public final int skipped;

        Result(List<Server> servers, int skipped) {
            this.servers = Collections.unmodifiableList(servers);
            this.skipped = skipped;
        }
    }

    public static final class DeviceIdRequiredException extends IllegalArgumentException {
        DeviceIdRequiredException() { super("Подписка требует ID устройства"); }
    }

    public static Result parse(String body) {
        if (body == null || body.trim().isEmpty()) throw new IllegalArgumentException("Подписка пуста");
        String decoded = body.trim();
        if (!decoded.toLowerCase(Locale.ROOT).contains("vless://") &&
                !decoded.toLowerCase(Locale.ROOT).contains("hysteria2://")) {
            try {
                String normalized = decoded.replace('-', '+').replace('_', '/');
                decoded = new String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Подписка не содержит ссылок VLESS или Hysteria2", ex);
            }
        }

        List<Server> servers = new ArrayList<>();
        int skipped = 0;
        for (String line : decoded.split("[\\r\\n]+")) {
            String link = line.trim();
            if (link.isEmpty()) continue;
            if (!link.toLowerCase(Locale.ROOT).startsWith("vless://") &&
                    !link.toLowerCase(Locale.ROOT).startsWith("hysteria2://")) {
                if (link.contains("://")) skipped++;
                continue;
            }
            try {
                URI uri = URI.create(link);
                if (uri.getPort() == 1 && decode(uri.getRawFragment()).toUpperCase(Locale.ROOT).contains("HWID"))
                    throw new DeviceIdRequiredException();
                String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
                String host = uri.getHost();
                int port = uri.getPort();
                if (host == null || host.trim().isEmpty() || port < 1 || port > 65535)
                    throw new IllegalArgumentException("Нет адреса сервера или порта");
                if (scheme.equals("vless")) {
                    if (uri.getRawUserInfo() == null) throw new IllegalArgumentException("Нет UUID VLESS");
                    UUID.fromString(uri.getRawUserInfo());
                    String security = parameter(uri.getRawQuery(), "security").toLowerCase(Locale.ROOT);
                    String transport = parameter(uri.getRawQuery(), "type").toLowerCase(Locale.ROOT);
                    if (!security.equals("reality") && !security.equals("tls") && !security.equals("none"))
                        throw new IllegalArgumentException("Неподдерживаемая защита VLESS");
                    if (!transport.isEmpty() && !transport.equals("raw") && !transport.equals("tcp") && !transport.equals("grpc"))
                        throw new IllegalArgumentException("Неподдерживаемый транспорт VLESS");
                    if (security.equals("reality") && (parameter(uri.getRawQuery(), "pbk").isEmpty() ||
                            parameter(uri.getRawQuery(), "sni").isEmpty()))
                        throw new IllegalArgumentException("В ссылке Reality нет ключа или SNI");
                    String flow = parameter(uri.getRawQuery(), "flow");
                    if (!flow.isEmpty() && !flow.equals("xtls-rprx-vision"))
                        throw new IllegalArgumentException("Неподдерживаемый flow VLESS");
                    String encryption = parameter(uri.getRawQuery(), "encryption");
                    if (security.equals("none") && (encryption.isEmpty() || encryption.equals("none")))
                        throw new IllegalArgumentException("VLESS без TLS требует шифрование");
                } else if (uri.getRawUserInfo() == null || uri.getRawUserInfo().isEmpty()) {
                    throw new IllegalArgumentException("Нет пароля Hysteria2");
                }
                String name = decode(uri.getRawFragment());
                servers.add(new Server(name.isEmpty() ? host : name, host, port,
                        scheme.equals("vless") ? "VLESS" : "Hysteria2", link));
            } catch (DeviceIdRequiredException ex) {
                throw ex;
            } catch (IllegalArgumentException ex) {
                skipped++;
            }
        }
        if (servers.isEmpty()) throw new IllegalArgumentException("В подписке нет поддерживаемых серверов");
        return new Result(servers, skipped);
    }

    private static String parameter(String query, String name) {
        if (query == null) return "";
        for (String item : query.split("&")) {
            String[] pair = item.split("=", 2);
            if (decode(pair[0]).equalsIgnoreCase(name))
                return pair.length == 2 ? decode(pair[1]) : "";
        }
        return "";
    }

    private static String decode(String raw) {
        if (raw == null) return "";
        try { return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8"); }
        catch (UnsupportedEncodingException ex) { throw new AssertionError(ex); }
    }
}
