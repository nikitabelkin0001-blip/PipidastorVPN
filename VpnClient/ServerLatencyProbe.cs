using System.Diagnostics;
using System.Net.Sockets;

namespace VpnClient;

public static class ServerLatencyProbe
{
    public static async Task<long?> MeasureAsync(string host, int port, CancellationToken cancellationToken = default)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(3));
        using var client = new TcpClient();
        var watch = Stopwatch.StartNew();
        try
        {
            await client.ConnectAsync(host, port, timeout.Token);
            return Math.Max(1, watch.ElapsedMilliseconds);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { return null; }
        catch (SocketException) { return null; }
    }
}
