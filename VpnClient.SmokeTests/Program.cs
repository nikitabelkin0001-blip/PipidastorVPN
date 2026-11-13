using System.Diagnostics;
using System.Net;
using System.Text;
using System.Text.Json;
using VpnClient;

var link = "vless://bf000d23-0752-40b4-affe-68f7707a9661@example.com:443?security=reality&type=tcp&sni=www.example.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=01234567&fp=chrome&flow=xtls-rprx-vision#Sample";
var node = Vless.Parse(link);
if (node.Name != "Sample" || node.ShortId != "01234567") throw new Exception("VLESS parser failed");
var tlsLink = "vless://bf000d23-0752-40b4-affe-68f7707a9661@example.com:443?security=tls&type=tcp&sni=www.example.com&alpn=h2%2Chttp%2F1.1&pcs=" + new string('a', 64) + "#TLS";
var tlsNode = Vless.Parse(tlsLink);
if (tlsNode.Security != "tls" || tlsNode.Alpn.Length != 2 || tlsNode.PinnedCertSha256 != new string('a', 64))
    throw new Exception("VLESS TLS parser failed");
using (var tlsConfig = JsonDocument.Parse(XrayConfigBuilder.Build(tlsNode, 10880, 10881)))
    if (tlsConfig.RootElement.GetProperty("outbounds")[0].GetProperty("streamSettings").GetProperty("tlsSettings")
        .GetProperty("pinnedPeerCertSha256").GetString() != tlsNode.PinnedCertSha256)
        throw new Exception("Xray TLS certificate pin missing");
var grpcLink = link.Replace("type=tcp", "type=grpc&serviceName=demo");
var grpcNode = Vless.Parse(grpcLink);
if (grpcNode.Transport != "grpc" || grpcNode.ServiceName != "demo") throw new Exception("VLESS gRPC parser failed");
var encryptedLink = "vless://bf000d23-0752-40b4-affe-68f7707a9661@example.com:443?security=none&type=tcp&encryption=mlkem768x25519plus.example&fm=%7B%22tcp%22%3A%5B%5D%7D#Encrypted";
var encryptedNode = Vless.Parse(encryptedLink);
if (encryptedNode.Security != "none" || encryptedNode.Encryption == "none" || encryptedNode.FinalMask == null)
    throw new Exception("Encrypted VLESS parser failed");
var hysteriaLink = "hysteria2://password@example.com:443?obfs=salamander&obfs-password=mask&sni=example.com&pinSHA256=" + new string('a', 64) + "#Hysteria";
var hysteriaNode = Hysteria.Parse(hysteriaLink);
if (hysteriaNode.Password != "password" || hysteriaNode.FinalMask == null) throw new Exception("Hysteria2 parser failed");
var encoded = Convert.ToBase64String(Encoding.UTF8.GetBytes(link + "\n" + link.Replace("#Sample", "#Second")));
if (Vless.ParseSubscription(encoded).Count != 2) throw new Exception("Subscription parser failed");
if (Vless.ParseSubscription(Convert.ToBase64String(Encoding.UTF8.GetBytes(link + "\n" + tlsLink + "\n" + grpcLink + "\n" + encryptedLink + "\n" + hysteriaLink))).Count != 5)
    throw new Exception("Mixed VLESS subscription parser failed");
var partial = Vless.ParseSubscriptionDetailed(link + "\n" + tlsLink + "\n" + "trojan://redacted@example.com:443#Other");
if (partial.Servers.Count != 2 || partial.SkippedCount != 1 || !partial.SkipSummary.Contains("Другой протокол"))
    throw new Exception("Subscription import summary failed");
var decoy = "vless://bf000d23-0752-40b4-affe-68f7707a9661@0.0.0.0:1?security=none&type=tcp#" + Uri.EscapeDataString("Включите отправку HWID в настройках приложения");
try { Vless.ParseSubscription(Convert.ToBase64String(Encoding.UTF8.GetBytes(decoy))); throw new Exception("HWID decoy was accepted"); }
catch (DeviceIdRequiredException) { }
var seenIds = new List<string?>();
using var handler = new StubHandler(request =>
{
    var sent = request.Headers.TryGetValues("X-Hwid", out var values) ? values.Single() : null;
    seenIds.Add(sent);
    return new HttpResponseMessage(HttpStatusCode.OK)
    {
        Content = new StringContent(Convert.ToBase64String(Encoding.UTF8.GetBytes(sent == null ? decoy : link)))
    };
});
using var http = new HttpClient(handler);
var idFile = Path.Combine(Path.GetTempPath(), "pipidastr-test-" + Guid.NewGuid().ToString("N"));
try
{
    var client = new SubscriptionClient(http, new DeviceIdentity(idFile));
    var fetched = await client.FetchAsync(new Uri("https://example.com/subscription"));
    if (!fetched.UsedDeviceId || fetched.Servers.Count != 1 || seenIds.Count != 2 || seenIds[0] != null || seenIds[1]?.Length != 32)
        throw new Exception("Automatic device ID retry failed");
    var repeated = await client.FetchAsync(new Uri("https://example.com/subscription"), knownToNeedId: true);
    if (repeated.Servers.Count != 1 || seenIds[2] != seenIds[1]) throw new Exception("Device ID was not stable");
}
finally { if (File.Exists(idFile)) File.Delete(idFile); }
var profile = new ConnectionProfile { Name = "Test", Applications = [@"C:\Program Files\App\app.exe"] };
var selected = ConfigBuilder.Build(profile, 10880);
using (var json = JsonDocument.Parse(selected))
{
    if (json.RootElement.GetProperty("inbounds")[0].GetProperty("stack").GetString() != "gvisor")
        throw new Exception("TUN UDP stack is not gVisor");
    if (json.RootElement.GetProperty("inbounds")[0].GetProperty("address").GetArrayLength() != 1 ||
        json.RootElement.GetProperty("dns").GetProperty("strategy").GetString() != "ipv4_only")
        throw new Exception("IPv6 route is enabled without a working IPv6 uplink");
    if (json.RootElement.GetProperty("inbounds")[0].GetProperty("udp_timeout").GetString() != "15m")
        throw new Exception("TUN UDP lifetime is too short");
    var dns = json.RootElement.GetProperty("dns").GetProperty("servers")[0];
    if (dns.GetProperty("type").GetString() != "https" || dns.GetProperty("detour").GetString() != "proxy" ||
        dns.GetProperty("tls").GetProperty("server_name").GetString() != "cloudflare-dns.com")
        throw new Exception("DNS bypasses VPN");
    var route = json.RootElement.GetProperty("route");
    if (route.GetProperty("final").GetString() != "direct") throw new Exception("Selected-app final route failed");
    var rules = route.GetProperty("rules");
    if (!rules.EnumerateArray().Any(r => r.TryGetProperty("process_path", out _))) throw new Exception("App rule missing");
    if (!rules.EnumerateArray().Any(r => r.TryGetProperty("process_name", out var names) &&
        names.EnumerateArray().Any(n => n.GetString() == "app.exe")))
        throw new Exception("App filename fallback rule missing");
}
var legacyFile = Path.Combine(Path.GetTempPath(), "pipidastr-profile-test-" + Guid.NewGuid().ToString("N") + ".json");
try
{
    await File.WriteAllTextAsync(legacyFile, "[{\"Name\":\"Legacy\",\"Mode\":0,\"Applications\":[],\"DirectApplications\":[\"Discord.exe\",\"winws.exe\"]}]");
    var migrated = new ProfileStore(legacyFile).Load().Single();
    if (migrated.DirectApplications.Contains("Discord.exe") || !migrated.Applications.Contains("Discord.exe") || !migrated.DirectApplications.Contains("winws.exe"))
        throw new Exception("Discord routing migration failed");
    using var config = JsonDocument.Parse(ConfigBuilder.Build(migrated, 10880));
    var discordRule = config.RootElement.GetProperty("route").GetProperty("rules").EnumerateArray()
        .FirstOrDefault(r => r.TryGetProperty("process_name", out var names) && names.EnumerateArray().Any(n => n.GetString() == "Discord.exe"));
    if (discordRule.ValueKind == JsonValueKind.Undefined || discordRule.GetProperty("outbound").GetString() != "proxy")
        throw new Exception("Discord is not routed through VPN");
}
finally { File.Delete(legacyFile); }
profile.Mode = TrafficMode.FullTunnel;
var full = ConfigBuilder.Build(profile, 10880);
using (var json = JsonDocument.Parse(full))
    if (json.RootElement.GetProperty("route").GetProperty("final").GetString() != "proxy") throw new Exception("Full-tunnel final route failed");
var binary = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "VpnClient", "third_party", "sing-box-1.14.4-windows-amd64", "sing-box.exe"));
foreach (var (name, config) in new[] { ("selected", selected), ("full", full) })
{
    var temp = Path.GetTempFileName();
    try
    {
        await ConfigFile.WriteAsync(temp, config);
        if (File.ReadAllBytes(temp)[0] != (byte)'{') throw new Exception("Config has a UTF-8 BOM");
        var psi = new ProcessStartInfo(binary) { RedirectStandardError = true, RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true };
        psi.ArgumentList.Add("check"); psi.ArgumentList.Add("-c"); psi.ArgumentList.Add(temp);
        using var process = Process.Start(psi)!;
        var stderr = process.StandardError.ReadToEnd();
        process.WaitForExit();
        if (process.ExitCode != 0) throw new Exception($"sing-box rejected {name}: {stderr}");
    }
    finally { File.Delete(temp); }
}
var xrayBinary = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "VpnClient", "third_party", "xray-v26.11.9", "xray.exe"));
foreach (var (name, xrayConfig) in new[]
{
    ("Reality/TCP", XrayConfigBuilder.Build(node, 10880, 10881)),
    ("TLS/TCP", XrayConfigBuilder.Build(tlsNode, 10880, 10881)),
    ("Reality/gRPC", XrayConfigBuilder.Build(grpcNode, 10880, 10881)),
    ("Hysteria2", XrayConfigBuilder.Build(hysteriaNode, 10880, 10881))
})
{
    var xrayTemp = Path.Combine(Path.GetTempPath(), "pipidastr-xray-test-" + Guid.NewGuid().ToString("N") + ".json");
    try
    {
        await ConfigFile.WriteAsync(xrayTemp, xrayConfig);
        var psi = new ProcessStartInfo(xrayBinary) { RedirectStandardError = true, RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true };
        foreach (var arg in new[] { "run", "-test", "-config", xrayTemp }) psi.ArgumentList.Add(arg);
        using var process = Process.Start(psi)!;
        var stdout = process.StandardOutput.ReadToEnd();
        var stderr = process.StandardError.ReadToEnd();
        process.WaitForExit();
        if (process.ExitCode != 0) throw new Exception($"Xray rejected {name}: {stderr} {stdout}");
    }
    finally { File.Delete(xrayTemp); }
}
Console.WriteLine("VLESS and Hysteria2, subscription, automatic device ID, Discord migration, routing, sing-box and Xray config checks: OK");

sealed class StubHandler(Func<HttpRequestMessage, HttpResponseMessage> reply) : HttpMessageHandler
{
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        => Task.FromResult(reply(request));
}
