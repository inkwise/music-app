plugins {
    alias(libs.plugins.android.application)  // ← 必须有这个！它是 android {} 的来源
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.dagger.hilt.android") version "2.59"
    alias(libs.plugins.ksp)  // 如果你已经加了 KSP
}

hilt {
    enableAggregatingTask = false
}
android {
    namespace = "com.inkwise.music"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.inkwise.music"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
        }
    }
    lint {
        checkReleaseBuilds = false
    }

    val releaseStoreFile =
        project.findProperty("RELEASE_STORE_FILE") as String?
            ?: error("RELEASE_STORE_FILE not found")

    val releaseStorePassword =
        project.findProperty("RELEASE_STORE_PASSWORD") as String?
            ?: error("RELEASE_STORE_PASSWORD not found")

    val releaseKeyAlias =
        project.findProperty("RELEASE_KEY_ALIAS") as String?
            ?: error("RELEASE_KEY_ALIAS not found")

    val releaseKeyPassword =
        project.findProperty("RELEASE_KEY_PASSWORD") as String?
            ?: error("RELEASE_KEY_PASSWORD not found")

    signingConfigs {
        create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {


        release {
        	//签名
        	signingConfig = signingConfigs.getByName("release")
            // 关闭代码混淆/压缩
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.navigation:navigation-runtime-ktx:2.7.7")

    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    //图片
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("jp.wasabeef:glide-transformations:4.3.0")
    implementation("io.coil-kt:coil-compose:2.6.0")


	// Hilt runtime
    implementation("com.google.dagger:hilt-android:2.59.2")  // 匹配 hilt 版本
    // Hilt compiler
    ksp("com.google.dagger:hilt-compiler:2.59.2")   // 注意：是 hilt-compiler，不是 hilt-android-compiler
	implementation ("androidx.hilt:hilt-navigation-compose:1.2.0" )

	implementation ("androidx.room:room-runtime:2.8.4")
  	ksp ("androidx.room:room-compiler:2.8.4" )
	implementation ("androidx.room:room-ktx:2.8.4")
	implementation ("com.github.AdrienPoupa:jaudiotagger:2.2.3")
	//主题色
	implementation("androidx.palette:palette-ktx:1.0.0")
	implementation ("androidx.core:core-ktx:1.12.0")

	// Retrofit + OkHttp for networking
	implementation("com.squareup.retrofit2:retrofit:2.9.0")
	implementation("com.squareup.retrofit2:converter-gson:2.9.0")
	implementation("com.squareup.okhttp3:okhttp:4.12.0")
	implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

	// MMKV for persistent key-value storage
	implementation("com.tencent:mmkv:1.3.5")

    // Accompanist Lyrics - karaoke-style lyric rendering


}
