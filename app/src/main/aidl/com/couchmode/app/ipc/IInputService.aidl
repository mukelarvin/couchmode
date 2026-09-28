// Phase 2 scope only: prove the Shizuku round-trip works. Phase 3 replaces
// ping() with real /dev/input/eventX enumeration (see spec.md, PLAN.md).
package com.couchmode.app.ipc;

interface IInputService {
    // Returns the PID of *this* remote process, so the caller can confirm
    // it's genuinely running as a separate shell/root process, not just that
    // a Binder connection exists.
    int ping() = 1;

    // Required by Shizuku's UserService contract: the server calls this at
    // a fixed transaction code to tell the service to clean up and exit.
    // Do not remove or renumber — see:
    // https://github.com/RikkaApps/Shizuku-API (User Service docs)
    void destroy() = 16777114;
}
