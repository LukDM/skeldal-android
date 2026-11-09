#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#ifndef _SKELDAL_PLATFORM_HEADER_
#define _SKELDAL_PLATFORM_HEADER_

#define BGSWITCHBIT 0x8000

#ifdef _MSC_VER
#pragma warning(disable: 4244)
#pragma warning(disable: 4996)
#pragma warning(disable: 4459)
#pragma warning(disable: 4267)
#pragma warning(disable: 4100)
#pragma warning(disable: 4456)
#pragma warning(disable: 4457)
#pragma warning(disable: 4702)
#pragma warning(disable: 4100)
//microsoft doesn't support FALLTHROUGH
#define CASE_FALLTHROUGH

//microsoft doesn't support VLA
#define DECL_VLA(type, variable, count) type *variable = (type *)alloca((count)*sizeof(type));
#define GET_VLA_SIZE(variable, count) ((count)*sizeof(*variable))

#else
//support FALLTHROUGH
#define CASE_FALLTHROUGH [[fallthrough]]
//support VLA
#define DECL_VLA(type, variable, count) type variable[count];
#define GET_VLA_SIZE(variable, count) sizeof(variable)
#endif


#define SKELDALINI "skeldal.ini"

#ifdef __cplusplus
extern "C"
  {
#endif


#define _KEYBRD_READY 0
#define _KEYBRD_READ 1

#define TIMERSPEED 25
extern int timerspeed_val;

uint32_t _bios_keybrd(int mode);


#define RGB888(r,g,b) ((unsigned short)((((r)<<7)&0x7C00) | (((g)<<2) & 0x3E0) | ((b)>>3)))
#define RGB555(r,g,b) (((unsigned short)(((r)<<10) | ((g)<<5) | (b))) & ~BGSWITCHBIT)
#define RGB555_ALPHA(r,g,b) (((unsigned short)(((r)<<10) | ((g)<<5) | (b))) | BGSWITCHBIT)
#define AVG_PIXEL_MASK (0x7BDE)
#define BLEND_PIXELS(px1, px2) ((((px1) & AVG_PIXEL_MASK) + ((px2) & AVG_PIXEL_MASK)) >> 1)



///build pathname from parts
/**
 * @param nparts count of parts (must be > 0)
 * @param part first part
 * @return complete path - statically allocated buffer
 */
const char * build_pathname(size_t nparts, const char *part1, ...);
///creates directories recursively
/**
 * @param path path
 * @retval 1 success
 * @retval 0 failure
 */
char create_directories(const char *path);
///change current directory
/**
 * @param path path
 * @retval 1 success
 * @retval 0 failure
 */
char change_current_directory(const char *path);



void *map_file_to_memory(const char *name, size_t *sz);
void unmap_file(void *ptr, size_t sz);

char copy_text_to_clipboard(const char *);

void ShareCPU(void);

char get_control_key_state(void);
char get_shift_key_state(void);
char get_capslock_state(void);
void display_error(const char *text,...);
///returns -1 if doesn't exists
char check_file_exists(const char *pathname);
FILE *fopen_icase(const char *pathname, const char *mode);
const char *file_icase_find(const char *pathname);

int istrcmp(const char *a, const char *b);
int istrncmp(const char *a, const char *b, size_t sz);
int imatch(const char *haystack, const char *needle);
#define SAFESTRCOPY(target, source) strcopy_n(target, source, sizeof(target) )
const char *strcopy_n(char *target, const char *source, int target_size);
#define MIN(a, b) ((a)<(b)?(a):(b))
#define MAX(a, b) ((a)>(b)?(a):(b))
void strupper(char *c);
const char * int2ascii(int i, char *c, int radix);

int get_timer_value(void);
uint32_t get_game_tick_count(void);
void sleep_ms(uint32_t);

/// Reasons why the game is inactive (bit mask for game_set_inactive)
#define GAME_INACTIVE_BACKGROUND 1   ///< application is in the background (mobile)
#define GAME_INACTIVE_FOCUS 2        ///< window lost focus (only if enabled in config)
/// Marks the game inactive / active again. While inactive the game clock is frozen and the
/// game thread waits in game_wait_while_inactive(). Thread safe.
void game_set_inactive(int reason, char inactive);
/// Called from the game thread (ShareCPU, keyboard polling): blocks while the game is inactive.
void game_wait_while_inactive(void);
/// The game shows / leaves a text field (on mobile this opens / closes the soft keyboard).
/// Thread safe.
void game_text_input(char active);
/// Platform callback when a text field opens / closes (called on the SDL event thread).
void game_set_text_input_listener(void (*listener)(int active));
/// Shows the soft keyboard again while a text field is open (e.g. after the user closed it).
void game_show_keyboard(void);
///mobile: keyboard button that is always available - free typing on / off
void game_toggle_keyboard(void);
///mobile: the soft keyboard was closed by the user (ends the free typing mode)
void game_keyboard_hidden(void);
///puts a key (BIOS code: scan code << 8 | character) straight into the game's keyboard queue
void game_inject_key(unsigned int bios_code);
///opens the walkthrough (mobile: the launcher's manual screen over the game); no-op without a listener
void game_open_manual(void);
///platform callback for game_open_manual (called on the game thread)
void game_set_manual_listener(void (*listener)(void));
///the next "app goes to background" pauses the game without the autosave (e.g. the manual opens)
void game_skip_next_autosave(void);
/// Mutes / unmutes the game audio (e.g. Android audio focus). Thread safe, never blocks:
/// the change is applied by the SDL event loop.
void game_set_audio_muted(char muted);

typedef enum {
    file_type_normal = 1,
    file_type_directory = 2,
    file_type_dot = 4,
    file_type_just_name = 8,
    file_type_need_timestamp = 16
} LIST_FILE_TYPE;

typedef int (*LIST_FILES_CALLBACK)(const char *, LIST_FILE_TYPE , size_t, void *);
int list_files(const char *directory, int type, LIST_FILES_CALLBACK cb, void *ctx);


#ifdef __cplusplus
  }
#endif


#include "sdl/BGraph2.h"

void init_joystick(const INI_CONFIG_SECTION *section);
char is_joystick_used();
char is_joystick_enabled();

/// Controller button numbers used by the [controller] section of skeldal.ini:
///  0-14  standard layout (A, B, X, Y, Back, Guide, Start, L3, R3, LB, RB, D-pad)
///  15    misc (e.g. Share / capture), 16-19 paddles, 20 touchpad click
///  21,22 left / right analog trigger (pressed when pulled past half)
///  23-31 extra buttons reported by the platform (see controller_virtual_button)
#define CONTROLLER_BUTTON_LEFT_TRIGGER 21
#define CONTROLLER_BUTTON_RIGHT_TRIGGER 22
#define CONTROLLER_BUTTON_FIRST_EXTRA 23
#define CONTROLLER_BUTTON_COUNT 32

/// Press / release a controller button from platform code (e.g. Android buttons outside the
/// standard layout). Thread safe, may be called from any thread.
void controller_virtual_button(int button, char pressed);

#define WM_RELOADMAP (WM_APP+215)
#define E_RELOADMAP 40

typedef struct _ReloadMapInfo {
        const char *fname;
        int sektor;
   } ReloadMapInfo;



#endif
