using System.Text.Json;

namespace VpnClient;

public static class XrayConfigBuilder
{
    public static string Build(VlessNode node, int socksPort, int httpPort)
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
            outbounds = new object[]
            {
                new
                {
                    tag = "vpn", protocol = "vless",
                    settings = new { vnext = new[] { new { address = node.Host, port = node.Port, users = new[] { new { id = node.Id.ToString(), encryption = "none", flow = node.Flow } } } } },
                    streamSettings = new
                    {
                        network = "tcp", security = "reality",
                        realitySettings = new { serverName = node.Sni, fingerprint = node.Fingerprint, publicKey = node.PublicKey, shortId = node.ShortId, spiderX = "/" }
                    }
                }
            }
        };
        return JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true });
    }
}
