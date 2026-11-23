import Flutter
import GTNative
import UIKit

class SceneDelegate: FlutterSceneDelegate {
  override func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
    super.scene(scene, willConnectTo: session, options: connectionOptions)
    // Apple Çeviri iOS 18–25'te bir SwiftUI görünümü ister; pencere hazır olunca eklenir.
    DispatchQueue.main.async { [weak self] in TranslationHost.install(in: self?.window) }
  }

  // Arka plandan dönünce kopmuş PS5 oturumu hemen yeniden kurulur.
  override func sceneWillEnterForeground(_ scene: UIScene) {
    super.sceneWillEnterForeground(scene)
    Engine.shared.resumed()
  }

  override func sceneDidEnterBackground(_ scene: UIScene) {
    super.sceneDidEnterBackground(scene)
    Engine.shared.suspended()
  }
}
