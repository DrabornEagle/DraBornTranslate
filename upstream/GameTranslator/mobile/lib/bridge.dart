// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:convert';

import 'package:flutter/services.dart';

/// Çekirdeğin (Android tarafındaki Engine) arayüze bildirdiği durum.
class EngineState {
  const EngineState({
    this.phase = 'idle',
    this.message = '',
    this.running = false,
    this.tvClients = 0,
    this.otherServer,
    this.serverPort = 0,
    this.localAddress,
    this.lastSource = '',
    this.lastTranslation = '',
    this.lastRead = '',
    this.frames = 0,
    this.frameWidth = 0,
    this.frameHeight = 0,
    this.translated = 0,
    this.packHits = 0,
    this.packGame = '',
    this.runningGame = '',
    this.nativeAvailable = true,
  });

  /// idle, starting, waking, connecting, streaming, reconnecting, stopping, failed
  final String phase;
  final String message;
  final bool running;
  final int tvClients;

  /// Ağda açık duran başka bir GameTranslator'ın (bilgisayar uygulaması) adresi; TV ona bağlanmış olabilir.
  final String? otherServer;
  final int serverPort;
  final String? localAddress;
  final String lastSource;
  final String lastTranslation;
  final String lastRead;
  final int frames;
  final int frameWidth;
  final int frameHeight;
  final int translated;

  /// Oyun paketinden gösterilen satır sayısı ve paketin (oynanan oyunun) adı.
  final int packHits;
  final String packGame;

  /// PS5'in bildirdiği, o an çalışan oyunun adı (bilinmiyorsa ya da Xbox'ta boş).
  final String runningGame;
  final bool nativeAvailable;

  factory EngineState.fromMap(Map<Object?, Object?> map) => EngineState(
        phase: map['phase'] as String? ?? 'idle',
        message: map['message'] as String? ?? '',
        running: map['running'] as bool? ?? false,
        tvClients: (map['tvClients'] as num?)?.toInt() ?? 0,
        otherServer: map['otherServer'] as String?,
        serverPort: (map['serverPort'] as num?)?.toInt() ?? 0,
        localAddress: map['localAddress'] as String?,
        lastSource: map['lastSource'] as String? ?? '',
        lastTranslation: map['lastTranslation'] as String? ?? '',
        lastRead: map['lastRead'] as String? ?? '',
        frames: (map['frames'] as num?)?.toInt() ?? 0,
        frameWidth: (map['frameWidth'] as num?)?.toInt() ?? 0,
        frameHeight: (map['frameHeight'] as num?)?.toInt() ?? 0,
        translated: (map['translated'] as num?)?.toInt() ?? 0,
        packHits: (map['packHits'] as num?)?.toInt() ?? 0,
        packGame: map['packGame'] as String? ?? '',
        runningGame: map['runningGame'] as String? ?? '',
        nativeAvailable: map['nativeAvailable'] as bool? ?? true,
      );
}

/// Kurulum durumu: seçili konsol türü; PlayStation için PSN hesabı ve eşleştirilmiş PS5, Xbox (Beta) için Microsoft
/// girişi ve seçili Xbox.
class SetupInfo {
  const SetupInfo({
    this.hasAccount = false,
    this.consoleHost,
    this.consoleName,
    this.introSeen = false,
    this.version = '',
    this.isXbox = false,
    this.xboxSignedIn = false,
    this.xboxConsoleName,
  });

  final bool hasAccount;
  final String? consoleHost;
  final String? consoleName;
  final bool introSeen;
  final String version;

  /// Görüntü Xbox'tan mı alınıyor (Beta)? Değilse PlayStation (varsayılan).
  final bool isXbox;
  final bool xboxSignedIn;
  final String? xboxConsoleName;

  bool get hasConsole => consoleHost != null && consoleHost!.isNotEmpty;
  bool get hasXboxConsole => xboxConsoleName != null;
  bool get ready => isXbox ? xboxSignedIn && hasXboxConsole : hasAccount && hasConsole;

  /// Arayüzde konsolun kısa adı.
  String get consoleLabel => isXbox ? 'Xbox' : 'PS5';
}

/// Microsoft cihaz kodu: kullanıcı [verificationUri] sayfasında [userCode]'u yazar.
class XboxSignInCode {
  const XboxSignInCode(this.userCode, this.verificationUri);

  final String userCode;
  final String verificationUri;
}

/// Hesaptaki bir Xbox. Yalnız [streamingEnabled] olanlar seçilebilir (konsolda uzak özellikler açık).
class XboxConsoleInfo {
  const XboxConsoleInfo({required this.id, required this.name, required this.model, required this.streamingEnabled, required this.powerState});

  final String id;
  final String name;
  final String model;
  final bool streamingEnabled;
  final String powerState;

  String get label => name.isNotEmpty ? name : (model.isNotEmpty ? model : 'Xbox');
}

/// Xbox listesi ya da neden alınamadığı.
class XboxConsolesResult {
  const XboxConsolesResult(this.consoles, this.error, this.needsSignIn);

  final List<XboxConsoleInfo> consoles;
  final String? error;
  final bool needsSignIn;
}

/// Köprüdeki Xbox çağrısı başarısız olunca: [message] kullanıcıya gösterilecek metnin kodu (`nt` ile gösterilir).
class XboxFailure implements Exception {
  const XboxFailure(this.message);

  final String message;
}

class FoundConsole {
  const FoundConsole(this.host, this.name, this.awake);

  final String host;
  final String name;
  final bool awake;
}

class RegisterResult {
  const RegisterResult(this.ok, this.name, this.detail);

  final bool ok;
  final String? name;
  final String? detail;
}

/// Uygulama ayarları; Android tarafındaki Settings ile aynı alanlar (JSON olarak taşınır).
class AppSettings {
  AppSettings(this.json);

  final Map<String, dynamic> json;

  Map<String, dynamic> get region => (json['region'] as Map).cast<String, dynamic>();
  Map<String, dynamic> get style => (json['style'] as Map).cast<String, dynamic>();

  double regionValue(String edge) => (region[edge] as num).toDouble();
  String get engine => json['engine'] as String? ?? 'google';
  String get targetLanguage => json['targetLanguage'] as String? ?? 'tr';
  String get sourceLanguage => json['sourceLanguage'] as String? ?? 'en';
  String get deepLKey => json['deepLKey'] as String? ?? '';
  bool get autoWake => json['autoWake'] as bool? ?? true;
  String get glossary => json['glossary'] as String? ?? '';
  bool get packsEnabled => json['packsEnabled'] as bool? ?? true;

  /// Altyazının yeri kendiliğinden bulunur; elle seçilen bölge yalnız kapalıyken kullanılır.
  bool get autoRegion => json['autoRegion'] as bool? ?? true;

  /// Ek alanlarda tanınan tuş simgelerinin kumandası: 'auto' (Xbox kaynağında Xbox, PS5'te PlayStation),
  /// 'playstation' ya da 'xbox'.
  String get buttonLayout => json['buttonLayout'] as String? ?? 'auto';

  /// Arayüz dili: 'auto' (telefonun dili) ya da bir tablo kodu.
  String get uiLanguage => json['uiLanguage'] as String? ?? 'auto';

  /// Oyun paketleri, terim sözlüğü ve kısa söz kuralları yalnız İngilizce → Türkçe çeviride vardır.
  bool get turkishExtras => sourceLanguage == 'en' && targetLanguage == 'tr';

  /// Oyun paketleri şu an kullanılıyor mu?
  bool get packsApply => turkishExtras && packsEnabled;

  // ---------------------------------------------------------------- Ek alanlar
  // Sürekli okunan ek alanlar (görev yazısı, ipucu kutusu, telefon mesajı): üç yuva. Yuva JSON'da
  // {"preset": ad, "live": açık mı} (Kotlin bölgeyi addan bulur; core/ExtraAreas.kt) ya da boş (null).

  /// Hazır ek alanların adları (core/ExtraAreas.kt ExtraAreaPreset.key), arayüzdeki sırayla.
  static const extraPresets = ['bottom_objective', 'help_box', 'phone_message', 'quest', 'notification', 'phone'];

  /// Aynı anda en çok bu kadar ek alan okunur.
  static const extraSlots = 3;

  /// Üç yuva; eksik ya da bozuk yuva boştur (null).
  List<Map<String, dynamic>?> get extraAreas {
    final raw = json['extraAreas'];
    final list = raw is List ? raw : const [];
    return [for (var i = 0; i < extraSlots; i++) i < list.length && list[i] is Map ? (list[i] as Map).cast<String, dynamic>() : null];
  }

  bool _live(Map<String, dynamic>? slot) => slot != null && slot['live'] == true;

  /// Hazır ek alan sürekli okunuyor mu?
  bool hasExtraPreset(String preset) => extraAreas.any((slot) => _live(slot) && slot!['preset'] == preset);

  /// Açık ek alan sayısı.
  int get extraLiveCount => extraAreas.where(_live).length;

  /// Üç yuva da dolu: yeni alan açılamaz.
  bool get extraFull => extraLiveCount >= extraSlots;

  /// Hazır ek alanı açar ya da kapatır (Kotlin'deki withExtraPreset ile aynı kural). Yuvalar doluysa false döner.
  bool setExtraPreset(String preset, bool on) {
    final slots = extraAreas;
    if (!on) {
      for (final slot in slots) {
        if (slot != null && slot['preset'] == preset) slot['live'] = false;
      }
      json['extraAreas'] = slots;
      return true;
    }
    if (hasExtraPreset(preset)) return true;
    final free = [for (var i = 0; i < slots.length; i++) if (!_live(slots[i])) i]
      ..sort((a, b) => (slots[a]?['preset'] == preset ? 0 : 1).compareTo(slots[b]?['preset'] == preset ? 0 : 1));
    if (free.isEmpty) return false;
    slots[free.first] = {'preset': preset, 'live': true};
    json['extraAreas'] = slots;
    return true;
  }

  /// GTA 6 için tek dokunuş: altyazı otomatik, alttaki görev yazısı, ipucu kutusu ve telefon mesajı okunur.
  void applyGta6Reading() {
    json['autoRegion'] = true;
    json['extraAreas'] = [
      for (final preset in const ['bottom_objective', 'help_box', 'phone_message']) {'preset': preset, 'live': true},
    ];
  }

  AppSettings copy() => AppSettings(jsonDecode(jsonEncode(json)) as Map<String, dynamic>);
}

/// "Bu telefonda göster" ekranına gelen altyazı olayı: TV'ye giden `subtitle` ya da `clear` ile aynı anda gelir.
class SubtitleEvent {
  const SubtitleEvent.show(this.text, {this.durationMs = 15000, this.replace = false}) : clear = false;
  const SubtitleEvent.clear()
      : clear = true,
        text = '',
        durationMs = 0,
        replace = false;

  /// Satır ekrandan kalktı.
  final bool clear;
  final String text;

  /// Satırın ekranda kalacağı en uzun süre.
  final int durationMs;

  /// Aynı cümlenin uzamış okuması: önceki satırın yerine geçer.
  final bool replace;

  factory SubtitleEvent.fromMap(Map<Object?, Object?> map) => map['type'] == 'subtitle'
      ? SubtitleEvent.show(
          map['text'] as String? ?? '',
          durationMs: (map['duration'] as num?)?.toInt() ?? 15000,
          replace: map['replace'] as bool? ?? false,
        )
      : const SubtitleEvent.clear();
}

/// İkinci ekranın hatırlanan ayarları: yazı ölçeği ve "önceki satır".
class ScreenPrefs {
  const ScreenPrefs({this.scale = 1, this.previous = true});

  final double scale;
  final bool previous;
}

/// Ekran olarak bağlanılacak GameTranslator sunucusu (bilgisayar). [active]: çeviri şu an çalışıyor.
class DisplayServer {
  const DisplayServer(this.host, this.port, {this.active = false});

  final String host;
  final int port;
  final bool active;
}

/// Ağdaki arama sonucu: [server] yoksa bulunamadı; [wifi] yanlışsa telefon bir ağa bağlı değil.
class DisplaySearch {
  const DisplaySearch(this.server, {this.wifi = true});

  final DisplayServer? server;
  final bool wifi;
}

/// Altyazı geçmişindeki bir satır (Android tarafındaki core/SubtitleHistory.kt; Windows'taki geçmişle aynı kurallar).
class HistoryEntry {
  const HistoryEntry({required this.source, required this.translation, required this.date, this.fromPack = false});

  final String source;
  final String translation;

  /// Satırın çevrildiği an (yerel saat).
  final DateTime date;

  /// Satır oyun paketinden mi geldi (insan çevirisi), yoksa çeviri motorundan mı.
  final bool fromPack;

  factory HistoryEntry.fromMap(Map<Object?, Object?> map) => HistoryEntry(
        source: map['source'] as String? ?? '',
        translation: map['translation'] as String? ?? '',
        date: DateTime.fromMillisecondsSinceEpoch((map['date'] as num?)?.toInt() ?? 0),
        fromPack: map['fromPack'] as bool? ?? false,
      );

  /// Saat: bugünse "HH:mm:ss", değilse "gg.AA HH:mm" (Windows'taki gibi).
  String timeLabel([DateTime? now]) {
    final today = now ?? DateTime.now();
    String two(int value) => value.toString().padLeft(2, '0');
    final clock = '${two(date.hour)}:${two(date.minute)}';
    final sameDay = date.year == today.year && date.month == today.month && date.day == today.day;
    return sameDay ? '$clock:${two(date.second)}' : '${two(date.day)}.${two(date.month)} $clock';
  }

  /// Kopyalamak için düz metin: saat, çeviri ve altında kaynak satır (Windows'taki SubtitleHistory.PlainText).
  static String plainText(Iterable<HistoryEntry> entries) => entries.map((entry) {
        final source = entry.source.replaceAll('\n', ' ');
        return '[${entry.timeLabel()}] ${entry.translation}\n$source';
      }).join('\n\n');
}

/// Android tarafındaki "gt/bridge", "gt/events" ve "gt/subtitles" kanalları.
class Bridge {
  static const _channel = MethodChannel('gt/bridge');
  static const _events = EventChannel('gt/events');
  static const _subtitles = EventChannel('gt/subtitles');

  /// Tek bir ortak akış: birden çok ekran dinleyebilir (ana ekran ve "Bu telefonda göster"). Her dinleyiciye ayrı akış
  /// açılsaydı birinin kapanması Android tarafındaki tek dinleyiciyi de kapatırdı.
  static final Stream<EngineState> states =
      _events.receiveBroadcastStream().map((event) => EngineState.fromMap(event as Map<Object?, Object?>));

  /// Altyazı olayları (göster ve sil), bekletilmeden. Dinlemeye başlayınca ekranda duran satır varsa o da gelir.
  static final Stream<SubtitleEvent> subtitles =
      _subtitles.receiveBroadcastStream().map((event) => SubtitleEvent.fromMap(event as Map<Object?, Object?>));

  // --- İkinci ekran ---

  /// Ekran açık kalsın (FLAG_KEEP_SCREEN_ON); sayfadan çıkarken kapatılır.
  static Future<void> keepScreenOn(bool on) => _channel.invokeMethod('keepScreenOn', on);

  static Future<ScreenPrefs> screenPrefs() async {
    final map = (await _channel.invokeMethod<Map<Object?, Object?>>('getScreenPrefs'))!;
    return ScreenPrefs(scale: (map['scale'] as num?)?.toDouble() ?? 1, previous: map['previous'] as bool? ?? true);
  }

  static Future<void> saveScreenPrefs({double? scale, bool? previous}) =>
      _channel.invokeMethod('setScreenPrefs', {'scale': ?scale, 'previous': ?previous});

  static DisplayServer? _displayServer(Map<Object?, Object?> map) => map['found'] == true
      ? DisplayServer(map['host'] as String, (map['port'] as num).toInt(), active: map['active'] as bool? ?? false)
      : null;

  /// Aynı Wi-Fi'de GameTranslator sunucusu arar (birkaç saniye): çeviri yapan öncelikli, yoksa bulunan herhangi biri.
  static Future<DisplaySearch> findDisplayServer() async {
    final map = (await _channel.invokeMethod<Map<Object?, Object?>>('findDisplayServer'))!;
    return DisplaySearch(_displayServer(map), wifi: map['wifi'] as bool? ?? true);
  }

  /// Elle yazılan adresi dener. Adres yerel ağ adresi değilse [FormatException] atar; sunucu yoksa null döner.
  static Future<DisplayServer?> probeDisplayServer(String address) async {
    final map = (await _channel.invokeMethod<Map<Object?, Object?>>('probeDisplayServer', address))!;
    if (map['valid'] != true) throw const FormatException('invalid address');
    return _displayServer(map);
  }

  /// Sunucunun /ekran sayfasını telefonun kendi adresinden veren köprüyü açar; dinlenen portu döner
  /// (uygulama içi tarayıcı http://127.0.0.1:port/ekran adresini açar).
  static Future<int> openDisplayRelay(DisplayServer server) async =>
      (await _channel.invokeMethod<int>('openDisplayRelay', {'host': server.host, 'port': server.port}))!;

  static Future<void> closeDisplayRelay() => _channel.invokeMethod('closeDisplayRelay');

  static Future<EngineState> state() async =>
      EngineState.fromMap((await _channel.invokeMethod<Map<Object?, Object?>>('getState'))!);

  static Future<SetupInfo> setup() async {
    final map = (await _channel.invokeMethod<Map<Object?, Object?>>('getSetup'))!;
    return SetupInfo(
      hasAccount: map['hasAccount'] as bool? ?? false,
      consoleHost: map['consoleHost'] as String?,
      consoleName: map['consoleName'] as String?,
      introSeen: map['introSeen'] as bool? ?? false,
      version: map['version'] as String? ?? '',
      isXbox: map['consoleKind'] == 'xbox',
      xboxSignedIn: map['xboxSignedIn'] as bool? ?? false,
      xboxConsoleName: map['xboxConsoleName'] as String?,
    );
  }

  // --- Konsol türü ve Xbox (Beta) ---

  /// Görüntünün alınacağı konsol: PlayStation (varsayılan) ya da Xbox.
  static Future<void> setConsoleKind({required bool xbox}) => _channel.invokeMethod('setConsoleKind', xbox ? 'xbox' : 'ps5');

  /// Microsoft girişini başlatır; başarısızsa [XboxFailure] atar.
  static Future<XboxSignInCode> xboxStartSignIn() async {
    final map = (await _channel.invokeMethod<Map<Object?, Object?>>('xboxStartSignIn'))!;
    if (map['ok'] != true) throw XboxFailure(map['error'] as String? ?? '@xs_start_failed');
    return XboxSignInCode(map['userCode'] as String, map['verificationUri'] as String? ?? 'https://www.microsoft.com/link');
  }

  /// Kod Microsoft'un sayfasında onaylanana kadar bekler. true: giriş tamam; false: iptal edildi.
  /// Kodun süresi dolarsa ya da giriş reddedilirse [XboxFailure] atar.
  static Future<bool> xboxAwaitSignIn() async {
    final map = (await _channel.invokeMethod<Map<Object?, Object?>>('xboxAwaitSignIn'))!;
    if (map['ok'] == true) return true;
    if (map['cancelled'] == true) return false;
    throw XboxFailure(map['error'] as String? ?? '@xs_finish_failed');
  }

  static Future<void> xboxCancelSignIn() => _channel.invokeMethod('xboxCancelSignIn');

  static Future<XboxConsolesResult> xboxConsoles() async {
    final map = (await _channel.invokeMethod<Map<Object?, Object?>>('xboxConsoles'))!;
    if (map['ok'] != true) {
      return XboxConsolesResult(const [], map['error'] as String? ?? '@xs_list_failed', map['needsSignIn'] as bool? ?? false);
    }
    final list = (map['consoles'] as List<Object?>? ?? const []).map((item) {
      final console = item as Map<Object?, Object?>;
      return XboxConsoleInfo(
        id: console['id'] as String,
        name: console['name'] as String? ?? '',
        model: console['model'] as String? ?? '',
        streamingEnabled: console['streamingEnabled'] as bool? ?? false,
        powerState: console['powerState'] as String? ?? '',
      );
    }).toList();
    return XboxConsolesResult(list, null, false);
  }

  static Future<void> xboxSelectConsole(XboxConsoleInfo console) => _channel.invokeMethod('xboxSelectConsole', {
        'id': console.id,
        'name': console.name,
        'model': console.model,
        'streamingEnabled': console.streamingEnabled,
        'powerState': console.powerState,
      });

  /// Seçili Xbox'ı unutur (giriş kalır); kurulum Xbox seçimine döner.
  static Future<void> xboxForgetConsole() => _channel.invokeMethod('xboxForgetConsole');

  /// Microsoft hesabından çıkar: giriş anahtarı telefondan silinir, seçili Xbox unutulur.
  static Future<void> xboxSignOut() => _channel.invokeMethod('xboxSignOut');

  static Future<AppSettings> settings() async =>
      AppSettings(jsonDecode((await _channel.invokeMethod<String>('getSettings'))!) as Map<String, dynamic>);

  /// Çeviri dilleri (ad, yön, hangi motor çevirebiliyor) ve oyun dili seçenekleri; tek tanım Android tarafındadır
  /// (core/Languages.kt).
  static Future<Map<Object?, Object?>> languages() async =>
      await _channel.invokeMethod<Map<Object?, Object?>>('getLanguages') ?? const {};

  static Future<void> saveSettings(AppSettings settings) => _channel.invokeMethod('setSettings', jsonEncode(settings.json));

  /// [accountId]: 8 baytlık hesap kimliği.
  static Future<void> setAccountId(Uint8List accountId) => _channel.invokeMethod('setAccountId', base64Encode(accountId));

  static Future<void> setIntroSeen(bool seen) => _channel.invokeMethod('setIntroSeen', seen);

  /// Sürüm duyurusu [id] gösterildi mi (her duyuru bir kez).
  static Future<bool> noticeSeen(String id) async => await _channel.invokeMethod<bool>('noticeSeen', id) ?? false;
  static Future<void> setNoticeSeen(String id) => _channel.invokeMethod('setNoticeSeen', id);
  static Future<void> forgetAccount() => _channel.invokeMethod('forgetAccount');
  static Future<void> forgetConsole() => _channel.invokeMethod('forgetConsole');

  static Future<List<FoundConsole>> discover({List<String> hosts = const [], int timeoutMs = 3000}) async {
    final list = await _channel.invokeMethod<List<Object?>>('discover', {'hosts': hosts, 'timeoutMs': timeoutMs}) ?? [];
    return list.map((item) {
      final map = item as Map<Object?, Object?>;
      return FoundConsole(map['host'] as String, map['name'] as String? ?? 'PS5', map['awake'] as bool? ?? false);
    }).toList();
  }

  static Future<RegisterResult> register(String host, int pin) async {
    final map = (await _channel.invokeMethod<Map<Object?, Object?>>('register', {'host': host, 'pin': pin}))!;
    return RegisterResult(map['ok'] as bool? ?? false, map['name'] as String?, map['detail'] as String?);
  }

  static Future<void> start() => _channel.invokeMethod('start');
  static Future<void> stop() => _channel.invokeMethod('stop');
  static Future<void> testSubtitle() => _channel.invokeMethod('testSubtitle');
  static Future<Uint8List?> snapshot({int width = 960}) => _channel.invokeMethod<Uint8List>('snapshot', width);

  /// Altyazı geçmişi, en yenisi başta.
  static Future<List<HistoryEntry>> history() async => (await _channel.invokeMethod<List<Object?>>('getHistory') ?? [])
      .map((item) => HistoryEntry.fromMap(item as Map<Object?, Object?>))
      .toList();

  /// Geçmişi siler (telefondaki dosya da boşalır).
  static Future<void> clearHistory() => _channel.invokeMethod('clearHistory');

  static Future<List<String>> logs() async =>
      (await _channel.invokeMethod<List<Object?>>('logs') ?? []).cast<String>();
}
