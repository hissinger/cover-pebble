plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.woody.cremacover"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.woody.cremacover"
        minSdk = 26
        // 크레마 페블(Android 11)의 내장메모리 루트(/sdcard/sleep 등)에 직접 쓰기 위해
        // requestLegacyExternalStorage 가 유효한 29 를 타깃으로 한다. (사이드로딩 전용 앱)
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 29
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 개인 기기 사이드로딩용이라 디버그 키로 서명한다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 빌드 결과 APK 이름에 앱 이름과 버전을 넣는다. 예: CoverPebble-1.0.0-release.apk
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "CoverPebble-${versionName}-${buildType.name}.apk"
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)
}
