using Microsoft.Win32;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;

namespace VpnClient;

public partial class AppPickerWindow : Window
{
    private readonly List<AppListItem> candidates;
    public IReadOnlyList<string> SelectedPaths { get; private set; } = [];

    public AppPickerWindow()
    {
        InitializeComponent();
        SourceInitialized += (_, _) => WindowAppearance.Apply(this);
        candidates = RunningApplications();
        CandidatesList.ItemsSource = candidates;
        SearchBox.Focus();
    }

    private static List<AppListItem> RunningApplications()
    {
        var found = new Dictionary<string, AppListItem>(StringComparer.OrdinalIgnoreCase);
        var windows = Environment.GetFolderPath(Environment.SpecialFolder.Windows) + System.IO.Path.DirectorySeparatorChar;
        foreach (var process in Process.GetProcesses())
        {
            using (process)
            {
                try
                {
                    var path = process.MainModule?.FileName;
                    if (string.IsNullOrWhiteSpace(path) || !path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) continue;
                    if (path.StartsWith(windows, StringComparison.OrdinalIgnoreCase) && string.IsNullOrWhiteSpace(process.MainWindowTitle)) continue;
                    if (string.Equals(path, Environment.ProcessPath, StringComparison.OrdinalIgnoreCase)) continue;
                    found.TryAdd(path, AppListItem.FromPath(path));
                }
                catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException or NotSupportedException)
                { /* Protected/system process: not selectable. */ }
            }
        }
        return found.Values.OrderBy(x => x.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (CandidatesList?.ItemsSource == null) return;
        var query = SearchBox.Text.Trim();
        CollectionViewSource.GetDefaultView(CandidatesList.ItemsSource).Filter = item =>
            item is AppListItem app && (query.Length == 0 || app.Name.Contains(query, StringComparison.CurrentCultureIgnoreCase) || app.Path.Contains(query, StringComparison.CurrentCultureIgnoreCase));
    }

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        var picker = new OpenFileDialog { Title = "Выберите исполняемый файл", Filter = "Приложения (*.exe)|*.exe", Multiselect = true };
        if (picker.ShowDialog(this) != true) return;
        SelectedPaths = picker.FileNames;
        DialogResult = true;
    }

    private void Add_Click(object sender, RoutedEventArgs e)
    {
        SelectedPaths = CandidatesList.SelectedItems.Cast<AppListItem>().Select(x => x.Path).ToArray();
        if (SelectedPaths.Count == 0)
        {
            MessageBox.Show("Выберите программу из списка или нажмите «Выбрать файл .exe…». ", "Pipidastr VPN", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        DialogResult = true;
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => DialogResult = false;
}
