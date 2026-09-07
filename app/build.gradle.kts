import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La firma NUNCA va escrita aqui ni se sube al repo.
//
// 🚨 El auto-update EXIGE que todas las versiones lleven la MISMA firma: si
// cambia, Android rechaza la actualizacion y hay que desinstalar y perder los
// ajustes de la tablet. Por eso se reutiliza el mismo llavero de Yammbo Music,
// cuyas credenciales ya viven en su local.properties (fuera de este repo).
// Se puede sobreescribir con un keystore.properties propio o con variables de
// entorno SIGNING_*.
val llavero = File(rootDir, "../Yammbo-Music/local.properties")
val firmaProps = Properties().apply {
    val propio = rootProject.file("keystore.properties")
    when {
        propio.exists() -> propio.inputStream().use { load(it) }
        llavero.exists() -> llavero.inputStream().use { load(it) }
    }
}
fun firma(clave: String): String? =
    System.getenv(clave) ?: firmaProps.getProperty(clave) ?: firmaProps.getProperty(
        when (clave) {
            "SIGNING_STORE_FILE" -> "storeFile"
            "SIGNING_STORE_PASSWORD" -> "storePassword"
            "SIGNING_KEY_ALIAS" -> "keyAlias"
            else -> "keyPassword"
        }
    )
val hayFirma = firma("SIGNING_STORE_FILE") != null && firma("SIGNING_STORE_PASSWORD") != null

android {
    namespace = "com.yammbo.kds"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yammbo.kds"
        // Una tablet barata de cocina puede ser vieja; 24 cubre de Android 7 en
        // adelante sin pedir nada moderno.
        minSdk = 24
        targetSdk = 36
        // Invariante del proyecto: el ULTIMO tramo del nombre ES el
        // versionCode (1.5 <-> 5). El respaldo del actualizador deduce el
        // codigo del tag de GitHub (v1.5 -> 5); si se rompe esa
        // correspondencia, la app compara mal y deja de ver actualizaciones
        // EN SILENCIO.
        versionCode = 5
        versionName = "1.5"
    }

    if (hayFirma) {
        signingConfigs {
            create("yammbo") {
                // La ruta del llavero de Yammbo Music es relativa a SU modulo.
                val ruta = firma("SIGNING_STORE_FILE")!!
                val f = File(ruta)
                storeFile = if (f.isAbsolute && f.exists()) f
                else File(rootDir, "../Yammbo-Music/composeApp/" + ruta).canonicalFile
                storePassword = firma("SIGNING_STORE_PASSWORD")
                keyAlias = firma("SIGNING_KEY_ALIAS")
                keyPassword = firma("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hayFirma) signingConfig = signingConfigs.getByName("yammbo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = false }
    testOptions { unitTests.all { it.testLogging { showStandardStreams = true } } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
