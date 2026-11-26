using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Diagnostics;
using System.Text.Json.Nodes;

namespace VpnClient;

public sealed record DiscoveryProgress(int Completed, int Total, string ServerName);

public sealed class BootstrapDiscovery
{
    public static string BuildProbeConfig(VlessNode target, VlessNode? bootstrap, int socksPort, int httpPort,
        string sourceAddress, string interfaceName)
    {
        var config = JsonNode.Parse(XrayConfigBuilder.Build(target, socksPort, httpPort, bootstrap))!;
        // Bind only the outermost connection. The inner connection uses dialerProxy.
        var outer = config["outbounds"]!.AsArray().Last()!;
        outer["sendThrough"] = sourceAddress;
        var stream = outer["streamSettings"]!;
        stream["sockopt"] = new JsonObject { ["interface"] = interfaceName };
        return config.ToJsonString();
    }

    public async Task<ServerEntry?> FindAsync(ServerEntry target, IEnumerable<ServerEntry> servers,
        string? previousName, IProgress<DiscoveryProgress>? progress, CancellationToken cancellationToken)
    {
        if (!target.Link.StartsWith("vless://", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Резервный маршрут пока поддерживается для серверов VLESS.");
        var targetNode = Vless.Parse(target.Link);
        var binary = Path.Combine(AppContext.BaseDirectory, "xray.exe");
        if (!File.Exists(binary)) throw new FileNotFoundException("В папке клиента отсутствует xray.exe.");
        var network = NetworkInterface.GetAllNetworkInterfaces().FirstOrDefault(adapter =>
            adapter.OperationalStatus == OperationalStatus.Up &&
            adapter.NetworkInterfaceType is NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.Ethernet &&
            !System.Text.RegularExpressions.Regex.IsMatch(adapter.Name + " " + adapter.Description,
                "vpn|tunnel|wintun|virtual|happ|tap", System.Text.RegularExpressions.RegexOptions.IgnoreCase) &&
            adapter.GetIPProperties().GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any)));
        var source = network?.GetIPProperties().UnicastAddresses.FirstOrDefault(a =>
            a.Address.AddressFamily == AddressFamily.InterNetwork)?.Address.ToString();
        if (network == null || source == null)
            throw new InvalidOperationException("Не найдено прямое подключение Wi-Fi или Ethernet для проверки резерва.");
        var candidates = new List<(ServerEntry Entry, VlessNode Node)>();
        foreach (var entry in servers.DistinctBy(s => s.Name).OrderByDescending(s => s.Name == previousName))
        {
            if (entry.Link == target.Link || !entry.Link.StartsWith("vless://", StringComparison.OrdinalIgnoreCase)) continue;
            try { candidates.Add((entry, Vless.Parse(entry.Link))); }
            catch (Exception ex) when (ex is FormatException or NotSupportedException) { }
        }
        if (candidates.Count == 0) throw new InvalidOperationException("В подписке нет подходящих серверов для резерва.");
        using var found = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        ServerEntry? winner = null;
        var completed = 0;
        try
        {
            await Parallel.ForEachAsync(candidates, new ParallelOptions { MaxDegreeOfParallelism = 4, CancellationToken = found.Token }, async (candidate, token) =>
            {
                try
                {
                    if (!await ProbeAsync(binary, candidate.Node, null, source, network.Name, token)) return;
                    if (!await ProbeAsync(binary, targetNode, candidate.Node, source, network.Name, token)) return;
                    if (Interlocked.CompareExchange(ref winner, candidate.Entry, null) == null) found.Cancel();
                }
                finally { progress?.Report(new(Interlocked.Increment(ref completed), candidates.Count, candidate.Entry.Name)); }
            });
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested && winner != null) { }
        cancellationToken.ThrowIfCancellationRequested();
        return winner;
    }

    private static async Task<bool> ProbeAsync(string binary, VlessNode target, VlessNode? bootstrap,
        string source, string network, CancellationToken token)
    {
        using var reservation = new TcpListener(IPAddress.Loopback, 0);
        reservation.Start();
        var socksPort = ((IPEndPoint)reservation.LocalEndpoint).Port;
        var httpPort = FreePort();
        reservation.Stop();
        var file = Path.Combine(Path.GetTempPath(), "pipidastr-reserve-" + Guid.NewGuid().ToString("N") + ".json");
        Process? process = null;
        using var engineJob = new EngineJob();
        try
        {
            await ConfigFile.WriteAsync(file, BuildProbeConfig(target, bootstrap, socksPort, httpPort, source, network));
            token.ThrowIfCancellationRequested();
            var start = new ProcessStartInfo(binary) { UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = AppContext.BaseDirectory };
            foreach (var arg in new[] { "run", "-config", file }) start.ArgumentList.Add(arg);
            process = Process.Start(start) ?? throw new IOException("Не удалось запустить проверку Xray.");
            engineJob.Track(process);
            process.OutputDataReceived += (_, _) => { };
            process.ErrorDataReceived += (_, _) => { };
            process.BeginOutputReadLine(); process.BeginErrorReadLine();
            for (var i = 0; i < 30; i++)
            {
                token.ThrowIfCancellationRequested();
                if (process.HasExited) return false;
                using var client = new TcpClient();
                try { await client.ConnectAsync(IPAddress.Loopback, httpPort, token); break; }
                catch (SocketException) { await Task.Delay(100, token); }
            }
            using var handler = new HttpClientHandler { Proxy = new WebProxy($"http://127.0.0.1:{httpPort}"), UseProxy = true };
            using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(7) };
            foreach (var url in new[] { "https://www.cloudflare.com/cdn-cgi/trace", "https://api.ipify.org" })
            {
                try
                {
                    var body = await http.GetStringAsync(url, token);
                    var ip = url.Contains("cloudflare", StringComparison.Ordinal)
                        ? body.Split('\n').FirstOrDefault(line => line.StartsWith("ip=", StringComparison.Ordinal))?[3..].Trim()
                        : body.Trim();
                    if (IPAddress.TryParse(ip, out _)) return true;
                }
                catch (Exception ex) when (ex is HttpRequestException || ex is OperationCanceledException && !token.IsCancellationRequested) { }
            }
            return false;
        }
        finally
        {
            if (process != null)
            {
                try { if (!process.HasExited) process.Kill(entireProcessTree: true); await process.WaitForExitAsync(); }
                finally { process.Dispose(); }
            }
            File.Delete(file);
        }
    }

    private static int FreePort()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        return ((IPEndPoint)listener.LocalEndpoint).Port;
    }
}
