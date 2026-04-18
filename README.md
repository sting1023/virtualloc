# VirtuaLoc

Android 虚拟定位应用 - 通过开发者模式 Mock Location API 实现，无需 Root 权限。

## 功能

- 📍 输入经纬度，实时设置虚拟定位
- 🗺️ OpenStreetMap 地图预览（无需 API Key，离线可用）
- 🔄 前台服务保活，App 退后台依然生效
- ⚙️ 内置开发者模式开启引导

## 技术栈

- Kotlin + Jetpack Compose
- osmdroid 6.1.18（OpenStreetMap，无需 API Key）
- LocationManager.addTestProvider（开发者模式 Mock Location）
- Foreground Service
- Min SDK 26 / Target SDK 34

## 使用前提

1. 开启开发者选项：设置 → 关于手机 → 连续点击「版本号」7次
2. 开启模拟位置：设置 → 系统 → 开发者选项 → 选择模拟位置信息应用 → 选择 VirtuaLoc
3. 授予位置权限

## 构建

```bash
./gradlew assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`
