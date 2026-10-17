plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.ultrastream.tv"
 compileSdk = 35
 defaultConfig { applicationId = "com.ultrastream.tv"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.2.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("io.github.webrtc-sdk:android:150.7871.01")
 implementation("org.java-websocket:Java-WebSocket:1.5.7")
}
