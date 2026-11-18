# HONDA FR-V SMART DRIVE

Proyecto Android nativo (Kotlin + WebView) para el panel multimedia del Honda FR-V.

## Requisitos
- JDK 17
- Android SDK Platform 35 y Build Tools 35.0.0
- Gradle 8.7 (el workflow de GitHub Actions lo instala automáticamente)

## Compilación local (Windows PowerShell)
Con Android SDK instalado y `gradle` disponible en PATH:

```powershell
gradle --no-daemon clean assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`

## Compilación en GitHub Actions
El workflow `.github/workflows/build-apk.yml` compila en cada push a `main` y también permite ejecución manual desde Actions. El APK se publica como artefacto descargable de la ejecución.

## Compatibilidad prevista
`minSdk 23` permite Android 6.0 o superior; la tableta objetivo Android 8.1 está dentro de ese rango. La compatibilidad funcional de GPS, Bluetooth y controles debe validarse en el dispositivo real. La integración OBD2 requiere un adaptador compatible y no se considera probada.

## Estado
Código fuente preparado para compilación CI. No se declara APK validado hasta que GitHub Actions termine correctamente y el APK se instale y pruebe en la Lenovo TB-X104F.
