use std::sync::{Mutex, MutexGuard};

/// Recover state after a worker panic instead of turning one poisoned mutex
/// into a permanent cascade of virtual-camera failures. This helper never
/// acquires a second lock, so it adds no lock-order or reentrancy edge.
pub trait RecoverMutex<T> {
    fn lock_recover(&self) -> MutexGuard<'_, T>;
}

impl<T> RecoverMutex<T> for Mutex<T> {
    fn lock_recover(&self) -> MutexGuard<'_, T> {
        match self.lock() {
            Ok(guard) => guard,
            Err(poisoned) => {
                eprintln!("OpenCamBridge recovered a poisoned desktop state mutex");
                let guard = poisoned.into_inner();
                self.clear_poison();
                guard
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::RecoverMutex;
    use std::sync::{Arc, Mutex};

    #[test]
    fn poisoned_virtual_camera_state_does_not_disable_future_operations() {
        let state = Arc::new(Mutex::new(1u32));
        let worker_state = Arc::clone(&state);
        let _ = std::thread::spawn(move || {
            let mut guard = worker_state.lock().unwrap();
            *guard = 2;
            panic!("simulated virtual-camera worker panic");
        })
        .join();

        assert!(state.is_poisoned());
        *state.lock_recover() = 3;
        assert!(!state.is_poisoned());
        assert_eq!(*state.lock_recover(), 3);
    }
}
