// Desktop-only modules
#[cfg(not(target_os = "android"))]
mod blocker;
#[cfg(not(target_os = "android"))]
mod timelock;

// Android plugin module
#[cfg(target_os = "android")]
mod android;
#[cfg(target_os = "android")]
mod timelock;

#[cfg(not(target_os = "android"))]
use blocker::{HostsBlocker, ProcessWatcher};
#[cfg(not(target_os = "android"))]
use timelock::{LockState, TimeLock};

#[cfg(target_os = "android")]
use timelock::LockState;

#[cfg(not(target_os = "android"))]
use std::sync::Mutex;
#[cfg(not(target_os = "android"))]
use tauri::{Manager, State};
#[cfg(target_os = "android")]
use tauri::Manager;

#[cfg(not(target_os = "android"))]
use tauri::{
    image::Image,
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    WindowEvent,
};

#[cfg(not(target_os = "android"))]
use tauri_plugin_autostart::MacosLauncher;

// App state for desktop
#[cfg(not(target_os = "android"))]
struct AppState {
    timelock: TimeLock,
    hosts_blocker: HostsBlocker,
    process_watcher: ProcessWatcher,
}

// ============ Desktop Commands ============

#[cfg(not(target_os = "android"))]
#[tauri::command]
fn start_lock(state: State<Mutex<AppState>>, duration_minutes: i64) -> Result<LockState, String> {
    let state = state.lock().unwrap();

    let lock_state = state
        .timelock
        .start_lock(duration_minutes)
        .map_err(|e| e.to_string())?;

    state
        .hosts_blocker
        .block()
        .map_err(|e| e.to_string())?;
    state.process_watcher.start();

    Ok(lock_state)
}

#[cfg(not(target_os = "android"))]
#[tauri::command]
fn get_lock_state(state: State<Mutex<AppState>>) -> Result<LockState, String> {
    let state = state.lock().unwrap();
    let lock_state = state.timelock.get_state().map_err(|e| e.to_string())?;

    if !lock_state.is_locked && state.hosts_blocker.is_blocked() {
        let _ = state.hosts_blocker.unblock();
        state.process_watcher.stop();
    }

    Ok(lock_state)
}

#[cfg(not(target_os = "android"))]
#[tauri::command]
fn emergency_unlock(state: State<Mutex<AppState>>) -> Result<(), String> {
    let state = state.lock().unwrap();

    state.timelock.clear_lock().map_err(|e| e.to_string())?;
    state.hosts_blocker.unblock().map_err(|e| e.to_string())?;
    state.process_watcher.stop();

    Ok(())
}

#[cfg(not(target_os = "android"))]
#[tauri::command]
fn get_blocked_domains() -> Vec<&'static str> {
    HostsBlocker::get_blocked_domains()
}

#[cfg(not(target_os = "android"))]
#[tauri::command]
fn enable_autostart(app: tauri::AppHandle) -> Result<(), String> {
    use tauri_plugin_autostart::ManagerExt;
    app.autolaunch().enable().map_err(|e| e.to_string())
}

#[cfg(not(target_os = "android"))]
#[tauri::command]
fn disable_autostart(app: tauri::AppHandle) -> Result<(), String> {
    use tauri_plugin_autostart::ManagerExt;
    app.autolaunch().disable().map_err(|e| e.to_string())
}

#[cfg(not(target_os = "android"))]
#[tauri::command]
fn is_autostart_enabled(app: tauri::AppHandle) -> Result<bool, String> {
    use tauri_plugin_autostart::ManagerExt;
    app.autolaunch().is_enabled().map_err(|e| e.to_string())
}

// ============ Android Commands ============
// These call through to the Kotlin plugin

#[cfg(target_os = "android")]
#[tauri::command]
fn start_lock(app: tauri::AppHandle, duration_minutes: i64) -> Result<LockState, String> {
    use android::BlockerExt;

    let result = app.blocker().start_blocking(duration_minutes as i32)
        .map_err(|e| format!("Failed to start blocking: {:?}", e))?;

    if result.success {
        let state = app.blocker().get_lock_state()
            .map_err(|e| format!("Failed to get lock state: {:?}", e))?;

        Ok(LockState {
            is_locked: state.is_locked,
            unlock_time: state.unlock_time.map(|t| {
                chrono::DateTime::from_timestamp_millis(t)
                    .unwrap_or_else(chrono::Utc::now)
            }),
            remaining_seconds: state.remaining_seconds,
        })
    } else {
        Err(result.error.unwrap_or_else(|| "Unknown error".to_string()))
    }
}

#[cfg(target_os = "android")]
#[tauri::command]
fn get_lock_state(app: tauri::AppHandle) -> Result<LockState, String> {
    use android::BlockerExt;

    let state = app.blocker().get_lock_state()
        .map_err(|e| format!("Failed to get lock state: {:?}", e))?;

    Ok(LockState {
        is_locked: state.is_locked,
        unlock_time: state.unlock_time.map(|t| {
            chrono::DateTime::from_timestamp_millis(t)
                .unwrap_or_else(chrono::Utc::now)
        }),
        remaining_seconds: state.remaining_seconds,
    })
}

#[cfg(target_os = "android")]
#[tauri::command]
fn emergency_unlock(app: tauri::AppHandle) -> Result<(), String> {
    use android::BlockerExt;

    app.blocker().stop_blocking()
        .map_err(|e| format!("Failed to stop blocking: {:?}", e))?;
    Ok(())
}

#[cfg(target_os = "android")]
#[tauri::command]
fn get_blocked_domains(app: tauri::AppHandle) -> Vec<String> {
    use android::BlockerExt;

    app.blocker().get_blocked_apps()
        .map(|r| r.apps)
        .unwrap_or_default()
}

#[cfg(target_os = "android")]
#[tauri::command]
fn check_permissions(app: tauri::AppHandle) -> Result<android::AndroidPermissions, String> {
    use android::BlockerExt;

    app.blocker().check_permissions()
        .map_err(|e| format!("Failed to check permissions: {:?}", e))
}

#[cfg(target_os = "android")]
#[tauri::command]
fn request_vpn_permission(app: tauri::AppHandle) -> Result<android::VpnPermissionResult, String> {
    use android::BlockerExt;

    app.blocker().request_vpn_permission()
        .map_err(|e| format!("Failed to request VPN permission: {:?}", e))
}

#[cfg(target_os = "android")]
#[tauri::command]
fn open_accessibility_settings(app: tauri::AppHandle) -> Result<(), String> {
    use android::BlockerExt;

    app.blocker().open_accessibility_settings()
        .map_err(|e| format!("Failed to open accessibility settings: {:?}", e))
}

// Stub commands for Android (not needed on mobile)
#[cfg(target_os = "android")]
#[tauri::command]
fn enable_autostart() -> Result<(), String> {
    Ok(())
}

#[cfg(target_os = "android")]
#[tauri::command]
fn disable_autostart() -> Result<(), String> {
    Ok(())
}

#[cfg(target_os = "android")]
#[tauri::command]
fn is_autostart_enabled() -> Result<bool, String> {
    Ok(false)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    env_logger::init();

    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_opener::init());

    // Desktop-only: Add autostart plugin
    #[cfg(not(target_os = "android"))]
    let builder = builder.plugin(tauri_plugin_autostart::init(
        MacosLauncher::LaunchAgent,
        None,
    ));

    // Android: Add blocker plugin
    #[cfg(target_os = "android")]
    let builder = builder.plugin(android::init());

    builder
        .setup(|app| {
            // Desktop-only setup
            #[cfg(not(target_os = "android"))]
            {
                let app_data_dir = app
                    .path()
                    .app_data_dir()
                    .expect("Failed to get app data dir");

                let timelock = TimeLock::new(app_data_dir).expect("Failed to initialize timelock");
                let hosts_blocker = HostsBlocker::new();
                let process_watcher = ProcessWatcher::new();

                // Check if there's an existing lock and resume blocking
                let is_locked = if let Ok(state) = timelock.get_state() {
                    if state.is_locked {
                        log::info!("Resuming existing lock");
                        let _ = hosts_blocker.block();
                        process_watcher.start();
                        true
                    } else {
                        false
                    }
                } else {
                    false
                };

                // Enable autostart if locked
                if is_locked {
                    use tauri_plugin_autostart::ManagerExt;
                    let _ = app.autolaunch().enable();
                }

                app.manage(Mutex::new(AppState {
                    timelock,
                    hosts_blocker,
                    process_watcher,
                }));

                // Setup tray icon
                let show_item = MenuItem::with_id(app, "show", "열기", true, None::<&str>)?;
                let status_item = MenuItem::with_id(
                    app,
                    "status",
                    if is_locked { "🔒 잠금 중" } else { "🔓 잠금 해제됨" },
                    false,
                    None::<&str>,
                )?;
                let quit_item = MenuItem::with_id(app, "quit", "종료", !is_locked, None::<&str>)?;

                let menu = Menu::with_items(app, &[&status_item, &show_item, &quit_item])?;

                let icon_bytes = include_bytes!("../icons/32x32.png");
                let icon = Image::from_bytes(icon_bytes)?;

                let _tray = TrayIconBuilder::new()
                    .icon(icon)
                    .menu(&menu)
                    .tooltip("FocusLock")
                    .on_menu_event(|app, event| match event.id.as_ref() {
                        "show" => {
                            if let Some(window) = app.get_webview_window("main") {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                        "quit" => {
                            let state = app.state::<Mutex<AppState>>();
                            let state = state.lock().unwrap();
                            if let Ok(lock_state) = state.timelock.get_state() {
                                if !lock_state.is_locked {
                                    app.exit(0);
                                }
                            }
                        }
                        _ => {}
                    })
                    .on_tray_icon_event(|tray, event| {
                        if let TrayIconEvent::Click {
                            button: MouseButton::Left,
                            button_state: MouseButtonState::Up,
                            ..
                        } = event
                        {
                            let app = tray.app_handle();
                            if let Some(window) = app.get_webview_window("main") {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                    })
                    .build(app)?;

                // Handle window close event
                let main_window = app.get_webview_window("main").unwrap();
                let app_handle = app.handle().clone();

                main_window.on_window_event(move |event| {
                    if let WindowEvent::CloseRequested { api, .. } = event {
                        let state = app_handle.state::<Mutex<AppState>>();
                        let state = state.lock().unwrap();

                        if let Ok(lock_state) = state.timelock.get_state() {
                            if lock_state.is_locked {
                                api.prevent_close();
                                if let Some(window) = app_handle.get_webview_window("main") {
                                    let _ = window.hide();
                                }
                            }
                        }
                    }
                });
            }

            // Android setup - nothing needed, plugin handles everything
            #[cfg(target_os = "android")]
            {
                let _ = app;  // suppress unused warning
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            start_lock,
            get_lock_state,
            emergency_unlock,
            get_blocked_domains,
            enable_autostart,
            disable_autostart,
            is_autostart_enabled,
            #[cfg(target_os = "android")]
            check_permissions,
            #[cfg(target_os = "android")]
            request_vpn_permission,
            #[cfg(target_os = "android")]
            open_accessibility_settings,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
