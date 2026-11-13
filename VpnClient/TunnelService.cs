using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Security.Principal;
using System.Text.RegularExpressions;

namespace VpnClient;

public sealed class TunnelService : IDisposable
{
    private readonly object gate = new();
    private readonly Queue<string> recentLog = new();
    private Process? xray;
    private Process? singBox;
    private string? xrayConfig;
    private string? singConfig;
    private bool stopping;

    public bool IsRunning => xray is { HasExited: false } && singBox is { HasExited: false };
    public string? EgressIp { get; private set; }
    public event Action<string>? Log;
    public event Action? Stopped;

    public static bool IsAdministrator()
    {
        using var identity = WindowsIdentity.GetCurrent();
        return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
    }

    public async Task<string> StartAsync(ConnectionProfile profile, ServerEntry server)
    {
        if (IsRunning) throw new InvalidOperationException("VPN уже подключён.");
        if (!IsAdministrator()) throw new InvalidOperationException("Для TUN запустите Pipidastr VPN от имени администратора.");
        var xrayBinary = Path.Combine(AppContext.BaseDirectory, "xray.exe");
        var singBinary = Path.Combine(AppContext.BaseDirectory, "sing-box.exe");
        if (!File.Exists(xrayBinary) || !File.Exists(singBinary))
            throw new FileNotFoundException("В папке клиента нужны xray.exe и sing-box.exe. Переустановите Pipidastr VPN.");

        var hysteria = server.Link.StartsWith("hysteria2://", StringComparison.OrdinalIgnoreCase);
        var socksPort = FreePort();
        var httpPort = FreePort();
        while (httpPort == socksPort) httpPort = FreePort();
        var transportConfig = hysteria
            ? XrayConfigBuilder.Build(Hysteria.Parse(server.Link), socksPort, httpPort)
            : XrayConfigBuilder.Build(Vless.Parse(server.Link), socksPort, httpPort);
        xrayConfig = Path.Combine(Path.GetTempPath(), "pipidastr-xray-" + Guid.NewGuid().ToString("N") + ".json");
        singConfig = Path.Combine(Path.GetTempPath(), "pipidastr-tun-" + Guid.NewGuid().ToString("N") + ".json");
        try
        {
            await ConfigFile.WriteAsync(xrayConfig, transportConfig);
            await ConfigFile.WriteAsync(singConfig, ConfigBuilder.Build(profile, socksPort));
            await CheckAsync(xrayBinary, "run", "-test", "-config", xrayConfig);
            await CheckAsync(singBinary, "check", "-c", singConfig);

            xray = StartEngine(xrayBinary, "Xray", "run", "-config", xrayConfig);
            await WaitForPortAsync(httpPort);
            EgressIp = await ProbeProxyAsync(httpPort);
            Log?.Invoke("VPN-сервер ответил. Включаю системный туннель…");

            singBox = StartEngine(singBinary, "TUN", "run", "-c", singConfig);
            await Task.Delay(1200);
            if (!IsRunning) throw new IOException("Не удалось запустить туннель. " + LastError());
            return EgressIp;
        }
        catch
        {
            Stop();
            throw;
        }
    }

    private Process StartEngine(string binary, string label, params string[] args)
    {
        var info = new ProcessStartInfo(binary)
        {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardError = true,
            RedirectStandardOutput = true, WorkingDirectory = AppContext.BaseDirectory
        };
        foreach (var arg in args) info.ArgumentList.Add(arg);
        var process = Process.Start(info) ?? throw new IOException($"Не удалось запустить {label}.");
        process.OutputDataReceived += (_, e) => { if (e.Data != null) Record(label, e.Data); };
        process.ErrorDataReceived += (_, e) => { if (e.Data != null) Record(label, e.Data); };
        process.EnableRaisingEvents = true;
        process.Exited += (_, _) => { if (!stopping) _ = Task.Run(Stop); };
        process.BeginOutputReadLine(); process.BeginErrorReadLine();
        return process;
    }

    private static async Task CheckAsync(string binary, params string[] args)
    {
        var info = new ProcessStartInfo(binary) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardError = true, RedirectStandardOutput = true };
        foreach (var arg in args) info.ArgumentList.Add(arg);
        using var process = Process.Start(info) ?? throw new IOException("Не удалось проверить конфигурацию.");
        var stdout = process.StandardOutput.ReadToEndAsync();
        var stderr = process.StandardError.ReadToEndAsync();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        try { await process.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException) { process.Kill(entireProcessTree: true); throw new IOException("Проверка конфигурации заняла слишком много времени."); }
        if (process.ExitCode != 0) throw new InvalidDataException("Конфигурация ядра: " + (await stderr) + (await stdout));
    }

    private static int FreePort()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        try { return ((IPEndPoint)listener.LocalEndpoint).Port; }
        finally { listener.Stop(); }
    }

    private static async Task WaitForPortAsync(int port)
    {
        for (var attempt = 0; attempt < 50; attempt++)
        {
            try
            {
                using var client = new TcpClient();
                using var timeout = new CancellationTokenSource(TimeSpan.FromMilliseconds(250));
                await client.ConnectAsync(IPAddress.Loopback, port, timeout.Token);
                return;
            }
            catch (Exception ex) when (ex is SocketException or OperationCanceledException) { await Task.Delay(100); }
        }
        throw new IOException("Xray не открыл локальный порт. Проверьте журнал.");
    }

    private async Task<string> ProbeProxyAsync(int port)
    {
        using var handler = new HttpClientHandler { Proxy = new WebProxy($"http://127.0.0.1:{port}"), UseProxy = true };
        using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(10) };
        foreach (var url in new[] { "https://www.cloudflare.com/cdn-cgi/trace", "https://api.ipify.org" })
        {
            try
            {
                var body = await client.GetStringAsync(url);
                var ip = url.Contains("cloudflare", StringComparison.Ordinal)
                    ? body.Split('\n').FirstOrDefault(x => x.StartsWith("ip=", StringComparison.Ordinal))?[3..].Trim()
                    : body.Trim();
                if (IPAddress.TryParse(ip, out _)) return ip;
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException) { /* Try the second endpoint. */ }
        }
        throw new IOException("VPN-сервер не передаёт трафик. " + LastError());
    }

    private void Record(string label, string line)
    {
        var clean = Regex.Replace(line, "\u001b\\[[0-9;]*m", "");
        lock (gate)
        {
            recentLog.Enqueue($"{label}: {clean}");
            while (recentLog.Count > 100) recentLog.Dequeue();
        }
        Log?.Invoke($"{label}: {clean}");
    }

    private string LastError()
    {
        lock (gate)
            return recentLog.LastOrDefault(x => x.Contains("error", StringComparison.OrdinalIgnoreCase) || x.Contains("failed", StringComparison.OrdinalIgnoreCase))
                ?? "Сервер не ответил на проверочный запрос.";
    }

    public void Stop()
    {
        Process? tun;
        Process? transport;
        string? tunPath;
        string? xrayPath;
        lock (gate)
        {
            if (stopping) return;
            if (singBox == null && xray == null && singConfig == null && xrayConfig == null) return;
            stopping = true;
            tun = singBox; transport = xray; singBox = null; xray = null;
            tunPath = singConfig; xrayPath = xrayConfig; singConfig = null; xrayConfig = null;
            EgressIp = null;
        }
        Kill(tun); Kill(transport);
        foreach (var path in new[] { tunPath, xrayPath })
        {
            if (path == null) continue;
            try { File.Delete(path); } catch (IOException) { }
        }
        lock (gate) stopping = false;
        Stopped?.Invoke();
    }

    private static void Kill(Process? process)
    {
        if (process == null) return;
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
                process.WaitForExit(3000);
            }
        }
        catch (InvalidOperationException) { }
        finally { process.Dispose(); }
    }

    public void Dispose() => Stop();
}
