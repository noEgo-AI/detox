# CLAUDE.md - FocusLock Digital Detox Platform

## 🎯 프로젝트 개요

**FocusLock**은 시험 기간 등 집중이 필요한 시기에 방해 요소(LoL, YouTube, Chzzk, Instagram)를 **시스템 레벨**에서 원천 차단하는 크로스 플랫폼 앱입니다.

### 핵심 철학
> "의지력에 의존하지 않는다. 시스템이 강제한다."

- 설정된 기간(D-Day)까지 **해제 불가능** (암호화 키 폐기)
- 웹 브라우저 + 네이티브 앱 **동시 차단**
- 우회 시도 감지 및 차단

---

## 🛠️ 기술 스택

```
┌─────────────────────────────────────────────────────────┐
│  Frontend: Svelte 5 + TailwindCSS                       │
│  ├─ 빠른 렌더링, 작은 번들 사이즈                          │
│  └─ 반응형 UI (Desktop/Mobile 공유)                      │
├─────────────────────────────────────────────────────────┤
│  Core: Tauri 2.0 (Rust)                                 │
│  ├─ 시스템 파일 제어 (hosts 파일)                         │
│  ├─ 프로세스 모니터링 & 강제 종료                          │
│  ├─ 암호화 키 관리                                       │
│  └─ 백그라운드 서비스                                     │
├─────────────────────────────────────────────────────────┤
│  Storage: SQLite (rusqlite)                             │
│  ├─ 차단 설정 저장                                       │
│  └─ 잠금 상태 & 만료 시간 관리                            │
├─────────────────────────────────────────────────────────┤
│  Mobile: Tauri Mobile Plugins                           │
│  ├─ Android: VpnService (Kotlin)                        │
│  └─ iOS: Content Blocker Extension (제한적)              │
└─────────────────────────────────────────────────────────┘
```

---

## 🚫 차단 대상 명세

### 1. League of Legends (리그 오브 레전드)

#### 프로세스 차단 목록
```rust
const LOL_PROCESSES: &[&str] = &[
    "LeagueClient.exe",
    "LeagueClientUx.exe", 
    "League of Legends.exe",
    "RiotClientServices.exe",
    "RiotClientUx.exe",
    "RiotClientCrashHandler.exe",
    // macOS
    "LeagueClient",
    "League of Legends",
    "RiotClient",
];
```

#### 관련 도메인 (웹 접속 차단)
```
# 롤 관련 웹사이트
127.0.0.1 www.leagueoflegends.com
127.0.0.1 leagueoflegends.com
127.0.0.1 signup.leagueoflegends.com
127.0.0.1 authenticate.riotgames.com
127.0.0.1 www.op.gg
127.0.0.1 op.gg
127.0.0.1 www.fow.kr
127.0.0.1 fow.kr
```

### 2. YouTube

#### 도메인 차단 목록
```
127.0.0.1 www.youtube.com
127.0.0.1 youtube.com
127.0.0.1 m.youtube.com
127.0.0.1 youtu.be
127.0.0.1 www.youtube-nocookie.com
127.0.0.1 youtube-nocookie.com
127.0.0.1 youtubei.googleapis.com
127.0.0.1 yt3.ggpht.com
127.0.0.1 music.youtube.com
127.0.0.1 studio.youtube.com
```

#### 브라우저 URL 감지 패턴
```rust
const YOUTUBE_URL_PATTERNS: &[&str] = &[
    "youtube.com",
    "youtu.be",
    "youtube-nocookie.com",
];
```

### 3. Chzzk (치지직)

#### 도메인 차단 목록
```
127.0.0.1 chzzk.naver.com
127.0.0.1 api.chzzk.naver.com
127.0.0.1 live.chzzk.naver.com
127.0.0.1 m.chzzk.naver.com
```

#### 관련 프로세스 (Naver 게임런처)
```rust
const CHZZK_PROCESSES: &[&str] = &[
    "NaverGameLauncher.exe",
    "CHZZK.exe", // 데스크톱 앱 출시 시
];
```

### 4. Instagram

#### 도메인 차단 목록
```
127.0.0.1 www.instagram.com
127.0.0.1 instagram.com
127.0.0.1 i.instagram.com
127.0.0.1 graph.instagram.com
127.0.0.1 api.instagram.com
127.0.0.1 l.instagram.com
127.0.0.1 static.cdninstagram.com
127.0.0.1 scontent.cdninstagram.com
```

#### 모바일 앱 패키지명 (Android)
```kotlin
val INSTAGRAM_PACKAGES = listOf(
    "com.instagram.android",
    "com.instagram.lite",
)
```

---

## 🔧 핵심 구현 가이드

### 1. Hosts 파일 조작 (웹사이트 차단)

```rust
// src-tauri/src/blocker/hosts.rs

use std::fs::{self, OpenOptions};
use std::io::{BufRead, BufReader, Write};

pub struct HostsBlocker {
    hosts_path: String,
    backup_path: String,
    marker_start: String,
    marker_end: String,
}

impl HostsBlocker {
    pub fn new() -> Self {
        let hosts_path = if cfg!(target_os = "windows") {
            r"C:\Windows\System32\drivers\etc\hosts".to_string()
        } else {
            "/etc/hosts".to_string()
        };
        
        Self {
            hosts_path,
            backup_path: "hosts.backup".to_string(),
            marker_start: "# === FOCUSLOCK START ===".to_string(),
            marker_end: "# === FOCUSLOCK END ===".to_string(),
        }
    }
    
    pub fn block(&self, domains: &[&str]) -> Result<(), String> {
        // 1. 기존 hosts 파일 백업
        // 2. 마커 사이에 차단 규칙 삽입
        // 3. 파일 권한 보호 (선택적)
        todo!()
    }
    
    pub fn unblock(&self) -> Result<(), String> {
        // 마커 사이의 내용만 제거
        todo!()
    }
}
```

### 2. 프로세스 모니터링 & 강제 종료

```rust
// src-tauri/src/blocker/process.rs

use sysinfo::{System, Process, ProcessRefreshKind, RefreshKind};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

pub struct ProcessWatcher {
    targets: Vec<String>,
    running: Arc<AtomicBool>,
}

impl ProcessWatcher {
    pub fn start(&self) {
        let targets = self.targets.clone();
        let running = self.running.clone();
        
        thread::spawn(move || {
            let mut sys = System::new();
            
            while running.load(Ordering::Relaxed) {
                sys.refresh_processes_specifics(
                    ProcessRefreshKind::new()
                );
                
                for (pid, process) in sys.processes() {
                    let name = process.name().to_lowercase();
                    
                    for target in &targets {
                        if name.contains(&target.to_lowercase()) {
                            // 프로세스 강제 종료
                            process.kill();
                            log::info!("Killed process: {} (PID: {})", name, pid);
                        }
                    }
                }
                
                thread::sleep(Duration::from_secs(3));
            }
        });
    }
}
```

### 3. 브라우저 URL 감지 (고급)

**접근 방식**: 브라우저의 창 제목(Window Title)을 모니터링하여 차단 대상 URL 감지

```rust
// src-tauri/src/blocker/browser.rs

#[cfg(target_os = "windows")]
use windows::Win32::UI::WindowsAndMessaging::*;

pub struct BrowserWatcher {
    blocked_patterns: Vec<String>,
}

impl BrowserWatcher {
    /// 브라우저 창 제목에서 URL 패턴 감지
    pub fn check_browser_titles(&self) -> Vec<String> {
        let mut detected = Vec::new();
        
        // Windows: EnumWindows API 사용
        // macOS: AppleScript 또는 Accessibility API
        // Linux: wmctrl 또는 X11 API
        
        for title in self.get_window_titles() {
            for pattern in &self.blocked_patterns {
                if title.to_lowercase().contains(&pattern.to_lowercase()) {
                    detected.push(title.clone());
                }
            }
        }
        
        detected
    }
    
    /// 차단된 URL 감지 시 브라우저 탭/창 닫기
    pub fn close_blocked_tabs(&self) {
        // 1. 감지된 창에 Alt+F4 (Windows) / Cmd+W (macOS) 전송
        // 2. 또는 전체 브라우저 프로세스 종료 (강력 모드)
        todo!()
    }
    
    #[cfg(target_os = "windows")]
    fn get_window_titles(&self) -> Vec<String> {
        // Windows API를 통한 창 제목 수집
        todo!()
    }
    
    #[cfg(target_os = "macos")]
    fn get_window_titles(&self) -> Vec<String> {
        // AppleScript: tell application "System Events" to get name of every window
        todo!()
    }
}
```

### 4. 타임락 암호화 시스템

```rust
// src-tauri/src/timelock.rs

use aes_gcm::{Aes256Gcm, Key, Nonce};
use aes_gcm::aead::{Aead, NewAead};
use chrono::{DateTime, Utc};
use rand::Rng;

pub struct TimeLock {
    unlock_time: DateTime<Utc>,
    encrypted_config: Vec<u8>,
    nonce: [u8; 12],
    // 키는 메모리에서 즉시 폐기됨 - 복구 불가능!
}

impl TimeLock {
    pub fn create(unlock_time: DateTime<Utc>, config: &BlockConfig) -> Self {
        // 1. 256비트 랜덤 키 생성
        let key_bytes: [u8; 32] = rand::thread_rng().gen();
        let key = Key::from_slice(&key_bytes);
        let cipher = Aes256Gcm::new(key);
        
        // 2. 논스 생성
        let nonce_bytes: [u8; 12] = rand::thread_rng().gen();
        let nonce = Nonce::from_slice(&nonce_bytes);
        
        // 3. 설정 암호화
        let config_bytes = serde_json::to_vec(config).unwrap();
        let encrypted = cipher.encrypt(nonce, config_bytes.as_ref()).unwrap();
        
        // 4. 키 폐기 (스코프 종료 시 자동 drop)
        // key_bytes는 이 함수가 끝나면 메모리에서 사라짐
        
        Self {
            unlock_time,
            encrypted_config: encrypted,
            nonce: nonce_bytes,
        }
    }
    
    pub fn is_locked(&self) -> bool {
        Utc::now() < self.unlock_time
    }
    
    pub fn time_remaining(&self) -> chrono::Duration {
        self.unlock_time - Utc::now()
    }
}
```

---

## 📁 프로젝트 구조

```
focus-lock/
├── CLAUDE.md                 # 이 파일
├── README.md
├── package.json
├── src/                      # Svelte Frontend
│   ├── lib/
│   │   ├── components/
│   │   │   ├── LockButton.svelte
│   │   │   ├── Timer.svelte
│   │   │   ├── BlockList.svelte
│   │   │   └── StatusIndicator.svelte
│   │   └── stores/
│   │       └── lockState.ts
│   ├── routes/
│   │   ├── +page.svelte      # 메인 화면
│   │   └── settings/
│   │       └── +page.svelte  # 설정 화면
│   └── app.html
├── src-tauri/                # Rust Backend
│   ├── Cargo.toml
│   ├── tauri.conf.json
│   ├── src/
│   │   ├── main.rs
│   │   ├── lib.rs
│   │   ├── commands/         # Tauri IPC 커맨드
│   │   │   ├── mod.rs
│   │   │   ├── lock.rs
│   │   │   └── status.rs
│   │   ├── blocker/          # 차단 로직
│   │   │   ├── mod.rs
│   │   │   ├── hosts.rs      # Hosts 파일 조작
│   │   │   ├── process.rs    # 프로세스 킬러
│   │   │   └── browser.rs    # 브라우저 URL 감지
│   │   ├── timelock.rs       # 타임락 암호화
│   │   └── db.rs             # SQLite 연동
│   └── icons/
├── static/
└── tests/
```

---

## ⚙️ Tauri 설정 (tauri.conf.json)

```json
{
  "$schema": "../node_modules/@tauri-apps/cli/config.schema.json",
  "productName": "FocusLock",
  "version": "0.1.0",
  "identifier": "com.focuslock.app",
  "build": {
    "beforeDevCommand": "npm run dev",
    "beforeBuildCommand": "npm run build",
    "devUrl": "http://localhost:5173",
    "frontendDist": "../build"
  },
  "app": {
    "withGlobalTauri": true,
    "windows": [
      {
        "title": "FocusLock",
        "width": 400,
        "height": 600,
        "resizable": false,
        "center": true
      }
    ],
    "security": {
      "csp": null
    }
  },
  "bundle": {
    "active": true,
    "icon": [
      "icons/32x32.png",
      "icons/128x128.png",
      "icons/icon.icns",
      "icons/icon.ico"
    ],
    "windows": {
      "wix": {
        "language": "ko-KR"
      }
    }
  },
  "plugins": {
    "shell": {
      "open": true
    }
  }
}
```

---

## 🔐 권한 요구사항

### Windows
- **관리자 권한 필수**: Hosts 파일 수정에 필요
- `requestedExecutionLevel`: `requireAdministrator`
- 매니페스트 파일에 UAC 설정 추가

### macOS
- **sudo 권한**: `/etc/hosts` 수정에 필요
- **Accessibility 권한**: 창 제목 읽기에 필요
- Info.plist에 권한 설명 추가

### Linux
- **root 권한**: `/etc/hosts` 수정에 필요
- PolicyKit 또는 pkexec 사용 권장

---

## 🎨 UI/UX 가이드라인

### 디자인 원칙
1. **원클릭 잠금**: 복잡한 설정 없이 바로 잠금 가능
2. **공포 유발**: 잠금 중에는 빨간색 경고 UI
3. **카운트다운**: 남은 시간 실시간 표시
4. **우회 불가 강조**: "해제할 방법이 없습니다" 명시

### 색상 팔레트
```css
:root {
  --color-safe: #10b981;      /* 잠금 해제 상태 - 초록 */
  --color-warning: #f59e0b;   /* 설정 중 - 주황 */
  --color-locked: #ef4444;    /* 잠금 상태 - 빨강 */
  --color-bg-dark: #0f172a;   /* 배경 */
  --color-text: #f1f5f9;      /* 텍스트 */
}
```

### 핵심 화면
1. **메인 화면**: 큰 LOCK 버튼 + 잠금 시간 설정
2. **잠금 중 화면**: 카운트다운 + 차단 목록 표시
3. **설정 화면**: 차단 대상 커스터마이징

---

## 🧪 테스트 체크리스트

### 차단 기능 테스트
- [ ] YouTube 웹사이트 접속 불가 확인
- [ ] YouTube 앱(있을 경우) 실행 차단 확인
- [ ] Chzzk 접속 불가 확인
- [ ] Instagram 웹/앱 차단 확인
- [ ] LoL 클라이언트 실행 즉시 종료 확인
- [ ] LoL 관련 웹사이트(op.gg 등) 차단 확인

### 브라우저 감지 테스트
- [ ] Chrome에서 YouTube 접속 시 탭 닫힘 확인
- [ ] Edge에서 Instagram 접속 감지 확인
- [ ] Firefox에서 차단 동작 확인
- [ ] 시크릿/프라이빗 모드에서도 차단 확인

### 타임락 테스트
- [ ] 설정 시간 전 해제 불가능 확인
- [ ] 앱 재시작 후에도 잠금 유지 확인
- [ ] 시스템 시간 변경으로 우회 불가 확인 (NTP 검증)

### 시스템 안정성
- [ ] 관리자 권한 없이 실행 시 적절한 에러 메시지
- [ ] hosts 파일 복구 기능 정상 동작
- [ ] CPU/메모리 사용량 5% 미만 유지

---

## 📋 개발 규칙

### Rust 코드 스타일
```rust
// 1. 에러 처리: Result 타입 적극 활용
pub fn block_sites() -> Result<(), BlockError> { ... }

// 2. 로깅: log 크레이트 사용
log::info!("Blocking {} domains", count);
log::error!("Failed to modify hosts: {}", e);

// 3. 스레드 안전성: Arc<Mutex<T>> 또는 AtomicBool 사용
let running = Arc::new(AtomicBool::new(true));
```

### Frontend 규칙
```typescript
// 1. Tauri 커맨드 호출
import { invoke } from '@tauri-apps/api/core';

const lockResult = await invoke<boolean>('start_lock', { 
  duration: 3600 
});

// 2. 상태 관리: Svelte stores
import { writable } from 'svelte/store';
export const isLocked = writable(false);
```

### Git 커밋 컨벤션
```
feat: 새 기능 추가
fix: 버그 수정
refactor: 코드 리팩토링
docs: 문서 수정
test: 테스트 추가
chore: 빌드/설정 변경
```

---

## 🚨 주의사항 & 한계

1. **관리자 권한 필수**: 앱이 관리자로 실행되지 않으면 hosts 파일 수정 불가
2. **DNS 우회 가능성**: 사용자가 수동으로 DNS 서버를 변경하면 우회 가능 (고급 사용자 한정)
3. **브라우저 확장 프로그램**: 일부 VPN 확장이 hosts 우회 가능
4. **iOS 제약**: 앱 차단 기능 매우 제한적 (Content Blocker만 가능)
5. **시스템 불안정성**: hosts 파일 손상 시 네트워크 문제 발생 가능

---

## 🔮 향후 로드맵

### v0.1 (MVP)
- [x] 프로젝트 설정
- [ ] Hosts 파일 기반 웹사이트 차단
- [ ] LoL 프로세스 킬러
- [ ] 기본 타임락 기능

### v0.2
- [ ] 브라우저 창 제목 감지
- [ ] 차단 목록 커스터마이징 UI
- [ ] 잠금 히스토리 저장

### v0.3
- [ ] Android 지원 (VpnService)
- [ ] 클라우드 동기화 (선택적)
- [ ] 통계 대시보드

### v1.0
- [ ] macOS 완전 지원
- [ ] 다국어 지원
- [ ] 자동 업데이트

---

## 📚 참고 자료

- [Tauri 2.0 공식 문서](https://v2.tauri.app/)
- [sysinfo 크레이트](https://docs.rs/sysinfo/)
- [Windows hosts 파일 경로](https://support.microsoft.com/ko-kr/topic/hosts-파일을-기본값으로-다시-설정하는-방법)
- [AES-GCM 암호화](https://docs.rs/aes-gcm/)

---

*Last Updated: 2025-12-07*
*Version: 0.1.0-draft*