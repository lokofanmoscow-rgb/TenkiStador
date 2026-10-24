plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tankistador.game"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tankistador.game"
        minSdk = 21
        targetSdk = 35
        versionCode = 5
        versionName = "4.1"
    }

    signingConfigs {
        // A fixed key committed with the project so every build (local or CI)
        // produces an APK that installs over the previous one.
        create("shared") {
            storeFile = file("tankistador.keystore")
            storePassword = "tankistador"
            keyAlias = "tankistador"
            keyPassword = "tankistador"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.systemProperty("screenshots.dir", rootProject.file("screenshots-out").absolutePath)
            // Lets sandboxes without access to repo1.maven.org point Robolectric at a mirror.
            System.getenv("ROBOLECTRIC_REPO")?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url) }
            it.maxHeapSize = "3g"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
