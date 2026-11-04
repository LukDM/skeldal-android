// Test of the pausable game clock (platform/timer.cpp): frozen while inactive, game thread waits.
#include "platform/platform.h"
#include <cstdlib>
#include <cstdio>
#include <thread>
#include <atomic>
// assert() is disabled in release builds, this check always runs
#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAILED: %s (line %d)\n", #cond, __LINE__); return 1; } } while (0)

extern "C" int q_is_mastertask() { return 1; }
int main() {
    using namespace std::chrono_literals;
    std::this_thread::sleep_for(50ms);
    uint32_t a = get_game_tick_count();
    game_set_inactive(GAME_INACTIVE_BACKGROUND, 1);
    uint32_t f1 = get_game_tick_count();
    std::atomic<bool> passed{false};
    std::thread t([&]{ game_wait_while_inactive(); passed = true; });
    std::this_thread::sleep_for(300ms);
    uint32_t f2 = get_game_tick_count();
    CHECK(f1 == f2);                 // frozen while inactive
    CHECK(!passed);                  // game thread blocked
    game_set_inactive(GAME_INACTIVE_FOCUS, 1);
    game_set_inactive(GAME_INACTIVE_BACKGROUND, 0);
    std::this_thread::sleep_for(50ms);
    CHECK(!passed);                  // still inactive (focus)
    game_set_inactive(GAME_INACTIVE_FOCUS, 0);
    t.join();
    CHECK(passed);
    uint32_t b = get_game_tick_count();
    CHECK(b - a < 200);              // the 350 ms pause is not counted
    std::this_thread::sleep_for(100ms);
    CHECK(get_game_tick_count() - b >= 90);  // running again
    printf("timer pause test OK (a=%u f=%u b=%u)\n", a, f1, b);
}
