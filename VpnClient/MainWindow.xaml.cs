using System.Net.Http;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;

namespace VpnClient;

public partial class MainWindow : Window
{
    private readonly ProfileStore store = new();
    private readonly TunnelService tunnel = new();
    private readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(20) };
    private readonly SubscriptionClient subscriptions;
    private List<ConnectionProfile> profiles = [];
    private ConnectionProfile? Current => ProfilesBox.SelectedItem as ConnectionProfile;
    public bool HasConfiguredServer => Current?.Servers.Count > 0;
    private CancellationTokenSource? pingCancellation;
    private bool refreshingSubscription;
    private CancellationTokenSource? reserveCancellation;
    private bool loading;
    private bool connecting;
    private bool exitRequested;
    private readonly System.Windows.Forms.NotifyIcon tray;
    private readonly System.Windows.Forms.ToolStripMenuItem trayToggle;

    public MainWindow()
    {
        InitializeComponent();
        SourceInitialized += (_, _) => WindowAppearance.Apply(this);
        subscriptions = new SubscriptionClient(http, new DeviceIdentity());
        try { profiles = store.Load(); }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Профили", MessageBoxButton.OK, MessageBoxImage.Warning); profiles = [new() { Name = "Приложения" }]; }
        RefreshProfiles(profiles.FirstOrDefault(profile => profile.LastSelected) ?? profiles.FirstOrDefault());
        tunnel.Log += line => Dispatcher.InvokeAsync(() => FooterText.Text = line.Length > 260 ? line[..260] : line);
        tunnel.Stopped += () => Dispatcher.InvokeAsync(() => SetConnectionState(false));
        trayToggle = new System.Windows.Forms.ToolStripMenuItem("Подключить", null,
            (_, _) => Dispatcher.InvokeAsync(ToggleFromExternal));
        var trayOpen = new System.Windows.Forms.ToolStripMenuItem("Открыть Pipidastr", null,
            (_, _) => Dispatcher.InvokeAsync(ShowFromExternal));
        var trayExit = new System.Windows.Forms.ToolStripMenuItem("Выход", null,
            (_, _) => Dispatcher.InvokeAsync(ExitApplication));
        tray = new System.Windows.Forms.NotifyIcon
        {
            Icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? System.Drawing.SystemIcons.Application,
            Text = "Pipidastr VPN",
            ContextMenuStrip = new System.Windows.Forms.ContextMenuStrip()
        };
        tray.ContextMenuStrip.Items.AddRange([trayToggle, trayOpen, trayExit]);
        tray.DoubleClick += (_, _) => Dispatcher.InvokeAsync(ShowFromExternal);
        tray.Visible = true;
        Closing += OnClosing;
        Closed += (_, _) => { pingCancellation?.Cancel(); reserveCancellation?.Cancel(); tray.Visible = false; tray.Dispose(); tunnel.Dispose(); http.Dispose(); };
    }

    private void RefreshProfiles(ConnectionProfile? select)
    {
        loading = true;
        ProfilesBox.ItemsSource = null;
        ProfilesBox.ItemsSource = profiles;
        ProfilesBox.SelectedItem = select ?? profiles.FirstOrDefault();
        loading = false;
        LoadProfile();
        RememberCurrentProfile();
    }

    private void LoadProfile()
    {
        pingCancellation?.Cancel();
        reserveCancellation?.Cancel();
        loading = true;
        var profile = Current;
        if (profile != null)
        {
            AppsList.ItemsSource = null;
            AppsList.ItemsSource = (profile.Mode == TrafficMode.FullTunnel ? profile.DirectApplications : profile.Applications)
                .Select(AppListItem.FromPath).ToList();
            ServersList.ItemsSource = null; ServersList.ItemsSource = profile.Servers;
            ApplyServerFilter();
            var selectedServer = profile.Servers.Count == 0 ? null : profile.Servers[Math.Clamp(profile.SelectedServer, 0, profile.Servers.Count - 1)];
            ServersList.SelectedItem = selectedServer;
            ServerCountText.Text = profile.Servers.Count.ToString();
            ReserveStatusText.Text = profile.BootstrapServerName is { Length: > 0 } name ? "Резерв: " + name : "Резерв не выбран";
            ProfileSummaryName.Text = profile.Name;
            ProfileSummaryMode.Text = profile.Mode == TrafficMode.FullTunnel ? "Весь трафик через VPN" : "VPN для выбранных приложений";
            AppsHeading.Text = profile.Mode == TrafficMode.FullTunnel ? "Исключения из VPN" : "Приложения через VPN";
            if (!tunnel.IsRunning) ServerText.Text = selectedServer?.Name ?? "Добавьте подписку";
        }
        loading = false;
    }

    private void Save()
    {
        try { store.Save(profiles); }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Не удалось сохранить", MessageBoxButton.OK, MessageBoxImage.Error); }
    }

    private void ProfilesBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (loading) return;
        LoadProfile();
        RememberCurrentProfile();
    }

    private void RememberCurrentProfile()
    {
        foreach (var profile in profiles) profile.LastSelected = ReferenceEquals(profile, Current);
        Save();
    }

    private void ServerSearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (ServersList == null) return;
        var wasLoading = loading;
        loading = true;
        ApplyServerFilter();
        loading = wasLoading;
    }

    private void ApplyServerFilter()
    {
        if (ServersList.ItemsSource == null) return;
        var query = ServerSearchBox.Text.Trim();
        CollectionViewSource.GetDefaultView(ServersList.ItemsSource).Filter = item =>
            item is ServerEntry server && (query.Length == 0 ||
                server.Name.Contains(query, StringComparison.CurrentCultureIgnoreCase) ||
                server.Details.Contains(query, StringComparison.CurrentCultureIgnoreCase));
    }

    private void AddProfile_Click(object sender, RoutedEventArgs e)
    {
        var editor = new ProfileEditorWindow(null, subscriptions, false) { Owner = this };
        if (editor.ShowDialog() != true || editor.Result == null) return;
        profiles.Add(editor.Result); Save(); RefreshProfiles(editor.Result);
    }

    private void EditProfile_Click(object sender, RoutedEventArgs e)
    {
        var current = Current;
        if (current == null) return;
        var editor = new ProfileEditorWindow(current, subscriptions, profiles.Count > 1 && !tunnel.IsRunning) { Owner = this };
        if (editor.ShowDialog() != true) return;
        if (editor.DeleteRequested)
        {
            profiles.Remove(current); Save(); RefreshProfiles(profiles.FirstOrDefault());
            return;
        }
        if (editor.Result == null) return;
        profiles[profiles.IndexOf(current)] = editor.Result;
        Save(); RefreshProfiles(editor.Result);
    }

    private void ServersList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (loading || Current == null || ServersList.SelectedItem is not ServerEntry server) return;
        reserveCancellation?.Cancel();
        Current.SelectedServer = Current.Servers.IndexOf(server);
        ServerText.Text = server.Name;
        Save();
    }

    private async void AddSubscription_Click(object sender, RoutedEventArgs e)
    {
        var profile = Current;
        if (profile == null) return;
        if (tunnel.IsRunning) { ShowError("Отключите VPN перед обновлением подписки."); return; }
        var input = Prompt("Добавить подписку", "Вставьте HTTPS-ссылку на подписку", profile.SubscriptionUrl ?? "");
        if (string.IsNullOrWhiteSpace(input)) return;
        if (!Uri.TryCreate(input.Trim(), UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps)
        { ShowError("Нужна HTTPS-ссылка на подписку."); return; }
        await RefreshSubscriptionAsync(profile, uri);
    }

    private async void RefreshSubscription_Click(object sender, RoutedEventArgs e)
    {
        var profile = Current;
        if (profile == null || !Uri.TryCreate(profile.SubscriptionUrl, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps)
        { ShowError("Сначала добавьте подписку в этот профиль."); return; }
        await RefreshSubscriptionAsync(profile, uri);
    }

    private async Task RefreshSubscriptionAsync(ConnectionProfile profile, Uri uri)
    {
        if (refreshingSubscription) return;
        refreshingSubscription = true;
        AddSubscriptionButton.IsEnabled = RefreshSubscriptionButton.IsEnabled = false;
        FooterText.Text = "Обновляю подписку…";
        var previous = profile.Servers.ElementAtOrDefault(profile.SelectedServer);
        try
        {
            var result = await subscriptions.FetchAsync(uri, profile.SubscriptionHwid, profile.SubscriptionNeedsDeviceId);
            profile.SubscriptionUrl = uri.ToString();
            profile.SubscriptionNeedsDeviceId = result.UsedDeviceId;
            profile.Servers = result.Servers;
            var index = previous == null ? -1 : profile.Servers.FindIndex(server => server.Link == previous.Link);
            if (index < 0 && previous != null) index = profile.Servers.FindIndex(server => server.Name == previous.Name);
            profile.SelectedServer = Math.Max(0, index);
            Save();
            if (ReferenceEquals(Current, profile)) LoadProfile();
            FooterText.Text = $"Подписка обновлена: {result.Servers.Count} серверов." +
                (result.SkippedCount > 0 ? $" Пропущено: {result.SkippedCount}." : "") +
                (tunnel.IsRunning ? " Изменения подключения применятся после переподключения." : "");
        }
        catch (Exception ex) when (ex is HttpRequestException or FormatException or TaskCanceledException or InvalidDataException)
        { FooterText.Text = "Не удалось обновить подписку."; ShowError(ex.Message); }
        finally { refreshingSubscription = false; AddSubscriptionButton.IsEnabled = RefreshSubscriptionButton.IsEnabled = true; }
    }

    private async void Ping_Click(object sender, RoutedEventArgs e)
    {
        if (pingCancellation != null) { pingCancellation.Cancel(); return; }
        var servers = Current?.Servers.ToArray() ?? [];
        if (servers.Length == 0) { ShowError("Добавьте подписку для проверки серверов."); return; }
        using var cancellation = new CancellationTokenSource();
        pingCancellation = cancellation;
        PingButton.Content = "Остановить";
        foreach (var server in servers) server.LatencyText = "…";
        var done = 0;
        using var slots = new SemaphoreSlim(6);
        try
        {
            await Task.WhenAll(servers.Select(async server =>
            {
                await slots.WaitAsync(cancellation.Token);
                try
                {
                    if (server.Link.StartsWith("hysteria2://", StringComparison.OrdinalIgnoreCase))
                    { server.LatencyText = "UDP"; return; }
                    if (!Uri.TryCreate(server.Link, UriKind.Absolute, out var uri) || uri.Port < 1)
                    { server.LatencyText = "—"; return; }
                    server.LatencyText = "Проверка…";
                    var latency = await ServerLatencyProbe.MeasureAsync(uri.Host, uri.Port, cancellation.Token);
                    server.LatencyText = latency == null ? "Недоступен" : $"{latency} мс";
                    FooterText.Text = $"Тест пинга (TCP): {++done}/{servers.Length}.";
                }
                finally { slots.Release(); }
            }));
            FooterText.Text = $"Тест пинга завершён: {done} серверов (TCP).";
        }
        catch (OperationCanceledException) { FooterText.Text = "Тест пинга остановлен."; }
        finally
        {
            foreach (var server in servers) if (server.LatencyText is "…" or "Проверка…") server.LatencyText = "";
            pingCancellation = null;
            PingButton.Content = "Тест пинга";
        }
    }

    private async void FindReserve_Click(object sender, RoutedEventArgs e)
    {
        if (reserveCancellation != null) { reserveCancellation.Cancel(); return; }
        var profile = Current;
        var target = profile?.Servers.ElementAtOrDefault(profile.SelectedServer);
        if (profile == null || target == null) { ShowError("Выберите основной сервер из подписки."); return; }
        pingCancellation?.Cancel();
        using var cancellation = new CancellationTokenSource();
        reserveCancellation = cancellation;
        FindReserveButton.Content = "Остановить поиск";
        PingButton.IsEnabled = false;
        var progress = new Progress<DiscoveryProgress>(p =>
        {
            if (ReferenceEquals(reserveCancellation, cancellation) && !cancellation.IsCancellationRequested)
                ReserveStatusText.Text = $"Проверено: {p.Completed}/{p.Total} · {p.ServerName}";
        });
        try
        {
            ReserveStatusText.Text = "Ищу рабочий резерв…";
            var result = await new BootstrapDiscovery().FindAsync(target, profile.Servers.ToArray(), profile.BootstrapServerName, progress, cancellation.Token);
            cancellation.Token.ThrowIfCancellationRequested();
            if (result == null)
            {
                ReserveStatusText.Text = "Рабочий резерв не найден";
                FooterText.Text = "Проверенные маршруты не ответили. Прежний резерв сохранён.";
                return;
            }
            profile.BootstrapServerName = result.Name;
            Save();
            ReserveStatusText.Text = "Резерв: " + result.Name;
            FooterText.Text = "Рабочий резерв сохранён: " + result.Name +
                (tunnel.IsRunning ? ". Применится после переподключения." : ". Можно подключаться.");
        }
        catch (OperationCanceledException)
        {
            if (ReferenceEquals(Current, profile)) ReserveStatusText.Text = profile.BootstrapServerName is { Length: > 0 } name ? "Резерв: " + name : "Поиск остановлен";
        }
        catch (Exception ex)
        {
            if (ReferenceEquals(Current, profile)) ReserveStatusText.Text = profile.BootstrapServerName is { Length: > 0 } name ? "Резерв: " + name : "Поиск не выполнен";
            ShowError(ex.Message);
        }
        finally
        {
            reserveCancellation = null;
            FindReserveButton.Content = "Найти резервный сервер";
            PingButton.IsEnabled = true;
        }
    }

    private void DeleteSubscription_Click(object sender, RoutedEventArgs e)
    {
        var profile = Current;
        if (profile == null || (profile.SubscriptionUrl == null && profile.Servers.Count == 0)) return;
        if (tunnel.IsRunning) { ShowError("Отключите VPN перед удалением подписки."); return; }
        if (MessageBox.Show($"Удалить подписку и все {profile.Servers.Count} серверов из профиля «{profile.Name}»?",
            "Удалить подписку", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        profile.SubscriptionUrl = null;
        profile.SubscriptionHwid = null;
        profile.SubscriptionNeedsDeviceId = false;
        profile.Servers.Clear();
        profile.SelectedServer = 0;
        Save(); LoadProfile();
    }

    private async void Connect_Click(object sender, RoutedEventArgs e) => await ToggleConnectionAsync();

    public void ToggleFromExternal() => _ = ToggleConnectionAsync();

    public void ShowFromExternal()
    {
        ShowInTaskbar = true;
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        if (exitRequested) return;
        e.Cancel = true;
        Hide();
        ShowInTaskbar = false;
    }

    private void ExitApplication()
    {
        exitRequested = true;
        Close();
        System.Windows.Application.Current.Shutdown();
    }

    private async Task ToggleConnectionAsync()
    {
        if (connecting) return;
        if (tunnel.IsRunning) { tunnel.Stop(); return; }
        var profile = Current;
        if (profile == null || profile.Servers.Count == 0) { ShowError("Добавьте и выберите сервер."); return; }
        var server = profile.Servers[Math.Clamp(profile.SelectedServer, 0, profile.Servers.Count - 1)];
        ConnectButton.IsEnabled = false;
        connecting = true;
        ConnectionLabel.Text = "ПОДКЛЮЧЕНИЕ";
        StatusText.Text = "Проверяю сервер…";
        try
        {
            var ip = await tunnel.StartAsync(profile, server);
            SetConnectionState(true);
            FooterText.Text = "VPN-сервер ответил. Выходной IP: " + ip;
        }
        catch (Exception ex) { SetConnectionState(false); ShowError(ex.Message); }
        finally { connecting = false; ConnectButton.IsEnabled = true; }
    }

    private void SetConnectionState(bool connected)
    {
        ConnectionLabel.Text = connected ? "ПОДКЛЮЧЕНО" : "НЕ ПОДКЛЮЧЕНО";
        StatusText.Text = connected ? "Туннель запущен · сервер проверен" : "Не подключено";
        ConnectButton.Content = connected ? "Отключить" : "Подключить";
        trayToggle.Text = connected ? "Отключить" : "Подключить";
        tray.Text = connected ? "Pipidastr VPN — подключено" : "Pipidastr VPN — не подключено";
        ConnectButton.Background = (System.Windows.Media.Brush)FindResource(connected ? "BorderColor" : "Accent");
        ProfilesBox.IsHitTestVisible = !connected;
        ServersList.IsHitTestVisible = !connected;
        ServerSearchBox.IsEnabled = !connected;
    }
    private static void ShowError(string text) => MessageBox.Show(text, "Pipidastr VPN", MessageBoxButton.OK, MessageBoxImage.Warning);

    private string? Prompt(string title, string hint, string initial)
    {
        var dialog = new Window { Title = title, Width = 520, Height = 210, WindowStartupLocation = WindowStartupLocation.CenterOwner, Owner = this, ResizeMode = ResizeMode.NoResize, Background = (System.Windows.Media.Brush)FindResource("Card"), Foreground = System.Windows.Media.Brushes.White };
        var panel = new DockPanel { Margin = new Thickness(20) };
        var label = new TextBlock { Text = hint, Foreground = (System.Windows.Media.Brush)FindResource("Muted"), Margin = new Thickness(0, 0, 0, 12) };
        DockPanel.SetDock(label, Dock.Top); panel.Children.Add(label);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 12, 0, 0) };
        var cancel = new Button { Content = "Отмена", Margin = new Thickness(0, 0, 8, 0) };
        var ok = new Button { Content = "Добавить", Background = (System.Windows.Media.Brush)FindResource("Accent") };
        cancel.Click += (_, _) => dialog.DialogResult = false; ok.Click += (_, _) => dialog.DialogResult = true;
        buttons.Children.Add(cancel); buttons.Children.Add(ok); DockPanel.SetDock(buttons, Dock.Bottom); panel.Children.Add(buttons);
        var box = new TextBox { Text = initial };
        panel.Children.Add(box); dialog.Content = panel; dialog.Loaded += (_, _) => box.Focus();
        return dialog.ShowDialog() == true ? box.Text : null;
    }
}
