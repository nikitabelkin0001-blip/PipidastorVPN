using System.Text.Json;

namespace VpnClient;

public enum TrafficMode { SelectedApps, FullTunnel }

public sealed class ServerEntry
{
    public string Name { get; set; } = "Сервер";
    public string Link { get; set; } = "";
    public string Details => Uri.TryCreate(Link, UriKind.Absolute, out var uri)
        ? $"{uri.Host}:{uri.Port}  ·  {(uri.Scheme == "hysteria2" ? "Hysteria2" : "VLESS")}"
        : "VLESS";
    public override string ToString() => Name;
}

public sealed class ConnectionProfile
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = "Новый профиль";
    public TrafficMode Mode { get; set; } = TrafficMode.SelectedApps;
    public List<string> Applications { get; set; } = [];
    public List<string> DirectApplications { get; set; } = ["winws.exe", "Discord.exe"];
    public int? RoutingVersion { get; set; }
    public List<ServerEntry> Servers { get; set; } = [];
    public string? SubscriptionUrl { get; set; }
    public string? SubscriptionHwid { get; set; }
    public bool SubscriptionNeedsDeviceId { get; set; }
    public int SelectedServer { get; set; }
    public override string ToString() => Name;
}

public sealed class ProfileStore
{
    private readonly string file;
    private readonly string? legacyFile;
    public ProfileStore(string? file = null)
    {
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        this.file = file ?? Path.Combine(local, "PipidastrVPN", "profiles.json");
        legacyFile = file == null ? Path.Combine(local, "OrbitVPN", "profiles.json") : null;
    }
    public List<ConnectionProfile> Load()
    {
        if (!File.Exists(file) && legacyFile is { } old && File.Exists(old))
        {
            Directory.CreateDirectory(Path.GetDirectoryName(file)!);
            File.Copy(old, file);
        }
        if (!File.Exists(file)) return [new() { Name = "Приложения" }, new() { Name = "Весь трафик", Mode = TrafficMode.FullTunnel }];
        try
        {
            var profiles = JsonSerializer.Deserialize<List<ConnectionProfile>>(File.ReadAllText(file)) ?? [];
            foreach (var profile in profiles)
            {
                if (profile.RoutingVersion >= 2) continue;
                profile.Applications.RemoveAll(path => Path.GetFileName(path).Equals("Discord.exe", StringComparison.OrdinalIgnoreCase));
                if (!profile.DirectApplications.Any(path => Path.GetFileName(path).Equals("Discord.exe", StringComparison.OrdinalIgnoreCase)))
                    profile.DirectApplications.Add("Discord.exe");
                profile.RoutingVersion = 2;
            }
            return profiles;
        }
        catch (JsonException) { throw new InvalidDataException($"Файл профилей повреждён: {file}"); }
    }
    public void Save(List<ConnectionProfile> profiles)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        var temp = file + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(profiles, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(temp, file, true);
    }
}
