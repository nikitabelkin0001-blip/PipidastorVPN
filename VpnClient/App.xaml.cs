using System.Security.Principal;
using System.Threading;
using System.Windows;

namespace VpnClient;

public partial class App : System.Windows.Application
{
    private Mutex? instance;
    private EventWaitHandle? toggleEvent;
    private EventWaitHandle? showEvent;
    private EventWaitHandle? quitEvent;
    private Thread? monitor;
    private MainWindow? window;
    private bool ownsInstance;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var name = "Local\\PipidastrVPN-" + WindowsIdentity.GetCurrent().User!.Value;
        instance = new Mutex(true, name, out var primary);
        ownsInstance = primary;
        var toggleName = name + "-toggle";
        var showName = name + "-show";
        // The island launches the executable without arguments. A plain launch toggles
        // the tunnel; --show opens the full window from a dedicated shortcut.
        var command = e.Args.Contains("--show", StringComparer.OrdinalIgnoreCase) ? "show" : "toggle";
        if (!primary)
        {
            try
            {
                using var signal = EventWaitHandle.OpenExisting(command == "toggle" ? toggleName : showName);
                signal.Set();
            }
            catch (WaitHandleCannotBeOpenedException)
            {
                MessageBox.Show("Клиент ещё запускается. Повторите нажатие через несколько секунд.",
                    "Pipidastr VPN", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            Shutdown();
            return;
        }

        ShutdownMode = ShutdownMode.OnExplicitShutdown;
        toggleEvent = new EventWaitHandle(false, EventResetMode.AutoReset, toggleName);
        showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, showName);
        quitEvent = new EventWaitHandle(false, EventResetMode.AutoReset);
        var signals = new WaitHandle[] { toggleEvent, showEvent, quitEvent };
        monitor = new Thread(() =>
        {
            while (true)
            {
                var action = WaitHandle.WaitAny(signals);
                if (action == 2) return;
                Dispatcher.InvokeAsync(() =>
                {
                    if (action == 0) window?.ToggleFromExternal();
                    else window?.ShowFromExternal();
                });
            }
        }) { IsBackground = true, Name = "Pipidastr command listener" };
        window = new MainWindow();
        MainWindow = window;
        monitor.Start();
        if (command == "toggle" && window.HasConfiguredServer)
        {
            window.ShowInTaskbar = false;
            window.WindowState = WindowState.Minimized;
            window.Show();
            window.Hide();
            window.WindowState = WindowState.Normal;
            Dispatcher.InvokeAsync(window.ToggleFromExternal);
        }
        else window.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        quitEvent?.Set();
        monitor?.Join(2000);
        toggleEvent?.Dispose();
        showEvent?.Dispose();
        quitEvent?.Dispose();
        if (instance != null)
        {
            if (ownsInstance) instance.ReleaseMutex();
            instance.Dispose();
        }
        base.OnExit(e);
    }
}
