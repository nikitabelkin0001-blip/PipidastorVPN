using System.Text;

namespace VpnClient;

public static class ConfigFile
{
    // sing-box expects JSON to begin with '{'; a UTF-8 BOM is rejected.
    public static Task WriteAsync(string path, string json) =>
        File.WriteAllTextAsync(path, json, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
}
