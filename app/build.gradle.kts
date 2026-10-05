plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The server the app defaults to. Override for off-campus testing:
//   ./gradlew assembleDebug -PserverUrl=http://10.0.2.2:8080
val defaultServerUrl: String =
    (
        project.findProperty("serverUrl")?.toString()
            ?: project.findProperty("pmsBaseUrl")?.toString()
            ?: "https://pms.sustech.edu.cn"
        ).trim()

android {
    namespace = "edu.sustech.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "edu.sustech.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 25
        versionName = "0.3.22-loans"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServerUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 原生生成二维码（校园卡）
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
