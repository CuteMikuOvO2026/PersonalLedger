plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    buildFeatures {
        viewBinding = true
    }

    namespace = "com.example.personalledger"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.personalledger"
        minSdk = 24
        targetSdk = 36
        // versionCode 从 1.0 起一直是 1：本应用不通过应用商店分发，升级靠覆盖安装，
        // 系统不校验 versionCode，因此版本一律由 versionName 与 git tag 标识。
        // 将来若要上架，再从这里开始逐次递增。
        versionCode = 1
        versionName = "2.5.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests {
            // 被测代码里个别路径会碰到 android.util.Log。让它返回默认值而不是抛
            // 「not mocked」，这样纯 JVM 测试不必为了打一行日志而引入 Robolectric。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.gson)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // 纯 JVM 单元测试：被测对象都是不依赖 Android 框架的纯函数
    // （重试策略、模型输出解析、评分口径、统计换算）。
    // 刻意不引入 Robolectric / Mockito —— 离线环境取不到，且这些逻辑本就不需要它们。
    testImplementation(libs.junit)
}
