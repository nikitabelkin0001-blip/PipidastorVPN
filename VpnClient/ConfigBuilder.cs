using System.Text.Json;

namespace VpnClient;

public static class ConfigBuilder
{
    public static string Build(ConnectionProfile profile, int socksPort, IEnumerable<string>? serverAddresses = null)
    {
        if (socksPort is < 1 or > 65535) throw new ArgumentOutOfRangeException(nameof(socksPort));
        if (profile.Mode == TrafficMode.SelectedApps && profile.Applications.Count == 0)
            throw new InvalidOperationException("Добавьте хотя бы одно приложение в профиль.");

        var rules = new List<object>
        {
            // Xray must reach the remote server directly; otherwise TUN creates a loop.
            new { process_name = new[] { "xray.exe" }, outbound = "direct" },
            new { ip_is_private = true, outbound = "direct" },
            new { process_name = profile.DirectApplications.Distinct(StringComparer.OrdinalIgnoreCase).ToArray(), outbound = "direct" }
        };
        if (profile.Mode == TrafficMode.SelectedApps)
        {
            var appNames = profile.Applications.Select(Path.GetFileName).Where(name => !string.IsNullOrWhiteSpace(name))
                .Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
            var appPaths = profile.Applications.Where(Path.IsPathFullyQualified)
                .Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
            if (appNames.Length > 0) rules.Add(new { process_name = appNames, outbound = "proxy" });
            if (appPaths.Length > 0) rules.Add(new { process_path = appPaths, outbound = "proxy" });
        }

        var excludedRoutes = new List<string>();
        if (serverAddresses != null)
            excludedRoutes.AddRange(serverAddresses.Select(address => address + "/32"));
        if (profile.DirectApplications.Any(name => Path.GetFileName(name).Equals("Discord.exe", StringComparison.OrdinalIgnoreCase)))
            excludedRoutes.Add("162.159.128.0/17");

        var config = new
        {
            log = new { level = "warn", timestamp = true },
            dns = new { servers = new object[] { new { type = "https", tag = "vpn-dns", server = "1.1.1.1", path = "/dns-query", tls = new { enabled = true, server_name = "cloudflare-dns.com" }, detour = "proxy" } }, final = "vpn-dns", strategy = "ipv4_only" },
            inbounds = new object[] { new { type = "tun", tag = "tun-in", interface_name = "PipidastrVPN", address = new[] { "172.19.0.1/30" }, auto_route = true, strict_route = false, stack = "mixed", udp_timeout = "15m",
                route_exclude_address = excludedRoutes.Distinct(StringComparer.OrdinalIgnoreCase).ToArray() } },
            outbounds = new object[] { new { type = "socks", tag = "proxy", server = "127.0.0.1", server_port = socksPort, version = "5" }, new { type = "direct", tag = "direct" } },
            route = new { auto_detect_interface = true, rules, final = profile.Mode == TrafficMode.FullTunnel ? "proxy" : "direct" }
        };
        return JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true });
    }
}
