plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.stickermaker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.stickermaker"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests {
            // Robolectric tests need the app's resources and manifest.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Image loading (thumbnails, animated GIF/WebP previews, video frames)
    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)

    // On-device background removal (Google Play services ML Kit, model downloaded on demand)
    implementation(libs.mlkit.subject.segmentation)

    // Frame-by-frame GIF decoding (turn GIFs into animated stickers)
    implementation(libs.gifdecoder)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
