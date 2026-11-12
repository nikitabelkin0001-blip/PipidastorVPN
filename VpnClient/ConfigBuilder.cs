using System.Text.Json;

namespace VpnClient;

public static class ConfigBuilder
{
    public static string Build(ConnectionProfile profile, int socksPort)
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
            var appNames = profile.Applications.Where(name => !Path.IsPathFullyQualified(name))
                .Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
            var appPaths = profile.Applications.Where(Path.IsPathFullyQualified)
                .Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
            if (appNames.Length > 0) rules.Add(new { process_name = appNames, outbound = "proxy" });
            if (appPaths.Length > 0) rules.Add(new { process_path = appPaths, outbound = "proxy" });
        }

        var config = new
        {
            log = new { level = "info", timestamp = true },
            dns = new { servers = new object[] { new { type = "udp", tag = "local-dns", server = "1.1.1.1" } }, final = "local-dns" },
            inbounds = new object[] { new { type = "tun", tag = "tun-in", interface_name = "PipidastrVPN", address = new[] { "172.19.0.1/30", "fdfe:dcba:9876::1/126" }, auto_route = true, strict_route = false, stack = "system" } },
            outbounds = new object[] { new { type = "socks", tag = "proxy", server = "127.0.0.1", server_port = socksPort, version = "5" }, new { type = "direct", tag = "direct" } },
            route = new { auto_detect_interface = true, rules, final = profile.Mode == TrafficMode.FullTunnel ? "proxy" : "direct" }
        };
        return JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true });
    }
}
