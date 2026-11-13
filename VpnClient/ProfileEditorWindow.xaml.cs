using System.Net.Http;
using System.Windows;
using System.Windows.Controls;

namespace VpnClient;

public partial class ProfileEditorWindow : Window
{
    private readonly SubscriptionClient subscriptions;
    private readonly ConnectionProfile draft;
    public ConnectionProfile? Result { get; private set; }
    public bool DeleteRequested { get; private set; }

    public ProfileEditorWindow(ConnectionProfile? source, SubscriptionClient subscriptions, bool allowDelete)
    {
        InitializeComponent();
        SourceInitialized += (_, _) => WindowAppearance.Apply(this);
        this.subscriptions = subscriptions;
        draft = source == null ? new ConnectionProfile { RoutingVersion = 1 } : new ConnectionProfile
        {
            Id = source.Id, Name = source.Name, Mode = source.Mode,
            Applications = [.. source.Applications], DirectApplications = [.. source.DirectApplications],
            RoutingVersion = source.RoutingVersion,
            Servers = [.. source.Servers], SubscriptionUrl = source.SubscriptionUrl,
            SubscriptionHwid = source.SubscriptionHwid, SubscriptionNeedsDeviceId = source.SubscriptionNeedsDeviceId,
            SelectedServer = source.SelectedServer
        };
        Heading.Text = source == null ? "Новый профиль" : "Настроить профиль";
        DeleteButton.Visibility = source == null || !allowDelete ? Visibility.Collapsed : Visibility.Visible;
        NameBox.Text = source?.Name ?? "";
        ModeBox.SelectedIndex = draft.Mode == TrafficMode.FullTunnel ? 1 : 0;
        SubscriptionBox.Text = draft.SubscriptionUrl ?? "";
        RefreshLists();
    }

    private void RefreshLists()
    {
        var full = ModeBox.SelectedIndex == 1;
        AppsHeading.Text = full ? "Исключения из VPN" : "Приложения через VPN";
        AppsList.ItemsSource = null;
        AppsList.ItemsSource = (full ? draft.DirectApplications : draft.Applications).Select(AppListItem.FromPath).ToList();
        ServerCount.Text = $"Серверов: {draft.Servers.Count}";
    }

    private void Mode_Changed(object sender, SelectionChangedEventArgs e) { if (AppsList != null) RefreshLists(); }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        if (!Uri.TryCreate(SubscriptionBox.Text.Trim(), UriKind.Absolute, out var uri) || uri.Scheme != "https")
        { MessageBox.Show("Введите HTTPS-ссылку на подписку."); return; }
        RefreshButton.IsEnabled = false;
        try
        {
            var result = await subscriptions.FetchAsync(uri, draft.SubscriptionHwid, draft.SubscriptionNeedsDeviceId);
            draft.SubscriptionUrl = uri.ToString(); draft.Servers = result.Servers;
            draft.SubscriptionNeedsDeviceId = result.UsedDeviceId; draft.SelectedServer = 0;
            RefreshLists();
            if (result.SkippedCount > 0)
                MessageBox.Show($"Добавлено серверов: {result.Servers.Count}. Пропущено: {result.SkippedCount}.\n{result.SkipSummary}",
                    "Результат импорта", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex) when (ex is HttpRequestException or FormatException or TaskCanceledException or InvalidDataException)
        { MessageBox.Show(ex.Message, "Не удалось обновить подписку", MessageBoxButton.OK, MessageBoxImage.Warning); }
        finally { RefreshButton.IsEnabled = true; }
    }

    private void AddApp_Click(object sender, RoutedEventArgs e)
    {
        var picker = new AppPickerWindow { Owner = this };
        if (picker.ShowDialog() != true) return;
        var full = ModeBox.SelectedIndex == 1;
        var list = full ? draft.DirectApplications : draft.Applications;
        foreach (var path in picker.SelectedPaths)
        {
            var value = full ? Path.GetFileName(path) : path;
            if (!list.Contains(value, StringComparer.OrdinalIgnoreCase)) list.Add(value);
        }
        RefreshLists();
    }
    private void RemoveApp_Click(object sender, RoutedEventArgs e)
    {
        if (AppsList.SelectedItem is not AppListItem value) return;
        (ModeBox.SelectedIndex == 1 ? draft.DirectApplications : draft.Applications).Remove(value.Path);
        RefreshLists();
    }
    private void Exceptions_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Window { Title = "Исключения", Owner = this, Width = 430, Height = 360, WindowStartupLocation = WindowStartupLocation.CenterOwner, Background = (System.Windows.Media.Brush)FindResource("Page"), Foreground = System.Windows.Media.Brushes.White };
        var panel = new DockPanel { Margin = new Thickness(20) };
        var label = new TextBlock { Text = "Имена процессов, по одному в строке", Foreground = (System.Windows.Media.Brush)FindResource("Muted"), Margin = new Thickness(0, 0, 0, 10) };
        DockPanel.SetDock(label, Dock.Top); panel.Children.Add(label);
        var save = new Button { Content = "Сохранить", HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 10, 0, 0) };
        save.Click += (_, _) => dialog.DialogResult = true;
        DockPanel.SetDock(save, Dock.Bottom); panel.Children.Add(save);
        var box = new TextBox { Text = string.Join(Environment.NewLine, draft.DirectApplications), AcceptsReturn = true, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        panel.Children.Add(box); dialog.Content = panel;
        if (dialog.ShowDialog() != true) return;
        draft.DirectApplications = box.Text.Split(['\r','\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        RefreshLists();
    }
    private void Save_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(NameBox.Text)) { MessageBox.Show("Введите название профиля."); return; }
        draft.Name = NameBox.Text.Trim(); draft.Mode = ModeBox.SelectedIndex == 1 ? TrafficMode.FullTunnel : TrafficMode.SelectedApps;
        draft.SubscriptionUrl = string.IsNullOrWhiteSpace(SubscriptionBox.Text) ? null : SubscriptionBox.Text.Trim();
        Result = draft; DialogResult = true;
    }
    private void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show($"Удалить профиль «{draft.Name}»?", "Pipidastr VPN", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        DeleteRequested = true; DialogResult = true;
    }
    private void Cancel_Click(object sender, RoutedEventArgs e) => DialogResult = false;
}
