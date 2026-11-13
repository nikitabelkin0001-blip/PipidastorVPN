using System.Net;
using System.Net.Http;

namespace VpnClient;

public sealed class DeviceIdentity
{
    private readonly string file;
    public DeviceIdentity(string? file = null)
    {
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        this.file = file ?? Path.Combine(local, "PipidastrVPN", "device-id.txt");
        if (file == null && !File.Exists(this.file))
        {
            var old = Path.Combine(local, "OrbitVPN", "device-id.txt");
            if (File.Exists(old))
            {
                Directory.CreateDirectory(Path.GetDirectoryName(this.file)!);
                File.Copy(old, this.file);
            }
        }
    }

    public string GetOrCreate()
    {
        if (File.Exists(file))
        {
            var saved = File.ReadAllText(file).Trim();
            if (saved.Length is >= 10 and <= 64 && saved.All(c => char.IsAsciiLetterOrDigit(c) || c is '=' or '-'))
                return saved;
            throw new InvalidDataException("Сохранённый идентификатор устройства повреждён.");
        }
        var id = Guid.NewGuid().ToString("N");
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        using var stream = new FileStream(file, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        using var writer = new StreamWriter(stream);
        writer.Write(id);
        return id;
    }
}

public sealed record SubscriptionResult(List<ServerEntry> Servers, bool UsedDeviceId, int SkippedCount, string SkipSummary);

public sealed class SubscriptionClient(HttpClient http, DeviceIdentity deviceIdentity)
{
    private static SubscriptionResult Result(SubscriptionParseResult parsed, bool usedDeviceId)
        => new(parsed.Servers, usedDeviceId, parsed.SkippedCount, parsed.SkipSummary);

    public async Task<SubscriptionResult> FetchAsync(Uri uri, string? existingId = null, bool knownToNeedId = false)
    {
        if (uri.Scheme != Uri.UriSchemeHttps) throw new FormatException("Нужна HTTPS-ссылка на подписку.");
        if (existingId is { Length: > 0 })
            return Result(await FetchOnceAsync(uri, existingId), true);
        if (knownToNeedId)
            return Result(await FetchWithGeneratedIdAsync(uri), true);
        try { return Result(await FetchOnceAsync(uri, null), false); }
        catch (DeviceIdRequiredException) { return Result(await FetchWithGeneratedIdAsync(uri), true); }
    }

    private async Task<SubscriptionParseResult> FetchWithGeneratedIdAsync(Uri uri)
    {
        var id = deviceIdentity.GetOrCreate();
        try { return await FetchOnceAsync(uri, id); }
        catch (DeviceIdRequiredException)
        {
            throw new FormatException("Сервис не принял это устройство. Возможно, достигнут лимит устройств — проверьте кабинет подписки или обратитесь к провайдеру.");
        }
    }

    private async Task<SubscriptionParseResult> FetchOnceAsync(Uri uri, string? deviceId)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, uri);
        request.Headers.UserAgent.ParseAdd("OrbitVPN/0.3");
        if (deviceId != null)
        {
            if (deviceId.Length is < 10 or > 64 || deviceId.Any(c => !char.IsAsciiLetterOrDigit(c) && c is not ('=' or '-')))
                throw new FormatException("Сохранённый идентификатор устройства имеет неверный формат.");
            request.Headers.Add("X-Hwid", deviceId);
        }
        using var response = await http.SendAsync(request);
        var content = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode)
        {
            if ((response.StatusCode is HttpStatusCode.BadRequest or HttpStatusCode.NotFound) &&
                content.Contains("HWID", StringComparison.OrdinalIgnoreCase))
                throw new DeviceIdRequiredException();
            if (response.Headers.TryGetValues("x-hwid-max-devices-reached", out var reached) &&
                reached.Any(v => v.Equals("true", StringComparison.OrdinalIgnoreCase)))
                throw new FormatException("Достигнут лимит устройств подписки. Проверьте кабинет провайдера.");
            throw new HttpRequestException($"Сервер подписки ответил кодом {(int)response.StatusCode}.");
        }
        return Vless.ParseSubscriptionDetailed(content);
    }
}
