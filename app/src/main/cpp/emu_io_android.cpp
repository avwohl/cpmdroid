/*
 * Emulator I/O Implementation - Android (JNI)
 *
 * This implementation uses JNI callbacks to communicate with the
 * Kotlin/Java Android application layer.
 */

#include "emu_io.h"
#include <jni.h>
#include <android/log.h>
#include <string>
#include <queue>
#include <mutex>
#include <vector>
#include <cstring>
#include <cstdarg>
#include <ctime>
#include <random>
#include <thread>
#include <chrono>

#include "hbios_cpu.h"
#include "hbios_dispatch.h"
#include "emu_init.h"
#include "romwbw_mem.h"

#define LOG_TAG "CPMDroid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

//=============================================================================
// Android Emulator Delegate
//=============================================================================

class AndroidEmulatorDelegate : public HBIOSCPUDelegate {
private:
    banked_mem* memory;
    HBIOSDispatch* hbios;
    bool debug;

public:
    AndroidEmulatorDelegate(banked_mem* mem, HBIOSDispatch* hb)
        : memory(mem), hbios(hb), debug(false) {}

    banked_mem* getMemory() override { return memory; }
    HBIOSDispatch* getHBIOS() override { return hbios; }

    void initializeRamBankIfNeeded(uint8_t bank) override {
        // Use HBIOSDispatch's shared bitmap
        uint16_t* bitmap = hbios->getInitializedBanksBitmap();
        if (bitmap) {
            emu_init_ram_bank(memory, bank, bitmap);
        }
    }

    void onHalt() override {
        LOGE("CPU HALT at PC=0x%04X", 0);
    }

    void onUnimplementedOpcode(uint8_t opcode, uint16_t pc) override {
        LOGE("Unimplemented opcode 0x%02X at PC=0x%04X", opcode, pc);
    }

    void logDebug(const char* fmt, ...) override {
        if (debug) {
            char buf[1024];
            va_list args;
            va_start(args, fmt);
            vsnprintf(buf, sizeof(buf), fmt, args);
            va_end(args);
            LOGD("%s", buf);
        }
    }

    void setDebug(bool d) { debug = d; }
};

//=============================================================================
// Emulator State Class - Encapsulates all emulator state for clean reboot
//=============================================================================

class EmulatorState {
public:
    banked_mem* memory = nullptr;
    hbios_cpu* cpu = nullptr;
    HBIOSDispatch* hbios = nullptr;
    AndroidEmulatorDelegate* delegate = nullptr;

    EmulatorState() {
        LOGI("EmulatorState: Creating new instance");
        memory = new banked_mem();
        hbios = new HBIOSDispatch();
        delegate = new AndroidEmulatorDelegate(memory, hbios);
        cpu = new hbios_cpu(memory, delegate);
        hbios->setCPU(cpu);
        hbios->setMemory(memory);
        hbios->setBlockingAllowed(false);  // Android uses non-blocking I/O
    }

    ~EmulatorState() {
        LOGI("EmulatorState: Destroying instance");
        delete cpu;
        delete delegate;
        delete hbios;
        delete memory;
    }

    // Non-copyable
    EmulatorState(const EmulatorState&) = delete;
    EmulatorState& operator=(const EmulatorState&) = delete;
};

//=============================================================================
// Global State
//=============================================================================

static EmulatorState* g_emu = nullptr;
static bool g_running = false;
static bool g_initialized = false;

// Cached ROM and disk data for reboot
static std::vector<uint8_t> g_cached_rom;
static std::vector<uint8_t> g_cached_disks[16];
static int g_cached_disk_slices[16] = {0};
static bool g_cached_disk_manifest[16] = {false};  // Track which disks are manifest (downloaded)

//=============================================================================
// I/O State
//=============================================================================

static std::queue<int> g_input_queue;
static std::queue<uint8_t> g_output_queue;
static std::mutex g_input_mutex;
static std::mutex g_output_mutex;

// JNI callback references
static JavaVM* g_jvm = nullptr;
static jobject g_callback_obj = nullptr;
static jmethodID g_on_output_method = nullptr;

// Debug and logging state
static volatile bool g_debug_enabled = false;

// Debug counters (file-scope so they can be reset on reboot)
static int g_run_count = 0;
static int g_output_log_count = 0;

// Random number generator
static std::mt19937 g_rng(std::random_device{}());

// Video state
// Note: cursor position is approximate — only updated by emu_video_* calls,
// not by normal console output via emu_console_write_char().  The Kotlin
// TerminalView tracks the real cursor.  These satisfy the emu_io.h interface
// but will be stale after any normal console output.
static int g_cursor_row = 0;
static int g_cursor_col = 0;
// Note: g_text_attr satisfies the emu_io.h interface but is not used by
// Android rendering (colors are driven by VT100 escapes in TerminalView).
static uint8_t g_text_attr = 0x07;

// Host file transfer state
static emu_host_file_state g_host_file_state = HOST_FILE_IDLE;
static std::vector<uint8_t> g_host_read_buffer;
static size_t g_host_read_pos = 0;
static std::string g_host_read_filename;
static std::vector<uint8_t> g_host_write_buffer;
static std::string g_host_write_filename;
// The app's Exports directory, handed down from Kotlin once at startup. The
// C++ side has no way to ask Android where getExternalFilesDir() points, and
// emu_host_file_get_write_name() has to report where the bytes really land -
// W8 prints that string to the CP/M user.
static std::string g_host_exports_dir;
// The effective destination, rebuilt at each open. Held separately from
// g_host_write_filename so the leaf and the full path cannot drift.
static std::string g_host_write_destination;

//=============================================================================
// Platform Utilities
//=============================================================================

void emu_sleep_ms(int ms) {
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
}

int emu_strcasecmp(const char* s1, const char* s2) {
    return strcasecmp(s1, s2);
}

int emu_strncasecmp(const char* s1, const char* s2, size_t n) {
    return strncasecmp(s1, s2, n);
}

//=============================================================================
// Console I/O Implementation
//=============================================================================

void emu_io_init() {
    LOGI("emu_io_init");
}

void emu_io_cleanup() {
    LOGI("emu_io_cleanup");
}

bool emu_console_has_input() {
    std::lock_guard<std::mutex> lock(g_input_mutex);
    return !g_input_queue.empty();
}

int emu_console_read_char() {
    std::lock_guard<std::mutex> lock(g_input_mutex);
    if (g_input_queue.empty()) {
        return -1;
    }
    int ch = g_input_queue.front();
    g_input_queue.pop();
    if (ch == '\n') ch = '\r';  // LF -> CR for CP/M
    return ch;
}

void emu_console_queue_char(int ch) {
    std::lock_guard<std::mutex> lock(g_input_mutex);
    g_input_queue.push(ch);
}

void emu_console_clear_queue() {
    std::lock_guard<std::mutex> lock(g_input_mutex);
    while (!g_input_queue.empty()) g_input_queue.pop();
}

bool emu_console_input_exhausted() {
    return false;  // GUI: more input can always arrive
}

bool emu_console_input_eof() {
    return false;  // GUI: no piped stdin
}

void emu_console_write_char(uint8_t ch) {
    ch &= 0x7F;  // Strip high bit
    std::lock_guard<std::mutex> lock(g_output_mutex);
    g_output_queue.push(ch);
}

bool emu_console_check_escape(char escape_char) {
    // Unreachable in this build, and deliberately a no-op rather than the
    // queue-pop it used to be. The only callers are in romwbw_emu.cc, which
    // CMakeLists does not compile, and there is no sim> debugger to escape to
    // on Android - so popping could only take a key away from the guest for
    // nothing. It took one, too: the old code compared the head of the queue
    // against escape_char without checking for the v1.36 contract's
    // escape_char == 0 ("reserve no key at all"), and the on-screen Ctrl
    // button reaches the whole '@'..'_' window, so Ctrl+@ / Ctrl+Space queue a
    // real NUL that a caller passing 0 would have eaten.
    // Every Ctrl-letter belongs to CP/M - see "Ctrl-A..Ctrl-Z Belong to the
    // Guest" in romwbw_emu/DOWNSTREAM.md. The web backend is the same shape.
    (void)escape_char;
    return false;
}

//=============================================================================
// Auxiliary Device I/O (Stubs for Android)
//=============================================================================

void emu_printer_set_file(const char* path) {
    (void)path;
    // Not implemented for Android
}

void emu_printer_out(uint8_t ch) {
    LOGD("Printer: %c", ch & 0x7F);
}

bool emu_printer_ready() {
    return true;
}

void emu_aux_set_input_file(const char* path) {
    (void)path;
}

void emu_aux_set_output_file(const char* path) {
    (void)path;
}

int emu_aux_in() {
    return 0x1A;  // ^Z (EOF)
}

void emu_aux_out(uint8_t ch) {
    (void)ch;
}

//=============================================================================
// Debug/Log Output Implementation
//=============================================================================

void emu_set_debug(bool enable) {
    g_debug_enabled = enable;
}

void emu_log(const char* fmt, ...) {
    if (!g_debug_enabled) return;
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    LOGD("%s", buf);
}

void emu_error(const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    LOGE("%s", buf);
}

[[noreturn]] void emu_fatal(const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    LOGE("*** FATAL ERROR ***");
    LOGE("%s", buf);
    LOGE("*** ABORTING ***");
    abort();
}

void emu_status(const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    LOGI("%s", buf);
}

//=============================================================================
// File I/O Implementation (Android uses assets/content providers)
//=============================================================================

bool emu_file_load(const std::string& path, std::vector<uint8_t>& data) {
    // For Android, file loading is handled via JNI from Java side
    // This stub returns false; actual loading happens through loadRom/loadDisk
    LOGE("emu_file_load not supported on Android: %s", path.c_str());
    data.clear();
    return false;
}

size_t emu_file_load_to_mem(const std::string& path, uint8_t* mem,
                            size_t mem_size, size_t offset) {
    (void)path;
    (void)mem;
    (void)mem_size;
    (void)offset;
    return 0;
}

bool emu_file_save(const std::string& path, const std::vector<uint8_t>& data) {
    (void)path;
    (void)data;
    return false;
}

bool emu_file_exists(const std::string& path) {
    (void)path;
    return false;
}

size_t emu_file_size(const std::string& path) {
    (void)path;
    return 0;
}

//=============================================================================
// Disk Image I/O (In-memory for Android)
//=============================================================================

// Android disks are in-memory inside the core (hbios->loadDisk from JNI).
// The emu_disk_* handle API is only exercised by the core's file-backed
// loadDiskFromFile path, which has no Android caller, so these are pure
// link-satisfying stubs (matching the emu_file_* stubs above). The previous
// implementation here grew its buffer on out-of-bounds writes, which would
// silently diverge from the v1.34 no-grow contract if ever wired up.

emu_disk_handle emu_disk_open(const std::string& path, const char* mode) {
    (void)path;
    (void)mode;
    return nullptr;
}

void emu_disk_close(emu_disk_handle handle) {
    (void)handle;
}

size_t emu_disk_read(emu_disk_handle handle, size_t offset,
                     uint8_t* buffer, size_t count) {
    (void)handle;
    (void)offset;
    (void)buffer;
    (void)count;
    return 0;
}

size_t emu_disk_write(emu_disk_handle handle, size_t offset,
                      const uint8_t* buffer, size_t count) {
    (void)handle;
    (void)offset;
    (void)buffer;
    (void)count;
    return 0;
}

void emu_disk_flush(emu_disk_handle handle) {
    (void)handle;
    // In-memory, nothing to flush
}

void emu_disk_flush_all() {
    // In-memory disks - persistence is handled by Java layer via saveDirtyDisks()
    // This is called on warm boot; Java polls dirty flags periodically and on pause/exit
}

size_t emu_disk_size(emu_disk_handle handle) {
    (void)handle;
    return 0;
}

//=============================================================================
// Time Implementation
//=============================================================================

void emu_get_time(emu_time* t) {
    time_t now = time(nullptr);
    struct tm* tm = localtime(&now);

    t->year = tm->tm_year + 1900;
    t->month = tm->tm_mon + 1;
    t->day = tm->tm_mday;
    t->hour = tm->tm_hour;
    t->minute = tm->tm_min;
    t->second = tm->tm_sec;
    t->weekday = tm->tm_wday;
}

//=============================================================================
// Random Numbers Implementation
//=============================================================================

unsigned int emu_random(unsigned int min, unsigned int max) {
    if (min >= max) return min;
    std::uniform_int_distribution<unsigned int> dist(min, max);
    return dist(g_rng);
}

//=============================================================================
// Video/Display Implementation
//=============================================================================

void emu_video_get_caps(emu_video_caps* caps) {
    caps->has_text_display = true;
    caps->has_pixel_display = false;
    caps->has_dsky = false;
    caps->text_rows = 25;
    caps->text_cols = 80;
    caps->pixel_width = 0;
    caps->pixel_height = 0;
}

void emu_video_clear() {
    g_cursor_row = 0;
    g_cursor_col = 0;
    // Clear is handled by VT100 escape in terminal view
    emu_console_write_char(0x1B);
    emu_console_write_char('[');
    emu_console_write_char('2');
    emu_console_write_char('J');
    emu_console_write_char(0x1B);
    emu_console_write_char('[');
    emu_console_write_char('H');
}

void emu_video_set_cursor(int row, int col) {
    g_cursor_row = row;
    g_cursor_col = col;
    // Emit VT100 cursor position sequence
    char buf[32];
    snprintf(buf, sizeof(buf), "\x1B[%d;%dH", row + 1, col + 1);
    for (char* p = buf; *p; p++) {
        emu_console_write_char(*p);
    }
}

void emu_video_get_cursor(int* row, int* col) {
    *row = g_cursor_row;
    *col = g_cursor_col;
}

void emu_video_write_char(uint8_t ch) {
    emu_console_write_char(ch);
    g_cursor_col++;
}

void emu_video_write_char_at(int row, int col, uint8_t ch) {
    emu_video_set_cursor(row, col);
    emu_video_write_char(ch);
}

void emu_video_scroll_up(int lines) {
    (void)lines;
    // Scrolling handled by terminal view
}

void emu_video_set_attr(uint8_t attr) {
    g_text_attr = attr;
}

uint8_t emu_video_get_attr() {
    return g_text_attr;
}

// Dazzler operations (stubs - not used on Android)
extern "C" {
uint8_t dazzler_port_in(uint8_t port) {
    (void)port;
    return 0;
}

void dazzler_port_out(uint8_t port, uint8_t value) {
    (void)port;
    (void)value;
}
}

// DSKY operations (stubs)
void emu_dsky_show_hex(uint8_t position, uint8_t value) {
    (void)position;
    (void)value;
}

void emu_dsky_show_segments(uint8_t position, uint8_t segments) {
    (void)position;
    (void)segments;
}

void emu_dsky_set_leds(uint8_t leds) {
    (void)leds;
}

void emu_dsky_beep(int duration_ms) {
    (void)duration_ms;
}

int emu_dsky_get_key() {
    return -1;
}

//=============================================================================
// Host File Transfer Implementation
//=============================================================================

// A copy of romwbw_emu/src/emu_io_common.cc's emu_host_path_basename(), which
// CMakeLists does not compile: that file also defines emu_file_*, emu_disk_*
// and emu_get_time, all of which have Android versions here, so adding it
// would collide on ten symbols. Keep this in step with the shared original -
// diverging is what the helper was written to stop (romwbw_emu
// docs/DOWNSTREAM_2026-08-25.md section 2: three ports had three different
// answers, and one of them was the iOS data-loss bug).
static std::string android_host_path_basename(const std::string& path,
                                              const char* fallback) {
    const std::string fb = (fallback && *fallback) ? fallback : "download.bin";

    // Ignore trailing separators: "a/b/" names b, not "".
    size_t end = path.size();
    while (end > 0 && (path[end - 1] == '/' || path[end - 1] == '\\')) end--;
    if (end == 0) return fb;

    size_t start = end;
    while (start > 0 && path[start - 1] != '/' && path[start - 1] != '\\') start--;
    std::string base = path.substr(start, end - start);

    // A Windows path can name a file with no separator at all ("C:OUT.TXT"),
    // and the drive letter is not part of the name. Strip exactly that prefix
    // and nothing else - a colon is a legal character in an Android filename,
    // so cutting at the last one would turn "my:file.txt" into "file.txt" here
    // while the CLI wrote "my:file.txt".
    if (base.size() >= 2 && base[1] == ':' &&
        ((base[0] >= 'A' && base[0] <= 'Z') || (base[0] >= 'a' && base[0] <= 'z'))) {
        base = base.substr(2);
    }

    // What is left has to be a name that cannot escape the directory it will
    // be joined to. "." and ".." are the two that can.
    if (base.empty() || base == "." || base == "..") return fb;
    return base;
}

// Reduce a guest path to the leaf this app will actually use, lowercased.
//
// The lowercasing is a convention, not a recovery: the CCP uppercases the
// whole command line before the emulator sees it, so the typed case is already
// gone. The CLI and the browser backends both lowercase the name they create,
// and a backend that picks differently makes the same W8 command produce
// differently-named files on different front ends - so this port matches them.
static std::string android_host_leaf(const char* filename, const char* fallback) {
    std::string leaf = android_host_path_basename(filename ? filename : "", fallback);
    for (char& c : leaf) {
        if (c >= 'A' && c <= 'Z') c = (char)(c - 'A' + 'a');
    }
    return leaf;
}

emu_host_file_state emu_host_file_get_state() {
    return g_host_file_state;
}

// What this front end guarantees about a guest-supplied path (HBF_HOST_CAPS,
// 0xE9). W8.COM refuses to send a host path unless EMU_HOST_CAP_SAFE_PATHS is
// set, and it believes the answer - which is why the core declares this
// function and deliberately does not define it, so the assertion is written by
// the code the assertion is about.
//
// This port may set it: a guest path never reaches the filesystem. Both open
// calls reduce it to a single leaf component right here, before anything else
// sees the string, so there is nothing left that could name a directory, walk
// out of one with "..", or be joined to the app's Exports/Imports folders and
// land outside them. The bit means "never used destructively", not "confined
// to one directory" - this backend happens to be both.
//
// The reduction is deliberately in the C++ rather than in Kotlin, where it
// used to live alone. The Kotlin check still runs and is still worth having,
// but a UI layer is the wrong place for the only copy: this function speaks
// for the shim, and a second consumer of emu_host_file_get_write_name() would
// not have inherited a guarantee that lived in the caller.
uint8_t emu_host_path_caps() {
    return EMU_HOST_CAP_SAFE_PATHS;
}

bool emu_host_file_open_read(const char* filename) {
    g_host_read_buffer.clear();
    g_host_read_pos = 0;
    // R8 can be given a host path (romwbw_emu src/r8.asm). This app has no
    // filesystem outside its sandbox to honour a directory with, so reduce to
    // the leaf and look for that in Imports rather than refusing outright -
    // which is what the shared helper exists for, and what every sandboxed
    // port is asked to do.
    g_host_read_filename = filename ? android_host_leaf(filename, "") : "";
    g_host_file_state = HOST_FILE_WAITING_READ;
    LOGI("Host file read requested: %s (from %s)", g_host_read_filename.c_str(),
         filename ? filename : "");
    return true;
}

bool emu_host_file_open_write(const char* filename) {
    g_host_write_buffer.clear();
    g_host_write_filename = android_host_leaf(filename, "download.bin");
    // The effective destination, for HBF_HOST_GETNAME. The Exports folder is
    // where the Kotlin layer will put it, so that is what the CP/M user needs
    // to be told - the folder is not visible from the guest and, on Android
    // 11+, the stock Files app does not show it either.
    g_host_write_destination = g_host_exports_dir.empty()
                                   ? g_host_write_filename
                                   : g_host_exports_dir + "/" + g_host_write_filename;
    g_host_file_state = HOST_FILE_WRITING;
    LOGI("Host file write opened: %s (from %s)", g_host_write_destination.c_str(),
         filename ? filename : "");
    return true;
}

int emu_host_file_read_byte() {
    if (g_host_file_state != HOST_FILE_READING) return -1;
    if (g_host_read_pos >= g_host_read_buffer.size()) return -1;
    return g_host_read_buffer[g_host_read_pos++];
}

bool emu_host_file_write_byte(uint8_t byte) {
    if (g_host_file_state != HOST_FILE_WRITING) return false;
    g_host_write_buffer.push_back(byte);
    return true;
}

void emu_host_file_close_read() {
    g_host_read_buffer.clear();
    g_host_read_pos = 0;
    g_host_file_state = HOST_FILE_IDLE;
}

bool emu_host_file_close_write() {
    // Set state to WRITE_READY so UI can poll and save the file
    if (g_host_file_state == HOST_FILE_WRITING && !g_host_write_buffer.empty()) {
        g_host_file_state = HOST_FILE_WRITE_READY;
        LOGI("Host file write ready: %s (%zu bytes)", g_host_write_filename.c_str(), g_host_write_buffer.size());
    } else {
        g_host_write_buffer.clear();
        g_host_write_filename.clear();
        g_host_write_destination.clear();
        g_host_file_state = HOST_FILE_IDLE;
    }
    // The buffer is handed to the OS asynchronously (UI thread saves it to
    // the Exports folder), so no failure is detectable here - return true
    // like the browser backend (v1.34 contract; false would make W8 report
    // a truncated export).
    return true;
}

// Called after UI has saved the write buffer
void emu_host_file_write_done() {
    g_host_write_buffer.clear();
    g_host_write_filename.clear();
    g_host_write_destination.clear();
    g_host_file_state = HOST_FILE_IDLE;
    LOGI("Host file write done");
}

// Called if user cancels file read
void emu_host_file_cancel() {
    g_host_file_state = HOST_FILE_IDLE;
    g_host_read_buffer.clear();
    g_host_read_pos = 0;
    g_host_write_buffer.clear();
    g_host_write_filename.clear();
    g_host_write_destination.clear();
    LOGI("Host file operation cancelled");
}

// Get the suggested read filename
const char* emu_host_file_get_read_name() {
    return g_host_read_filename.c_str();
}

void emu_host_file_provide_data(const uint8_t* data, size_t size) {
    g_host_read_buffer.assign(data, data + size);
    g_host_read_pos = 0;
    g_host_file_state = HOST_FILE_READING;
}

const uint8_t* emu_host_file_get_write_data() {
    return g_host_write_buffer.empty() ? nullptr : g_host_write_buffer.data();
}

size_t emu_host_file_get_write_size() {
    return g_host_write_buffer.size();
}

// The effective destination, not an echo of what the guest asked for - see the
// contract above the declaration in emu_io.h. W8 prints this string, so it has
// to name a file the user can go and find, and on Android that is the hardest
// part of a transfer: Exports lives under getExternalFilesDir(), which the
// stock Files app has hidden since Android 11.
//
// The core gates its own use of this on HOST_FILE_WRITING (HBF_HOST_GETNAME),
// so a guest never sees a stale answer. It deliberately stays readable through
// HOST_FILE_WRITE_READY, because that is the window in which the Kotlin layer
// collects the buffer and writes it.
const char* emu_host_file_get_write_name() {
    return g_host_write_destination.c_str();
}

// Called once from Kotlin at startup: the absolute path of the Exports folder.
void emu_host_set_exports_dir(const char* dir) {
    g_host_exports_dir = dir ? dir : "";
    while (!g_host_exports_dir.empty() && g_host_exports_dir.back() == '/') {
        g_host_exports_dir.pop_back();
    }
    LOGI("Host exports dir: %s", g_host_exports_dir.c_str());
}

//=============================================================================
// Internal Helpers
//=============================================================================

static void log_drive_map() {
    if (!g_emu || !g_emu->memory) return;
    uint8_t* rom = g_emu->memory->get_rom();
    if (!rom) return;
    LOGI("Drive map:");
    for (int i = 0; i < 16; i++) {
        uint8_t val = rom[0x120 + i];
        if (val != 0xFF) {
            LOGI("  Drive %c: unit=%d, slice=%d (0x%02X)",
                 'A' + i, val & 0x0F, (val >> 4) & 0x0F, val);
        }
    }
}

static void init_cpu_start_state() {
    if (!g_emu || !g_emu->cpu) return;
    g_emu->cpu->set_cpu_mode(qkz80::MODE_Z80);
    g_emu->cpu->regs.PC.set_pair16(0x0000);
    g_emu->cpu->regs.SP.set_pair16(0x0000);
}

static void register_reset_callback() {
    if (!g_emu || !g_emu->hbios) return;
    // Custom callback instead of emu_setup_reset_callback() because
    // emu_disk_flush_all() is a no-op on Android (in-memory disks;
    // persistence handled by Java layer via saveDirtyDisks()).
    g_emu->hbios->setResetCallback([](uint8_t reset_type) {
        LOGI("[SYSRESET] %s boot - restarting",
             reset_type == 0x01 ? "Warm" : "Cold");
        g_emu->memory->select_bank(0x00);
        g_emu->cpu->regs.PC.set_pair16(0x0000);
    });
}

//=============================================================================
// JNI Interface
//=============================================================================

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    LOGI("JNI_OnLoad called");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeInit(JNIEnv* env, jobject thiz) {
    LOGI("Initializing emulator engine");

    if (g_initialized) {
        LOGI("Already initialized");
        return;
    }

    emu_io_init();

    // Create emulator state (memory, cpu, hbios, delegate)
    g_emu = new EmulatorState();

    // Clear cached data
    g_cached_rom.clear();
    for (int i = 0; i < 16; i++) {
        g_cached_disks[i].clear();
        g_cached_disk_slices[i] = 0;
        g_cached_disk_manifest[i] = false;
    }

    g_callback_obj = env->NewGlobalRef(thiz);
    jclass clazz = env->GetObjectClass(thiz);
    g_on_output_method = env->GetMethodID(clazz, "onOutput", "([B)V");

    g_initialized = true;
    LOGI("Emulator engine initialized");
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeDestroy(JNIEnv* env, jobject thiz) {
    (void)thiz;
    LOGI("Destroying emulator engine");

    g_running = false;

    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }

    delete g_emu;
    g_emu = nullptr;

    // Clear cached data
    g_cached_rom.clear();
    for (int i = 0; i < 16; i++) {
        g_cached_disks[i].clear();
        g_cached_disk_slices[i] = 0;
        g_cached_disk_manifest[i] = false;
    }

    emu_io_cleanup();

    g_initialized = false;
    LOGI("Emulator engine destroyed");
}

JNIEXPORT jboolean JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeLoadRom(JNIEnv* env, jobject thiz,
                                                       jbyteArray romData) {
    (void)thiz;
    if (!g_initialized || !g_emu) {
        LOGE("Engine not initialized");
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(romData);
    jbyte* data = env->GetByteArrayElements(romData, nullptr);

    LOGI("Loading ROM, size: %d bytes", len);

    // Cache ROM data for reboot
    g_cached_rom.assign(reinterpret_cast<uint8_t*>(data),
                        reinterpret_cast<uint8_t*>(data) + len);

    // Ask the core why a ROM is unusable, while the Java array is still
    // mapped, so the log names the real problem: a corrupt HBIOS
    // configuration block, or a ROM built for a RomWBW release other than
    // the pinned one (src/romwbw_pin.h). Before the core validated this, a
    // bad ROM was accepted and the emulator started a CPU that produced no
    // output at all. emu_load_rom_from_buffer runs the same check and
    // refuses too; this only recovers the reason for the log.
    const char* rom_problem = emu_validate_rom_hcb(
        reinterpret_cast<const uint8_t*>(data), static_cast<size_t>(len));
    std::string rom_error = rom_problem ? rom_problem : std::string();

    bool success = emu_load_rom_from_buffer(g_emu->memory,
                                            reinterpret_cast<uint8_t*>(data),
                                            static_cast<size_t>(len));

    env->ReleaseByteArrayElements(romData, data, JNI_ABORT);

    if (success) {
        LOGI("ROM loaded successfully");
    } else if (!rom_error.empty()) {
        LOGE("Failed to load ROM: %s", rom_error.c_str());
    } else {
        LOGE("Failed to load ROM");
    }

    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeLoadDisk(JNIEnv* env, jobject thiz,
                                                        jint unit, jbyteArray diskData) {
    (void)thiz;
    if (!g_initialized || !g_emu) {
        LOGE("Engine not initialized");
        return JNI_FALSE;
    }

    if (unit < 0 || unit >= 16) {
        LOGE("Invalid disk unit: %d", unit);
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(diskData);
    jbyte* data = env->GetByteArrayElements(diskData, nullptr);

    LOGI("Loading disk unit %d, size: %d bytes", unit, len);

    // Hot-patch the miscompiled w8.com tolower: um80 0.3.42 assembled
    // "add a,'a'-'A'" as "add a,0", so W8 exported UPPERCASE filenames.
    // Every hd1k image built before 2026-07-21 (including all ioscpm v1.4.5
    // catalog assets and any persisted copies of them) carries it, and the
    // broken and fixed builds differ only in this one byte. Images with the
    // fixed w8.com don't match the signature, so this is a no-op for them.
    // (On ART, GetByteArrayElements usually returns a direct pointer for
    // large arrays, so the caller's ByteArray may be patched in place too -
    // don't rely on it still matching the on-disk file.)
    static const uint8_t W8_BROKEN[8] =
        {0xfe, 0x41, 0xd8, 0xfe, 0x5b, 0xd0, 0xc6, 0x00};
    uint8_t* bytes = reinterpret_cast<uint8_t*>(data);
    for (jsize i = 0; len >= 8 && i <= len - 8; i++) {
        if (bytes[i] == 0xfe && memcmp(bytes + i, W8_BROKEN, 8) == 0) {
            bytes[i + 7] = 0x20;  // add a,0 -> add a,'a'-'A'
            LOGI("Patched broken w8.com tolower at offset %d in disk %d",
                 (int)i, unit);
        }
    }

    // Cache disk data for reboot
    g_cached_disks[unit].assign(reinterpret_cast<uint8_t*>(data),
                                 reinterpret_cast<uint8_t*>(data) + len);

    bool success = g_emu->hbios->loadDisk(
        static_cast<uint8_t>(unit),
        reinterpret_cast<uint8_t*>(data),
        static_cast<size_t>(len)
    );

    env->ReleaseByteArrayElements(diskData, data, JNI_ABORT);

    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeCompleteInit(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_emu) {
        LOGE("Engine not initialized");
        return;
    }

    LOGI("Completing emulator initialization");

    // Build disk_slices array from HBIOSDispatch's stored slice counts
    // This is needed for emu_populate_drive_map to assign drive letters (A:, B:, etc.)
    int disk_slices[16];
    for (int i = 0; i < 16; i++) {
        disk_slices[i] = g_emu->hbios->getDisk(i).max_slices;
        // Cache slice counts for reboot
        g_cached_disk_slices[i] = disk_slices[i];
        if (g_emu->hbios->isDiskLoaded(i)) {
            LOGI("Disk %d: loaded=true, max_slices=%d", i, disk_slices[i]);
        }
    }

    emu_complete_init(g_emu->memory, g_emu->hbios, disk_slices);

    register_reset_callback();
    log_drive_map();
    init_cpu_start_state();

    LOGI("Emulator ready to run");
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeRun(JNIEnv* env, jobject thiz,
                                                   jint instructionCount) {
    (void)thiz;
    if (!g_initialized || !g_emu) {
        LOGE("nativeRun: not initialized");
        return;
    }

    // Check if we're blocked waiting for input (CIOIN/VDAKRD called with no data)
    if (g_emu->hbios->isWaitingForInput()) {
        if (emu_console_has_input()) {
            // Input arrived - clear waiting flag so CPU will process it
            g_emu->hbios->clearWaitingForInput();
        } else {
            // No input available - skip execution entirely (power saving)
            // Just flush any pending output below and return
            goto flush_output;
        }
    }

    g_running = true;

    for (int i = 0; i < instructionCount && g_running; i++) {
        g_emu->cpu->execute();

        // Check if CPU is now waiting for input
        if (g_emu->hbios->isWaitingForInput()) {
            break;  // Stop executing until input is provided
        }
        // Pause after a completed W8 export until the UI drains it: a queued
        // second W8 in the same batch would otherwise clobber the write
        // buffer before checkHostFileState ever sees it.
        if (emu_host_file_get_state() == HOST_FILE_WRITE_READY) {
            break;
        }
        if (g_emu->hbios->getState() == HBIOS_HALTED) {
            g_running = false;
            break;
        }
    }

    // Debug: log PC after batch
    if (++g_run_count <= 5) {
        LOGI("nativeRun #%d: PC=0x%04X after %d instructions",
             g_run_count, g_emu->cpu->regs.PC.get_pair16(), instructionCount);
    }

flush_output:
    // Flush output queue (from direct port 0x01 writes)
    std::vector<uint8_t> output;
    {
        std::lock_guard<std::mutex> lock(g_output_mutex);
        while (!g_output_queue.empty()) {
            output.push_back(g_output_queue.front());
            g_output_queue.pop();
        }
    }

    // Also flush HBIOS output buffer (from CIOOUT calls via port 0xEF dispatch)
    if (g_emu->hbios) {
        std::vector<uint8_t> hbios_output = g_emu->hbios->getOutputChars();
        if (!hbios_output.empty() && g_run_count <= 5) {
            LOGI("nativeRun: got %zu chars from HBIOS buffer", hbios_output.size());
        }
        output.insert(output.end(), hbios_output.begin(), hbios_output.end());
    }

    if (!output.empty() && g_output_log_count++ < 3) {
        LOGI("nativeRun: sending %zu chars to Java", output.size());
    }

    if (!output.empty() && g_callback_obj && g_on_output_method) {
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(output.size()));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(output.size()),
                               reinterpret_cast<jbyte*>(output.data()));
        env->CallVoidMethod(g_callback_obj, g_on_output_method, arr);
        env->DeleteLocalRef(arr);
    }
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeStop(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    g_running = false;
}

JNIEXPORT jboolean JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeIsWaitingForInput(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_emu) {
        return JNI_FALSE;
    }
    return g_emu->hbios->isWaitingForInput() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeQueueInput(JNIEnv* env, jobject thiz,
                                                          jint ch) {
    (void)env;
    (void)thiz;
    emu_console_queue_char(ch);
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeQueueInputString(JNIEnv* env, jobject thiz,
                                                                jstring str) {
    (void)thiz;
    const char* cstr = env->GetStringUTFChars(str, nullptr);
    for (size_t i = 0; cstr[i] != '\0'; i++) {
        emu_console_queue_char(static_cast<int>(cstr[i]));
    }
    env->ReleaseStringUTFChars(str, cstr);
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeReset(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (!g_initialized) {
        return;
    }

    LOGI("Emulator reset: destroying and recreating state");

    g_running = false;

    // Clear console queues
    emu_console_clear_queue();
    {
        std::lock_guard<std::mutex> lock(g_output_mutex);
        while (!g_output_queue.empty()) g_output_queue.pop();
    }

    // Refresh the reboot cache from the live disk images before tearing the
    // state down. The cache otherwise holds the bytes from the last
    // nativeLoadDisk (app start), so a reboot would revert every disk to
    // that stale snapshot - and the next dirty save would then overwrite
    // the user's newer persisted copy with it.
    if (g_emu && g_emu->hbios) {
        for (int i = 0; i < 16; i++) {
            if (g_emu->hbios->isDiskLoaded(i)) {
                const uint8_t* data = g_emu->hbios->getDiskData(i);
                size_t size = g_emu->hbios->getDiskDataSize(i);
                if (data && size > 0) {
                    g_cached_disks[i].assign(data, data + size);
                }
            }
        }
    }

    // Destroy old emulator state
    delete g_emu;
    g_emu = nullptr;

    // Create fresh emulator state
    g_emu = new EmulatorState();

    // Reload ROM from cache
    if (!g_cached_rom.empty()) {
        LOGI("Reloading ROM from cache (%zu bytes)", g_cached_rom.size());
        // The core can refuse a ROM now, so do not assume this succeeded:
        // a silent failure here leaves a running CPU with no ROM behind it,
        // which looks like a hang rather than an error.
        if (!emu_load_rom_from_buffer(g_emu->memory, g_cached_rom.data(),
                                      g_cached_rom.size())) {
            LOGE("Reboot failed: the cached ROM was rejected by the core");
        }
    }

    // Reload disks from cache
    for (int i = 0; i < 16; i++) {
        if (!g_cached_disks[i].empty()) {
            LOGI("Reloading disk %d from cache (%zu bytes)", i, g_cached_disks[i].size());
            g_emu->hbios->loadDisk(i, g_cached_disks[i].data(), g_cached_disks[i].size());
            if (g_cached_disk_slices[i] > 0) {
                g_emu->hbios->setDiskSliceCount(i, g_cached_disk_slices[i]);
            }
            // Restore manifest flag (for downloaded disk write warning)
            if (g_cached_disk_manifest[i]) {
                g_emu->hbios->setDiskIsManifest(i, true);
            }
        }
    }

    // Complete initialization (builds drive map, sets up HCB, etc.)
    emu_complete_init(g_emu->memory, g_emu->hbios, g_cached_disk_slices);
    register_reset_callback();
    log_drive_map();
    init_cpu_start_state();

    // Reset debug counters so post-reboot logging works
    g_run_count = 0;
    g_output_log_count = 0;

    LOGI("Emulator reset complete (fresh state)");
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeSetDiskSliceCount(JNIEnv* env, jobject thiz,
                                                                  jint unit, jint slices) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_emu) {
        return;
    }
    g_emu->hbios->setDiskSliceCount(unit, slices);
    // Also cache for reboot
    if (unit >= 0 && unit < 16) {
        g_cached_disk_slices[unit] = slices;
    }
    LOGI("Set disk %d slice count to %d", unit, slices);
}

JNIEXPORT jboolean JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeIsDiskLoaded(JNIEnv* env, jobject thiz,
                                                            jint unit) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_emu) {
        return JNI_FALSE;
    }
    return g_emu->hbios->isDiskLoaded(unit) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeCloseDisk(JNIEnv* env, jobject thiz,
                                                         jint unit) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_emu) return;
    if (unit < 0 || unit >= 16) return;
    g_emu->hbios->closeDisk(unit);
    // Clear the reboot cache too, or nativeReset would resurrect the disk.
    g_cached_disks[unit].clear();
    g_cached_disks[unit].shrink_to_fit();
    g_cached_disk_slices[unit] = 0;
    g_cached_disk_manifest[unit] = false;
    LOGI("Closed disk unit %d", unit);
}

//=============================================================================
// Host File Transfer JNI Interface
//=============================================================================

JNIEXPORT jint JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeGetHostFileState(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    return static_cast<jint>(emu_host_file_get_state());
}

JNIEXPORT jstring JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeGetHostFileReadName(JNIEnv* env, jobject thiz) {
    (void)thiz;
    const char* name = emu_host_file_get_read_name();
    return env->NewStringUTF(name ? name : "");
}

JNIEXPORT jstring JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeGetHostFileWriteName(JNIEnv* env, jobject thiz) {
    (void)thiz;
    const char* name = emu_host_file_get_write_name();
    return env->NewStringUTF(name ? name : "");
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeSetHostExportsDir(JNIEnv* env, jobject thiz,
                                                              jstring dir) {
    (void)thiz;
    if (dir == nullptr) {
        emu_host_set_exports_dir(nullptr);
        return;
    }
    const char* chars = env->GetStringUTFChars(dir, nullptr);
    emu_host_set_exports_dir(chars);
    env->ReleaseStringUTFChars(dir, chars);
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeProvideHostFileData(JNIEnv* env, jobject thiz,
                                                                    jbyteArray data) {
    (void)thiz;
    if (data == nullptr) {
        // User cancelled - cancel the read
        emu_host_file_cancel();
        return;
    }

    jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);

    emu_host_file_provide_data(reinterpret_cast<uint8_t*>(bytes), static_cast<size_t>(len));

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

JNIEXPORT jbyteArray JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeGetHostFileWriteData(JNIEnv* env, jobject thiz) {
    (void)thiz;
    const uint8_t* data = emu_host_file_get_write_data();
    size_t size = emu_host_file_get_write_size();

    if (data == nullptr || size == 0) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(size),
                            reinterpret_cast<const jbyte*>(data));
    return result;
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeHostFileWriteDone(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    emu_host_file_write_done();
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeHostFileCancel(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    emu_host_file_cancel();
}

//=============================================================================
// NVRAM Boot Configuration JNI Interface (String-based API)
//=============================================================================

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeSetNvramSetting(JNIEnv* env, jobject thiz,
                                                               jstring setting) {
    (void)thiz;
    if (!g_initialized || !g_emu) {
        return;
    }
    const char* str = env->GetStringUTFChars(setting, nullptr);
    g_emu->hbios->setNvramSetting(str ? str : "");
    LOGI("Set NVRAM setting: %s", str ? str : "(empty)");
    env->ReleaseStringUTFChars(setting, str);
}

JNIEXPORT jstring JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeGetNvramSetting(JNIEnv* env, jobject thiz) {
    (void)thiz;
    if (!g_initialized || !g_emu) {
        return env->NewStringUTF("");
    }
    std::string setting = g_emu->hbios->getNvramSetting();
    return env->NewStringUTF(setting.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeHasNvramChange(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_emu) {
        return JNI_FALSE;
    }
    return g_emu->hbios->hasNvramChange() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeIsNvramInitialized(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_emu) {
        return JNI_FALSE;
    }
    return g_emu->hbios->isNvramInitialized() ? JNI_TRUE : JNI_FALSE;
}

//=============================================================================
// Manifest Disk Write Warning JNI Interface
//=============================================================================

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeSetDiskIsManifest(JNIEnv* env, jobject thiz,
                                                                  jint unit, jboolean isManifest) {
    (void)env;
    (void)thiz;
    if (unit < 0 || unit >= 16) {
        return;
    }
    // Cache the manifest flag for restoration after reset
    g_cached_disk_manifest[unit] = (isManifest == JNI_TRUE);
    if (g_initialized && g_emu) {
        g_emu->hbios->setDiskIsManifest(unit, isManifest == JNI_TRUE);
    }
    LOGI("Set disk %d isManifest=%s", unit, isManifest ? "true" : "false");
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeSetDiskWarningSuppressed(JNIEnv* env, jobject thiz,
                                                                         jint unit, jboolean suppressed) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_emu) {
        return;
    }
    g_emu->hbios->setDiskWarningSuppressed(unit, suppressed == JNI_TRUE);
    LOGI("Set disk %d warningSuppressed=%s", unit, suppressed ? "true" : "false");
}

JNIEXPORT jboolean JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeCheckManifestWriteWarning(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_emu) {
        return JNI_FALSE;
    }
    return g_emu->hbios->pollManifestWriteWarning() ? JNI_TRUE : JNI_FALSE;
}

//=============================================================================
// Disk Persistence JNI Interface
//=============================================================================

JNIEXPORT jboolean JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeIsDiskDirty(JNIEnv* env, jobject thiz, jint unit) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_emu) {
        return JNI_FALSE;
    }
    return g_emu->hbios->isDiskDirty(unit) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeClearDiskDirty(JNIEnv* env, jobject thiz, jint unit) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_emu) {
        return;
    }
    g_emu->hbios->clearDiskDirty(unit);
    LOGI("Cleared dirty flag for disk %d", unit);
}

JNIEXPORT jbyteArray JNICALL
Java_com_awohl_cpmdroid_EmulatorEngine_nativeGetDiskData(JNIEnv* env, jobject thiz, jint unit) {
    (void)thiz;
    if (!g_initialized || !g_emu) {
        return nullptr;
    }

    const uint8_t* data = g_emu->hbios->getDiskData(unit);
    size_t size = g_emu->hbios->getDiskDataSize(unit);

    if (data == nullptr || size == 0) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    if (result == nullptr) {
        LOGE("Failed to allocate byte array for disk %d data (%zu bytes)", unit, size);
        return nullptr;
    }

    env->SetByteArrayRegion(result, 0, static_cast<jsize>(size),
                            reinterpret_cast<const jbyte*>(data));
    LOGI("Retrieved disk %d data (%zu bytes)", unit, size);
    return result;
}

} // extern "C"
