using System.Text;
using System.Text.Json;

namespace VpnClient;

public sealed record VlessNode(string Name, string Host, int Port, Guid Id, string Sni, string PublicKey, string ShortId, string Flow, string Fingerprint);
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
        if (!Get("security").Equals("reality", StringComparison.OrdinalIgnoreCase))
            throw new NotSupportedException("Первая версия поддерживает VLESS с Reality.");
        if (!string.IsNullOrEmpty(Get("type")) && Get("type") != "tcp")
            throw new NotSupportedException("Пока поддерживается только транспорт TCP.");
        if (string.IsNullOrWhiteSpace(Get("pbk"))) throw new FormatException("В ссылке нет ключа Reality (pbk).");
        var sni = Get("sni");
        if (string.IsNullOrWhiteSpace(sni)) throw new FormatException("В ссылке нет SNI.");
        var flow = Get("flow");
        if (flow is not ("" or "xtls-rprx-vision")) throw new NotSupportedException("Неподдерживаемый flow.");
        var sid = Get("sid");
        if (sid.Length > 16 || sid.Length % 2 != 0 || sid.Any(c => !Uri.IsHexDigit(c))) throw new FormatException("Некорректный short id Reality.");
        var fp = Get("fp");
        return new(Uri.UnescapeDataString(uri.Fragment.TrimStart('#')) is { Length: > 0 } n ? n : uri.Host,
            uri.Host, uri.Port, id, sni, Get("pbk"), sid, flow, string.IsNullOrEmpty(fp) ? "chrome" : fp);
    }

    public static List<ServerEntry> ParseSubscription(string body)
    {
        string decoded = body.Trim();
        if (!decoded.Contains("vless://", StringComparison.OrdinalIgnoreCase))
        {
            try
            {
                var base64 = decoded.Replace('-', '+').Replace('_', '/');
                decoded = Encoding.UTF8.GetString(Convert.FromBase64String(base64.PadRight((base64.Length + 3) / 4 * 4, '=')));
            }
            catch (FormatException) { throw new FormatException("Подписка не содержит ссылок VLESS."); }
        }
        var result = new List<ServerEntry>();
        foreach (var line in decoded.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries))
        {
            if (!line.TrimStart().StartsWith("vless://", StringComparison.OrdinalIgnoreCase)) continue;
            if (Uri.TryCreate(line.Trim(), UriKind.Absolute, out var candidate) && candidate.Port == 1 &&
                Uri.UnescapeDataString(candidate.Fragment).Contains("HWID", StringComparison.OrdinalIgnoreCase))
                throw new DeviceIdRequiredException();
            try { var node = Parse(line.Trim()); result.Add(new ServerEntry { Name = node.Name, Link = line.Trim() }); }
            catch (Exception ex) when (ex is FormatException or NotSupportedException) { /* Skip incompatible nodes. */ }
        }
        if (result.Count == 0) throw new FormatException("В подписке нет поддерживаемых VLESS/Reality TCP серверов.");
        return result;
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
