// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import GTNative

/// Arayüz dili: ayardaki seçim ("auto", "tr", "en"); "auto" Mac'in diline bakar (Türkçe değilse İngilizce).
enum UILanguage {
    @MainActor static var setting = "auto"

    @MainActor static var isTurkish: Bool {
        switch setting {
        case "tr": true
        case "en": false
        default: Locale.preferredLanguages.first?.hasPrefix("tr") ?? false
        }
    }
}

/// Türkçe ve İngilizce metinden arayüz dilindekini seçer.
@MainActor func L(_ turkish: String, _ english: String) -> String { UILanguage.isTurkish ? turkish : english }

/// Motorun "@anahtar␟değer" iletilerinin okunur hâli (telefondaki lib/l10n 'n.*' tablolarının aynısı).
enum MessageText {
    @MainActor static func text(_ raw: String) -> String {
        guard raw.hasPrefix("@") else { return raw }
        let parts = raw.dropFirst().split(separator: "\u{1f}", omittingEmptySubsequences: false).map(String.init)
        guard let key = parts.first else { return raw }
        var text = (UILanguage.isTurkish ? turkish[key] : english[key]) ?? english[key] ?? key
        for (index, value) in parts.dropFirst().enumerated() { text = text.replacingOccurrences(of: "{\(index)}", with: value) }
        return text
    }

    static let turkish: [String: String] = [
        "cancelled": "İptal edildi",
        "in_use": "Remote Play başka bir cihazda kullanılıyor",
        "login_pin": "PS5 kullanıcısının giriş şifresi var; şifresiz bir kullanıcı kullanın",
        "lost": "bağlantı koptu",
        "native_missing": "Yerel kütüphane yüklenemedi",
        "need_ps_setup": "Önce PSN girişi ve PS5 eşleştirmesi gerekli",
        "need_psn": "Önce PSN girişi gerekli",
        "need_xbox_setup": "Önce Microsoft hesabınla giriş yapıp Xbox'ını seç",
        "no_account": "PSN hesabı yok",
        "no_pairing": "PS5 eşleştirmesi yok",
        "no_wake": "PS5 uyanmadı",
        "no_xbox": "Xbox seçilmemiş",
        "not_found": "PS5 ağda bulunamadı ({0})",
        "refused": "PS5 bağlantıyı reddetti; yeniden eşleştirin",
        "reg_bad_code": "Kod yanlış ya da süresi dolmuş. PS5'te Cihaz Eşleştir'i yeniden açıp yeni kodu girin.",
        "reg_no_reply": "PS5 yanıt vermedi. Konsolun açık ve Cihaz Eşleştir ekranında olduğunu kontrol edin.",
        "reg_not_ready": "PS5 eşleştirmeye hazır değil. PS5'te Ayarlar › Sistem › Uzaktan Oynatma › Cihaz Eşleştir ekranını açık tutup yeniden deneyin.",
        "reg_unknown_account": "PS5 bu PSN hesabını tanımadı. PS5'te oturum açık olan kullanıcıyla aynı hesapla giriş yapın.",
        "reg_unreachable": "Telefon PS5'i ağda göremiyor. Telefon ile PS5 aynı Wi-Fi bandında olmalı (ikisi de 2.4 GHz ya da ikisi de 5 GHz) ya da PS5 modeme kabloyla bağlı olmalı. Modemde \\\"Bant Geçiş\\\" (band steering) ya da istemci yalıtımı açıksa kapatıp telefonun Wi-Fi'ını kapatıp açın.",
        "rest_mode": "PS5 dinlenme modunda",
        "session_failed": "Oturum başlatılamadı",
        "unexpected": "Beklenmeyen hata: {0}",
        "x_no_profile": "Bu Microsoft hesabının Xbox profili yok. Xbox'ta kullandığın hesapla giriş yap.",
        "xr_busy": "Xbox başka bir uzaktan oynatmada. Onu kapat; yeniden deneniyor…",
        "xr_network": "Xbox bağlantısı koptu. Yeniden bağlanılıyor…",
        "xr_service": "Xbox hizmeti beklenmedik yanıt verdi. Yeniden deneniyor…",
        "xr_signin_expired": "Microsoft girişinin süresi doldu. Ayarlar › Xbox › \"Microsoft Hesabından Çık\" ile yeniden giriş yap.",
        "xr_timeout": "Xbox yanıt vermedi. Yeniden deneniyor…",
        "xr_unavailable": "Xbox'a ulaşılamadı. Xbox açık mı, Ayarlar › Cihazlar ve bağlantılar › Uzak özellikler'de uzak özellikler etkin mi? Yeniden deneniyor…",
        "xs_code_expired": "Kodun süresi doldu. Yeniden dene.",
        "xs_finish_failed": "Giriş tamamlanamadı.",
        "xs_list_failed": "Xbox listesi alınamadı.",
        "xs_network": "İnternete bağlanılamadı. Bağlantını kontrol edip yeniden dene.",
        "xs_service": "Xbox hizmeti beklenmedik yanıt verdi ({0}).",
        "xs_signin_incomplete": "Microsoft girişi tamamlanmadı. Yeniden giriş yap.",
        "xs_start_failed": "Giriş başlatılamadı.",
        "xs_start_first": "Önce girişi başlat.",
    ]

    static let english: [String: String] = [
        "cancelled": "Cancelled",
        "in_use": "Remote Play is in use on another device",
        "login_pin": "The PS5 user has a login passcode; use a user without one",
        "lost": "connection lost",
        "native_missing": "The native library could not be loaded",
        "need_ps_setup": "Sign in to PSN and pair your PS5 first",
        "need_psn": "Sign in to PSN first",
        "need_xbox_setup": "Sign in with your Microsoft account and choose your Xbox first",
        "no_account": "No PSN account",
        "no_pairing": "No PS5 is paired",
        "no_wake": "The PS5 did not wake up",
        "no_xbox": "No Xbox selected",
        "not_found": "The PS5 was not found on the network ({0})",
        "refused": "The PS5 refused the connection; pair again",
        "reg_bad_code": "The code is wrong or has expired. Open Link Device on the PS5 again and enter the new code.",
        "reg_no_reply": "The PS5 did not respond. Check that the console is on and showing the Link Device screen.",
        "reg_not_ready": "The PS5 is not ready to pair. Keep Settings › System › Remote Play › Link Device open on the PS5 and try again.",
        "reg_unknown_account": "The PS5 did not recognize this PSN account. Sign in with the same account as the user logged in on the PS5.",
        "reg_unreachable": "The phone can't see the PS5 on the network. The phone and the PS5 must be on the same Wi-Fi band (both 2.4 GHz or both 5 GHz), or the PS5 must be connected to the router by cable. If the router has band steering or client isolation on, turn it off and reconnect the phone's Wi-Fi.",
        "rest_mode": "The PS5 is in rest mode",
        "session_failed": "The session could not be started",
        "unexpected": "Unexpected error: {0}",
        "x_no_profile": "This Microsoft account has no Xbox profile. Sign in with the account you use on your Xbox.",
        "xr_busy": "The Xbox is in another remote play session. Close that one; trying again…",
        "xr_network": "The connection to the Xbox was lost. Reconnecting…",
        "xr_service": "The Xbox service gave an unexpected response. Trying again…",
        "xr_signin_expired": "Your Microsoft sign-in has expired. Sign in again from Settings › Xbox › \"Sign Out of Microsoft Account\".",
        "xr_timeout": "The Xbox did not respond. Trying again…",
        "xr_unavailable": "Could not reach the Xbox. Is it on, and is \"Enable remote features\" turned on under Settings › Devices & connections › Remote features? Trying again…",
        "xs_code_expired": "The code has expired. Try again.",
        "xs_finish_failed": "Sign-in could not be completed.",
        "xs_list_failed": "Could not get the list of Xbox consoles.",
        "xs_network": "Could not connect to the internet. Check your connection and try again.",
        "xs_service": "The Xbox service gave an unexpected response ({0}).",
        "xs_signin_incomplete": "The Microsoft sign-in was not completed. Sign in again.",
        "xs_start_failed": "Sign-in could not be started.",
        "xs_start_first": "Start the sign-in first.",
    ]
}
