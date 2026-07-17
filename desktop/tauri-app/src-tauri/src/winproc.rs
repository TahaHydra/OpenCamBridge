//! Shared Windows process-creation constants.
//!
//! The desktop app is a GUI process (`windows_subsystem = "windows"`), so any
//! console child it spawns *without* this flag is given its own console window.
//! That console both flashes visibly (an implementation detail leaking into the
//! product UI) and attaches the child to a console control group, so a console
//! close/Ctrl event terminates the child with `STATUS_CONTROL_C_EXIT`
//! (`0xC000013A`) — which the UI then reported as a producer crash.
//!
//! Spawning with `CREATE_NO_WINDOW` gives the child no console at all, which
//! fixes both problems while leaving piped stdout/stderr capture intact.

/// `CREATE_NO_WINDOW` from `winbase.h`. Passed to `CommandExt::creation_flags`.
pub const CREATE_NO_WINDOW: u32 = 0x0800_0000;
