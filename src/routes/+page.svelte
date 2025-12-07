<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import { invoke } from '@tauri-apps/api/core';

  interface LockState {
    is_locked: boolean;
    unlock_time: string | null;
    remaining_seconds: number;
  }

  interface AndroidPermissions {
    vpn: boolean;
    accessibility: boolean;
    allReady: boolean;
  }

  let lockState: LockState = $state({
    is_locked: false,
    unlock_time: null,
    remaining_seconds: 0
  });

  // Platform detection
  let isAndroid = $state(false);
  let androidPermissions: AndroidPermissions = $state({ vpn: false, accessibility: false, allReady: false });
  let showPermissionSetup = $state(false);

  // Mode: 'duration' or 'date'
  let mode: 'duration' | 'date' = $state('duration');

  // Duration mode
  let durationHours = $state(1);
  let durationMinutes = $state(0);

  // Date mode
  let targetDate = $state('');
  let targetTime = $state('');

  let isLoading = $state(false);
  let errorMessage = $state('');
  let blockedDomains: string[] = $state([]);
  let showDomains = $state(false);
  let interval: number | null = null;

  onMount(async () => {
    // Platform detection via user agent
    isAndroid = /android/i.test(navigator.userAgent);

    await refreshState();
    await loadBlockedDomains();

    interval = setInterval(refreshState, 1000) as unknown as number;

    // Default date/time (tomorrow)
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    targetDate = tomorrow.toISOString().split('T')[0];
    targetTime = '09:00';

    // Check Android permissions
    if (isAndroid) {
      await checkAndroidPermissions();
    }
  });

  onDestroy(() => {
    if (interval) clearInterval(interval);
  });

  async function checkAndroidPermissions() {
    if (!isAndroid) return;

    try {
      androidPermissions = await invoke<AndroidPermissions>('check_permissions');
    } catch (e) {
      console.error('Failed to check permissions:', e);
    }
  }

  async function refreshState() {
    try {
      lockState = await invoke<LockState>('get_lock_state');
    } catch (e) {
      console.error('Failed to get lock state:', e);
    }
  }

  async function loadBlockedDomains() {
    try {
      blockedDomains = await invoke<string[]>('get_blocked_domains');
    } catch (e) {
      console.error('Failed to load blocked domains:', e);
    }
  }

  function calculateMinutes(): number {
    if (mode === 'duration') {
      return durationHours * 60 + durationMinutes;
    } else {
      const target = new Date(`${targetDate}T${targetTime}`);
      const now = new Date();
      const diffMs = target.getTime() - now.getTime();
      return Math.max(1, Math.floor(diffMs / 60000));
    }
  }

  async function startLock() {
    const totalMinutes = calculateMinutes();

    if (totalMinutes < 1) {
      errorMessage = '최소 1분 이상 설정해주세요.';
      return;
    }

    if (mode === 'date') {
      const target = new Date(`${targetDate}T${targetTime}`);
      if (target <= new Date()) {
        errorMessage = '미래의 날짜와 시간을 선택해주세요.';
        return;
      }
    }

    isLoading = true;
    errorMessage = '';

    try {
      // Android: Check permissions first
      if (isAndroid) {
        await checkAndroidPermissions();

        if (!androidPermissions.allReady) {
          showPermissionSetup = true;
          isLoading = false;
          return;
        }
      }

      // Start lock via Tauri command
      lockState = await invoke<LockState>('start_lock', { durationMinutes: totalMinutes });

      // Desktop: Enable autostart
      if (!isAndroid) {
        await invoke('enable_autostart');
      }
    } catch (e: any) {
      const errStr = e.toString();
      if (errStr.includes('VPN_PERMISSION_REQUIRED')) {
        showPermissionSetup = true;
        errorMessage = 'VPN 권한이 필요합니다.';
      } else if (errStr.includes('ACCESSIBILITY_PERMISSION_REQUIRED')) {
        showPermissionSetup = true;
        errorMessage = '접근성 권한이 필요합니다.';
      } else if (errStr.includes('Permission')) {
        errorMessage = '관리자 권한으로 실행해주세요.';
      } else {
        errorMessage = `오류: ${e}`;
      }
    } finally {
      isLoading = false;
    }
  }

  async function requestVpnPermission() {
    try {
      await invoke('request_vpn_permission');
      // Refresh permissions after request
      setTimeout(checkAndroidPermissions, 1000);
    } catch (e) {
      console.error('Failed to request VPN permission:', e);
    }
  }

  async function openAccessibilitySettings() {
    try {
      await invoke('open_accessibility_settings');
    } catch (e) {
      console.error('Failed to open accessibility settings:', e);
    }
  }

  function formatTime(seconds: number): string {
    const days = Math.floor(seconds / 86400);
    const h = Math.floor((seconds % 86400) / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;

    if (days > 0) {
      return `${days}일 ${h}시간 ${m}분`;
    } else if (h > 0) {
      return `${h}시간 ${m}분 ${s}초`;
    } else if (m > 0) {
      return `${m}분 ${s}초`;
    } else {
      return `${s}초`;
    }
  }

  function formatTimeDigital(seconds: number): string {
    const days = Math.floor(seconds / 86400);
    const h = Math.floor((seconds % 86400) / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;

    if (days > 0) {
      return `${days}일 ${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
    }
    return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  }

  function formatUnlockTime(isoString: string | null): string {
    if (!isoString) return '';
    const date = new Date(isoString);
    const month = date.getMonth() + 1;
    const day = date.getDate();
    const hours = date.getHours();
    const minutes = date.getMinutes();
    return `${month}월 ${day}일 ${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}`;
  }

  function setPreset(hours: number) {
    mode = 'duration';
    durationHours = hours;
    durationMinutes = 0;
  }

  function setDatePreset(days: number) {
    mode = 'date';
    const target = new Date();
    target.setDate(target.getDate() + days);
    targetDate = target.toISOString().split('T')[0];
    targetTime = '09:00';
  }

  function getMinDate(): string {
    return new Date().toISOString().split('T')[0];
  }
</script>

<main class="min-h-screen bg-bg-dark p-6 flex flex-col">
  <!-- Header -->
  <div class="text-center mb-6">
    <h1 class="text-3xl font-bold text-white mb-2">FocusLock</h1>
    <p class="text-slate-400 text-sm">의지력에 의존하지 않는다. 시스템이 강제한다.</p>
    {#if isAndroid}
      <span class="text-xs text-slate-600 mt-1 block">Android</span>
    {/if}
  </div>

  <!-- Android Permission Setup Modal -->
  {#if isAndroid && showPermissionSetup}
    <div class="fixed inset-0 bg-black/80 flex items-center justify-center z-50 p-6">
      <div class="bg-bg-card rounded-2xl p-6 w-full max-w-sm">
        <h3 class="text-xl font-bold text-white mb-4">권한 설정 필요</h3>
        <p class="text-slate-400 text-sm mb-6">
          앱과 웹사이트를 차단하려면 다음 권한이 필요합니다:
        </p>

        <!-- VPN Permission -->
        <div class="mb-4">
          <div class="flex items-center justify-between mb-2">
            <span class="text-white">VPN 권한</span>
            {#if androidPermissions.vpn}
              <span class="text-safe text-sm">✓ 허용됨</span>
            {:else}
              <span class="text-red-400 text-sm">필요</span>
            {/if}
          </div>
          {#if !androidPermissions.vpn}
            <button
              onclick={requestVpnPermission}
              class="w-full bg-slate-700 hover:bg-slate-600 text-white py-2 rounded-lg text-sm"
            >
              VPN 권한 요청
            </button>
          {/if}
        </div>

        <!-- Accessibility Permission -->
        <div class="mb-6">
          <div class="flex items-center justify-between mb-2">
            <span class="text-white">접근성 권한</span>
            {#if androidPermissions.accessibility}
              <span class="text-safe text-sm">✓ 허용됨</span>
            {:else}
              <span class="text-red-400 text-sm">필요</span>
            {/if}
          </div>
          {#if !androidPermissions.accessibility}
            <button
              onclick={openAccessibilitySettings}
              class="w-full bg-slate-700 hover:bg-slate-600 text-white py-2 rounded-lg text-sm"
            >
              접근성 설정 열기
            </button>
            <p class="text-slate-500 text-xs mt-2">
              설정에서 "FocusLock"을 찾아 활성화하세요
            </p>
          {/if}
        </div>

        <div class="flex gap-2">
          <button
            onclick={() => { showPermissionSetup = false; checkAndroidPermissions(); }}
            class="flex-1 bg-slate-600 hover:bg-slate-500 text-white py-3 rounded-lg"
          >
            닫기
          </button>
          {#if androidPermissions.allReady}
            <button
              onclick={() => { showPermissionSetup = false; startLock(); }}
              class="flex-1 bg-safe hover:bg-green-600 text-white py-3 rounded-lg"
            >
              잠금 시작
            </button>
          {/if}
        </div>
      </div>
    </div>
  {/if}

  {#if lockState.is_locked}
    <!-- Locked State -->
    <div class="flex-1 flex flex-col items-center justify-center">
      <div class="bg-red-500/10 border-2 border-red-500 rounded-2xl p-8 w-full max-w-sm text-center">
        <div class="text-6xl mb-4">🔒</div>
        <h2 class="text-2xl font-bold text-red-500 mb-4">잠금 중</h2>

        <div class="text-4xl font-mono text-white mb-2">
          {formatTimeDigital(lockState.remaining_seconds)}
        </div>

        <p class="text-slate-400 text-sm mb-2">
          남은 시간: {formatTime(lockState.remaining_seconds)}
        </p>

        {#if lockState.unlock_time}
          <p class="text-slate-500 text-xs mb-6">
            해제 예정: {formatUnlockTime(lockState.unlock_time)}
          </p>
        {/if}

        <div class="bg-red-500/20 rounded-lg p-4 mb-6">
          <p class="text-red-400 text-sm font-medium">
            해제할 방법이 없습니다
          </p>
          <p class="text-slate-500 text-xs mt-1">
            설정된 시간이 지나야 자동으로 해제됩니다
          </p>
        </div>

        <!-- Blocked items indicator -->
        <div class="text-left">
          <p class="text-slate-400 text-xs mb-2">차단 중:</p>
          <div class="flex flex-wrap gap-2">
            <span class="bg-slate-700 text-slate-300 text-xs px-2 py-1 rounded">YouTube</span>
            <span class="bg-slate-700 text-slate-300 text-xs px-2 py-1 rounded">Instagram</span>
            <span class="bg-slate-700 text-slate-300 text-xs px-2 py-1 rounded">Chzzk</span>
            <span class="bg-slate-700 text-slate-300 text-xs px-2 py-1 rounded">LoL</span>
          </div>
        </div>
      </div>
    </div>
  {:else}
    <!-- Unlocked State -->
    <div class="flex-1 flex flex-col">
      <!-- Android Permission Status -->
      {#if isAndroid && !androidPermissions.allReady}
        <button
          onclick={() => showPermissionSetup = true}
          class="bg-yellow-500/20 border border-yellow-500 rounded-lg p-4 mb-4 text-left"
        >
          <p class="text-yellow-400 text-sm font-medium">권한 설정 필요</p>
          <p class="text-slate-400 text-xs mt-1">탭하여 권한을 설정하세요</p>
        </button>
      {/if}

      <!-- Mode Toggle -->
      <div class="flex gap-2 mb-4">
        <button
          onclick={() => mode = 'duration'}
          class="flex-1 py-2 rounded-lg text-sm font-medium transition-colors {mode === 'duration' ? 'bg-safe text-white' : 'bg-slate-700 text-slate-400'}"
        >
          시간으로 설정
        </button>
        <button
          onclick={() => mode = 'date'}
          class="flex-1 py-2 rounded-lg text-sm font-medium transition-colors {mode === 'date' ? 'bg-safe text-white' : 'bg-slate-700 text-slate-400'}"
        >
          날짜로 설정
        </button>
      </div>

      <!-- Duration Setting -->
      {#if mode === 'duration'}
        <div class="bg-bg-card rounded-2xl p-6 mb-4">
          <h3 class="text-lg font-semibold text-white mb-4">잠금 시간 설정</h3>

          <div class="flex items-center gap-4 mb-4">
            <div class="flex-1">
              <label for="hours" class="text-slate-400 text-sm block mb-2">시간</label>
              <select
                id="hours"
                bind:value={durationHours}
                class="w-full bg-slate-700 text-white rounded-lg px-4 py-3 outline-none focus:ring-2 focus:ring-safe"
              >
                {#each Array.from({ length: 25 }, (_, i) => i) as h}
                  <option value={h}>{h}시간</option>
                {/each}
              </select>
            </div>
            <div class="flex-1">
              <label for="minutes" class="text-slate-400 text-sm block mb-2">분</label>
              <select
                id="minutes"
                bind:value={durationMinutes}
                class="w-full bg-slate-700 text-white rounded-lg px-4 py-3 outline-none focus:ring-2 focus:ring-safe"
              >
                {#each [0, 15, 30, 45] as m}
                  <option value={m}>{m}분</option>
                {/each}
              </select>
            </div>
          </div>

          <!-- Quick presets -->
          <div class="flex gap-2">
            <button
              onclick={() => setPreset(1)}
              class="flex-1 bg-slate-700 hover:bg-slate-600 text-slate-300 text-sm py-2 rounded-lg transition-colors"
            >
              1시간
            </button>
            <button
              onclick={() => setPreset(2)}
              class="flex-1 bg-slate-700 hover:bg-slate-600 text-slate-300 text-sm py-2 rounded-lg transition-colors"
            >
              2시간
            </button>
            <button
              onclick={() => setPreset(4)}
              class="flex-1 bg-slate-700 hover:bg-slate-600 text-slate-300 text-sm py-2 rounded-lg transition-colors"
            >
              4시간
            </button>
          </div>
        </div>
      {:else}
        <!-- Date Setting -->
        <div class="bg-bg-card rounded-2xl p-6 mb-4">
          <h3 class="text-lg font-semibold text-white mb-4">해제 날짜 선택</h3>

          <div class="flex items-center gap-4 mb-4">
            <div class="flex-1">
              <label for="date" class="text-slate-400 text-sm block mb-2">날짜</label>
              <input
                id="date"
                type="date"
                bind:value={targetDate}
                min={getMinDate()}
                class="w-full bg-slate-700 text-white rounded-lg px-4 py-3 outline-none focus:ring-2 focus:ring-safe"
              />
            </div>
            <div class="flex-1">
              <label for="time" class="text-slate-400 text-sm block mb-2">시간</label>
              <input
                id="time"
                type="time"
                bind:value={targetTime}
                class="w-full bg-slate-700 text-white rounded-lg px-4 py-3 outline-none focus:ring-2 focus:ring-safe"
              />
            </div>
          </div>

          <!-- Quick date presets -->
          <div class="flex gap-2">
            <button
              onclick={() => setDatePreset(1)}
              class="flex-1 bg-slate-700 hover:bg-slate-600 text-slate-300 text-sm py-2 rounded-lg transition-colors"
            >
              내일
            </button>
            <button
              onclick={() => setDatePreset(3)}
              class="flex-1 bg-slate-700 hover:bg-slate-600 text-slate-300 text-sm py-2 rounded-lg transition-colors"
            >
              3일 후
            </button>
            <button
              onclick={() => setDatePreset(7)}
              class="flex-1 bg-slate-700 hover:bg-slate-600 text-slate-300 text-sm py-2 rounded-lg transition-colors"
            >
              1주일
            </button>
          </div>
        </div>
      {/if}

      <!-- Block Targets -->
      <div class="bg-bg-card rounded-2xl p-6 mb-4">
        <button
          onclick={() => showDomains = !showDomains}
          class="w-full flex items-center justify-between text-left"
        >
          <h3 class="text-lg font-semibold text-white">차단 대상</h3>
          <span class="text-slate-400">{showDomains ? '▲' : '▼'}</span>
        </button>

        <div class="flex flex-wrap gap-2 mt-4">
          <span class="bg-red-500/20 text-red-400 text-sm px-3 py-1 rounded-full">YouTube</span>
          <span class="bg-pink-500/20 text-pink-400 text-sm px-3 py-1 rounded-full">Instagram</span>
          <span class="bg-green-500/20 text-green-400 text-sm px-3 py-1 rounded-full">Chzzk</span>
          <span class="bg-blue-500/20 text-blue-400 text-sm px-3 py-1 rounded-full">LoL</span>
        </div>

        {#if showDomains}
          <div class="mt-4 max-h-40 overflow-y-auto">
            <p class="text-slate-500 text-xs mb-2">
              {isAndroid ? '차단될 앱 목록:' : '차단될 도메인 목록:'}
            </p>
            <div class="text-xs text-slate-400 space-y-1">
              {#each blockedDomains as item}
                <div class="font-mono">{item}</div>
              {/each}
            </div>
          </div>
        {/if}
      </div>

      <!-- Error Message -->
      {#if errorMessage}
        <div class="bg-red-500/20 border border-red-500 rounded-lg p-4 mb-4">
          <p class="text-red-400 text-sm">{errorMessage}</p>
        </div>
      {/if}

      <!-- Lock Button -->
      <div class="mt-auto">
        <button
          onclick={startLock}
          disabled={isLoading}
          class="w-full bg-locked hover:bg-red-600 disabled:bg-slate-600 text-white font-bold text-xl py-5 rounded-2xl transition-colors shadow-lg shadow-red-500/25"
        >
          {#if isLoading}
            잠금 중...
          {:else}
            🔒 지금 잠금 시작
          {/if}
        </button>

        <p class="text-center text-slate-500 text-xs mt-4">
          잠금이 시작되면 해제할 수 없습니다
        </p>
      </div>
    </div>
  {/if}
</main>
