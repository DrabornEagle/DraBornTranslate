import java.security.MessageDigest

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Oyun paketleri (bilgisayar uygulamasıyla aynı dosyalar) ve derlemede kopyalandıkları yer.
val gamePacks: File = rootProject.file("../../windows/src/GameTranslator.Core/Resources/packs")
val gamePackAssets: File = layout.buildDirectory.dir("generated/gt-packs").get().asFile
// İkinci ekran sayfası (/ekran): bilgisayar uygulamasının sunduğu dosyanın kendisi; derlemede kopyalanır (copySecondScreen).
val secondScreenPage: File = rootProject.file("../../windows/src/GameTranslator.Core/Resources/second-screen.html")
val secondScreenAssets: File = layout.buildDirectory.dir("generated/gt-screen").get().asFile
// Altyazı fontu (Quicksand Bold, SIL OFL): bilgisayardakiyle aynı dosya; TV style olayındaki fontUrl'den indirir.
val subtitleFont: File = rootProject.file("../../windows/src/GameTranslator.Core/Resources/fonts/Quicksand-Bold.ttf")
// İngilizce sözcük listesi (okuma onarımı ve okumaların birleştirilmesi için): bilgisayar uygulamasındaki dosyanın kendisi;
// derlemede kopyalanır (copyEnglishWords).
val englishWords: File = rootProject.file("../../windows/src/GameTranslator.App/Assets/english-words.txt.gz")
val englishWordsAssets: File = layout.buildDirectory.dir("generated/gt-words").get().asFile

android {
    namespace = "com.gametranslator.gametranslator_mobile"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    // Tarayıcı/OBS altyazı sayfası bütün platformlarda aynı dosyadır.
    // Oyun paketleri bilgisayar uygulamasıyla aynı dosyalardır (windows/.../Resources/packs); depoda iki kez durmasınlar
    // diye derlemede assets/packs altına kopyalanır (bkz. copyGamePacks).
    sourceSets["main"].assets.srcDir(gamePackAssets)
    // İkinci ekran sayfası da bilgisayardakiyle aynı dosyadır; iki kopya ayrışmasın diye depoda bir kez durur.
    sourceSets["main"].assets.srcDir(secondScreenAssets)
    // İngilizce sözcük listesi de bilgisayardakiyle aynı dosyadır (copyEnglishWords).
    sourceSets["main"].assets.srcDir(englishWordsAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.gametranslator.gametranslator_mobile"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        externalNativeBuild {
            cmake {
                // nanopb üreteci için Python (protobuf + grpcio-tools kurulu olmalı): -PgtPython=... ya da GT_PYTHON.
                val python = (project.findProperty("gtPython") as String?) ?: System.getenv("GT_PYTHON")
                if (python != null) arguments += "-DPYTHON_EXECUTABLE=$python"
                val protoc = (project.findProperty("gtProtoc") as String?) ?: System.getenv("GT_PROTOC")
                if (protoc != null) arguments += "-DPROTOC=$protoc"
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    // libchiaki'nin kullandığı OpenSSL (hazır derlenmiş, prefab).
    implementation("com.android.ndk.thirdparty:openssl:1.1.1q-beta-1")
    // Cihaz üstünde yazı tanıma (Latin) ve çevrimdışı çeviri.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // LG TV'ye geliştirici modu SSH'ı ile kurulum (BSD lisanslı JSch çatalı).
    implementation("com.github.mwiede:jsch:0.2.20")
    // Xbox ev yayını (Beta): Google libwebrtc'nin Android derlemesi (webrtc-sdk, BSD-3). Görüntü donanım çözücüsüyle
    // alınır, kareler PS5 kareleriyle aynı okuma hattına gider (xbox/XboxStreamSession.kt).
    implementation("io.github.webrtc-sdk:android:144.7559.15")
    testImplementation("junit:junit:4.13.2")
    // android.jar'daki org.json yalnız iskelettir; JVM testleri gerçeğini kullanır.
    testImplementation("org.json:json:20240303")
}

// AGP .gz varlıkları açıp APK'ya sıkıştırılmış JSON olarak koyar (packs/<id>.json; GamePackLibrary ikisini de okur).
// Sunucu .gz dosyasının SHA-256'sını verdiği için uygulamadaki paketlerin özetleri burada, asıl dosyadan yazılır
// (packs-sha256.txt); PackStore aynı paketi yeniden indirmez.
val copyGamePacks by tasks.registering(Copy::class) {
    from(gamePacks) { include("*.json.gz") }
    into(File(gamePackAssets, "packs"))
    doLast {
        val digest = { file: File ->
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { b -> "%02x".format(b) }
        }
        val lines = gamePacks.listFiles { f -> f.name.endsWith(".json.gz") }!!.sortedBy { it.name }.map { "${digest(it)} ${it.name}" }
        File(gamePackAssets, "packs-sha256.txt").writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
tasks.named("preBuild") { dependsOn(copyGamePacks) }

// Telefonun altyazı sunucusu /ekran adresinde bilgisayardakiyle aynı sayfayı verir (assets/second-screen.html).
val copySecondScreen by tasks.registering(Copy::class) {
    from(secondScreenPage)
    from(subtitleFont)
    // Tarayıcı/OBS altyazı sayfası bütün platformlarda aynı dosyadır (Swift paketindeki kopya); paketin öteki
    // kaynakları (sözcük listesi, font) burada ayrıca kopyalandığı için klasörün tamamı alınmaz.
    from(rootProject.file("../../core/Sources/GameTranslatorCore/Resources/obs-overlay.html"))
    into(secondScreenAssets)
    doFirst { check(secondScreenPage.isFile) { "İkinci ekran sayfası bulunamadı: $secondScreenPage" } }
}
tasks.named("preBuild") { dependsOn(copySecondScreen) }

// Okuma onarımının sözcük listesi (assets/english-words.txt.gz; AGP açıp koyabilir, EnglishWords ikisini de okur).
val copyEnglishWords by tasks.registering(Copy::class) {
    from(englishWords)
    into(englishWordsAssets)
    doFirst { check(englishWords.isFile) { "İngilizce sözcük listesi bulunamadı: $englishWords" } }
}
tasks.named("preBuild") { dependsOn(copyEnglishWords) }

// Testler gömülü paketleri depodaki yerinden okur.
tasks.withType<Test>().configureEach {
    systemProperty("gametranslator.packs", gamePacks.absolutePath)
    systemProperty("gametranslator.secondScreen", secondScreenPage.absolutePath)
    systemProperty("gametranslator.englishWords", englishWords.absolutePath)
    maxHeapSize = "2g"
}
