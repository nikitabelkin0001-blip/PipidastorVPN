using System.Text;
using System.Text.Json;

namespace VpnClient;

public sealed record VlessNode(string Name, string Host, int Port, Guid Id, string Sni, string PublicKey, string ShortId, string Flow, string Fingerprint, string Security, string[] Alpn, string Transport, string ServiceName, string Encryption, JsonElement? FinalMask, string? PinnedCertSha256);
public sealed record SubscriptionParseResult(List<ServerEntry> Servers, int SkippedCount, string SkipSummary);
public sealed class DeviceIdRequiredException : FormatException
{
    public DeviceIdRequiredException() : base("Сервис подписки требует идентификатор устройства.") { }
}

public static class Vless
{
    public static VlessNode Parse(string link)
    {
        if (!Uri.TryCreate(link.Trim(), UriKind.Absolute, out var uri) || uri.Scheme != "vless")
            throw new FormatException("Нужна ссылка vless://.");
        if (!Guid.TryParse(uri.UserInfo, out var id) || string.IsNullOrWhiteSpace(uri.Host) || uri.Port is < 1 or > 65535)
            throw new FormatException("В ссылке нет корректного UUID, адреса или порта.");
        var query = Query(uri.Query);
        string Get(string key) => query.GetValueOrDefault(key, "");
        var security = Get("security").ToLowerInvariant();
        var encryption = Get("encryption");
        if (security is not ("reality" or "tls" or "none") ||
            (security == "none" && (string.IsNullOrEmpty(encryption) || encryption == "none")))
            throw new NotSupportedException("VLESS без TLS требует шифрование VLESS.");
        var transport = Get("type").ToLowerInvariant();
        if (transport is "" or "raw") transport = "tcp";
        if (transport is not ("tcp" or "grpc"))
            throw new NotSupportedException("Поддерживаются транспорты TCP и gRPC.");
        if (security == "reality" && string.IsNullOrWhiteSpace(Get("pbk"))) throw new FormatException("В ссылке нет ключа Reality (pbk).");
        var sni = Get("sni");
        if (string.IsNullOrWhiteSpace(sni))
        {
            if (security == "reality") throw new FormatException("В ссылке нет SNI.");
            sni = uri.Host;
        }
        var flow = Get("flow");
        if (flow is not ("" or "xtls-rprx-vision")) throw new NotSupportedException("Неподдерживаемый flow.");
        var sid = Get("sid");
        if (sid.Length > 16 || sid.Length % 2 != 0 || sid.Any(c => !Uri.IsHexDigit(c))) throw new FormatException("Некорректный short id Reality.");
        var fp = Get("fp");
        var alpn = Get("alpn").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        JsonElement? finalMask = null;
        if (!string.IsNullOrWhiteSpace(Get("fm")))
        {
            try
            {
                using var mask = JsonDocument.Parse(Get("fm"));
                if (mask.RootElement.ValueKind != JsonValueKind.Object) throw new FormatException("Некорректный finalmask.");
                finalMask = mask.RootElement.Clone();
            }
            catch (JsonException) { throw new FormatException("Некорректный finalmask."); }
        }
        return new(Uri.UnescapeDataString(uri.Fragment.TrimStart('#')) is { Length: > 0 } n ? n : uri.Host,
            uri.Host, uri.Port, id, sni, Get("pbk"), sid, flow, string.IsNullOrEmpty(fp) ? "chrome" : fp, security, alpn, transport, Get("serviceName"), string.IsNullOrEmpty(encryption) ? "none" : encryption, finalMask,
            string.IsNullOrEmpty(Get("pcs")) ? null : Get("pcs"));
    }

    public static List<ServerEntry> ParseSubscription(string body)
        => ParseSubscriptionDetailed(body).Servers;

    public static SubscriptionParseResult ParseSubscriptionDetailed(string body)
    {
        string decoded = body.Trim();
        if (!decoded.Contains("vless://", StringComparison.OrdinalIgnoreCase) &&
            !decoded.Contains("hysteria2://", StringComparison.OrdinalIgnoreCase))
        {
            try
            {
                var base64 = decoded.Replace('-', '+').Replace('_', '/');
                decoded = Encoding.UTF8.GetString(Convert.FromBase64String(base64.PadRight((base64.Length + 3) / 4 * 4, '=')));
            }
            catch (FormatException) { throw new FormatException("Подписка не содержит ссылок VLESS."); }
        }
        var result = new List<ServerEntry>();
        var skipped = new Dictionary<string, int>(StringComparer.Ordinal);
        void Skip(string reason) => skipped[reason] = skipped.GetValueOrDefault(reason) + 1;
        foreach (var line in decoded.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries))
        {
            var trimmed = line.Trim();
            if (trimmed.StartsWith("hysteria2://", StringComparison.OrdinalIgnoreCase))
            {
                try { var node = Hysteria.Parse(trimmed); result.Add(new ServerEntry { Name = node.Name, Link = trimmed }); }
                catch (Exception ex) when (ex is FormatException or NotSupportedException) { Skip(ex.Message); }
                continue;
            }
            if (!trimmed.StartsWith("vless://", StringComparison.OrdinalIgnoreCase))
            {
                if (trimmed.Contains("://", StringComparison.Ordinal)) Skip("Другой протокол");
                continue;
            }
            if (Uri.TryCreate(line.Trim(), UriKind.Absolute, out var candidate) && candidate.Port == 1 &&
                Uri.UnescapeDataString(candidate.Fragment).Contains("HWID", StringComparison.OrdinalIgnoreCase))
                throw new DeviceIdRequiredException();
            try { var node = Parse(trimmed); result.Add(new ServerEntry { Name = node.Name, Link = trimmed }); }
            catch (Exception ex) when (ex is FormatException or NotSupportedException) { Skip(ex.Message); }
        }
        if (result.Count == 0) throw new FormatException("В подписке нет поддерживаемых серверов VLESS или Hysteria2.");
        return new(result, skipped.Values.Sum(), string.Join("; ", skipped.Select(pair => $"{pair.Key}: {pair.Value}")));
    }

    private static Dictionary<string, string> Query(string raw)
    {
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var part in raw.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var pair = part.Split('=', 2);
            result[Uri.UnescapeDataString(pair[0])] = pair.Length == 2 ? Uri.UnescapeDataString(pair[1].Replace('+', ' ')) : "";
        }
        return result;
    }
}
