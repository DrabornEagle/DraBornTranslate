// SPDX-License-Identifier: AGPL-3.0-only

using GameTranslator.Core;

namespace GameTranslator.Core.Tests;

/// <summary>Tuş çubuğu komutları sözlükten çevrilir; motorun düşürdüğü tuş simgeleri geri konur.</summary>
public class UiPromptsTests
{
    [Theory]
    [InlineData("OPEN ✕", "Aç ✕")]
    [InlineData("PUT DOWN ○", "Bırak ○")]
    [InlineData("SET DESTINATION ✕", "Hedef belirle ✕")]
    [InlineData("□ DRINK", "□ İç")]
    [InlineData("REFUEL", "Yakıt al")]
    [InlineData("Open the door", null)]
    [InlineData("SHOOT THE MOON ✕", null)]
    public void ButtonBarCommandsAreTranslatedFromTheList(string paragraph, string? expected) =>
        Assert.Equal(expected, UiPrompts.Translate(paragraph));

    [Theory]
    [InlineData("SHOW TEXT △", "Metni göster", "Metni göster △")]
    [InlineData("CLOSE L1", "Kapat l1", "Kapat L1")]
    [InlineData("□ Pick up", "Al", "□ Al")]
    [InlineData("Hold ✕ to climb", "Tırmanmak için ✕ basılı tut", "Tırmanmak için ✕ basılı tut")]
    [InlineData("Hold ✕ to climb", "Tırmanmak için basılı tut", "Tırmanmak için basılı tut ✕")]
    public void DroppedIconsComeBack(string source, string translation, string expected) =>
        Assert.Equal(expected, UiPrompts.KeepIcons(source, translation));
}
