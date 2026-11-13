using System.Text.Json;
using System.Text.Json.Serialization;

namespace VpnClient;

public static class XrayConfigBuilder
{
    public static string Build(VlessNode node, int socksPort, int httpPort)
    {
        var outbound = new
        {
            tag = "vpn", protocol = "vless",
            settings = new { vnext = new[] { new { address = node.Host, port = node.Port, users = new[] { new { id = node.Id.ToString(), encryption = node.Encryption, flow = node.Flow } } } } },
            streamSettings = new
            {
                network = node.Transport, security = node.Security,
                grpcSettings = node.Transport == "grpc" ? new { serviceName = node.ServiceName } : null,
                realitySettings = node.Security == "reality" ? new { serverName = node.Sni, fingerprint = node.Fingerprint, publicKey = node.PublicKey, shortId = node.ShortId, spiderX = "/" } : null,
                tlsSettings = node.Security == "tls" ? new { serverName = node.Sni, fingerprint = node.Fingerprint, alpn = node.Alpn, pinnedPeerCertSha256 = node.PinnedCertSha256 } : null,
                finalmask = node.FinalMask
            }
        };
        return BuildCore(outbound, socksPort, httpPort);
    }

    public static string Build(HysteriaNode node, int socksPort, int httpPort)
    {
        var outbound = new
        {
            tag = "vpn", protocol = "hysteria",
            settings = new { version = 2, address = node.Host, port = node.Port },
            streamSettings = new
            {
                method = "hysteria", security = "tls",
                hysteriaSettings = new { version = 2, auth = node.Password },
                tlsSettings = new { serverName = node.Sni, pinnedPeerCertSha256 = node.PinSha256 },
                finalmask = node.FinalMask
            }
        };
        return BuildCore(outbound, socksPort, httpPort);
    }

    private static string BuildCore(object outbound, int socksPort, int httpPort)
    {
        if (socksPort is < 1 or > 65535 || httpPort is < 1 or > 65535 || socksPort == httpPort)
            throw new ArgumentOutOfRangeException(nameof(socksPort));

        var config = new
        {
            log = new { loglevel = "warning" },
            inbounds = new object[]
            {
                new { tag = "local-socks", listen = "127.0.0.1", port = socksPort, protocol = "socks", settings = new { udp = true } },
                new { tag = "health-check", listen = "127.0.0.1", port = httpPort, protocol = "http", settings = new { } }
            },
            outbounds = new[] { outbound }
        };
        return JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true, DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull });
    }
}
