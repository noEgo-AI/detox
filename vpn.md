네, 말씀하신 대로 \*\*"브라우저 앱을 실행했을 때만 VPN이 작동"\*\*하거나, \*\*"다른 앱(게임 등)의 속도 저하 없이 브라우저의 특정 URL만 차단"\*\*하는 가장 확실하고 '제대로 된' 방법인 **DNS 필터링(DNS Sinkhole)** 방식의 기술 명세서를 작성해 드립니다.

이 방식은 모든 트래픽을 막는 게 아니라, **"어디로 갈지 물어보는 요청(DNS)"만 검사**하기 때문에 인터넷 속도 저하가 거의 없고, 다른 앱의 사용성도 해치지 않습니다.

아래 내용을 담은 `.md` 파일을 생성해 드립니다.

-----

### 📄 Android\_Vpn\_DNS\_Filtering\_Guide.md

````markdown
# 🛡️ Android Native: DNS 필터링 기반 VPN 구현 가이드

## 1. 개요 및 원리

### 기존 방식의 문제점
기존에 "인터넷이 아예 안 되던 이유"는 VPN이 모든 네트워크 패킷을 가로챈 후, 외부 인터넷으로 내보내주지 않았기 때문입니다.

### 해결책: DNS 패킷 감청 (DNS Interception)
우리가 구현할 방식은 데이터를 통째로 막는 것이 아니라, 주소록(DNS)만 변조하는 방식입니다.

1.  **터널링 분할 (Split Tunneling):** `Chrome`, `Samsung Internet` 등 브라우저 앱의 트래픽만 VPN으로 가져옵니다. (게임, 카톡은 VPN 안 거치게 설정)
2.  **DNS 감지:** 가져온 트래픽 중 포트 `53`번(DNS) 통신만 검사합니다.
3.  **필터링:**
    * 사용자: *"youtube.com IP가 뭐야?"*
    * 앱(VPN): 차단 목록 확인 -> **있음!**
    * 앱(VPN): *"거긴 없는 곳이야 (0.0.0.0)"*라고 거짓 응답 -> **접속 차단**
    * 사용자: *"google.com IP가 뭐야?"*
    * 앱(VPN): 차단 목록 확인 -> **없음.**
    * 앱(VPN): 실제 DNS 서버에 물어보고 올바른 IP 전달 -> **접속 성공**

---

## 2. 구현 아키텍처



* **Tauri App (UI):** 차단할 도메인 리스트를 Android Native로 전송.
* **Android Service (Kotlin):** `VpnService`를 상속받아 백그라운드에서 패킷 필터링 수행.
* **Packet Parser:** UDP 패킷 헤더를 분석하여 DNS 쿼리를 추출.

---

## 3. 핵심 구현 단계 (Kotlin)

이 코드는 Tauri의 플러그인 (`src-tauri/gen/android`) 영역에서 작성되어야 합니다.

### Step 1: AndroidManifest.xml 권한 설정
VPN 서비스를 사용하려면 매니페스트에 서비스 등록이 필수입니다.

```xml
<service
    android:name=".MyVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:exported="false">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
````

### Step 2: VpnService 설정 (가장 중요)

여기서 **어떤 앱을 VPN에 태울지 결정**합니다. 이것이 "브라우저 들어갔을 때만 작동"하게 하는 핵심입니다.

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val builder = Builder()
        .setSession("FocusLock VPN")
        .addAddress("10.0.0.2", 32) // 가상 IP 할당
        .addRoute("0.0.0.0", 0)     // 모든 트래픽을 감지하되...
        .addDnsServer("8.8.8.8")    // 기본 DNS (구글)

    // 🔥 핵심: 브라우저 앱만 VPN 터널로 들어오게 설정 (Whitelisting)
    // 이 설정이 없으면 폰 전체가 VPN에 걸립니다.
    // 카카오톡, 롤 등은 이 목록에 없으므로 VPN 영향을 받지 않고 아주 빠르게 작동합니다.
    try {
        builder.addAllowedApplication("com.android.chrome")
        builder.addAllowedApplication("com.sec.android.app.sbrowser") // 삼성 인터넷
        // 필요시 다른 브라우저 패키지명 추가
    } catch (e: Exception) {
        e.printStackTrace()
    }

    val vpnInterface = builder.establish()
    
    // 별도 스레드에서 패킷 처리 시작
    Thread { runVpnLoop(vpnInterface) }.start()
    
    return START_STICKY
}
```

### Step 3: 패킷 처리 루프 (The Packet Loop)

실제 데이터가 오고 가는 파이프라인입니다. 여기서 `53`번 포트만 골라냅니다.

*주의: 실제 구현 시에는 `PCap4J`나 `dnsjava` 같은 라이브러리를 활용하거나, 바이트 단위 파싱 로직이 정교해야 합니다. 아래는 논리적 흐름을 보여주는 의사 코드(Pseudo-code)입니다.*

```kotlin
private fun runVpnLoop(vpnInterface: ParcelFileDescriptor) {
    val inputStream = FileInputStream(vpnInterface.fileDescriptor)
    val outputStream = FileOutputStream(vpnInterface.fileDescriptor)
    val buffer = ByteBuffer.allocate(32767)

    while (isRunning) {
        val length = inputStream.read(buffer.array())
        if (length > 0) {
            // 1. IP 헤더 파싱
            val ipHeader = parseIpHeader(buffer)
            
            // 2. 프로토콜이 UDP(17)이고, 목적지 포트가 53(DNS)인지 확인
            if (ipHeader.protocol == UDP && ipHeader.destPort == 53) {
                val dnsQuery = parseDnsQuery(buffer)
                val domain = dnsQuery.getDomainName()

                if (blocklist.contains(domain)) {
                    // ⛔ 차단 대상: 가짜 DNS 응답(0.0.0.0) 생성 후 전송
                    val fakeResponse = createFakeDnsResponse(buffer, "0.0.0.0")
                    outputStream.write(fakeResponse)
                } else {
                    // ✅ 허용 대상: 실제 인터넷 DNS 서버(8.8.8.8)로 쿼리 전송 (Protect socket 필수)
                    // 중요: VPN 내부 소켓이 아니라 OS의 보호된 소켓을 써야 루프에 안 빠짐
                    val realResponse = sendToRealDnsServer(buffer)
                    outputStream.write(realResponse)
                }
            } else {
                // 3. DNS가 아닌 일반 트래픽 (HTTP 이미지, 영상 데이터 등)
                // 그냥 통과시킴 (NAT 처리 필요할 수 있음. 
                // 복잡성을 피하기 위해 보통 DNS만 처리하고 나머지는 Bypass 하기도 함)
                // 하지만 위에서 addAllowedApplication을 했으므로, 
                // 브라우저의 일반 트래픽도 여기로 들어옴. 이를 외부로 릴레이 해줘야 함.
                writeToInternet(buffer) 
            }
        }
    }
}
```

### 💡 팁: 가장 쉬운 구현 방법 (오픈소스 활용)

패킷을 직접 파싱(Step 3)하는 것은 매우 어렵고 오류가 많습니다. 처음부터 만드는 것보다 **검증된 오픈소스를 Tauri 플러그인으로 래핑**하는 것을 추천합니다.

  * **추천 오픈소스:** `Dns66` 또는 `PersonalDNSFilter` (Github 검색)
      * 이 프로젝트들은 이미 Android VpnService로 DNS 필터링을 완벽하게 구현해 두었습니다.
      * 이 코드에서 \*\*Core 로직(VPN Service 부분)\*\*만 가져와서 Tauri 앱에 이식하세요.

-----

## 4\. Tauri와 연동 (Bridge)

React 프론트엔드에서 버튼을 누르면 Android VPN이 켜지도록 연결합니다.

**Frontend (React):**

```typescript
import { invoke } from '@tauri-apps/api/core';

async function toggleFocusMode(isLocked: boolean) {
  // Rust 명령 호출 -> Rust가 Android Plugin 호출
  await invoke('toggle_vpn', { enable: isLocked });
}
```

**Backend (Rust - lib.rs):**

```rust
#[tauri::command]
fn toggle_vpn(app_handle: AppHandle, enable: bool) {
    // Android Intent를 날려서 VPN Service 시작/종료
    if enable {
        // Start VPN Service Intent
    } else {
        // Stop VPN Service Intent
    }
}
```

-----

## 5\. 결론 및 요약

1.  **VpnService 사용:** 안드로이드 시스템 레벨 차단을 위해 필수.
2.  **addAllowedApplication:** `Chrome`, `Samsung Internet` 등 브라우저만 VPN 필터에 걸리게 설정하여 **다른 앱(게임 등) 속도 저하 방지**.
3.  **DNS 필터링:** 데이터를 막는 게 아니라 도메인 주소 요청만 가로채서 차단.
4.  **오픈소스 참조:** 바이트 단위 코딩보다는 `Dns66` 등의 오픈소스 코드를 참고하여 안정성 확보.

이 구조로 개발하시면 "유튜브 앱"이나 "브라우저에서의 유튜브 접속"은 차단되면서도, "배달 앱"이나 "게임"은 쾌적하게 돌아가는 고성능 디톡스 앱이 됩니다.

```
```