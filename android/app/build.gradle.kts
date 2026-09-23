plugins {
  id("com.android.application")
}

android {
  namespace = "com.pjarczak.bmcuflasher"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.pjarczak.bmcuflasher"
    minSdk = 21
    targetSdk = 35
    versionCode = 131
    versionName = "1.3.1-nono"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    viewBinding = true
  }

  sourceSets {
    getByName("main") {
      assets.srcDir("../../i18n")
    }
  }
}

dependencies {
  testImplementation("junit:junit:4.13.2")
  implementation("androidx.appcompat:appcompat:1.7.1")
  implementation("com.github.mik3y:usb-serial-for-android:3.10.0")
}
