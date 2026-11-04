#include "timer.h"
#include <chrono>
#include "platform.h"

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <thread>

int timerspeed_val = TIMERSPEED;

using Clock = std::chrono::steady_clock;

static auto start_tm = Clock::now();

// ---- pausable game clock -------------------------------------------------------------------
// While the game is inactive (e.g. Android app in the background) the clock is frozen so
// game timers do not jump forward after resume.

static std::mutex pause_mx;
static std::condition_variable pause_cv;
static int inactive_mask = 0;                       // guarded by pause_mx
static Clock::time_point paused_at;                 // guarded by pause_mx
static std::atomic<int64_t> paused_total_ms{0};     // total time spent inactive
static std::atomic<int64_t> frozen_ms{-1};          // clock value while inactive, -1 = running

static int64_t raw_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start_tm).count();
}

static int64_t game_ms() {
    int64_t f = frozen_ms.load();
    if (f >= 0) return f;
    return raw_ms() - paused_total_ms.load();
}

void game_set_inactive(int reason, char inactive) {
    std::lock_guard _(pause_mx);
    int prev = inactive_mask;
    if (inactive) inactive_mask |= reason; else inactive_mask &= ~reason;
    if (prev == 0 && inactive_mask != 0) {
        frozen_ms = game_ms();
        paused_at = Clock::now();
    } else if (prev != 0 && inactive_mask == 0) {
        paused_total_ms += std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - paused_at).count();
        frozen_ms = -1;
        pause_cv.notify_all();
    }
}

void game_wait_while_inactive(void) {
    if (frozen_ms.load() < 0) return;   // fast path, no lock
    std::unique_lock lk(pause_mx);
    pause_cv.wait(lk, [] { return inactive_mask == 0; });
}

int get_timer_value() {
    return static_cast<int>(game_ms() / timerspeed_val);
}

uint32_t get_game_tick_count() {
    return static_cast<uint32_t>(game_ms());
}

void sleep_ms(uint32_t x) {
    std::this_thread::sleep_for(std::chrono::milliseconds(x));
}
