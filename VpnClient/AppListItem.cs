using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace VpnClient;

public sealed record AppListItem(string Name, string Path)
{
    public ImageSource? Icon => AppIcons.Get(Path);

    public static AppListItem FromPath(string path)
    {
        var name = System.IO.Path.GetFileNameWithoutExtension(path);
        return new(string.IsNullOrWhiteSpace(name) ? path : name, path);
    }
}

internal static class AppIcons
{
    private const uint ShgfiIcon = 0x100;
    private const uint ShgfiSmallIcon = 0x1;
    private static readonly Dictionary<string, ImageSource?> Cache = new(StringComparer.OrdinalIgnoreCase);

    public static ImageSource? Get(string path)
    {
        if (!System.IO.Path.IsPathFullyQualified(path)) return null;
        lock (Cache)
        {
            if (Cache.TryGetValue(path, out var cached)) return cached;
            return Cache[path] = Extract(path);
        }
    }

    private static ImageSource? Extract(string path)
    {
        if (!File.Exists(path) || ExtractIconEx(path, -1, IntPtr.Zero, IntPtr.Zero, 0) == 0) return null;
        var info = new ShFileInfo();
        if (ShGetFileInfo(path, 0, ref info, (uint)Marshal.SizeOf<ShFileInfo>(), ShgfiIcon | ShgfiSmallIcon) == IntPtr.Zero || info.Icon == IntPtr.Zero)
            return null;
        try
        {
            var bitmap = Imaging.CreateBitmapSourceFromHIcon(info.Icon, Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions());
            bitmap.Freeze();
            return bitmap;
        }
        finally { DestroyIcon(info.Icon); }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ShFileInfo
    {
        public IntPtr Icon;
        public int IconIndex;
        public uint Attributes;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string DisplayName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 80)] public string TypeName;
    }

    [DllImport("shell32.dll", EntryPoint = "SHGetFileInfoW", CharSet = CharSet.Unicode)]
    private static extern IntPtr ShGetFileInfo(string path, uint attributes, ref ShFileInfo info, uint size, uint flags);

    [DllImport("shell32.dll", EntryPoint = "ExtractIconExW", CharSet = CharSet.Unicode)]
    private static extern uint ExtractIconEx(string path, int iconIndex, IntPtr largeIcons, IntPtr smallIcons, uint icons);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr icon);
}
