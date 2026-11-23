// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using GameTranslator.Core;

namespace GameTranslator.App.Views;

/// <summary>
/// Rehber: üstte konu (PS5, Xbox, TV'm desteklenmiyor), solda adım listesi, sağda seçili adım tek başına;
/// "Önceki / Sonraki" ile ilerlenir, adımın ilgili ayarına tek tıkla gidilir. Metinler ortak katalogdan gelir.
/// </summary>
public partial class MainView
{
    private sealed record GuideStep(string Title, string[] Paragraphs, string? Note, string? Action, Action<MainView>? Run);
    private sealed record GuideTopic(string Lead, GuideStep[] Steps);

    private static readonly GuideTopic[] Guide =
    [
        new("From turning on the console to the translation on the TV, in five steps.", [
            new("Console and TV", ["Turn on your PS5 and switch the TV to its HDMI input. The PS5 stays connected directly to the TV."], null, null, null),
            new("TV App", ["Open the GameTranslator app on your TV. It connects to the computer automatically over the same Wi-Fi."], null, "Open the Connection tab", v => v.ShowSettings(v.TabSystem)),
            new("Reading Area", ["Depending on where your game shows its subtitles, pick a preset in the 'Reading Area' tab or adjust the blue box."], null, "Open the Reading Area", v => v.ShowSettings(v.TabOcrArea)),
            new("Start", ["Click the blue '▶ Start Translation' button on the home screen. The console picture is scanned and the subtitles appear translated on the TV."], null, "Go to the home screen", v => v.OnBackHome(v, new RoutedEventArgs())),
            new("TV Appearance", ["Set the subtitle color (yellow/white), size and position to your taste; the TV shows the change at once!"], null, "Open Subtitle Appearance", v => v.ShowSettings(v.TabDisplay)),
        ]),
        new("Just like on PS5: you sign in once with your Microsoft account and choose your Xbox, then you only start translating. The app connects to the console by itself (Xbox home streaming, that is, remote play from the console) and reads the game picture from there; no window opens. Sign-in and the player were tested without an account; it has not been tried on a real Xbox yet. If something does not work, share the altyazi.log file.", [
            new("1 · Prepare the Xbox (once)", ["On the Xbox, go to Settings › Devices & connections › Remote features and turn on \"Enable remote features\". Under Power options choose \"Sleep\" (you cannot connect remotely while \"Shutdown (energy saving)\" is selected). Keep the console and the computer on the same network."], null, null, null),
            new("2 · Sign in with your Microsoft account (once)", ["In the Connection tab choose \"Xbox\" as the video source and press \"Sign in with a Microsoft account\". The app shows an 8-character code and opens Microsoft's page in your browser; type the code there and approve with the account you use on the console. Once you approve, the app continues by itself."], "You enter your password at Microsoft in your own browser; the app never sees it. The session is stored encrypted on this computer and \"Forget the account\" deletes it.", "Open the Connection tab", v => v.ShowSettings(v.TabSystem)),
            new("3 · Choose your Xbox (once)", ["After you sign in, the consoles on your account are listed. If there is only one it is chosen automatically; if there are several, pick one from the list. A console shown dimmed has remote features turned off (step 1). If the console is not in the list, make sure you are signed in on the console with the same Microsoft account and press \"Refresh consoles\"."], null, null, null),
            new("4 · Every time: just start translating", ["Turn on subtitles in the game and start translating from the home screen. The app connects to the console (the first connection can take half a minute); on the TV, open GameTranslator and select the Xbox's HDMI input. Stopping the translation closes the connection. If the connection drops, the app retries every 5 seconds."], "To see it on the computer screen too, turn on \"Subtitles on the PC Screen\" in the TV Appearance tab.", "Go to the home screen", v => v.OnBackHome(v, new RoutedEventArgs())),
            new("Good to know", ["• This path streams from your own console; cloud games (Xbox Cloud Gaming) are not available in Turkey.\n• Xbox does not report the name of the running game: to use a ready-made Turkish pack, pick the game by hand from the \"Game\" row on the home screen.\n• The picture reaching the app is slightly behind the TV; the translation arrives that much later.\n• The stream picture is compressed; small subtitles may be read less reliably than on PS5. Increase the subtitle size in the game.\n• We have not yet tested whether the controller on the TV keeps working during streaming.\n• If you have a capture card, connecting the Xbox's HDMI to it and choosing \"Capture Card\" as the source is cleaner and has no delay.\n• The phone app also supports Xbox (Beta); see the guide on the phone for details."], null, null, null),
            new("If something goes wrong", ["It says \"Retrying\" and does not connect: is the console in \"Sleep\" mode, are remote features on, are the console and the computer on the same network? The note next to the status shows the reason the Xbox gave.", "Sign-in does not finish: the code is valid for 15 minutes; if it has expired, press \"Sign in with a Microsoft account\" again. If the page did not open, use \"Open the page again\".", "Connected but no translation: are the game's subtitles on? In the live picture in the Reading Area tab, are the subtitles inside the blue box?", "If you cannot connect at all, the fallback: tick \"Use a window I open myself\" in the Connection tab; you open the game yourself in the Xbox app or a browser and the app finds that window (the window must stay visible).", "Translations from the wrong game show up: pick the right game from the \"Game\" row on the home screen, or set it to \"Automatic\"."], null, null, null),
        ]),
        new("GameTranslator's TV app installs on LG, Samsung and Android TV / Google TV. On any other TV (Vestel, Arçelik, Beko, Grundig's Linux models, Philips Saphi, Titan OS, older TVs) the translation cannot be drawn over the game. The same workarounds apply on both PS5 and Xbox:", [
            new("1 · Second screen (recommended, no extra hardware)", ["You show the translation in large text on a phone or tablet and put the device in front of the TV. The game plays on the TV as usual; there is no delay.", "The \"My TV is not supported: second screen\" card in the Connection tab has a QR code and an address. With the phone or tablet on the same Wi-Fi as this computer, scan the QR code with its camera or type the address into its browser. Nothing to install; an iPhone, iPad, Android device or another computer all work.", "On the page you set the text size with A− / A+; touch the screen to show the buttons. If GameTranslator is on your phone, \"Show the computer's subtitles\" on its home screen connects without typing the address."], "If the page does not open: are both devices on the same network (not a guest network)? Does Windows Firewall allow GameTranslator? Is translation on (subtitles only arrive while translating)?", "Open the Connection tab", v => v.ShowSettings(v.TabSystem)),
            new("2 · Turkish dubbing", ["The translation can also be read aloud: turn on \"Turkish Dubbing\" in the TV Appearance tab. If you would rather not take your eyes off the TV, use it together with the second screen or instead of it."], null, "Open Languages and Sound", v => v.ShowSettings(v.TabLanguages)),
            new("3 · Connecting the computer to the TV", ["You connect the computer to the TV with an HDMI cable and show the game on the TV through the computer; for subtitles, turn on \"Subtitles on the PC Screen\" in the TV Appearance tab. If the picture comes over remote play, you will feel the delay in fast games.", "If you have a capture card, connecting the console's HDMI to it and choosing \"Capture Card\" as the source gives less delay and a cleaner picture."], null, "Open Subtitle Appearance", v => v.ShowSettings(v.TabDisplay)),
        ]),
    ];

    private int guideTopic, guideStep;

    private void OnGuideTopic(object sender, RoutedEventArgs e) =>
        ShowGuideTopic(GuideTopicXbox.IsChecked == true ? 1 : GuideTopicTv.IsChecked == true ? 2 : 0);

    private void ShowGuideTopic(int topic)
    {
        guideTopic = topic;
        guideStep = 0;
        RenderGuide();
    }

    private void OnGuidePrevious(object sender, RoutedEventArgs e)
    {
        if (guideStep == 0) return;
        guideStep--;
        RenderGuide();
    }

    private void OnGuideNext(object sender, RoutedEventArgs e)
    {
        if (guideStep >= Guide[guideTopic].Steps.Length - 1) return;
        guideStep++;
        RenderGuide();
    }

    private void OnGuideAction(object sender, RoutedEventArgs e) => Guide[guideTopic].Steps[guideStep].Run?.Invoke(this);

    /// <summary>Başlıklardaki "1 · " numarası atılır; numara zaten dairede yazılı.</summary>
    private static string GuideTitle(string title) => Regex.Replace(title, @"^\d+\s*·\s*", "");

    private void RenderGuide()
    {
        var text = Localizer.Current;
        var topic = Guide[guideTopic];
        GuideTopicPs5.IsChecked = guideTopic == 0;
        GuideTopicXbox.IsChecked = guideTopic == 1;
        GuideTopicTv.IsChecked = guideTopic == 2;
        GuideLead.Text = text[topic.Lead];

        // Sol: adım listesi (geçilenler ve o anki mavi daire)
        GuideStepList.Children.Clear();
        for (var i = 0; i < topic.Steps.Length; i++)
        {
            var index = i;
            var current = i == guideStep;
            var circle = new Border { Width = 28, Height = 28, CornerRadius = new CornerRadius(14), Margin = new Thickness(0, 0, 12, 0), VerticalAlignment = VerticalAlignment.Top };
            circle.SetResourceReference(Border.BackgroundProperty, current ? "accentBrush" : i < guideStep ? "accentSoftBrush" : "cardStrongBrush");
            var number = new TextBlock { Text = (i + 1).ToString(), FontSize = 13, FontWeight = FontWeights.SemiBold, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            number.SetResourceReference(TextBlock.ForegroundProperty, current ? "onAccentBrush" : i < guideStep ? "accentTextBrush" : "textSecondaryBrush");
            circle.Child = number;
            var title = new TextBlock { Text = GuideTitle(text[topic.Steps[i].Title]), FontSize = 14.5, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center, FontWeight = current ? FontWeights.SemiBold : FontWeights.Normal };
            title.SetResourceReference(TextBlock.ForegroundProperty, current ? "textPrimaryBrush" : "textSecondaryBrush");
            var row = new DockPanel();
            DockPanel.SetDock(circle, Dock.Left);
            row.Children.Add(circle);
            row.Children.Add(title);
            var button = new Button { Content = row, Margin = new Thickness(0, 0, 0, 2) };
            button.SetResourceReference(StyleProperty, "RowButton");
            button.Click += (_, _) => { guideStep = index; RenderGuide(); };
            GuideStepList.Children.Add(button);
        }

        // Sağ: seçili adım
        var step = topic.Steps[guideStep];
        GuideStepNumber.Text = (guideStep + 1).ToString();
        GuideStepTitle.Text = GuideTitle(text[step.Title]);
        GuideStepBody.Text = string.Join("\n\n", step.Paragraphs.Select(key => text[key]));
        GuideStepNote.Text = step.Note is null ? "" : text[step.Note];
        GuideStepNoteBox.Visibility = step.Note is null ? Visibility.Collapsed : Visibility.Visible;
        GuideStepAction.Visibility = step.Action is null ? Visibility.Collapsed : Visibility.Visible;
        if (step.Action is not null) GuideStepAction.Content = text[step.Action];
        GuideStepCounter.Text = text.Format("Step %lld of %lld", guideStep + 1, topic.Steps.Length);
        GuidePrevious.Visibility = guideStep == 0 ? Visibility.Hidden : Visibility.Visible;
        GuideNext.Visibility = guideStep == topic.Steps.Length - 1 ? Visibility.Hidden : Visibility.Visible;
    }
}
