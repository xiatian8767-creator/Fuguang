# 浮光 · 3D 相册 2.1.1 测试版

把普通照片存成可随手机转动的3D相册，支持本地深度缓存、拖动预览、沉浸查看和动态壁纸。

官网：https://fuguang.startimetable.xyz/

管理后台：https://fuguang.startimetable.xyz/admin/

## 本版功能

- 注册与登录，登录令牌由 Android Keystore 加密保存。
- 普通用户北京时间每周免费生成10次，每周一重置。
- 月卡¥1.99、年卡¥19.99、永久卡¥29.99，通过兑换码开通。
- 独立管理后台：用户查询/停用/重置密码/调整会员，兑换码生成/导出/停用，操作记录。
- 更新推广弹窗：文字或官网H5、目标版本、定时、频率、指定用户测试、启停与尺寸设置。
- 官网发布页和下载入口。服务器部署于 `/opt/fuguang`，独立数据库、容器和管理员，与 Star Orbit 仅共享域名入口网关。

## 开发

Android：Kotlin 2.0.21、Compose、ONNX Runtime、OpenGL ES。复用 TIM 的 SDK35/Build Tools35.0.0，使用本项目 JDK17 和 Gradle8.9。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-local.ps1 -Release
```

首次构建需要下载模型到 `app/src/main/assets`，运行 `scripts/download_model.ps1`。独立发布签名、管理员凭据在 `private`，不提交Git；构建工具和缓存在 `.local`。

后端：Python3.11、FastAPI、SQLite。安装 `backend/requirements.txt` 后：

```powershell
python -m uvicorn backend.app:app --host 127.0.0.1 --port 18765
python -m unittest backend.test_app -v
```

本版测试包和推送配置见 [2.1.1测试交付](docs/2.1.1-test.md)。官网页面和正式下载保留旧版，待用户实测通过后更新。

维护说明见 [会员与部署](docs/会员与部署.md)。相册渲染和设备检查保留在 `scripts/test_gl_render.py`、`scripts/run_device_checks.py` 与 `app/src/androidTest`。

来源：[ParallaxWallpaper](https://github.com/xiatian8767-creator/ParallaxWallpaper)，深度模型来自 [Tiefling](https://github.com/combatwombat/tiefling/tree/main/site/public/models)。原有中文版渲染修复和相册说明保留于 `docs/FUGUANG-2.0.md`。
