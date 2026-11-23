// SPDX-License-Identifier: AGPL-3.0-only
// Telefondan TV uygulaması kurma: Android tarafındaki tvinstall/TvInstallChannel ile konuşan köprü ve hata metinleri.
import 'package:flutter/services.dart';

import 'l10n.dart';

enum TvKind { lg, samsung, android }

extension TvKindText on TvKind {
  String get id => name;

  String get title => switch (this) {
        TvKind.lg => 'LG',
        TvKind.samsung => 'Samsung',
        TvKind.android => 'Android TV / Google TV',
      };
}

TvKind? tvKindOf(String? id) => switch (id) {
      'lg' => TvKind.lg,
      'samsung' => TvKind.samsung,
      'android' => TvKind.android,
      _ => null,
    };

class FoundTv {
  const FoundTv(this.kind, this.host, this.name, this.ready);

  final TvKind kind;
  final String host;
  final String name;

  /// Kurulum portu açık: LG'de geliştirici modu, Android'de hata ayıklama.
  final bool ready;
}

class SavedTv {
  const SavedTv(this.kind, this.host, this.name, this.version);

  final TvKind kind;
  final String host;
  final String name;
  final String? version;
}

class TvCheck {
  const TvCheck(this.ready, this.keyServer, this.hasKey,
      {this.signedIn = false, this.reachable = false, this.developerMode, this.developerIp, this.hostIpMatches, this.year});

  /// Kurulum bağlantısı hazır (Samsung: sdb bu telefondan kabul ediliyor).
  final bool ready;
  final bool keyServer;

  /// LG: bu TV'nin anahtarı kayıtlı, parola yeniden sorulmaz. Samsung: bu TV'nin sertifikası kayıtlı, giriş gerekmez.
  final bool hasKey;

  /// Samsung: bu oturumda Samsung hesabıyla giriş yapıldı.
  final bool signedIn;

  /// Samsung: TV'nin bilgi arayüzü (8001) yanıt verdi.
  final bool reachable;

  /// Samsung: TV'nin bildirdiği geliştirici modu ve "Host PC IP" (bilinmiyorsa null).
  final bool? developerMode;
  final String? developerIp;
  final bool? hostIpMatches;

  /// Samsung: model kodundaki yıl.
  final int? year;
}

class TvInstallResult {
  const TvInstallResult({required this.ok, this.version, this.installed = false, this.launched = false, this.hoursLeft, this.error, this.detail});

  final bool ok;
  final String? version;

  /// false: TV'deki sürüm zaten günceldi, yalnız açıldı.
  final bool installed;
  final bool launched;

  /// LG geliştirici modunun kalan süresi (saat).
  final int? hoursLeft;
  final String? error;
  final String? detail;
}

class TvBridge {
  static const _channel = MethodChannel('gt/tv');
  static const _events = EventChannel('gt/tv/events');

  /// Kurulum aşamaları: connecting, approve, checking, key, `uploading:yüzde`, installing, verifying, launching.
  static Stream<String> get stages => _events.receiveBroadcastStream().map((event) => ((event as Map)['stage'] as String?) ?? '');

  /// Uygulamayla gelen TV paketlerinin sürümleri (marka → sürüm).
  static Future<Map<TvKind, String>> bundled() async {
    final map = await _channel.invokeMethod<Map<Object?, Object?>>('bundled') ?? {};
    final result = <TvKind, String>{};
    for (final entry in map.entries) {
      final kind = tvKindOf(entry.key as String?);
      if (kind != null) result[kind] = entry.value as String;
    }
    return result;
  }

  static Future<String?> phoneAddress() => _channel.invokeMethod<String>('phoneAddress');

  static Future<SavedTv?> saved() async {
    final map = await _channel.invokeMethod<Map<Object?, Object?>>('saved');
    final kind = tvKindOf(map?['brand'] as String?);
    if (map == null || kind == null) return null;
    return SavedTv(kind, map['host'] as String? ?? '', map['name'] as String? ?? '', map['version'] as String?);
  }

  static Future<void> forget() => _channel.invokeMethod('forget');

  /// Elle yazılan adresi temizler; adres değilse null.
  static Future<String?> normalize(String text) => _channel.invokeMethod<String>('normalize', text);

  static Future<List<FoundTv>> find(TvKind kind) async {
    final list = await _channel.invokeMethod<List<Object?>>('find', kind.id) ?? [];
    return [
      for (final item in list.cast<Map<Object?, Object?>>())
        FoundTv(kind, item['host'] as String, item['name'] as String? ?? kind.title, item['ready'] as bool? ?? false),
    ];
  }

  static Future<TvCheck> check(TvKind kind, String host) async {
    final map = await _channel.invokeMethod<Map<Object?, Object?>>('check', {'brand': kind.id, 'host': host}) ?? {};
    return TvCheck(
      map['ready'] as bool? ?? false,
      map['keyServer'] as bool? ?? false,
      map['hasKey'] as bool? ?? false,
      signedIn: map['signedIn'] as bool? ?? false,
      reachable: map['reachable'] as bool? ?? false,
      developerMode: map['developerMode'] as bool?,
      developerIp: map['developerIp'] as String?,
      hostIpMatches: map['hostIpMatches'] as bool?,
      year: (map['year'] as num?)?.toInt(),
    );
  }

  /// Samsung girişini başlatır: telefonda geri dönüş dinleyicisi açılır, giriş sayfasının adresi döner (açılamazsa null).
  static Future<String?> samsungSignIn() => _channel.invokeMethod<String>('samsungSignIn');

  /// Giriş sayfası Samsung'un jetonunu gönderene kadar bekler. Başarılıysa ok ve e-posta.
  static Future<({bool ok, String? email, String? error, String? detail})> samsungSignInWait() async {
    final map = await _channel.invokeMethod<Map<Object?, Object?>>('samsungSignInWait') ?? {};
    return (
      ok: map['ok'] as bool? ?? false,
      email: map['email'] as String?,
      error: map['error'] as String?,
      detail: map['detail'] as String?,
    );
  }

  static Future<void> samsungSignInAbort() => _channel.invokeMethod('samsungSignInAbort');

  /// Adresi telefonun tarayıcısında açar.
  static Future<bool> openUrl(String url) async => await _channel.invokeMethod<bool>('openUrl', url) ?? false;

  static Future<TvInstallResult> install(TvKind kind, String host, {String name = '', String? passphrase, bool force = false}) async {
    final map = await _channel.invokeMethod<Map<Object?, Object?>>(
          'install',
          {'brand': kind.id, 'host': host, 'name': name, 'passphrase': passphrase, 'force': force},
        ) ??
        {};
    return TvInstallResult(
      ok: map['ok'] as bool? ?? false,
      version: map['version'] as String?,
      installed: map['installed'] as bool? ?? false,
      launched: map['launched'] as bool? ?? false,
      hoursLeft: (map['hoursLeft'] as num?)?.toInt(),
      error: map['error'] as String?,
      detail: map['detail'] as String?,
    );
  }

  static Future<void> cancel() => _channel.invokeMethod('cancel');
}

/// Hatanın sade dilde açıklaması ve çözümü (Windows'taki TvAppCard.Message ile aynı durumlar).
({String title, String fix}) tvErrorText(String? error, TvKind kind) {
  final id = switch (error) {
    'UNREACHABLE' => switch (kind) {
        TvKind.samsung => 'UNREACHABLE_SAMSUNG',
        TvKind.android => 'UNREACHABLE_ANDROID',
        TvKind.lg => 'UNREACHABLE',
      },
    'TOO_OLD' => kind == TvKind.samsung ? 'TOO_OLD_SAMSUNG' : 'TOO_OLD',
    'WRONG_HOST_IP' ||
    'SIGN_IN_FAILED' ||
    'SIGN_IN_NEEDED' ||
    'CERTIFICATE_FAILED' ||
    'KEY_SERVER_OFF' ||
    'WRONG_PASSPHRASE' ||
    'KEY_REFUSED' ||
    'NOT_APPROVED' ||
    'NOT_ENOUGH_SPACE' ||
    'CONNECTION_LOST' ||
    'PACKAGE_MISSING' ||
    'CANCELLED' =>
      error!,
    _ => 'OTHER',
  };
  return (title: t('tv.err.$id.title'), fix: t('tv.err.$id.fix'));
}

const _stages = {
  'connecting', 'approve', 'checking', 'key', 'reading', 'certificate', 'signing', 'uninstalling', 'installing', 'verifying', 'launching', //
};

/// Kurulum aşamasının arayüz dilindeki adı.
String tvStageText(String stage) {
  if (stage.startsWith('uploading:')) return t('tv.stage.uploading', [stage.substring(10)]);
  return _stages.contains(stage) ? t('tv.stage.$stage') : t('tv.stage.preparing');
}

/// [a] sürümü [b]'den yeni mi ("1.0.12" > "1.0.9").
bool isNewerVersion(String a, String b) {
  List<int> parts(String v) => v.split(RegExp(r'[.-]')).map((p) => int.tryParse(p) ?? 0).toList();
  final x = parts(a), y = parts(b);
  for (var i = 0; i < (x.length > y.length ? x.length : y.length); i++) {
    final l = i < x.length ? x[i] : 0, r = i < y.length ? y[i] : 0;
    if (l != r) return l > r;
  }
  return false;
}

/// Kayıtlı TV'nin uygulaması telefondaki paketten eskiyse (kurulu, yeni) sürümler; değilse null.
Future<(String, String)?> tvUpdateAvailable() async {
  try {
    final tv = await TvBridge.saved();
    final installed = tv?.version;
    if (tv == null || installed == null) return null;
    final bundled = (await TvBridge.bundled())[tv.kind];
    if (bundled == null || !isNewerVersion(bundled, installed)) return null;
    return (installed, bundled);
  } catch (_) {
    return null;
  }
}
