use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum HostsError {
    #[error("Failed to read hosts file: {0}")]
    ReadError(#[from] std::io::Error),
    #[error("Permission denied. Run as administrator.")]
    PermissionDenied,
    #[error("Failed to backup hosts file")]
    BackupFailed,
}

pub struct HostsBlocker {
    hosts_path: PathBuf,
    marker_start: String,
    marker_end: String,
}

impl HostsBlocker {
    pub fn new() -> Self {
        let hosts_path = if cfg!(target_os = "windows") {
            PathBuf::from(r"C:\Windows\System32\drivers\etc\hosts")
        } else if cfg!(target_os = "macos") {
            PathBuf::from("/etc/hosts")
        } else {
            PathBuf::from("/etc/hosts")
        };

        Self {
            hosts_path,
            marker_start: "# === FOCUSLOCK START ===".to_string(),
            marker_end: "# === FOCUSLOCK END ===".to_string(),
        }
    }

    pub fn get_blocked_domains() -> Vec<&'static str> {
        vec![
            // YouTube
            "www.youtube.com",
            "youtube.com",
            "m.youtube.com",
            "youtu.be",
            "www.youtube-nocookie.com",
            "youtube-nocookie.com",
            "youtubei.googleapis.com",
            "yt3.ggpht.com",
            "music.youtube.com",
            "studio.youtube.com",
            // Chzzk
            "chzzk.naver.com",
            "api.chzzk.naver.com",
            "live.chzzk.naver.com",
            "m.chzzk.naver.com",
            // Instagram
            "www.instagram.com",
            "instagram.com",
            "i.instagram.com",
            "graph.instagram.com",
            "api.instagram.com",
            "l.instagram.com",
            "static.cdninstagram.com",
            "scontent.cdninstagram.com",
            // League of Legends related
            "www.leagueoflegends.com",
            "leagueoflegends.com",
            "signup.leagueoflegends.com",
            "authenticate.riotgames.com",
            "www.op.gg",
            "op.gg",
            "www.fow.kr",
            "fow.kr",
        ]
    }

    pub fn block(&self) -> Result<(), HostsError> {
        let domains = Self::get_blocked_domains();

        // Read current hosts file
        let content = fs::read_to_string(&self.hosts_path)?;

        // Check if already blocked
        if content.contains(&self.marker_start) {
            // Already has our block, remove it first
            self.unblock()?;
        }

        // Re-read after potential unblock
        let _content = fs::read_to_string(&self.hosts_path)?;

        // Build block entries
        let mut block_entries = String::new();
        block_entries.push_str(&format!("\n{}\n", self.marker_start));
        for domain in &domains {
            block_entries.push_str(&format!("127.0.0.1 {}\n", domain));
        }
        block_entries.push_str(&format!("{}\n", self.marker_end));

        // Append to hosts file
        let mut file = OpenOptions::new()
            .write(true)
            .append(true)
            .open(&self.hosts_path)
            .map_err(|_| HostsError::PermissionDenied)?;

        file.write_all(block_entries.as_bytes())?;

        // Flush DNS cache on Windows
        #[cfg(target_os = "windows")]
        {
            let _ = std::process::Command::new("ipconfig")
                .args(["/flushdns"])
                .output();
        }

        log::info!("Blocked {} domains", domains.len());
        Ok(())
    }

    pub fn unblock(&self) -> Result<(), HostsError> {
        let content = fs::read_to_string(&self.hosts_path)?;

        let mut new_content = String::new();
        let mut in_block = false;

        for line in content.lines() {
            if line.contains(&self.marker_start) {
                in_block = true;
                continue;
            }
            if line.contains(&self.marker_end) {
                in_block = false;
                continue;
            }
            if !in_block {
                new_content.push_str(line);
                new_content.push('\n');
            }
        }

        // Remove trailing newlines
        while new_content.ends_with("\n\n") {
            new_content.pop();
        }

        fs::write(&self.hosts_path, new_content)
            .map_err(|_| HostsError::PermissionDenied)?;

        // Flush DNS cache
        #[cfg(target_os = "windows")]
        {
            let _ = std::process::Command::new("ipconfig")
                .args(["/flushdns"])
                .output();
        }

        log::info!("Unblocked all domains");
        Ok(())
    }

    pub fn is_blocked(&self) -> bool {
        if let Ok(content) = fs::read_to_string(&self.hosts_path) {
            content.contains(&self.marker_start)
        } else {
            false
        }
    }
}

impl Default for HostsBlocker {
    fn default() -> Self {
        Self::new()
    }
}
