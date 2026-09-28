# Build

本文件說明 Nivqo 原始碼的基本建置方式。正式發佈時請使用你自己的簽署金鑰；本倉庫不包含作者的私人 signing key。

## 環境

建議：

- JDK 17
- Android SDK / Build Tools
- Windows PowerShell 或相容 shell
- Git
- Gradle wrapper（各子專案已包含）

設定 Android SDK，例如：

```powershell
$env:ANDROID_HOME = "C:\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
```

## 1. HushFacebook

```powershell
cd source\hushfacebook
.\gradlew.bat --no-daemon -PallowMavenLocal=true --dependency-verification off :patches:buildAndroid
```

輸出的 `.mpp` 位於該專案的 `patches/build` 下。

## 2. HushMessenger

```powershell
cd source\hushmessenger
.\gradlew.bat :patches:test :patches:buildAndroid --no-daemon --dependency-verification off -PallowMavenLocal=true
```

目前 Messenger 相容版本會在 `MessengerTarget.kt` 中檢查 `versionCode`。

支援：

- `346013387`
- `346013440`
- `346013442`

## 3. 將 bundle 放入 Manager

正式 Nivqo Manager 會從：

```text
source/morphe-manager/app/src/main/assets/morphe-hush/
```

讀取內建的 HushFacebook / HushMessenger bundle。

如果你自行重建 patch bundle，請以新檔取代對應 `.mpp` 後再重新建置 Manager。

## 4. Nivqo Manager

Manager package：

```text
app.nivqo.manager
```

Release build 使用：

```text
source/morphe-manager/app/keystore.jks
```

以及以下環境變數：

```text
KEYSTORE_PASSWORD
KEYSTORE_ENTRY_ALIAS
KEYSTORE_ENTRY_PASSWORD
```

建置：

```powershell
cd source\morphe-manager
.\gradlew.bat :app:assembleRelease --no-daemon --dependency-verification off -PallowMavenLocal=true
```

若只需要不經 ProGuard / minify 的 release-signed 測試輸出，可依目前 Gradle 設定加上：

```text
-PnoProguard=true
```

## 金鑰安全

- 不要把 `.jks`、`.keystore`、密碼或私鑰提交到 Git。
- Nivqo Manager 的 release signing key 與使用者修補 Facebook / Messenger 的 patch signing key 是兩件不同的事。
- 使用者的 patch signing key 應由使用者自己建立、匯出與備份。
- Nivqo 不在 APK 中預置作者的私人 patch signing key。

## 第三方授權

各子專案保留其原始 LICENSE / NOTICE。請同時閱讀 [SOURCES.md](SOURCES.md)。