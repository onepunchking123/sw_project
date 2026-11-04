import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 에뮬레이터끼리는 서로 직접 닿지 않아 개발용 TURN 중계가 필요하다. 값은 git에 올리지 않는 local.properties에 둔다.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun devTurn(key: String): String = "\"" + localProps.getProperty("careguard.devTurn.$key", "") + "\""

android {
    namespace = "com.example.careguardcamera"
    compileSdk = 35 //34->35수정

    defaultConfig {
        applicationId = "com.example.careguardcamera"
        minSdk = 24
        targetSdk = 35 //34->35수정
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_TURN_URL", devTurn("url"))
            buildConfigField("String", "DEV_TURN_USERNAME", devTurn("username"))
            buildConfigField("String", "DEV_TURN_PASSWORD", devTurn("password"))
        }
        release {
            buildConfigField("String", "DEV_TURN_URL", "\"\"")
            buildConfigField("String", "DEV_TURN_USERNAME", "\"\"")
            buildConfigField("String", "DEV_TURN_PASSWORD", "\"\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // 의존성 추가
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // 1. CameraX 라이브러리
    val camerax_version = "1.3.1"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")

    implementation("androidx.camera:camera-view:1.3.1")
    // 2. 네트워크 및 WebSocket 통신용 OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WebRTC transport spike: prefixed namespace is livekit.org.webrtc.
    implementation("io.github.webrtc-sdk:android-prefixed:150.7871.01")

    //오류나서
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // ONNX Runtime Android (GRU 및 YOLO 모델 추론)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // 비동기 처리
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // 보호자 긴급 화면의 현장 클립 재생
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
}
