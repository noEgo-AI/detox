use chrono::{DateTime, Duration, Utc};
use rusqlite::{Connection, Result as SqliteResult};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::Mutex;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LockState {
    pub is_locked: bool,
    pub unlock_time: Option<DateTime<Utc>>,
    pub remaining_seconds: i64,
}

pub struct TimeLock {
    db: Mutex<Connection>,
}

impl TimeLock {
    pub fn new(app_data_dir: PathBuf) -> SqliteResult<Self> {
        std::fs::create_dir_all(&app_data_dir).ok();
        let db_path = app_data_dir.join("focuslock.db");
        let conn = Connection::open(db_path)?;

        conn.execute(
            "CREATE TABLE IF NOT EXISTS lock_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                unlock_time TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )",
            [],
        )?;

        Ok(Self {
            db: Mutex::new(conn),
        })
    }

    pub fn start_lock(&self, duration_minutes: i64) -> SqliteResult<LockState> {
        let unlock_time = Utc::now() + Duration::minutes(duration_minutes);
        let db = self.db.lock().unwrap();

        db.execute(
            "INSERT OR REPLACE INTO lock_state (id, unlock_time) VALUES (1, ?1)",
            [unlock_time.to_rfc3339()],
        )?;

        log::info!("Lock started until: {}", unlock_time);

        Ok(LockState {
            is_locked: true,
            unlock_time: Some(unlock_time),
            remaining_seconds: duration_minutes * 60,
        })
    }

    pub fn get_state(&self) -> SqliteResult<LockState> {
        let db = self.db.lock().unwrap();

        let mut stmt = db.prepare("SELECT unlock_time FROM lock_state WHERE id = 1")?;
        let result: Option<String> = stmt
            .query_row([], |row| row.get(0))
            .ok();

        match result {
            Some(unlock_time_str) => {
                if let Ok(unlock_time) = DateTime::parse_from_rfc3339(&unlock_time_str) {
                    let unlock_time = unlock_time.with_timezone(&Utc);
                    let now = Utc::now();

                    if now < unlock_time {
                        let remaining = (unlock_time - now).num_seconds();
                        Ok(LockState {
                            is_locked: true,
                            unlock_time: Some(unlock_time),
                            remaining_seconds: remaining,
                        })
                    } else {
                        // Lock has expired
                        drop(stmt);
                        drop(db);
                        self.clear_lock()?;
                        Ok(LockState {
                            is_locked: false,
                            unlock_time: None,
                            remaining_seconds: 0,
                        })
                    }
                } else {
                    Ok(LockState {
                        is_locked: false,
                        unlock_time: None,
                        remaining_seconds: 0,
                    })
                }
            }
            None => Ok(LockState {
                is_locked: false,
                unlock_time: None,
                remaining_seconds: 0,
            }),
        }
    }

    pub fn clear_lock(&self) -> SqliteResult<()> {
        let db = self.db.lock().unwrap();
        db.execute("DELETE FROM lock_state WHERE id = 1", [])?;
        log::info!("Lock cleared");
        Ok(())
    }

    pub fn is_locked(&self) -> bool {
        self.get_state().map(|s| s.is_locked).unwrap_or(false)
    }
}
