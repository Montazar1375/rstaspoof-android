# Native libraries (arm64-v8a)

These files are **not** committed (see root `.gitignore`). Build before assembling the APK:

| Library | Script |
|---------|--------|
| `librstaspoof.so` | `./scripts/build-android.sh` |
| `libhev-socks5-tunnel.so` | `./scripts/build-hevtun.sh` (needs `NDK_HOME`) or `./scripts/fetch-libhev.sh` (v2rayNG APK) |

JNI for hev is bound to `com.v2ray.ang.service.TProxyService` (see `app/src/main/java/com/v2ray/ang/service/TProxyService.kt`).
| `libv2ray.aar` (in `app/libs/`) | `./scripts/build-libv2ray.sh` |

All-in-one:

```bash
./scripts/build-all-native.sh
```
