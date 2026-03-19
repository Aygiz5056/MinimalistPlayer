plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")  // Вместо kapt
}



android {
    namespace = "com.example.minimalistplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.minimalistplayer"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Оставляем только implementation, kapt, ksp
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.0")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.0")

    // Media
    implementation("androidx.media:media:1.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Glide
    implementation("com.github.bumptech.glide:glide:4.15.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Lifecycle components (добавь эти строки)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Для lifecycleScope (это та самая зависимость)
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")

    // Для уведомлений
    implementation("androidx.media:media:1.6.0")

    implementation("com.github.vikramezhil:DroidSpeech:2.0.3")

    implementation("androidx.core:core-splashscreen:1.0.1")

    // Glide (убедись что она есть)
    implementation("com.github.bumptech.glide:glide:4.15.1")

    // Для размытия изображений (трансформации)
    implementation("jp.wasabeef:glide-transformations:4.3.0")

    // Если используешь поддержку старых версий
    implementation("jp.co.cyberagent.android:gpuimage:2.1.0")

    implementation("androidx.preference:preference-ktx:1.2.0")
}

// Принудительно указываем версию Kotlin
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.20")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.20")
        force("org.jetbrains.kotlin:kotlin-reflect:1.9.20")
    }
}