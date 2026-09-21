# 工厂手持机打卡 APK（纯 Java Android）

> 版本：v1.0 | 对应：手持机APK打卡设计_调动同步顺序修正版 + 百度离线人脸SDK 8.5

---

## 项目结构

```
app/src/main/
├── java/com/punch/app/
│   ├── PunchApplication.java        # Application：全局初始化、SDK init、启动 SyncService
│   ├── activity/
│   │   ├── SplashActivity.java      # 启动路由（判断 token 有效性）
│   │   ├── LoginActivity.java       # 登录页：账号登录 + 启动同步
│   │   └── MainActivity.java        # 主页：底部导航 + 网络监听 + 同步触发
│   ├── fragment/
│   │   ├── PunchFragment.java       # 打卡Tab：Camera1预览 + 帧识别 + 调动弹窗
│   │   ├── RecordsFragment.java     # 记录Tab：已签/未签列表 + 统计
│   │   └── ConfigFragment.java      # 配置Tab：阈值调整 + 同步 + 退出
│   ├── adapter/
│   │   ├── PunchRecordAdapter.java  # 打卡记录列表适配器
│   │   └── UnsignedEmployeeAdapter.java # 未签到员工列表适配器
│   ├── db/
│   │   └── DatabaseHelper.java     # SQLite：建表 + 全部 CRUD（employees/punch_records/
│   │                               #   transfer_records/sync_queue）
│   ├── face/
│   │   ├── FaceManager.java        # 百度SDK封装：init/register/recognize1N/rebuild
│   │   ├── FaceFileManager.java    # 人脸图片下载 + SHA256 校验
│   │   └── FaceRegistrationManager.java # 批量注册/刷新人脸
│   ├── model/
│   │   ├── Employee.java
│   │   ├── PunchRecord.java
│   │   ├── TransferRecord.java
│   │   └── SyncQueueItem.java
│   ├── network/
│   │   ├── ApiClient.java          # OkHttp 封装：get/post/put + 统一响应解析
│   │   └── ApiResponse.java
│   ├── service/
│   │   └── SyncService.java        # 后台同步：调动优先→打卡，5分钟定时
│   ├── utils/
│   │   ├── Constants.java          # 全局常量
│   │   ├── SessionManager.java     # SharedPrefs + EncryptedSharedPrefs（token）
│   │   ├── UlidGenerator.java      # 幂等 ID 生成
│   │   └── AppLogger.java
│   └── widget/
│       └── FaceFrameView.java      # 自定义View：四角识别框 + 扫描线动画
├── res/layout/                     # 所有布局 XML
├── res/drawable/                   # 按钮背景、图标、选择器
├── res/values/                     # strings / colors / styles
└── assets/
    ├── idl-license.face-android    # ⚠️ 百度授权文件（需自行放入）
    └── face-sdk-models/            # SDK 模型文件（已拷入）
app/libs/
└── facelibrary-release-8.5-*.aar  # 百度人脸 SDK（已拷入）
```

---

## 关键架构

### 1. 幂等打卡 ID
```
打卡：P{deviceId}_{ULID}
调动：T{deviceId}_{ULID}
```
`sync_queue` 对 `(action, record_id)` 加唯一约束，防止重复入队。

### 2. 调动优先同步顺序
```
SyncService.doSync()
  └─ syncTransfers()   → POST /employees/transfer
       └─ syncPunches()
            └─ is_transfer=1 的打卡：检查 transfer.is_synced==1 才上传
                                     否则跳过本轮，等下次
```

### 3. 离线 Token 策略
- 有效期 7 天；距过期 24h 内后台自动 refresh
- Token 有效 → 无需联网可打卡
- Token 过期 → 必须联网重新登录

### 4. 人脸库重建（App 重启后）
```
PunchApplication.initFaceSDK()
  └─ FaceManager.init()
       └─ FaceManager.rebuildFaceLibrary()   ← 从 DB 取 face_registered=1 的员工
            └─ extractFeature(localImagePath) + FaceSearch.pushPersonById()
```

### 5. Camera1 帧处理
```
Camera.setPreviewCallback(NV21 bytes)
  └─ 节流 600ms → 后台线程
       └─ FaceManager.recognizeFromNv21(nv21, w, h, angle)
            ├─ BDFaceImageInstance(nv21, h, w, NV21, angle, 0)
            ├─ FaceDetect.detect()
            ├─ FaceFeature.feature()
            └─ FaceSearch.search(threshold, topN=1, feature)
```

---

## SDK 集成说明

### 需要自行完成的步骤

1. **授权文件**：将百度控制台申请的 `idl-license.face-android` 放入
   `app/src/main/assets/`

2. **人脸 SDK 封装代码**：
   `FaceSDKManager`、`SdkInitListener` 已迁入
   `app/src/main/java/com/punch/app/face/`，不再依赖 Demo 的 `datalibrary` 目录。
3. **后端地址**：修改 `Constants.java` 中的 `BASE_URL`

---

## 构建与运行

```bash
# 确保已放入授权文件
cp idl-license.face-android app/src/main/assets/

# 构建 debug APK
./gradlew assembleDebug

# 构建 release APK
./gradlew assembleRelease

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 最低要求
- Android 7.0（API 24）
- 后置摄像头
- 2GB RAM（人脸模型加载约需 300MB）

---

---

## API 接口对应

| 功能 | 接口 |
|------|------|
| 登录 | `POST /auth/login` |
| 刷新 Token | `POST /auth/refresh` |
| 员工同步 | `GET /employees/sync` |
| 调动上报 | `POST /employees/transfer` |
| 单条打卡 | `POST /punch` |
| 批量打卡 | `POST /punch/batch` |
| 设备配置 | `GET/PUT /device/config` |

## 安装

```bash
adb install -r D:\code\faceapk\app\release\app-release.apk && adb shell dpm set-device-owner com.punch.app/.receiver.KioskDeviceAdminReceiver
```

当前需要设置打卡时间范围，即在班次的上下班时间点前后设置一定的时间，可以用来计算打卡时间，比如：如果你把 “打卡间隔” 设置为 20 分钟，则

当班次为：06:00 - 11:00
上班允许打卡时间范围为：05:40 <= 当前时间 <= 06:20

当班次为：06:00 - 11:00
下班允许打卡时间范围为：10:40 <= 当前时间 <= 11:20

1、需要在高级设置页面的 “打卡” 区块添加 “打卡间隔” 时间配置框，最好可以通过数字加减来配置，单位为分钟；
2、有了这个打卡间隔时间，就可以根据当前时间自动选中 打卡项 中的班次作为默认选中项，然后用户也可以切换为其它的选项；
3、有了这个打卡间隔时间，就可以计算打卡面板中当前时间是否在选中的打卡项的打卡时间范围，如果不在弹窗提示框，提示“当前打卡不在打卡时间范围”；
4、打卡项中的选项用不同颜色来区别显示；
5、打卡面板中默认使用前置摄像头；


存在特殊情况是否处理，比如：如果你把 “打卡间隔” 设置为 20 分钟，则

当前班次为：23:50 - 3:20
上班允许打卡时间范围为 当天的 23:30 到 隔天的 00:10
下班允许打卡时间范围为 隔天的 03:00 到 隔天的 03:40

当前班次为：18:20 - 23:50
上班允许打卡时间范围为 当天的 18:00 到 当天的 18:40
下班允许打卡时间范围为 当天的 23:30 到 隔天的 00:10

还有一个问题，如果用户设置的打卡间隔，导致同一个班次上班和下班打卡时间重叠怎么办？或者导致当前班次上班打卡时间和前一个班次的下班打卡时间重叠了怎么办？

adb install -r -t app-debug.apk&&adb shell dpm remove-active-admin com.punch.app/.receiver.KioskDeviceAdminReceiver
&&adb uninstall com.punch.app


卸载后重装后重新授权命令：
adb install -r app-release.apk && adb shell dpm set-device-owner com.punch.app/.receiver.KioskDeviceAdminReceiver && adb shell pm grant com.punch.app android.permission.WRITE_SECURE_SETTINGS
