plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pcylove.idleauto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pcylove.idleauto"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 서명 설정이 없으면 release 는 서명되지 않은 APK 가 나와요. 평소에는 debug 빌드를 설치하면 돼요.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// 외부 라이브러리 없이 안드로이드 기본 API 만 써요. 그래서 의존성이 비어 있어요.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
