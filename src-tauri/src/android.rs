// Android-specific blocking functionality via Tauri plugin system
use serde::{Deserialize, Serialize};
use tauri::{
    plugin::{Builder, TauriPlugin},
    Manager, Runtime,
};

// Define our own error type for Android
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("Plugin error: {0}")]
    Plugin(String),
}

// Result type alias for this module
pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidPermissions {
    pub vpn: bool,
    pub accessibility: bool,
    pub all_ready: bool,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidLockResult {
    pub success: bool,
    pub error: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidLockState {
    pub is_locked: bool,
    pub remaining_seconds: i64,
    pub unlock_time: Option<i64>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VpnPermissionResult {
    pub granted: bool,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BlockedAppsResult {
    pub apps: Vec<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StartBlockingRequest {
    pub duration_minutes: i32,
}

/// Blocker plugin handle for Android
pub struct Blocker<R: Runtime>(pub tauri::plugin::PluginHandle<R>);

impl<R: Runtime> Blocker<R> {
    pub fn check_permissions(&self) -> Result<AndroidPermissions> {
        self.0
            .run_mobile_plugin("checkBlockerPermissions", ())
            .map_err(|e| Error::Plugin(e.to_string()))
    }

    pub fn request_vpn_permission(&self) -> Result<VpnPermissionResult> {
        self.0
            .run_mobile_plugin("requestVpnPermission", ())
            .map_err(|e| Error::Plugin(e.to_string()))
    }

    pub fn open_accessibility_settings(&self) -> Result<()> {
        self.0
            .run_mobile_plugin::<()>("openAccessibilitySettings", ())
            .map_err(|e| Error::Plugin(e.to_string()))
    }

    pub fn start_blocking(&self, duration_minutes: i32) -> Result<AndroidLockResult> {
        self.0
            .run_mobile_plugin("startBlocking", StartBlockingRequest { duration_minutes })
            .map_err(|e| Error::Plugin(e.to_string()))
    }

    pub fn get_lock_state(&self) -> Result<AndroidLockState> {
        self.0
            .run_mobile_plugin("getLockState", ())
            .map_err(|e| Error::Plugin(e.to_string()))
    }

    pub fn stop_blocking(&self) -> Result<()> {
        self.0
            .run_mobile_plugin::<()>("stopBlocking", ())
            .map_err(|e| Error::Plugin(e.to_string()))
    }

    pub fn get_blocked_apps(&self) -> Result<BlockedAppsResult> {
        self.0
            .run_mobile_plugin("getBlockedApps", ())
            .map_err(|e| Error::Plugin(e.to_string()))
    }
}

/// Initialize the blocker plugin
pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("blocker")
        .setup(|app, api| {
            let handle = api.register_android_plugin("com.focuslock.detox", "BlockerPlugin")?;
            app.manage(Blocker(handle));
            Ok(())
        })
        .build()
}

/// Extension trait to access blocker functionality
pub trait BlockerExt<R: Runtime> {
    fn blocker(&self) -> &Blocker<R>;
}

impl<R: Runtime, T: Manager<R>> BlockerExt<R> for T {
    fn blocker(&self) -> &Blocker<R> {
        self.state::<Blocker<R>>().inner()
    }
}
