// SPDX-License-Identifier: AGPL-3.0-only

import CoreGraphics
import Foundation
import GameTranslatorCore

/// Konsol oturumu yerine görüntüyü veren dış kaynak (Mac: yakalama kartı, pencere, ekranın tamamı). Platformdan
/// bağımsızdır; Mac'teki uygulamaları AVFoundation ve ScreenCaptureKit ile yazılır.
///
/// Motor `Engine.start(source:)` ile açılınca `start(into:)`'yu denetim kuyruğunda çağırır. Kaynak kareleri
/// `Engine.submit(frame:)` ile verir (ilk kare evreyi "streaming" yapar); hata olursa
/// `Engine.sourceFailed(_:willRetry:)` çağırır. Çeviri durunca motor `stop()`'u çağırır.
public protocol FrameSource: AnyObject, Sendable {
    /// "connecting" evresinin iletisi (kartın, pencerenin ya da ekranın adı).
    var label: String { get }
    func start(into engine: Engine)
    func stop()
}

/// Ek alan kutularının olayları (Mac ekranındaki katman için; TV'ye giden `region` çerçevelerinin aynısı).
public enum RegionEvent: Equatable, Sendable {
    /// `text` boşsa o alanın kutusu kalkar.
    case show(id: String, text: String, box: TextBox?, lineHeight: Double?)
    case clearAll
}

extension TextBox {
    /// Altyazı olayındaki "box" alanı (kenarlara oranla).
    var eventMap: [String: Double] { ["left": left, "top": top, "right": right, "bottom": bottom] }

    /// Altyazı olayının "box" alanından kutu; yoksa ya da bozuksa nil.
    public static func fromEvent(_ value: Any?) -> TextBox? {
        guard let map = value as? [String: Any],
              let left = (map["left"] as? NSNumber)?.doubleValue, let top = (map["top"] as? NSNumber)?.doubleValue,
              let right = (map["right"] as? NSNumber)?.doubleValue, let bottom = (map["bottom"] as? NSNumber)?.doubleValue
        else { return nil }
        return TextBox(left: left, top: top, right: right, bottom: bottom)
    }
}
