using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace VpnClient;

internal static class WindowAppearance
{
    private const int UseImmersiveDarkMode = 20;
    private const int CaptionColor = 35;
    private const int TextColor = 36;

    public static void Apply(Window window)
    {
        var handle = new WindowInteropHelper(window).Handle;
        if (handle == IntPtr.Zero) return;

        var enabled = 1;
        if (DwmSetWindowAttribute(handle, UseImmersiveDarkMode, ref enabled, sizeof(int)) != 0)
            DwmSetWindowAttribute(handle, 19, ref enabled, sizeof(int));

        // COLORREF stores colors as 0x00BBGGRR.
        var caption = 0x0020100B; // #0B1020
        var text = 0x00FFF6F4; // #F4F6FF
        DwmSetWindowAttribute(handle, CaptionColor, ref caption, sizeof(int));
        DwmSetWindowAttribute(handle, TextColor, ref text, sizeof(int));
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int valueSize);
}
