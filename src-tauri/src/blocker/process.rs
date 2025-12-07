use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;
use sysinfo::System;

pub struct ProcessWatcher {
    running: Arc<AtomicBool>,
    targets: Vec<String>,
}

impl ProcessWatcher {
    pub fn new() -> Self {
        Self {
            running: Arc::new(AtomicBool::new(false)),
            targets: Self::get_target_processes(),
        }
    }

    fn get_target_processes() -> Vec<String> {
        vec![
            // League of Legends - Windows
            "LeagueClient.exe".to_string(),
            "LeagueClientUx.exe".to_string(),
            "League of Legends.exe".to_string(),
            "RiotClientServices.exe".to_string(),
            "RiotClientUx.exe".to_string(),
            "RiotClientCrashHandler.exe".to_string(),
            // League of Legends - macOS
            "LeagueClient".to_string(),
            "League of Legends".to_string(),
            "RiotClient".to_string(),
            // Chzzk related
            "NaverGameLauncher.exe".to_string(),
            "CHZZK.exe".to_string(),
        ]
    }

    pub fn start(&self) {
        if self.running.load(Ordering::Relaxed) {
            return; // Already running
        }

        self.running.store(true, Ordering::Relaxed);
        let running = self.running.clone();
        let targets = self.targets.clone();

        thread::spawn(move || {
            let mut sys = System::new();

            while running.load(Ordering::Relaxed) {
                sys.refresh_processes(sysinfo::ProcessesToUpdate::All);

                for (pid, process) in sys.processes() {
                    let name = process.name().to_string_lossy().to_string();

                    for target in &targets {
                        if name.eq_ignore_ascii_case(target) {
                            log::info!("Killing blocked process: {} (PID: {})", name, pid);
                            process.kill();
                        }
                    }
                }

                thread::sleep(Duration::from_secs(2));
            }

            log::info!("Process watcher stopped");
        });

        log::info!("Process watcher started");
    }

    pub fn stop(&self) {
        self.running.store(false, Ordering::Relaxed);
    }

    pub fn is_running(&self) -> bool {
        self.running.load(Ordering::Relaxed)
    }
}

impl Default for ProcessWatcher {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for ProcessWatcher {
    fn drop(&mut self) {
        self.stop();
    }
}
