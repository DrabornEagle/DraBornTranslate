// SPDX-License-Identifier: AGPL-3.0-only
// Mağaza görselleri: integration_test'in ekran görüntülerini SHOTS_DIR klasörüne yazar.
import 'dart:io';

import 'package:integration_test/integration_test_driver_extended.dart';

Future<void> main() async {
  final dir = Platform.environment['SHOTS_DIR'] ?? 'build/store_shots';
  await integrationDriver(onScreenshot: (name, bytes, [args]) async {
    final file = File('$dir/$name.png');
    await file.parent.create(recursive: true);
    await file.writeAsBytes(bytes);
    return true;
  });
}
