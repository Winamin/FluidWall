plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.txf.fluidwall"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.txf.fluidwall"
        minSdk = 33
        targetSdk = 36
        versionCode = 20261028
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexKeepProguard = file("multidex-keep.pro")
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootDir}/keystore/fluidwall.jks")
            storePassword = "fluidwall"
            keyAlias = "fluidwall"
            keyPassword = "fluidwall"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
        )
    }
}

dependencies {
    compileOnly(files("libs/api-82.jar"))
    compileOnly(files("libs/libxposed-stub.jar"))

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
