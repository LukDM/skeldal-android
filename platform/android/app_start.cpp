// Android entry point. SDLActivity (Java) loads libskeldal.so and calls SDL_main.
#include "../../game/skeldal.h"
#include "../platform.h"
#include "../error.h"
#include "version.h"

#include <SDL.h>
#include <SDL_system.h>
#include <android/log.h>

#include <chrono>
#include <cstdio>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>
#include <unistd.h>

#define LOG_TAG "skeldal"

namespace {

std::string internal_dir() { return SDL_AndroidGetInternalStoragePath(); }

// Game data imported by the launcher (GameImporter.kt) into the app's private storage.
std::string game_data_root() { return internal_dir() + "/game"; }

bool write_config(const std::string &path, const std::string &data_root, const std::string &saves) {
    std::ofstream out(path, std::ios::trunc);
    if (!out) return false;
    out << "[paths]\n"
        << "root=" << data_root << "\n"
        << "savegame=" << saves << "\n"
        << "\n"
        << "[video]\n"
        << "fullscreen=on\n"
        << "scaling=aspect\n"
        << "aspect_ratio=4:3\n"
        << "scale_quality=nearest\n"
        << "composer=auto\n";
    return static_cast<bool>(out);
}

// ---- log file ------------------------------------------------------------------------------
// Everything logged through SDL_Log goes to logcat (as before) and to files/logs/game.log,
// which the launcher can show after a crash and export ("Export debug logu").
// The log of the previous run is kept as game.prev.log.

std::mutex log_mx;
FILE *log_file = nullptr;

void open_log_file() {
    namespace fs = std::filesystem;
    std::error_code ec;
    const fs::path dir = internal_dir() + "/logs";
    fs::create_directories(dir, ec);
    if (fs::exists(dir / "game.log", ec)) fs::rename(dir / "game.log", dir / "game.prev.log", ec);
    log_file = std::fopen((dir / "game.log").c_str(), "w");
}

void log_output(void *, int category, SDL_LogPriority priority, const char *message) {
    int prio = ANDROID_LOG_INFO;
    char p = 'I';
    switch (priority) {
        case SDL_LOG_PRIORITY_VERBOSE: prio = ANDROID_LOG_VERBOSE; p = 'V'; break;
        case SDL_LOG_PRIORITY_DEBUG: prio = ANDROID_LOG_DEBUG; p = 'D'; break;
        case SDL_LOG_PRIORITY_WARN: prio = ANDROID_LOG_WARN; p = 'W'; break;
        case SDL_LOG_PRIORITY_ERROR: prio = ANDROID_LOG_ERROR; p = 'E'; break;
        case SDL_LOG_PRIORITY_CRITICAL: prio = ANDROID_LOG_FATAL; p = 'F'; break;
        default: break;
    }
    __android_log_print(prio, category == SDL_LOG_CATEGORY_APPLICATION ? "SDL/APP" : "SDL", "%s", message);
    std::lock_guard _(log_mx);
    if (log_file) {
        std::time_t t = std::time(nullptr);
        char tm[32];
        std::strftime(tm, sizeof(tm), "%Y-%m-%d %H:%M:%S", std::localtime(&t));
        std::fprintf(log_file, "%s %c %s\n", tm, p, message);
        std::fflush(log_file);
    }
}

void write_text(const std::string &path, const std::string &text) {
    std::ofstream out(path, std::ios::trunc);
    out << text;
}

/// Errors reported by the engine; the launcher shows the last one to the user.
void show_error(const char *text) {
    SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "ERROR: %s", text);
    write_text(internal_dir() + "/last_error.txt", text);
}

/// The launcher creates this marker before starting the game. It is removed on a normal end,
/// so a marker left behind means the game crashed or was killed.
std::string session_marker() { return internal_dir() + "/game_session"; }

[[noreturn]] void finish(int rc) {
    SDL_Log("Game finished (code %d), ending game process", rc);
    std::error_code ec;
    std::filesystem::remove(session_marker(), ec);
    {
        std::lock_guard _(log_mx);
        if (log_file) std::fclose(log_file);
        log_file = nullptr;
    }
    // The game runs in its own process (GameActivity, android:process=":game").
    // The engine keeps global state (SDL context, audio device, worker threads)
    // that cannot be safely reused, so the process ends together with the game.
    // This also stops audio immediately; the launcher process is not affected.
    std::fflush(nullptr);
    _exit(rc);
}

int run(int argc, char *argv[]) {
    const std::string data_root = game_data_root();
    const std::string saves = internal_dir() + "/saves";
    const std::string config = internal_dir() + "/skeldal.ini";

    // arguments from GameActivity.getArguments(): -l <language folder>
    std::string lang;
    for (int i = 1; i + 1 < argc; ++i) {
        if (std::string(argv[i]) == "-l") lang = argv[i + 1];
    }

    SDL_version sv;
    SDL_GetVersion(&sv);
    SDL_Log("Brany Skeldalu engine %s, SDL %d.%d.%d, language: %s", SKELDAL_VERSION,
            sv.major, sv.minor, sv.patch, lang.empty() ? "cz (default)" : lang.c_str());
    write_text(internal_dir() + "/engine_version.txt",
               std::string(SKELDAL_VERSION) + "\nSDL " + std::to_string(sv.major) + "." +
               std::to_string(sv.minor) + "." + std::to_string(sv.patch) + "\n");

    if (!std::filesystem::exists(data_root + "/SKELDAL.DDL")) {
        show_error(("Game data not found in: " + data_root + " (import the game first)").c_str());
        return 1;
    }

    std::filesystem::create_directories(saves);
    // The launcher writes skeldal.ini (settings) before starting the game;
    // a default one is created only when it is missing.
    if (!std::filesystem::exists(config) && !write_config(config, data_root, saves)) {
        show_error("Cannot write skeldal.ini");
        return 1;
    }

    // Do not expose the accelerometer as a joystick (the game would report a controller)
    SDL_SetHint(SDL_HINT_ACCELEROMETER_AS_JOYSTICK, "0");
    // Landscape only: SDL would allow any orientation for a resizable window (rotating the
    // device to portrait shrank the picture into a corner)
    SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");
    // Back button is delivered to the game as a key (mapped to ESC) instead of closing the activity
    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");

    SKELDAL_CONFIG cfg{};
    cfg.short_help = []() {};
    cfg.show_error = show_error;
    cfg.config_path = config.c_str();
    cfg.lang_path = lang.empty() ? nullptr : lang.c_str();

    try {
        return skeldal_entry_point(&cfg);
    } catch (const std::exception &e) {
        show_error(e.what());
        return 1;
    }
}

}  // namespace

extern "C" int SDL_main(int argc, char *argv[]) {
    {
        std::lock_guard _(log_mx);
        open_log_file();
    }
    SDL_LogSetOutputFunction(log_output, nullptr);
    finish(run(argc, argv));
}

// ---- JNI: small bridge used by the launcher's GameActivity -----------------------------------

#include <jni.h>

namespace {
JavaVM *g_vm = nullptr;
jclass g_bridge = nullptr;            // cz.gates.skeldal.NativeBridge (global ref)
jmethodID g_on_text_input = nullptr;  // static void onTextInput(boolean)

/// Tells the Java side that a text field opened / closed (shows the keyboard button).
void notify_text_input(int active) {
    if (!g_vm || !g_bridge || !g_on_text_input) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallStaticVoidMethod(g_bridge, g_on_text_input, active ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}
}  // namespace

/// Called when System.loadLibrary("skeldal") runs (on a Java thread, so app classes are visible).
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_VERSION_1_6;
    jclass c = env->FindClass("cz/gates/skeldal/NativeBridge");
    if (c) {
        g_bridge = static_cast<jclass>(env->NewGlobalRef(c));
        g_on_text_input = env->GetStaticMethodID(c, "onTextInput", "(Z)V");
        env->DeleteLocalRef(c);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    game_set_text_input_listener(notify_text_input);
    return JNI_VERSION_1_6;
}

/// Keyboard button on the touch overlay: show the soft keyboard again.
extern "C" JNIEXPORT void JNICALL
Java_cz_gates_skeldal_NativeBridge_nativeShowKeyboard(JNIEnv *, jclass) {
    game_show_keyboard();
}

/// The soft keyboard was closed (GameActivity watches the IME insets).
extern "C" JNIEXPORT void JNICALL
Java_cz_gates_skeldal_NativeBridge_nativeKeyboardHidden(JNIEnv *, jclass) {
    game_keyboard_hidden();
}

/// Touch buttons whose key must reach the game even while typing (map, console...).
extern "C" JNIEXPORT void JNICALL
Java_cz_gates_skeldal_NativeBridge_nativeInjectKey(JNIEnv *, jclass, jint bios_code) {
    game_inject_key(static_cast<unsigned int>(bios_code));
}

/// Optional "keyboard" touch button: keyboard on / off anywhere in the game.
extern "C" JNIEXPORT void JNICALL
Java_cz_gates_skeldal_NativeBridge_nativeToggleKeyboard(JNIEnv *, jclass) {
    game_toggle_keyboard();
}

/// Extra gamepad button (outside the standard layout, e.g. M1/M2) pressed or released.
/// index 0..8 maps to controller buttons 23..31 of the [controller] section.
extern "C" JNIEXPORT void JNICALL
Java_cz_gates_skeldal_NativeBridge_nativeExtraButton(JNIEnv *, jclass, jint index, jboolean down) {
    if (index < 0 || CONTROLLER_BUTTON_FIRST_EXTRA + index >= CONTROLLER_BUTTON_COUNT) return;
    controller_virtual_button(CONTROLLER_BUTTON_FIRST_EXTRA + index, down ? 1 : 0);
}

/// Android audio focus: mute the game while another app (call, voice assistant, music) has it.
extern "C" JNIEXPORT void JNICALL
Java_cz_gates_skeldal_NativeBridge_nativeSetAudioMuted(JNIEnv *, jclass, jboolean muted) {
    game_set_audio_muted(muted ? 1 : 0);
}
