# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Ondafone: BouncyCastle (so o Ed25519 da lista assinada dos proxies e usado; o R8 remove o resto).
# Classes de JDK que o bcprov referencia e nao existem no Android:
-dontwarn org.bouncycastle.**
# Ondafone: Credential Manager ("Continuar com Google") carrega o provedor do Play Services por reflexao
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}
