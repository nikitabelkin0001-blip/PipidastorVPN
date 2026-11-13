using System.Text.Json;

namespace VpnClient;

public sealed record HysteriaNode(string Name, string Host, int Port, string Password, string Sni, string? PinSha256, JsonElement? FinalMask);

public static class Hysteria
{
    public static HysteriaNode Parse(string link)
    {
        if (!Uri.TryCreate(link.Trim(), UriKind.Absolute, out var uri) || uri.Scheme != "hysteria2")
            throw new FormatException("Нужна ссылка hysteria2://.");
        if (string.IsNullOrWhiteSpace(uri.Host) || uri.Port is < 1 or > 65535 || string.IsNullOrWhiteSpace(uri.UserInfo))
            throw new FormatException("В ссылке Hysteria2 нет адреса, порта или пароля.");

        var query = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var part in uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var pair = part.Split('=', 2);
            query[Uri.UnescapeDataString(pair[0])] = pair.Length == 2 ? Uri.UnescapeDataString(pair[1].Replace('+', ' ')) : "";
        }
        string Get(string key) => query.GetValueOrDefault(key, "");
        var pin = Get("pinSHA256");
        if (pin.Length > 0 && (pin.Length != 64 || pin.Any(c => !Uri.IsHexDigit(c))))
            throw new FormatException("Некорректный pinSHA256 Hysteria2.");
        if (Get("insecure") is "1" or "true" && pin.Length == 0)
            throw new NotSupportedException("Hysteria2 без проверки сертификата требует pinSHA256.");
        if (Get("obfs") is not ("" or "salamander"))
            throw new NotSupportedException("Поддерживается маскировка Hysteria2 salamander.");

        JsonElement? finalMask = null;
        if (!string.IsNullOrWhiteSpace(Get("fm")))
        {
            try
            {
                using var mask = JsonDocument.Parse(Get("fm"));
                if (mask.RootElement.ValueKind != JsonValueKind.Object) throw new FormatException("Некорректный finalmask Hysteria2.");
                finalMask = mask.RootElement.Clone();
            }
            catch (JsonException) { throw new FormatException("Некорректный finalmask Hysteria2."); }
        }
        else if (Get("obfs") == "salamander")
        {
            if (string.IsNullOrEmpty(Get("obfs-password"))) throw new FormatException("Нет пароля маскировки Hysteria2.");
            finalMask = JsonSerializer.SerializeToElement(new { udp = new[] { new { type = "salamander", settings = new { password = Get("obfs-password") } } } });
        }

        var name = Uri.UnescapeDataString(uri.Fragment.TrimStart('#'));
        return new(string.IsNullOrEmpty(name) ? uri.Host : name, uri.Host, uri.Port,
            Uri.UnescapeDataString(uri.UserInfo), string.IsNullOrEmpty(Get("sni")) ? uri.Host : Get("sni"),
            pin.Length == 0 ? null : pin, finalMask);
    }
}
