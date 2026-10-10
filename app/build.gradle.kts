plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.igit"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.igit"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    // SSH transport for JGit. The maintained JSch fork replaces the abandoned com.jcraft build
    // and adds modern key types (ed25519, rsa-sha2) and accept-new host key checking.
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:7.8.0.202609011348-r") {
        exclude(group = "com.jcraft", module = "jsch")
    }
    implementation("com.github.mwiede:jsch:2.28.8")
    // Ed25519 key generation, OpenSSH key encoding and SSH commit signatures.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.86")
}
