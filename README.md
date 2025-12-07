# FocusLock - Digital Detox App

> "의지력에 의존하지 않는다. 시스템이 강제한다."

시험 기간 등 집중이 필요한 시기에 방해 요소(YouTube, Instagram, Chzzk, LoL 등)를 **시스템 레벨**에서 원천 차단하는 크로스 플랫폼 앱입니다.

## 주요 기능

- **웹사이트 차단**: DNS 필터링 VPN으로 YouTube, Instagram, Chzzk 등 차단
- **앱 차단**: Android 앱 실행 감지 및 자동 종료
- **타임락**: 설정된 기간(D-Day)까지 해제 불가능
- **우회 방지**: 브라우저 시크릿 모드에서도 차단 유지

## 기술 스택

```
Frontend: Svelte 5 + TailwindCSS
Backend:  Tauri 2.0 (Rust)
Mobile:   Android VpnService (Kotlin)
Storage:  SQLite
```

## 차단 대상

### 웹사이트 (DNS 차단)
- YouTube (youtube.com, youtu.be, music.youtube.com 등)
- Instagram (instagram.com, i.instagram.com 등)
- Chzzk (chzzk.naver.com)
- LoL 관련 (op.gg, fow.kr, leagueoflegends.com)

### Android 앱 (강제 종료)
- YouTube (`com.google.android.youtube`)
- Instagram (`com.instagram.android`)
- Chzzk (`com.naver.chzzk`)
- LoL 관련 앱

## 빌드 방법

### 요구사항
- Node.js 18+
- Rust 1.70+
- Android SDK (NDK 포함)

### 설치
```bash
# 의존성 설치
npm install

# 개발 서버 실행
npm run tauri dev

# Android 빌드
npm run tauri android build -- --target aarch64
```

### APK 설치
```bash
adb install -r src-tauri/gen/android/app/build/outputs/apk/universal/release/app-universal-release.apk
```

## 프로젝트 구조

```
focus-lock/
├── src/                      # Svelte Frontend
│   ├── lib/
│   │   └── components/       # UI 컴포넌트
│   └── routes/
│       └── +page.svelte      # 메인 화면
├── src-tauri/                # Rust Backend
│   ├── src/
│   │   ├── lib.rs            # Tauri 진입점
│   │   └── timelock.rs       # 타임락 로직
│   └── gen/android/          # Android 네이티브 코드
│       └── app/src/main/java/com/focuslock/detox/
│           ├── BlockerVpnService.kt   # DNS 필터링 VPN
│           ├── AppBlockerService.kt   # 앱 차단 서비스
│           ├── BlockerManager.kt      # 차단 상태 관리
│           └── BootReceiver.kt        # 부팅 시 자동 시작
└── README.md
```

## 작동 원리

### DNS 필터링 VPN
1. Android VpnService로 브라우저 앱의 DNS 트래픽만 가로챔
2. 차단 도메인 요청 시 NXDOMAIN 응답 반환
3. 허용 도메인은 Google DNS(8.8.8.8)로 전달
4. DNS 캐싱(60초 TTL)으로 성능 최적화

### 앱 차단
1. UsageStatsManager로 현재 실행 중인 앱 감지
2. 차단 대상 앱 발견 시 홈 화면으로 강제 이동
3. 백그라운드 서비스로 지속 모니터링

## 권한 요구사항

### Android
- **VPN 권한**: 네트워크 트래픽 필터링
- **사용 정보 접근 권한**: 실행 중인 앱 감지
- **알림 권한**: Foreground Service 실행
- **부팅 완료 수신**: 재부팅 후 자동 시작

## 최적화

- HashSet 기반 도메인 검색 (O(1))
- DNS 캐싱 (60초 TTL, 최대 100개)
- 최소 스레드풀 (2개)
- 최적화된 버퍼 크기 (4KB)

## 제한사항

- Android에서 재부팅 후 VPN은 사용자가 앱을 한 번 열어야 활성화됨 (Android 보안 정책)
- iOS는 Content Blocker만 지원 (앱 차단 불가)
- DNS 서버 수동 변경 시 우회 가능 (고급 사용자)

## 라이선스

MIT License

## 기여

이슈 및 PR 환영합니다!
