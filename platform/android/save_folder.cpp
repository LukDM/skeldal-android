#include "../save_folder.h"

#include <SDL.h>
#include <SDL_system.h>

#include <string>

// Android: savegames live in the app-private internal storage (no permissions needed).
const char *get_default_savegame_directory(void) {
    static std::string dir;
    if (dir.empty()) {
        dir = std::string(SDL_AndroidGetInternalStoragePath()) + "/saves";
    }
    return dir.c_str();
}
