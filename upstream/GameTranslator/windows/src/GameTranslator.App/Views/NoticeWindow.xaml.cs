// SPDX-License-Identifier: AGPL-3.0-only

using System.Windows;

namespace GameTranslator.App.Views;

/// <summary>Sürüm duyurusu penceresi (bir kez gösterilir; bkz. Services.ReleaseNotice).</summary>
public partial class NoticeWindow : Window
{
    internal NoticeWindow() => InitializeComponent();

    public static void Open(Window? owner) => new NoticeWindow { Owner = owner }.ShowDialog();

    private void OnClose(object sender, RoutedEventArgs e) => Close();
}
