# Como compilar o Nexus Android v6.3.0-nexus.1

Gerado de `.github/workflows/*.yml` da tag (o CI não vai para o espelho). Valores `<...>` vêm do CI.

- JDK: 21
- Android SDK: o do Android Gradle Plugin (`compileSdk` em `app/build.gradle.kts`); linphone-sdk `5.6.0-alpha.185+9224908066` (Maven `download.linphone.org`).

```sh
./gradlew --no-daemon --stacktrace testDebugUnitTest --tests 'org.linphone.utils.Nexus*' --tests 'org.linphone.utils.Ondafone*' --tests 'org.linphone.utils.LogCompartilhado*' assembleDebug
./gradlew --no-daemon --stacktrace -PnexusSourceTag=v6.3.0-nexus.1 -PnexusVersionCode="$NEXUS_VERSION_CODE" assembleRelease bundleRelease
```

Release assinado: crie `keystore.properties` com a SUA chave (storeFile, storePassword, keyAlias, keyPassword).
O Android instala uma versão modificada assinada com outra chave depois de desinstalar a anterior.
