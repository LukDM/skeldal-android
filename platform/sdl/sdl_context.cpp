#include "sdl_context.h"
#include "keyboard_map.h"
#include "format_mapping.h"

#include <atomic>
#include <cassert>
#include "../platform.h"
#include "../error.h"

#include <cmath>
#include <iostream>
#include <fstream>
#include <stdexcept>
#include <sstream>
#include <algorithm>
#include <stdbool.h>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <string_view>

static Uint32 virtual_button_event_type() {
    static const Uint32 type = SDL_RegisterEvents(1);
    return type;
}

static Uint32 exit_loop_event_type() {
    static const Uint32 type = SDL_RegisterEvents(1);
    return type;
}

void SDLContext::SDL_Deleter::operator ()(SDL_Window* window) {
    SDL_DestroyWindow(window);
}

void SDLContext::SDL_Deleter::operator ()(SDL_Renderer* renderer) {
    SDL_DestroyRenderer(renderer);
}

void SDLContext::SDL_Deleter::operator ()(SDL_Surface* surface) {
    SDL_FreeSurface(surface);
}

void SDLContext::SDL_Deleter::operator ()(SDL_Texture* texture) {
    SDL_DestroyTexture(texture);
}
void SDLContext::SDL_Deleter::operator ()(SDL_PixelFormat* f) {
    SDL_FreeFormat(f);
}

void SDLContext::SDL_Audio_Deleter::operator()(SDL_AudioDeviceID x) {
    SDL_CloseAudioDevice(x);
}

struct SDL_INIT_Context {

    SDL_INIT_Context() {
        if (SDL_Init(SDL_INIT_VIDEO|SDL_INIT_TIMER|SDL_INIT_AUDIO|SDL_INIT_JOYSTICK|SDL_INIT_GAMECONTROLLER) < 0) {
            SDL_Log("Unable to initialize SDL: %s", SDL_GetError());
            exit(1);
        }
        for (int i = 0, n = SDL_NumJoysticks(); i < n; ++i) {
            const char *jname = SDL_JoystickNameForIndex(i);
            SDL_Log("Joystick %d: '%s'%s", i, jname ? jname : "?",
                    SDL_IsGameController(i) ? " (game controller)" : "");
        }
        open_first();
        inited = true;
    }
    ~SDL_INIT_Context() {
        if (controller) SDL_GameControllerClose(controller);
        SDL_Quit();
    }

    /// Opens the first device SDL recognizes as a game controller (standard layout:
    /// buttons and axes are numbered as SDL_GameControllerButton / SDL_GameControllerAxis,
    /// independent of the device). On Android the joystick list may also contain
    /// sensors or other virtual devices, those are skipped.
    void open_first() {
        std::lock_guard _(mx);
        if (controller) return;
        for (int i = 0, n = SDL_NumJoysticks(); i < n; ++i) {
            if (!SDL_IsGameController(i)) continue;
            SDL_GameController *c = SDL_GameControllerOpen(i);
            if (!c) continue;
            const char *name = SDL_GameControllerName(c);
            // Some phones report built-in key devices (fingerprint sensor, side keys) as game
            // controllers. A real gamepad has a stick or a D-pad hat, i.e. at least two axes.
            int axes = SDL_JoystickNumAxes(SDL_GameControllerGetJoystick(c));
            if (axes < 2) {
                SDL_Log("Ignoring '%s': not a gamepad (%d axes)", name ? name : "?", axes);
                SDL_GameControllerClose(c);
                continue;
            }
            controller = c;
            SDL_Log("Using game controller: '%s' (%d axes)", name ? name : "?", axes);
            break;
        }
    }

    /// hot-plug: a controller was connected
    void on_added() { open_first(); }

    /// hot-plug: a controller was disconnected
    void on_removed(SDL_JoystickID id) {
        std::lock_guard _(mx);
        if (controller && SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(controller)) == id) {
            SDL_Log("Game controller disconnected");
            SDL_GameControllerClose(controller);
            controller = nullptr;
        }
    }

    bool is_ours(SDL_JoystickID id) {
        std::lock_guard _(mx);
        return controller && SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(controller)) == id;
    }

    bool connected() {
        std::lock_guard _(mx);
        return controller != nullptr;
    }

    /// axis 0,1 = left stick X,Y; 2,3 = right stick X,Y (same numbering as before)
    int axis(int idx) {
        static const SDL_GameControllerAxis map[4] = {
            SDL_CONTROLLER_AXIS_LEFTX, SDL_CONTROLLER_AXIS_LEFTY,
            SDL_CONTROLLER_AXIS_RIGHTX, SDL_CONTROLLER_AXIS_RIGHTY
        };
        std::lock_guard _(mx);
        if (!controller || idx < 0 || idx > 3) return 0;
        return SDL_GameControllerGetAxis(controller, map[idx]);
    }

    bool inited = false;

private:
    std::mutex mx;   // the axes are read from an SDL timer thread
    SDL_GameController *controller = nullptr;
};

// Initialized lazily on first use. SDL_Init must not run from a static
// constructor: on Android the library is loaded before SDL's JNI glue is
// ready and before SDL_SetMainReady() is called, so SDL_Init fails there.
static SDL_INIT_Context &get_init_context() {
    static SDL_INIT_Context ctx;
    return ctx;
}

SDLContext::SDLContext() {
    if (!get_init_context().inited) throw std::runtime_error("SDL not inited");

}
void handle_sdl_error(const char *msg) {
    char buff[512];

    snprintf(buff, sizeof(buff), "SDL critical error: %s %s",msg, SDL_GetError());
    throw std::runtime_error(buff);
}

bool isFormatSupported(SDL_Renderer *renderer, Uint32 pixel_format) {
    SDL_RendererInfo info;
    if (SDL_GetRendererInfo(renderer, &info) != 0) {
        handle_sdl_error("Failed to get renderer info");
        return false;
    }

    for (Uint32 i = 0; i < info.num_texture_formats; ++i) {
        if (info.texture_formats[i] == pixel_format) {
            return true;
        }
    }
    return false;
}

void SDLContext::generateCRTTexture(SDL_Renderer* renderer, SDL_Texture** texture, int width, int height, CrtFilterType type) {

    if (type == CrtFilterType::autoselect) {
        if (height >= 1200) type = CrtFilterType::scanlines_2;
        else type = CrtFilterType::scanlines;
    }

    int interfer = 1;
    switch (type) {
        case CrtFilterType::scanlines: interfer = 2;break;
        case CrtFilterType::scanlines_2: interfer = 3;break;
        case CrtFilterType::rgb_matrix_2: interfer = 3;break;
        case CrtFilterType::rgb_matrix_3: interfer = 4;break;
        default: break;
    }



    if (type == CrtFilterType::scanlines || type == CrtFilterType::scanlines_2) {
        width = 32;
    } else {
        unsigned int mult_of_base = std::max<unsigned int>((height+320)/640,interfer);
        width = width * interfer / mult_of_base;
    }
    {
        unsigned int mult_of_base = std::max<unsigned int>((height+240)/480,interfer);
        height = height * interfer / mult_of_base;
    }



    // Vytvoř novou texturu ve správné velikosti
    *texture = SDL_CreateTexture(renderer, _texture_render_format, SDL_TEXTUREACCESS_STREAMING, width, height);
    if (!*texture) {
        type = CrtFilterType::none;
        return;  //crt filter failed to create, do not use filter
    }
    SDL_SetTextureBlendMode(*texture, SDL_BLENDMODE_MUL);


    // Zamkni texturu pro přímý přístup k pixelům
    void* pixels;
    int pitch;
    if (SDL_LockTexture(*texture, nullptr, &pixels, &pitch)<0) {
        SDL_DestroyTexture(*texture);
        *texture = nullptr;
        type = CrtFilterType::none;
        return;
    }

    Uint32* pixelArray = (Uint32*)pixels;


    if (type == CrtFilterType::scanlines) {


        Uint32 darkPixel = SDL_MapRGBA(_main_pixel_format.get(), 0xA0, 0xA0, 0xA0, 0xFF);
        Uint32 transparentPixel = SDL_MapRGBA(_main_pixel_format.get(), 0xFF, 0xFF, 0xFF, 0xC0);

        for (int y = 0; y < height; y++) {
            Uint32 color = ((y & 1)== 0) ? darkPixel : transparentPixel;
            for (int x = 0; x < width; x++) {
                pixelArray[y * (pitch / 4) + x] = color;
            }
        }
    }
    else if (type == CrtFilterType::scanlines_2) {


        Uint32 darkPixel = SDL_MapRGBA(_main_pixel_format.get(), 0x80, 0x80, 0x80, 0xFF);
        Uint32 transparentPixel = SDL_MapRGBA(_main_pixel_format.get(), 0xFF, 0xFF, 0xFF, 0xE0);

        for (int y = 0; y < height; y++) {
            Uint32 color = (y % 3== 2) ? darkPixel : transparentPixel;
            for (int x = 0; x < width; x++) {
                pixelArray[y * (pitch / 4) + x] = color;
            }
        }
    } else {

        static Uint32 red_pixel = SDL_MapRGBA(_main_pixel_format.get(), 0xFF, 0x80, 0x80, 0xA0);
        static Uint32 green_pixel = SDL_MapRGBA(_main_pixel_format.get(), 0x80, 0xFF, 0x80, 0xA0);
        static Uint32 blue_pixel = SDL_MapRGBA(_main_pixel_format.get(), 0x80, 0x80, 0xFF, 0xA0);
        static Uint32 dark_pixel = SDL_MapRGBA(_main_pixel_format.get(), 0x0, 0x0, 0x00, 0xA0);
        for (int y = 2; y < height; y++) {
            if (type == CrtFilterType::rgb_matrix_2) {
                for (int x = 2; x < width; x+=3) {
                    Uint32 *pos = pixelArray+y*(pitch/4)+x-2;
                    if ((y % 3) == 2) {
                        pos[0] = pos[1] = pos[2] = dark_pixel;
                    } else {
                        pos[0] = red_pixel;
                        pos[1] = green_pixel;
                        pos[2] = blue_pixel;
                    }
                }
            } else {
                for (int x = 2; x < width; x+=3) {
                    Uint32 *pos = pixelArray+y*(pitch/4)+x-2;
                    if ((y & 3) == 3) {
                        pos[0] = pos[1] = pos[2] = dark_pixel;
                    } else {
                        pos[0] = red_pixel;
                        pos[1] = green_pixel;
                        pos[2] = blue_pixel;
                    }
                }
            }
        }

    }

    // Odemkni texturu
    SDL_UnlockTexture(*texture);
}

static void crash_sdl_exception() {
    try {
        throw;
    } catch (std::exception &e) {
        display_error("Display server - unhandled exception: %s", e.what());
    } catch (...) {
        display_error("Display server - unhandled unknown exception (probably crash)");
    }
    abort();
}

// Seznam akceptovatelných formátů odpovídajících RGBA8888 (v různém pořadí)
constexpr Uint32 acceptable_formats[] = {
    SDL_PIXELFORMAT_RGBA8888,
    SDL_PIXELFORMAT_ARGB8888,
    SDL_PIXELFORMAT_BGRA8888,
    SDL_PIXELFORMAT_ABGR8888,

    SDL_PIXELFORMAT_RGBA4444,
    SDL_PIXELFORMAT_ARGB4444,
    SDL_PIXELFORMAT_BGRA4444,
    SDL_PIXELFORMAT_ABGR4444,

    SDL_PIXELFORMAT_ARGB2101010,
};

constexpr bool is_acceptable_format(Uint32 format) {
    for (size_t i = 0; i < sizeof(acceptable_formats)/sizeof(acceptable_formats[0]); ++i) {
        if (format == acceptable_formats[i]) {
            return true;
        }
    }
    return false;
}

static Uint32 find_best_rgba_like_format(SDL_Renderer* renderer) {
    SDL_RendererInfo info;
    if (SDL_GetRendererInfo(renderer, &info) != 0) {
        return 0;
    }

    if ((info.max_texture_width != 0 && info.max_texture_width < 640) ||
        (info.max_texture_height!= 0 && info.max_texture_height < 480)) return 0;

    for (Uint32 i = 0; i < info.num_texture_formats; ++i) {
        Uint32 fmt = info.texture_formats[i];
        if (is_acceptable_format(fmt)) {
            return fmt;
        }
    }

    return 0;
}

int SDLContext::init_window(const VideoConfig &config, const char *title, std::function<int()> game_thread) {
    static Uint32 update_request_event = SDL_RegisterEvents(1);
    static Uint32 refresh_request_event = SDL_RegisterEvents(1);
    _update_request_event = update_request_event;
    _refresh_request = refresh_request_event;


    int width = config.window_width;
    int height = config.window_height;
    aspect_x = config.aspect_x;
    aspect_y = config.aspect_y;
    _integer_scale = config.integer_scale;
    _picture_scale = config.picture_scale;
    _crt_filter = config.crt_filter ;
    if (_crt_filter == CrtFilterType::none) {
        _crt_filter = CrtFilterType::autoselect;
        _enable_crt = false;
    }

    _fullscreen_mode = config.fullscreen;
    _pause_on_focus_loss = config.pause_on_focus_loss;
    _autosave_on_background = config.autosave_on_background;
    _mouse_size = config.cursor_size;

    std::exception_ptr e;
    std::string_view stage;
    std::string rname;
    int exit_code = 0;
    try {
        stage = "window";
        SDL_Window *window = SDL_CreateWindow(title,
                    SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
                    width, height, SDL_WINDOW_RESIZABLE|(_fullscreen_mode?SDL_WINDOW_FULLSCREEN_DESKTOP:0));

        if (!window) {
            handle_sdl_error("SDL Error create window");
        }

        _window.reset(window);

        auto composer = config.composer | (config.vsync ? SDL_RENDERER_PRESENTVSYNC : 0);

        stage = "renderer";

        while (true) {

            SDL_Renderer *renderer = SDL_CreateRenderer(_window.get(), -1, composer);
            if (!renderer) {
                // fall back to the software renderer once (checking the current flags,
                // otherwise a failing software renderer would loop forever)
                if (composer & SDL_RENDERER_SOFTWARE) {
                    handle_sdl_error("Failed to create composer");
                } else {
                    composer |= SDL_RENDERER_SOFTWARE;
                    continue;
                }
            }

            _texture_render_format = find_best_rgba_like_format(renderer);
            if (_texture_render_format == 0) {
                if (composer & SDL_RENDERER_SOFTWARE) {
                    throw std::runtime_error("Failed to create composer, failed software fallback");
                } else {
                    SDL_DestroyRenderer(renderer);
                    composer |= SDL_RENDERER_SOFTWARE;
                    continue;
                }
            }
            _renderer.reset(renderer);
            break;
        }


        SDL_RendererInfo rinfo;
        SDL_GetRendererInfo(_renderer.get(), &rinfo);

        rname = rinfo.name;
        {
            int ww = 0, wh = 0, ow = 0, oh = 0;
            SDL_GetWindowSize(_window.get(), &ww, &wh);
            SDL_GetRendererOutputSize(_renderer.get(), &ow, &oh);
            SDL_DisplayMode dm = {};
            SDL_GetCurrentDisplayMode(SDL_GetWindowDisplayIndex(_window.get()), &dm);
            SDL_Log("Renderer: %s%s%s, texture format %s, window %dx%d, output %dx%d, display %d Hz",
                    rinfo.name,
                    (rinfo.flags & SDL_RENDERER_ACCELERATED) ? " (hardware)" : " (software)",
                    (rinfo.flags & SDL_RENDERER_PRESENTVSYNC) ? " vsync" : "",
                    SDL_GetPixelFormatName(_texture_render_format), ww, wh, ow, oh, dm.refresh_rate);
        }

        stage = "pixel format";

        _main_pixel_format.reset(SDL_AllocFormat(_texture_render_format));
        if (!_main_pixel_format) {
            handle_sdl_error("Failed to create texture format");
        }

        if (istrcmp(config.scale_quality, "auto") == 0) {
            if (rinfo.flags & SDL_RENDERER_ACCELERATED) {
                SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, "best");
            }
        } else {
            SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, config.scale_quality);
        }



        stage = "main render target";


        SDL_Texture *texture = SDL_CreateTexture(_renderer.get(), _texture_render_format, SDL_TEXTUREACCESS_STREAMING, 640, 480);
        if (!texture) {
            handle_sdl_error("Failed to create render target");
        }

        SDL_SetTextureBlendMode(texture, SDL_BLENDMODE_BLEND);
        _texture.reset(texture);

        stage = "secondary render target";

        texture = SDL_CreateTexture(_renderer.get(), _texture_render_format, SDL_TEXTUREACCESS_STREAMING, 640, 480);
        if (!texture) {
            handle_sdl_error("Failed to create second render target");
        }
        SDL_SetTextureBlendMode(texture, SDL_BLENDMODE_BLEND);
        _texture2.reset(texture);

        stage = "all done";

        _visible_texture = _texture.get();
        _hidden_texture = _texture2.get();

        std::thread main_thrd([&]{
            try {
                exit_code = game_thread();
            } catch (...) {
                e = std::current_exception();
            }
            // wake up the SDL event loop (portable replacement of std::stop_source)
            SDL_Event ev = {};
            ev.type = exit_loop_event_type();
            SDL_PushEvent(&ev);
        });
        main_thrd.detach();
        SDL_ShowCursor(SDL_DISABLE);
        // app lifecycle events must be seen when they are sent (on mobile the event loop
        // itself is blocked while the app is in the background), so use an event watch
        SDL_AddEventWatch(&SDLContext::app_event_watch, this);
        event_loop();
        SDL_DelEventWatch(&SDLContext::app_event_watch, this);
        game_set_inactive(GAME_INACTIVE_BACKGROUND | GAME_INACTIVE_FOCUS, 0);
        SDL_ShowCursor(SDL_ENABLE);

    } catch (...) {
        crash_sdl_exception();
        return -1;
    }
    _texture.reset();
    _texture2.reset();
    _renderer.reset();
    _window.reset();

    if (e) {
        std::rethrow_exception(e);
    }
    return exit_code;
}


int SDLContext::check_axis_dir(int &cooldown, int value) {
    int range = 0x8000-_jcontrol_map.walk_deadzone;
    int step = range >> 4;
    int max_speed = range+(step<<2);
    if (value == 0) {
        cooldown = -1;
    } else if (cooldown>0) {
        cooldown -= step;
        if (cooldown < 0) cooldown = 0;
    } else {
        if (value > 0) {
            if (cooldown < 0) value = step;
            cooldown = max_speed-value;
            return 1;
        }
        else if (value < 0) {
            if (cooldown < 0) value = -step;
            cooldown = max_speed+value;
            return -1;
        }
    }
    return 0;
}

int SDLContext::adjust_deadzone(int v, short deadzone) {
    if (v > deadzone) return v - deadzone;
    if (v < -deadzone) return v + deadzone;
    return 0;
}

template<int shift>
constexpr Uint32 shift_bits_5(Uint32 val) {
    if constexpr(shift > 0) {
        return val << shift | shift_bits_5<shift-5>(val);
    } else if constexpr(shift < 0) {
        return val >> (-shift);
    } else {
        return val;
    }
}


template<Uint32 pixel_format>
void SDLContext::convert_bitmap(const void *pixels, SDL_Rect rect, int pitch) {

    constexpr auto RShift = FormatMapping<pixel_format>::RShift;
    constexpr auto GShift = FormatMapping<pixel_format>::GShift;
    constexpr auto BShift = FormatMapping<pixel_format>::BShift;
    constexpr auto AShift = FormatMapping<pixel_format>::AShift;
    constexpr auto RBits = FormatMapping<pixel_format>::RBits;
    constexpr auto GBits = FormatMapping<pixel_format>::GBits;
    constexpr auto BBits = FormatMapping<pixel_format>::BBits;
    constexpr auto ABits = FormatMapping<pixel_format>::ABits;

    const Uint16 *src = static_cast<const Uint16*>(pixels);
    auto trg = converted_pixels.data();
    for (int y = 0; y < rect.h; ++y) {
        for (int x = 0; x < rect.w; ++x) {
            Uint16 pixel = src[x];
            Uint32 a = (pixel & 0x8000) ? 0 : 0x1F;
            Uint32 r = ((pixel >> 10) & 0x1F);
            Uint32 g = ((pixel >> 5) & 0x1F);
            Uint32 b = (pixel & 0x1F);

            r = shift_bits_5<RBits-5>(r);
            g = shift_bits_5<GBits-5>(g);
            b = shift_bits_5<BBits-5>(b);
            a = shift_bits_5<ABits-5>(a);

            trg[x] = (r << RShift) | (g << GShift) | (b << BShift) | (a << AShift);
        }
        trg += rect.w;
        src = src + pitch / 2;
    }
}

void SDLContext::update_texture_with_conversion(SDL_Texture *texture, const SDL_Rect *rect, const void *pixels, int pitch)
{
    SDL_Rect r;
    if (rect) {
        r = *rect;
    } else {
        SDL_QueryTexture(texture, nullptr, nullptr, &r.w, &r.h);
        r.x = 0;
        r.y = 0;
    }

    converted_pixels.clear();
    converted_pixels.resize(r.w * r.h);

    switch (_texture_render_format) {
        case SDL_PIXELFORMAT_ABGR8888:
            convert_bitmap<SDL_PIXELFORMAT_ABGR8888>(pixels, r, pitch);break;
        case SDL_PIXELFORMAT_ARGB8888:
            convert_bitmap<SDL_PIXELFORMAT_ARGB8888>(pixels, r, pitch);break;
        case SDL_PIXELFORMAT_ARGB2101010:
            convert_bitmap<SDL_PIXELFORMAT_ARGB2101010>(pixels, r, pitch);break;
        case SDL_PIXELFORMAT_RGBA8888:
            convert_bitmap<SDL_PIXELFORMAT_RGBA8888>(pixels, r, pitch);break;
        case SDL_PIXELFORMAT_BGRA8888:
            convert_bitmap<SDL_PIXELFORMAT_BGRA8888>(pixels, r, pitch);break;
        case SDL_PIXELFORMAT_ABGR4444:
            convert_bitmap<SDL_PIXELFORMAT_ABGR4444>(pixels, r, pitch);break;
        case SDL_PIXELFORMAT_ARGB4444:
            convert_bitmap<SDL_PIXELFORMAT_ARGB4444>(pixels, r, pitch);break;
        case SDL_PIXELFORMAT_BGRA4444:
            convert_bitmap<SDL_PIXELFORMAT_BGRA4444>(pixels, r, pitch);break;
        case SDL_PIXELFORMAT_RGBA4444:
            convert_bitmap<SDL_PIXELFORMAT_RGBA4444>(pixels, r, pitch);break;
        case SDL_PIXELFORMAT_ABGR1555:
            convert_bitmap<SDL_PIXELFORMAT_ABGR1555>(pixels, r, pitch);break;
        case SDL_PIXELFORMAT_ARGB1555:
            convert_bitmap<SDL_PIXELFORMAT_ARGB1555>(pixels, r, pitch);break;
        case SDL_PIXELFORMAT_RGBA5551:
            convert_bitmap<SDL_PIXELFORMAT_RGBA5551>(pixels, r, pitch);break;
        case SDL_PIXELFORMAT_BGRA5551:
            convert_bitmap<SDL_PIXELFORMAT_BGRA5551>(pixels, r, pitch);break;
        default:
            return;
    }

    if (SDL_UpdateTexture(texture, &r, converted_pixels.data(), r.w * 4) < 0) {
        handle_sdl_error("Failed to update texture");
    }
}

static int axis_dynamic(int c) {
    double f = std::floor(std::pow(std::abs(c)*0.001,2)*0.025);

    if (c < 0) return static_cast<int>(-f);
    else return static_cast<int>(f);

}

void SDLContext::joystick_handle() {

    int a1 = get_init_context().axis(_jcontrol_map.swap_axis?2:0);
    int a2 = get_init_context().axis(_jcontrol_map.swap_axis?3:1);
    if (std::abs(a1) - std::abs(a2) > 0x2000) a2 = 0;
    else if (std::abs(a2) - std::abs(a1) >0x2000) a1 = 0;
    else {
        a1 = 0;
        a2 = 0;
    }
    int axis1 =  check_axis_dir(axis1_cooldown,adjust_deadzone(a1, _jcontrol_map.walk_deadzone));
    int axis2 =  check_axis_dir(axis2_cooldown,adjust_deadzone(a2, _jcontrol_map.walk_deadzone));
    int raw3 = adjust_deadzone(get_init_context().axis(_jcontrol_map.swap_axis?0:2), _jcontrol_map.cursor_deadzone);
    int raw4 = adjust_deadzone(get_init_context().axis(_jcontrol_map.swap_axis?1:3), _jcontrol_map.cursor_deadzone);
    int axis3;
    int axis4;
    bool slow = _jcontrol_slow || (_jcontrol_map.slow_cursor_with_mod && _jcontrol_mod_key);
    if (slow) {
        // precise mode: about a quarter of the normal speed, sub-pixel steps are accumulated
        auto speed = [](int c) {
            double f = std::pow(std::abs(c) * 0.001, 2) * 0.025 * 0.25;
            return c < 0 ? -f : f;
        };
        _cursor_acc_x += speed(raw3);
        _cursor_acc_y += speed(raw4);
        axis3 = static_cast<int>(_cursor_acc_x);
        axis4 = static_cast<int>(_cursor_acc_y);
        _cursor_acc_x -= axis3;
        _cursor_acc_y -= axis4;
        if (raw3 == 0) _cursor_acc_x = 0;
        if (raw4 == 0) _cursor_acc_y = 0;
    } else {
        axis3 = axis_dynamic(raw3);
        axis4 = axis_dynamic(raw4);
        _cursor_acc_x = _cursor_acc_y = 0;
    }

    int newx  =  this->ms_event.x + axis3;
    int newy  =  this->ms_event.y + axis4;
    if (newx <0) newx = 0;
    if (newx > 639) newx = 639;
    if (newy <0) newy = 0;
    if (newy > 479) newy = 479;
    if (axis3 || axis4) {
        this->ms_event.x = (uint16_t)newx;
        this->ms_event.y = (uint16_t)newy;
        this->ms_event.event = 1;
        this->ms_event.event_type = 1;
        if (_mouse) {
            SDL_Point pt(this->ms_event.x, this->ms_event.y);
            SDL_Rect winrc = get_window_aspect_rect();
            pt = to_window_point(winrc,pt);
            _mouse_rect.x = pt.x;
            _mouse_rect.y = pt.y;
            SDL_Event event;
            event.type = _refresh_request;
            SDL_PushEvent(&event);
        }
    }

    SDL_Scancode scn = {};
    switch(axis2) {
        case -1:  scn = SDL_SCANCODE_UP;break;
        case 1:   scn = SDL_SCANCODE_DOWN;break;
        default:break;
    }
    switch(axis1) {
        case -1:  if (_jcontrol_mod_key) scn = SDL_SCANCODE_END; else scn = SDL_SCANCODE_LEFT;break;
        case 1:   if (_jcontrol_mod_key) scn = SDL_SCANCODE_PAGEDOWN; else scn = SDL_SCANCODE_RIGHT;break;
        default:break;
    }
    if (scn != SDL_Scancode{}) {
        std::lock_guard _(_mx);
        _keyboard_queue.push(sdl_keycode_map.get_bios_code(scn,false, false));
    }
}

int SDLCALL SDLContext::app_event_watch(void *ctx, SDL_Event *e) {
    auto *me = static_cast<SDLContext *>(ctx);
    switch (e->type) {
        case SDL_APP_WILLENTERBACKGROUND:
            me->_in_background = true;
            if (me->_autosave_on_background) {
                // let the game handle F5 (its autosave) first, pause shortly after
                {
                    std::lock_guard _(me->_mx);
                    me->_keyboard_queue.push(sdl_keycode_map.get_bios_code(SDL_SCANCODE_F5, false, false));
                }
                SDL_Log("App entering background: autosave requested");
                SDL_AddTimer(800, [](Uint32, void *ctx) -> Uint32 {
                    auto *self = static_cast<SDLContext *>(ctx);
                    if (self->_in_background) {
                        game_set_inactive(GAME_INACTIVE_BACKGROUND, 1);
                        SDL_Log("Game paused at %u ms", (unsigned)get_game_tick_count());
                    }
                    return 0;
                }, me);
            } else {
                game_set_inactive(GAME_INACTIVE_BACKGROUND, 1);
                SDL_Log("App entering background: game paused at %u ms", (unsigned)get_game_tick_count());
            }
            break;
        case SDL_APP_DIDENTERFOREGROUND:
            me->_in_background = false;
            SDL_Log("App in foreground: game resumed at %u ms", (unsigned)get_game_tick_count());
            game_set_inactive(GAME_INACTIVE_BACKGROUND, 0);
            break;
        case SDL_RENDER_DEVICE_RESET:
            SDL_Log("Warning: render device reset (graphics context lost)");
            break;
        case SDL_WINDOWEVENT:
            if (me->_pause_on_focus_loss) {
                if (e->window.event == SDL_WINDOWEVENT_FOCUS_LOST) game_set_inactive(GAME_INACTIVE_FOCUS, 1);
                else if (e->window.event == SDL_WINDOWEVENT_FOCUS_GAINED) game_set_inactive(GAME_INACTIVE_FOCUS, 0);
            }
            break;
        default:
            break;
    }
    return 1;
}

// audio device of the game, for muting from other threads (audio focus)
static std::atomic<SDL_AudioDeviceID> g_audio_device{0};
static std::atomic<bool> g_audio_muted{false};

static Uint32 text_input_event_type() {
    static const Uint32 type = SDL_RegisterEvents(1);
    return type;
}

static void (*g_text_input_listener)(int active) = nullptr;

void game_set_text_input_listener(void (*listener)(int active)) {
    g_text_input_listener = listener;
}

static Uint32 show_keyboard_event_type() {
    static const Uint32 type = SDL_RegisterEvents(1);
    return type;
}

void game_show_keyboard(void) {
    SDL_Event ev = {};
    ev.type = show_keyboard_event_type();
    SDL_PushEvent(&ev);
}

void game_text_input(char active) {
    SDL_Event ev = {};
    ev.type = text_input_event_type();
    ev.user.code = active ? 1 : 0;
    SDL_PushEvent(&ev);
}

void SDLContext::set_text_input(bool active) {
    _text_input_active = active;
    if (g_text_input_listener) g_text_input_listener(active ? 1 : 0);
#ifdef __ANDROID__
    // shows / hides the soft keyboard
    if (active) SDL_StartTextInput(); else SDL_StopTextInput();
#endif
}

#ifdef __ANDROID__
/// UTF-8 text -> characters of the game's font code page (Kamenicky / CP895, Czech letters)
static std::vector<int> utf8_to_kamenicky(const char *s) {
    static const std::pair<char32_t, int> cz[] = {
        {U'á',0xA0},{U'č',0x87},{U'ď',0x83},{U'é',0x82},{U'ě',0x88},{U'í',0xA1},{U'ň',0xA4},{U'ó',0xA2},
        {U'ř',0xA9},{U'š',0xA8},{U'ť',0x9F},{U'ú',0xA3},{U'ů',0x96},{U'ý',0x98},{U'ž',0x91},
        {U'Á',0x8F},{U'Č',0x80},{U'Ď',0x85},{U'É',0x90},{U'Ě',0x89},{U'Í',0x8B},{U'Ň',0xA5},{U'Ó',0x95},
        {U'Ř',0x9E},{U'Š',0x9B},{U'Ť',0x86},{U'Ú',0x97},{U'Ů',0xA6},{U'Ý',0x9D},{U'Ž',0x92},
    };
    std::vector<int> out;
    const unsigned char *p = reinterpret_cast<const unsigned char *>(s);
    while (*p) {
        char32_t c;
        int len;
        if (*p < 0x80) { c = *p; len = 1; }
        else if ((*p & 0xE0) == 0xC0) { c = *p & 0x1F; len = 2; }
        else if ((*p & 0xF0) == 0xE0) { c = *p & 0x0F; len = 3; }
        else { c = *p & 0x07; len = 4; }
        for (int i = 1; i < len; ++i) {
            if ((p[i] & 0xC0) != 0x80) { len = i; c = 0; break; }
            c = (c << 6) | (p[i] & 0x3F);
        }
        p += len;
        if (c >= 32 && c < 127) { out.push_back(static_cast<int>(c)); continue; }
        for (const auto &e : cz) if (e.first == c) { out.push_back(e.second); break; }
    }
    return out;
}
#endif

static Uint32 audio_mute_event_type() {
    static const Uint32 type = SDL_RegisterEvents(1);
    return type;
}

// Called from the Android UI thread. It must not call SDL audio functions there: while the app
// is paused SDL keeps the audio device locked until the event loop resumes it, and the event
// loop in turn waits for the UI thread (surface) -> deadlock / ANR. So only remember the state
// and let the event loop apply it.
void game_set_audio_muted(char muted) {
    g_audio_muted = muted != 0;
    SDL_Event ev = {};
    ev.type = audio_mute_event_type();
    SDL_PushEvent(&ev);
}

void SDLContext::push_virtual_button(int button, bool pressed) {
    SDL_Event ev = {};
    ev.type = virtual_button_event_type();
    ev.user.code = button;
    ev.user.data1 = reinterpret_cast<void *>(static_cast<intptr_t>(pressed ? 1 : 0));
    SDL_PushEvent(&ev);
}

void SDLContext::configure_controller(const JoystickConfig &cfg) {
    _jcontrol_map = cfg;
}
bool SDLContext::is_joystick_used() const {
    return _jcontrol_used;
}

 bool SDLContext::is_joystick_enabled() const {
    return get_init_context().connected() && _jcontrol_map.enabled;
}

void SDLContext::generate_j_event(int button, char up) {
    if (_jcontrol_map.enabled && button >= 0 && button < static_cast<int>(sizeof(_jcontrol_map.buttons)/sizeof(JoystickButton))) {
        if (!_jcontrol_used) {
            SDL_AddTimer(25,[](Uint32 tm, void *ptr){
                SDLContext *me = reinterpret_cast<SDLContext *>(ptr);
                me->joystick_handle();
                return tm;
            }, this);
            _jcontrol_used = true;
        }
        JoystickButton b = _jcontrol_mod_key?_jcontrol_map.buttons_mod[button]:_jcontrol_map.buttons[button];
        SDL_Scancode cd = {};
        switch (b) {
            default: break;
            case JoystickButton::enter: if (!up) cd = SDL_SCANCODE_RETURN;break;
            case JoystickButton::space: if (!up) cd = SDL_SCANCODE_SPACE; break;
            case JoystickButton::ctrl: _key_control = !up; break;
            case JoystickButton::lclick: ms_event.tl1 = !up;
                                         ms_event.event = 1;
                                         ms_event.event_type|= up?MS_EVENT_MOUSE_LRELEASE:MS_EVENT_MOUSE_LPRESS;
                                         break;
            case JoystickButton::rclick: ms_event.tl2 = !up;
                                         ms_event.event = 1;
                                         ms_event.event_type|= up?MS_EVENT_MOUSE_RRELEASE:MS_EVENT_MOUSE_RPRESS;
                                         break;
            case JoystickButton::ctrl_lclick:_key_control = !up;
                                         ms_event.tl1 = !up;
                                         ms_event.event = 1;
                                         ms_event.event_type|= up?MS_EVENT_MOUSE_LRELEASE:MS_EVENT_MOUSE_LPRESS;
                                         break;
            case JoystickButton::escape: if (!up) cd = SDL_SCANCODE_ESCAPE;break;
            case JoystickButton::up: if (!up) cd = SDL_SCANCODE_UP;break;
            case JoystickButton::down: if (!up) cd = SDL_SCANCODE_DOWN;break;
            case JoystickButton::left: if (!up) cd = SDL_SCANCODE_PAGEDOWN;break;
            case JoystickButton::right: if (!up) cd = SDL_SCANCODE_END;break;
            case JoystickButton::turn_left: if (!up) cd = SDL_SCANCODE_LEFT;break;
            case JoystickButton::turn_right: if (!up) cd = SDL_SCANCODE_RIGHT;break;
            case JoystickButton::merge: if (!up) cd = SDL_SCANCODE_INSERT;break;
            case JoystickButton::map: if (!up) cd = SDL_SCANCODE_TAB;break;
            case JoystickButton::split1: if (!up) cd = SDL_SCANCODE_1;break;
            case JoystickButton::split2: if (!up) cd = SDL_SCANCODE_2;break;
            case JoystickButton::split3: if (!up) cd = SDL_SCANCODE_3;break;
            case JoystickButton::split4: if (!up) cd = SDL_SCANCODE_4;break;
            case JoystickButton::split5: if (!up) cd = SDL_SCANCODE_5;break;
            case JoystickButton::split6: if (!up) cd = SDL_SCANCODE_6;break;
            case JoystickButton::F1: if (!up) cd = SDL_SCANCODE_F1;break;
            case JoystickButton::F2: if (!up) cd = SDL_SCANCODE_F2;break;
            case JoystickButton::F3: if (!up) cd = SDL_SCANCODE_F3;break;
            case JoystickButton::F4: if (!up) cd = SDL_SCANCODE_F4;break;
            case JoystickButton::F5: if (!up) cd = SDL_SCANCODE_F5;break;
            case JoystickButton::F6: if (!up) cd = SDL_SCANCODE_F6;break;
            case JoystickButton::F7: if (!up) cd = SDL_SCANCODE_F7;break;
            case JoystickButton::F8: if (!up) cd = SDL_SCANCODE_F8;break;
            case JoystickButton::F9: if (!up) cd = SDL_SCANCODE_F9;break;
            case JoystickButton::F10: if (!up) cd = SDL_SCANCODE_F10;break;
            case JoystickButton::F11: if (!up) cd = SDL_SCANCODE_F11;break;
            case JoystickButton::F12: if (!up) cd = SDL_SCANCODE_F12;break;
            case JoystickButton::backspace: if (!up) cd = SDL_SCANCODE_BACKSPACE;break;
            case JoystickButton::mod_key: _jcontrol_mod_key = !up; break;
            case JoystickButton::slow_cursor: _jcontrol_slow = !up; break;
        }
        if (cd != SDL_Scancode{}) {
            std::lock_guard _(_mx);
            _keyboard_queue.push(sdl_keycode_map.get_bios_code(cd, 0, 0));
        }
    }
}


void SDLContext::event_loop() {

    SDL_Event e;
    while (SDL_WaitEvent(&e)) {
        SDL_Scancode kbdevent = {};
        if (e.type == SDL_QUIT) {
            _quit_requested = true;
            if (_quit_callback) _quit_callback();
        } else if (e.type == exit_loop_event_type()) {
            break;
        } else if (e.type == _update_request_event) {
            update_screen();
        } else if (e.type == _refresh_request) {
            update_screen(true);
        } else if (e.type == SDL_WINDOWEVENT) {
                if (e.window.event == SDL_WINDOWEVENT_SIZE_CHANGED) {
                    _crt_effect.reset();
                    int ow = 0, oh = 0;
                    SDL_GetRendererOutputSize(_renderer.get(), &ow, &oh);
                    SDL_Log("Window size changed: %dx%d (output %dx%d)", e.window.data1, e.window.data2, ow, oh);
                }
                // On mobile the surface can appear or change after the game drew its first
                // screen (e.g. the controller info screen stayed black); present it again.
                if (e.window.event == SDL_WINDOWEVENT_SIZE_CHANGED || e.window.event == SDL_WINDOWEVENT_EXPOSED
                        || e.window.event == SDL_WINDOWEVENT_SHOWN || e.window.event == SDL_WINDOWEVENT_RESTORED) {
                    update_screen(true);
                    // and once more a moment later: the new surface may not be ready yet
                    SDL_AddTimer(150, [](Uint32, void *ctx) -> Uint32 {
                        SDL_Event ev = {};
                        ev.type = static_cast<SDLContext *>(ctx)->_refresh_request;
                        SDL_PushEvent(&ev);
                        return 0;
                    }, this);
                }
        } else if (e.type == SDL_RENDER_TARGETS_RESET || e.type == SDL_RENDER_DEVICE_RESET) {
            update_screen(true);
        } else if (e.type == text_input_event_type()) {
            set_text_input(e.user.code != 0);
        } else if (e.type == show_keyboard_event_type()) {
#ifdef __ANDROID__
            if (_text_input_active) SDL_StartTextInput();   // bring back a keyboard the user closed
#endif
#ifdef __ANDROID__
        } else if (e.type == SDL_TEXTINPUT) {
            // soft keyboard (and hardware keyboard while a text field is active): characters
            // arrive as text; convert them to the game's code page (Kamenicky)
            if (_text_input_active) {
                std::lock_guard _(_mx);
                for (int ch : utf8_to_kamenicky(e.text.text)) _keyboard_queue.push(static_cast<uint32_t>(ch));
            }
#endif
         } else if (e.type == SDL_KEYDOWN || e.type == SDL_KEYUP) {
            _key_capslock = e.key.keysym.mod & KMOD_CAPS;
            _key_shift =e.key.keysym.mod & KMOD_SHIFT;
            _key_control  =e.key.keysym.mod & KMOD_CTRL;
            if (e.type == SDL_KEYDOWN) {
                if (e.key.keysym.sym == SDLK_RETURN && (e.key.keysym.mod & KMOD_ALT)) {
                    _fullscreen_mode = !_fullscreen_mode;
                    SDL_SetWindowFullscreen(_window.get(), _fullscreen_mode ? SDL_WINDOW_FULLSCREEN_DESKTOP : 0);
                } else {
                    SDL_Scancode sc = e.key.keysym.scancode;
#ifdef __ANDROID__
                    // in a text field printable characters come as SDL_TEXTINPUT (see above)
                    if (_text_input_active && e.key.keysym.sym >= 32 && e.key.keysym.sym < 127
                            && !(e.key.keysym.mod & KMOD_CTRL)) {
                        continue;
                    }
#endif
                    // Android Back button / gesture behaves as ESC
                    if (sc == SDL_SCANCODE_AC_BACK) sc = SDL_SCANCODE_ESCAPE;
                    auto code =sdl_keycode_map.get_bios_code(sc,
                            e.key.keysym.mod & KMOD_SHIFT, e.key.keysym.mod & KMOD_CTRL);
                    if (code) {
                        std::lock_guard _(_mx);
                        _keyboard_queue.push(code);
                    }
                }
            }
        } else if (e.type == SDL_MOUSEMOTION) {
            SDL_Event temp;
            while (SDL_PeepEvents(&temp, 1, SDL_GETEVENT, SDL_MOUSEMOTION, SDL_MOUSEMOTION)) {
                e= temp;
            }

            SDL_Point mspt = window_to_output(SDL_Point{e.motion.x, e.motion.y});
            SDL_Rect winrc = get_window_aspect_rect();
            SDL_Point srcpt = to_source_point(winrc, mspt);
            ms_event.event = 1;
            ms_event.event_type |= 1;
            ms_event.x = srcpt.x;
            ms_event.y = srcpt.y;
            if (_mouse) {
                _mouse_rect.x = mspt.x;
                _mouse_rect.y = mspt.y;
                update_screen(true);

            }
        } else if (e.type == SDL_MOUSEBUTTONDOWN || e.type == SDL_MOUSEBUTTONUP) {
            int button = e.button.button;
            int up =  e.type == SDL_MOUSEBUTTONUP?1:0;
            ms_event.event = 1;
            int shift = 0;
            switch (button) {
                default: break;
                case 1: ms_event.tl1 = !up; shift = 1; break;
                case 2: ms_event.tl3 = !up; shift = 5; break;
                case 3: ms_event.tl2 = !up; shift = 3; break;
            }
            ms_event.event_type |= (1<<(shift+up));
        } else if (e.type == SDL_MOUSEWHEEL) {
            if (e.wheel.y > 0) kbdevent =SDL_SCANCODE_UP;
            else if (e.wheel.y < 0) kbdevent =SDL_SCANCODE_DOWN;
        } else if (e.type == SDL_CONTROLLERBUTTONDOWN) {
            // standard game controller layout (A=0, B=1, X=2, Y=3, Back=4, Guide=5, Start=6,
            // L3=7, R3=8, L1=9, R1=10, D-pad up/down/left/right=11..14)
            if (get_init_context().is_ours(e.cbutton.which)) generate_j_event(e.cbutton.button, 0);
        } else if (e.type == SDL_CONTROLLERBUTTONUP) {
            if (get_init_context().is_ours(e.cbutton.which)) generate_j_event(e.cbutton.button, 1);
        } else if (e.type == SDL_CONTROLLERAXISMOTION) {
            // analog triggers act as buttons 21 / 22 (with hysteresis)
            if ((e.caxis.axis == SDL_CONTROLLER_AXIS_TRIGGERLEFT || e.caxis.axis == SDL_CONTROLLER_AXIS_TRIGGERRIGHT)
                    && get_init_context().is_ours(e.caxis.which)) {
                int t = e.caxis.axis == SDL_CONTROLLER_AXIS_TRIGGERLEFT ? 0 : 1;
                bool down = _trigger_down[t] ? e.caxis.value > 8000 : e.caxis.value > 16000;
                if (down != _trigger_down[t]) {
                    _trigger_down[t] = down;
                    generate_j_event(CONTROLLER_BUTTON_LEFT_TRIGGER + t, down ? 0 : 1);
                }
            }
        } else if (e.type == audio_mute_event_type()) {
            SDL_AudioDeviceID id = g_audio_device.load();
            if (id) SDL_PauseAudioDevice(id, g_audio_muted.load() ? 1 : 0);
        } else if (e.type == virtual_button_event_type()) {
            bool pressed = reinterpret_cast<intptr_t>(e.user.data1) != 0;
            generate_j_event(e.user.code, pressed ? 0 : 1);
        } else if (e.type == SDL_CONTROLLERDEVICEADDED) {
            get_init_context().on_added();
        } else if (e.type == SDL_CONTROLLERDEVICEREMOVED) {
            get_init_context().on_removed(e.cdevice.which);
        }

        if (kbdevent != SDL_Scancode{}) {
            auto code =sdl_keycode_map.get_bios_code(kbdevent,false, false);
            std::lock_guard _(_mx);
            _keyboard_queue.push(code);
        }

    }
}



void SDLContext::present_rect(uint16_t *pixels, unsigned int pitch,
        unsigned int x, unsigned int y, unsigned int xs, unsigned ys) {


    auto beg = pixels + y * pitch + x;
    SDL_Rect r = {static_cast<int>(x),
                  static_cast<int>(y),
                  static_cast<int>(xs),
                  static_cast<int>(ys)};

    std::lock_guard _(_mx);
    signal_push();
    push_update_msg(r, beg, pitch);
}

std::uint16_t SDLContext::pop_keyboard_code()  {
    std::lock_guard _(_mx);
    std::uint16_t out = _keyboard_queue.front();
    _keyboard_queue.pop();
    return out;
}

bool SDLContext::is_keyboard_ready() const {
    std::lock_guard _(_mx);
    return !_keyboard_queue.empty();
}

void SDLContext::show_slide_transition(const SDL_Rect &visible_from,
        const SDL_Rect &visible_where, const SDL_Rect &hidden_from,
        const SDL_Rect &hidden_where) {
    std::lock_guard _(_mx);
    push_item(DisplayRequest::slide_transition);
    push_item(SlideTransitionReq{visible_from, visible_where,hidden_from, hidden_where});
}

void SDLContext::signal_push() {
    if (_display_update_queue.empty()) {
        SDL_Event event;
        event.type = _update_request_event;
        SDL_PushEvent(&event);
    }

}

void SDLContext::refresh_screen() {
    SDL_Rect winrc = get_window_aspect_rect();
    SDL_RenderClear(_renderer.get());
    if (slide_transition) {
        SDL_SetTextureAlphaMod(_hidden_texture, 255);
        SDL_SetTextureAlphaMod(_visible_texture, 255);
        SDL_RenderCopy(_renderer.get(), _hidden_texture, NULL, &winrc);
        SDL_Rect visible_where_win = to_window_rect(winrc, slide_transition->visible_where);
        SDL_Rect hidden_where_win = to_window_rect(winrc, slide_transition->hidden_where);
        SDL_RenderCopy(_renderer.get(), _visible_texture, &slide_transition->visible_from, &visible_where_win);
        SDL_RenderCopy(_renderer.get(), _hidden_texture, &slide_transition->hidden_from, &hidden_where_win);
    }
    else if (blend_transition) {
        SDL_SetTextureAlphaMod(_hidden_texture, 255);
        SDL_RenderCopy(_renderer.get(), _hidden_texture, NULL, &winrc);
        if (blend_transition->phase >= 0) {
            float f = blend_transition->phase;
            SDL_SetTextureAlphaMod(_visible_texture, 255);
            SDL_Rect wrkarea = to_window_rect(winrc, blend_transition->wrkarea);
            SDL_Rect src1 = transition_rect(blend_transition->prev, blend_transition->next, f);
            SDL_RenderCopy(_renderer.get(), _visible_texture, &src1, &wrkarea);
            if (SDL_SetTextureAlphaMod(_hidden_texture, (uint8_t)(255.0f*(f)))!= -1) {
                SDL_Rect trgnxt = to_window_rect(winrc, blend_transition->next);
                SDL_Rect trgwin = transition_rect(trgnxt, wrkarea, f);
                SDL_RenderCopy(_renderer.get(), _hidden_texture, &blend_transition->wrkarea, &trgwin);
            }
        } else {
            float f = blend_transition->phase;
            SDL_Rect wrkarea = to_window_rect(winrc, blend_transition->wrkarea);
            SDL_Rect src1 = transition_rect(blend_transition->prev, blend_transition->next, 1+f);
            SDL_RenderCopy(_renderer.get(), _hidden_texture, &src1, &wrkarea);
            if (SDL_SetTextureAlphaMod(_visible_texture, (uint8_t)(255.0f*(1+f))) != -1) {
                SDL_Rect trgnxt = to_window_rect(winrc, blend_transition->next);
                SDL_Rect trgwin = transition_rect(trgnxt, wrkarea, 1+f);
                SDL_RenderCopy(_renderer.get(), _visible_texture, &blend_transition->wrkarea, &trgwin);
            }
        }
    } else {
        SDL_SetTextureAlphaMod(_visible_texture, 255);
        SDL_RenderCopy(_renderer.get(), _visible_texture, NULL, &winrc);
    }
    for (const auto &sprite: _sprites) if (sprite.shown) {
        SDL_Rect rc = to_window_rect(winrc,sprite._rect);
        SDL_RenderCopy(_renderer.get(), sprite._txtr.get(), NULL, &rc);
    }
    if (_mouse) {
        SDL_Rect recalc_rect = to_window_rect(winrc, _mouse_rect);
        recalc_rect.w = static_cast<int>(recalc_rect.w * _mouse_size);
        recalc_rect.h = static_cast<int>(recalc_rect.h * _mouse_size);
        SDL_Point f= to_window_point({0,0,winrc.w, winrc.h}, _mouse_finger);
        recalc_rect.x = _mouse_rect.x - static_cast<int>(f.x * _mouse_size);
        recalc_rect.y = _mouse_rect.y - static_cast<int>(f.y * _mouse_size);
        SDL_RenderCopy(_renderer.get(), _mouse.get(), NULL, &recalc_rect);
    }
    if (winrc.h >= 720 && _crt_filter != CrtFilterType::none && _enable_crt && !_disable_crt_tmp)  {
        if (!_crt_effect) {
            SDL_Texture *txt;
              generateCRTTexture(_renderer.get(), &txt, winrc.w, winrc.h, _crt_filter);
            _crt_effect.reset(txt);
        }
        SDL_RenderCopy(_renderer.get(), _crt_effect.get(), NULL, &winrc);
    }
    SDL_RenderPresent(_renderer.get());

}

void SDLContext::update_screen(bool force_refresh) {
    {
        std::lock_guard _(_mx);
        if (_display_update_queue.empty()) {
            if (force_refresh) refresh_screen();
            return;
        }
        QueueIter iter = _display_update_queue.data();
        QueueIter end = iter + _display_update_queue.size();
        while (iter != end) {
            DisplayRequest req;
            pop_item(iter, req);
            switch (req) {
                case DisplayRequest::update: {
                    SDL_Rect r;
                    pop_item(iter, r);
                    std::string_view data = pop_data(iter, r.w*r.h*2);
                    update_texture_with_conversion(_texture.get(), &r, data.data(), r.w*2);
                }
                break;
                case DisplayRequest::show_mouse_cursor: {
                    SDL_Rect r;
                    pop_item(iter, r);
                    std::string_view data = pop_data(iter, r.w*r.h*2);
                    _mouse.reset(SDL_CreateTexture(_renderer.get(), _texture_render_format,SDL_TEXTUREACCESS_STATIC, r.w, r.h));
                    if (!_mouse) handle_sdl_error("Failed to create surface for mouse cursor");
                    SDL_SetTextureBlendMode(_mouse.get(), SDL_BLENDMODE_BLEND);
                    _mouse_rect.w = r.w;
                    _mouse_rect.h = r.h;
                    update_texture_with_conversion(_mouse.get(), NULL, data.data(), r.w*2);
                }
                break;
                case DisplayRequest::hide_mouse_cursor: {
                    _mouse.reset();
                }
                break;
                case DisplayRequest::swap_render_buffers: {
                    std::swap(_texture,_texture2);
                }
                break;
                case DisplayRequest::swap_visible_buffers: {
                    std::swap(_visible_texture,_hidden_texture);
                    blend_transition.reset();
                    slide_transition.reset();
                }
                break;
                case DisplayRequest::blend_transition:
                    blend_transition.emplace();
                     pop_item(iter, *blend_transition);
                break;
                case DisplayRequest::slide_transition:
                    slide_transition.emplace();
                     pop_item(iter, *slide_transition);
                break;
                case DisplayRequest::sprite_load: {
                    int id;
                    SDL_Rect r;
                    pop_item(iter, id);
                    pop_item(iter, r);
                    std::string_view data = pop_data(iter, r.w*r.h*2);
                    auto iter = std::find_if(_sprites.begin(), _sprites.end(),[&](const Sprite &x){
                        return x.id == id;
                    });
                    if (iter == _sprites.end()) {
                        iter = _sprites.insert(iter,{id});
                    }
                    iter->_txtr.reset(SDL_CreateTexture(_renderer.get(), _texture_render_format, SDL_TEXTUREACCESS_STATIC,r.w, r.h));
                    if (!iter->_txtr) handle_sdl_error("Failed to create compositor sprite");
                    SDL_SetTextureBlendMode(iter->_txtr.get(), SDL_BLENDMODE_BLEND);
                    update_texture_with_conversion(iter->_txtr.get(), NULL, data.data(), r.w*2);
                    iter->_rect = r;
                    update_zindex();
                } break;
                case DisplayRequest::sprite_unload: {
                    int id;
                    pop_item(iter, id);
                    auto iter = std::remove_if(_sprites.begin(), _sprites.end(),[&](const Sprite &x){
                        return x.id == id;
                    });
                    _sprites.erase(iter,_sprites.end());
                } break;
                case DisplayRequest::sprite_hide: {
                    int id;
                    pop_item(iter, id);
                    auto iter = std::find_if(_sprites.begin(), _sprites.end(),[&](const Sprite &x){
                        return x.id == id;
                    });
                    if (iter != _sprites.end()) iter->shown = false;
                } break;
                case DisplayRequest::sprite_place: {
                    int id;
                    SDL_Point pt;
                    pop_item(iter, id);
                    pop_item(iter, pt);
                    auto iter = std::find_if(_sprites.begin(), _sprites.end(),[&](const Sprite &x){
                        return x.id == id;
                    });
                    if (iter != _sprites.end()) {
                        iter->shown = true;
                        iter->_rect.x = pt.x;
                        iter->_rect.y = pt.y;
                    }
                } break;
                case DisplayRequest::sprite_scale: {
                    int id;
                    SDL_Rect rc;
                    pop_item(iter, id);
                    pop_item(iter, rc);
                    auto iter = std::find_if(_sprites.begin(), _sprites.end(),[&](const Sprite &x){
                        return x.id == id;
                    });
                    if (iter != _sprites.end()) {
                        iter->shown = true;
                        iter->_rect = rc;
                    }
                } break;
                case DisplayRequest::sprite_zindex: {
                    int id;
                    int zindex;
                    pop_item(iter, id);
                    pop_item(iter, zindex);
                    auto iter = std::find_if(_sprites.begin(), _sprites.end(),[&](const Sprite &x){
                        return x.id == id;
                    });
                    if (iter != _sprites.end()) {
                        iter->zindex = zindex;
                        update_zindex();
                    }
                } break;

            }
        }
        _display_update_queue.clear();

    }
    refresh_screen();
}


void SDLContext::update_zindex() {
    std::stable_sort(_sprites.begin(), _sprites.end(), [&](const Sprite &a, const Sprite &b){
        return a.zindex < b.zindex;
    });

}
template<typename T>
requires(std::is_trivially_copy_constructible_v<T>)
void  SDLContext::push_item(const T &item) {
    auto b = reinterpret_cast<const char *>(&item);
    auto e = b + sizeof(T);
    auto sz = _display_update_queue.size();
    if (sz == 0) signal_push();
    _display_update_queue.resize(sz + sizeof(T));
    std::copy(b, e, _display_update_queue.begin()+sz);
}

void SDLContext::push_item(const std::string_view &item) {
    auto sz = _display_update_queue.size();
    if (sz == 0) signal_push();
    _display_update_queue.resize(sz + item.size());
    std::copy(item.begin(), item.end(),  _display_update_queue.begin()+sz);
}

void SDLContext::push_update_msg(const SDL_Rect &rc, const uint16_t *data, int pitch) {
    push_item(DisplayRequest::update);
    push_item(rc);
    auto sz = _display_update_queue.size();
    _display_update_queue.resize(sz+rc.w*rc.h*2);
    short *trg = reinterpret_cast<short *>(_display_update_queue.data()+sz);
    for (int yp = 0; yp < rc.h; ++yp) {
        std::copy(data, data+rc.w, trg);
        trg += rc.w;
        data += pitch;
    }
}

template<typename T>
requires(std::is_trivially_copy_constructible_v<T>)
void SDLContext::pop_item(QueueIter &iter, T &item) {
    std::copy(iter, iter+sizeof(T), reinterpret_cast<char *>(&item));
    iter += sizeof(T);
}
std::string_view SDLContext::pop_data(QueueIter &iter, std::size_t size) {
    const char *c = iter;
    iter += size;
    return std::string_view(c, size);
}

void SDLContext::swap_render_buffers() {
    std::lock_guard _(_mx);
    push_item(DisplayRequest::swap_render_buffers);
}

void SDLContext::swap_display_buffers() {
    std::lock_guard _(_mx);
    push_item(DisplayRequest::swap_visible_buffers);
}

void SDLContext::show_blend_transition(const SDL_Rect &wrkarea, const SDL_Rect &prev,
        const SDL_Rect &next, float phase) {
    std::lock_guard _(_mx);
    push_item(DisplayRequest::blend_transition);
    push_item(BlendTransitionReq{wrkarea, prev, next, phase});
}

SDL_Point SDLContext::window_to_output(const SDL_Point &pt) const {
    // mouse / touch coordinates are in window units, rendering is in output pixels;
    // they differ e.g. with display scaling on some phones or high-DPI desktops
    int ww = 0, wh = 0, ow = 0, oh = 0;
    SDL_GetWindowSize(_window.get(), &ww, &wh);
    SDL_GetRendererOutputSize(_renderer.get(), &ow, &oh);
    if (ww <= 0 || wh <= 0 || (ww == ow && wh == oh)) return pt;
    return {pt.x * ow / ww, pt.y * oh / wh};
}

SDL_Rect SDLContext::get_window_aspect_rect() const {
    SDL_Rect w;
    int ww;
    int wh;
    // the picture is placed in renderer output pixels (not window units)
    if (SDL_GetRendererOutputSize(_renderer.get(), &ww, &wh) != 0 || ww <= 0 || wh <= 0) {
        SDL_GetWindowSize(_window.get(), &ww, &wh);
    }
    if (_integer_scale && ww >= 640 && wh >= 480) {
        // largest whole multiple of the 640x480 game screen that fits, centered
        int k = std::min(ww / 640, wh / 480);
        w.w = 640 * k;
        w.h = 480 * k;
        w.x = (ww - w.w) / 2;
        w.y = (wh - w.h) / 2;
    } else if (aspect_x && aspect_y) {
        int apw  = wh * aspect_x / aspect_y;
        int aph =  ww * aspect_y / aspect_x;
        int fw;
        int fh;
        if (apw > ww) {
            fw = ww;
            fh = aph;
        } else {
            fw = apw;
            fh = wh;
        }
        w.h = fh;
        w.w = fw;
        w.x = (ww - fw)/2;
        w.y = (wh - fh)/2;
    } else {
        w.x = 0;
        w.y = 0;
        w.w = ww;
        w.h = wh;
    }
    if (!_integer_scale && _picture_scale > 0 && _picture_scale < 100) {
        // user picture size (e.g. to keep the picture away from curved display edges)
        int nw = w.w * _picture_scale / 100;
        int nh = w.h * _picture_scale / 100;
        w.x += (w.w - nw) / 2;
        w.y += (w.h - nh) / 2;
        w.w = nw;
        w.h = nh;
    }
    return w;
}

SDL_Point SDLContext::to_window_point(const SDL_Rect &winrc, const SDL_Point &pt) {
    return {
        pt.x * winrc.w / 640 + winrc.x,
        pt.y * winrc.h / 480 + winrc.y,
    };
}

SDL_Point SDLContext::to_source_point(const SDL_Rect &winrc, const SDL_Point &win_pt)  {
    return {
        (win_pt.x - winrc.x) * 640 / winrc.w,
        (win_pt.y - winrc.y) * 480 / winrc.h,
    };
}

int SDLContext::transition_int(int beg, int end, float phase) {
    int w = end - beg;
    return beg + static_cast<int>(std::round(w * phase));
}

SDL_Rect SDLContext::transition_rect(const SDL_Rect &beg, const SDL_Rect &end, float phase) {
    SDL_Rect out;
    out.x = transition_int(beg.x, end.x, phase);
    out.y = transition_int(beg.y, end.y, phase);
    out.w = transition_int(beg.w, end.w, phase);
    out.h = transition_int(beg.h, end.h, phase);
    return out;
}


SDL_Rect SDLContext::to_window_rect(const SDL_Rect &winrc, const SDL_Rect &source_rect) {
    SDL_Point pt1(source_rect.x, source_rect.y);
    SDL_Point pt2(source_rect.x+source_rect.w, source_rect.y+source_rect.h);
    SDL_Point wpt1(to_window_point(winrc, pt1));
    SDL_Point wpt2(to_window_point(winrc, pt2));
    return {wpt1.x, wpt1.y, wpt2.x - wpt1.x, wpt2.y - wpt1.y};
}
void SDLContext::set_quit_callback(std::function<void()> fn) {
    _quit_callback = std::move(fn);
}

SDLContext::AudioInfo SDLContext::init_audio(const AudioConfig &config, SDL_AudioCallback cb, void *cb_ctx) {
    _audio.reset();
    SDL_AudioSpec aspec = {};
    aspec.callback = cb;
    aspec.userdata = cb_ctx;
    aspec.channels = 2;
    aspec.format = AUDIO_F32;
    aspec.freq = 44100;
    aspec.samples = 1024;
    SDL_AudioSpec obtaied = {};
    auto id = SDL_OpenAudioDevice(config.audioDevice, 0, &aspec, &obtaied, SDL_AUDIO_ALLOW_FREQUENCY_CHANGE|SDL_AUDIO_ALLOW_SAMPLES_CHANGE);
    if (id < 1) {
        std::ostringstream err;
        err << "SDL Error create audio:" << SDL_GetError() << "\n";
        int n = SDL_GetNumAudioDevices(0);
        if (n == 0) err << "No audio devices are available" << "\n";
        else {
            err << "Available audio devices:";
            char sep = ' ';
            for (int i = 0; i < n; ++i) {
                err << sep << SDL_GetAudioDeviceName(i, 0);
                sep = ',';
            }
        }
        throw std::runtime_error(err.str());
    }
    _audio.reset(id);
    g_audio_device = id;

    return {obtaied.freq};
}
void SDLContext::pause_audio(bool pause) {
    SDL_AudioDeviceID id = _audio.get();
    // stay paused while muted by the platform (audio focus lost)
    SDL_PauseAudioDevice(id, (pause || g_audio_muted.load()) ? 1 : 0);
}

void SDLContext::close_audio() {
    g_audio_device = 0;
    _audio.reset();
}

void SDLContext::set_window_icon(const void *icon_data, size_t icon_size) {
    SDL_Surface *surface = SDL_LoadBMP_RW(SDL_RWFromConstMem(icon_data, icon_size), 1);
    if (surface) {
        SDL_SetWindowIcon(_window.get(), surface);
    }
}

extern "C" {
void put_picture_ex(unsigned short x,unsigned short y,const void *p, unsigned short *target_addr, size_t pitch);
}

void  SDLContext::push_hi_image(const unsigned short *image) {
    SDL_Rect rc = {};
    rc.w= image[0];
    rc.h =image[1];
    push_item(rc);
    auto sz = _display_update_queue.size();
    auto imgsz = rc.w*rc.h;
    _display_update_queue.resize(sz+imgsz*2);
    unsigned short *trg = reinterpret_cast<unsigned short *>(_display_update_queue.data()+sz);
    std::fill(trg, trg+imgsz, 0x8000);
    put_picture_ex(0, 0, image, trg, rc.w);
}

void SDLContext::show_mouse_cursor(const unsigned short *ms_hi_format, SDL_Point finger) {
    std::lock_guard _(_mx);
    push_item(DisplayRequest::show_mouse_cursor);
    push_hi_image(ms_hi_format);
    _mouse_finger = finger;
}

void SDLContext::hide_mouse_cursor() {
    std::lock_guard _(_mx);
    push_item(DisplayRequest::hide_mouse_cursor);

}

void SDLContext::load_sprite(int sprite_id, const unsigned short *hi_image) {
    std::lock_guard _(_mx);
    push_item(DisplayRequest::sprite_load);
    push_item(sprite_id);
    push_hi_image(hi_image);

}

void SDLContext::place_sprite(int sprite_id, int x, int y) {
    std::lock_guard _(_mx);
    push_item(DisplayRequest::sprite_place);
    push_item(sprite_id);
    SDL_Point pt{x,y};
    push_item(pt);

}

void SDLContext::scale_sprite(int sprite_id, int x, int y, int w, int h) {
    std::lock_guard _(_mx);
    push_item(DisplayRequest::sprite_scale);
    push_item(sprite_id);
    SDL_Rect pt{x,y,w,h};
    push_item(pt);
}

void SDLContext::hide_sprite(int sprite_id) {
    std::lock_guard _(_mx);
    push_item(DisplayRequest::sprite_hide);
    push_item(sprite_id);
}

void SDLContext::sprite_set_zindex(int sprite_id, int zindex) {
    std::lock_guard _(_mx);
    push_item(DisplayRequest::sprite_hide);
    push_item(sprite_id);
    push_item(zindex);
}

void SDLContext::unload_sprite(int sprite) {
    std::lock_guard _(_mx);
    push_item(DisplayRequest::sprite_unload);
    push_item(sprite);
}

void SDLContext::enable_crt_filter(bool enable)
{
    std::lock_guard _(_mx);
    _enable_crt = enable;
    signal_push();
}

bool SDLContext::is_crt_enabled() const
{
    std::lock_guard _(_mx);
    return _enable_crt;
}

SDLContext::JoystickButton SDLContext::button_from_string(std::string_view s) {
    if (s=="enter") return JoystickButton::enter;
    if (s=="space") return JoystickButton::space;
    if (s=="ctrl") return JoystickButton::ctrl;
    if (s=="lclick") return JoystickButton::lclick;
    if (s=="rclick") return JoystickButton::rclick;
    if (s=="ctrl+lclick") return JoystickButton::ctrl_lclick;
    if (s=="escape") return JoystickButton::escape;
    if (s=="up") return JoystickButton::up;
    if (s=="down") return JoystickButton::down;
    if (s=="left") return JoystickButton::left;
    if (s=="right") return JoystickButton::right;
    if (s=="turn_left") return JoystickButton::turn_left;
    if (s=="turn_right") return JoystickButton::turn_right;
    if (s=="merge_group") return JoystickButton::merge;
    if (s=="split1") return JoystickButton::split1;
    if (s=="split2") return JoystickButton::split2;
    if (s=="split3") return JoystickButton::split3;
    if (s=="split4") return JoystickButton::split4;
    if (s=="split5") return JoystickButton::split5;
    if (s=="split6") return JoystickButton::split6;
    if (s=="map") return JoystickButton::map;
    if (s=="F1") return JoystickButton::F1;
    if (s=="F2") return JoystickButton::F2;
    if (s=="F3") return JoystickButton::F3;
    if (s=="F4") return JoystickButton::F4;
    if (s=="F5") return JoystickButton::F5;
    if (s=="F6") return JoystickButton::F6;
    if (s=="F7") return JoystickButton::F7;
    if (s=="F8") return JoystickButton::F8;
    if (s=="F9") return JoystickButton::F9;
    if (s=="F10") return JoystickButton::F10;
    if (s=="F11") return JoystickButton::F11;
    if (s=="F12") return JoystickButton::F12;
    if (s=="backspace") return JoystickButton::backspace;
    if (s=="mod_key") return JoystickButton::mod_key;
    if (s=="slow_cursor") return JoystickButton::slow_cursor;
    return JoystickButton::disabled;

}

void SDLContext::disable_crt_effect_temprary(bool disable) {
    _disable_crt_tmp = disable;
}
