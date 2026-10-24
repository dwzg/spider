plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.wtzg.spider"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.wtzg.spider"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Opt-in screenshot rendering on the JVM (no emulator needed):
//   ./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*Screenshot*'
// PNGs are written to app/build/screenshots/.
if (project.hasProperty("screenshots")) {
    android.sourceSets["test"].java.srcDir("src/screenshotTest/java")
    android.testOptions.unitTests.isIncludeAndroidResources = true
    dependencies {
        testImplementation("org.robolectric:robolectric:4.14.1")
    }
}
