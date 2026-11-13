using System.Net.Http;
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
    private bool loading;

    public MainWindow()
    {
        InitializeComponent();
        subscriptions = new SubscriptionClient(http, new DeviceIdentity());
        try { profiles = store.Load(); }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Профили", MessageBoxButton.OK, MessageBoxImage.Warning); profiles = [new() { Name = "Приложения" }]; }
        RefreshProfiles(profiles.FirstOrDefault());
        tunnel.Log += line => Dispatcher.InvokeAsync(() => FooterText.Text = line.Length > 260 ? line[..260] : line);
        tunnel.Stopped += () => Dispatcher.InvokeAsync(() => SetConnectionState(false));
        Closed += (_, _) => { tunnel.Dispose(); http.Dispose(); };
    }

    private void RefreshProfiles(ConnectionProfile? select)
    {
        loading = true;
        ProfilesBox.ItemsSource = null;
        ProfilesBox.ItemsSource = profiles;
        ProfilesBox.SelectedItem = select ?? profiles.FirstOrDefault();
        loading = false;
        LoadProfile();
    }

    private void LoadProfile()
    {
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
            ProfileSummaryName.Text = profile.Name;
            ProfileSummaryMode.Text = profile.Mode == TrafficMode.FullTunnel ? "Весь трафик через VPN" : "VPN для выбранных приложений";
            AppsHeading.Text = profile.Mode == TrafficMode.FullTunnel ? "Исключения из VPN" : "Приложения через VPN";
            ServerText.Text = selectedServer?.Name ?? "Добавьте подписку";
        }
        loading = false;
    }

    private void Save()
    {
        try { store.Save(profiles); }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Не удалось сохранить", MessageBoxButton.OK, MessageBoxImage.Error); }
    }

    private void ProfilesBox_SelectionChanged(object sender, SelectionChangedEventArgs e) { if (!loading) LoadProfile(); }

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
        AddSubscriptionButton.IsEnabled = false;
        try
        {
            var result = await subscriptions.FetchAsync(uri, profile.SubscriptionHwid, profile.SubscriptionNeedsDeviceId);
            profile.SubscriptionUrl = uri.ToString();
            profile.SubscriptionNeedsDeviceId = result.UsedDeviceId;
            profile.Servers = result.Servers;
            profile.SelectedServer = 0;
            Save(); LoadProfile();
            MessageBox.Show($"Добавлено серверов: {result.Servers.Count}." +
                (result.SkippedCount > 0 ? $"\nПропущено: {result.SkippedCount}.\n{result.SkipSummary}" : ""),
                "Подписка обновлена", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex) when (ex is HttpRequestException or FormatException or TaskCanceledException or InvalidDataException)
        { ShowError(ex.Message); }
        finally { AddSubscriptionButton.IsEnabled = true; }
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

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        if (tunnel.IsRunning) { tunnel.Stop(); return; }
        var profile = Current;
        if (profile == null || profile.Servers.Count == 0) { ShowError("Добавьте и выберите сервер."); return; }
        var server = profile.Servers[Math.Clamp(profile.SelectedServer, 0, profile.Servers.Count - 1)];
        ConnectButton.IsEnabled = false;
        ConnectionLabel.Text = "ПОДКЛЮЧЕНИЕ";
        StatusText.Text = "Проверяю сервер…";
        try
        {
            var ip = await tunnel.StartAsync(profile, server);
            SetConnectionState(true);
            FooterText.Text = "VPN-сервер ответил. Выходной IP: " + ip;
        }
        catch (Exception ex) { SetConnectionState(false); ShowError(ex.Message); }
        finally { ConnectButton.IsEnabled = true; }
    }

    private void SetConnectionState(bool connected)
    {
        ConnectionLabel.Text = connected ? "ПОДКЛЮЧЕНО" : "НЕ ПОДКЛЮЧЕНО";
        StatusText.Text = connected ? "Туннель запущен · сервер проверен" : "Не подключено";
        ConnectButton.Content = connected ? "Отключить" : "Подключить";
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
