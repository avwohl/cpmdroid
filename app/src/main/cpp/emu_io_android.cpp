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
#include "emu_init.h"
#include "romwbw_mem.h"

#define LOG_TAG "CPMDroid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

//=============================================================================
// Global Emulator State
//=============================================================================

static RomWBWMem* g_memory = nullptr;
static HBIOSCpu* g_cpu = nullptr;
static bool g_running = false;
static bool g_initialized = false;

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

// Ctrl+C tracking
static int g_consecutive_ctrl_c = 0;

// Random number generator
static std::mt19937 g_rng(std::random_device{}());

// Video state
static int g_cursor_row = 0;
static int g_cursor_col = 0;
static uint8_t g_text_attr = 0x07;

// Host file transfer state
static emu_host_file_state g_host_file_state = HOST_FILE_IDLE;
static std::vector<uint8_t> g_host_read_buffer;
static size_t g_host_read_pos = 0;
static std::vector<uint8_t> g_host_write_buffer;
static std::string g_host_write_filename;

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

void emu_console_write_char(uint8_t ch) {
    ch &= 0x7F;  // Strip high bit
    std::lock_guard<std::mutex> lock(g_output_mutex);
    g_output_queue.push(ch);
}

bool emu_console_check_escape(char escape_char) {
    std::lock_guard<std::mutex> lock(g_input_mutex);
    if (!g_input_queue.empty() && g_input_queue.front() == escape_char) {
        g_input_queue.pop();
        return true;
    }
    return false;
}

bool emu_console_check_ctrl_c_exit(int ch, int count) {
    if (ch == 0x03) {
        g_consecutive_ctrl_c++;
        if (g_consecutive_ctrl_c >= count) {
            LOGE("Exit: consecutive ^C received");
            return true;
        }
    } else {
        g_consecutive_ctrl_c = 0;
    }
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

// Disk images are loaded entirely into memory on Android
struct disk_mem {
    std::vector<uint8_t> data;
    bool readonly;
};

emu_disk_handle emu_disk_open(const std::string& path, const char* mode) {
    (void)path;
    (void)mode;
    // Disk images are loaded via JNI, not by path
    return nullptr;
}

void emu_disk_close(emu_disk_handle handle) {
    if (!handle) return;
    disk_mem* disk = static_cast<disk_mem*>(handle);
    delete disk;
}

size_t emu_disk_read(emu_disk_handle handle, size_t offset,
                     uint8_t* buffer, size_t count) {
    if (!handle) return 0;
    disk_mem* disk = static_cast<disk_mem*>(handle);

    if (offset >= disk->data.size()) return 0;
    size_t avail = disk->data.size() - offset;
    if (count > avail) count = avail;

    memcpy(buffer, disk->data.data() + offset, count);
    return count;
}

size_t emu_disk_write(emu_disk_handle handle, size_t offset,
                      const uint8_t* buffer, size_t count) {
    if (!handle) return 0;
    disk_mem* disk = static_cast<disk_mem*>(handle);
    if (disk->readonly) return 0;

    size_t needed = offset + count;
    if (needed > disk->data.size()) {
        disk->data.resize(needed);
    }

    memcpy(disk->data.data() + offset, buffer, count);
    return count;
}

void emu_disk_flush(emu_disk_handle handle) {
    (void)handle;
    // In-memory, nothing to flush
}

size_t emu_disk_size(emu_disk_handle handle) {
    if (!handle) return 0;
    disk_mem* disk = static_cast<disk_mem*>(handle);
    return disk->data.size();
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

emu_host_file_state emu_host_file_get_state() {
    return g_host_file_state;
}

bool emu_host_file_open_read(const char* filename) {
    g_host_read_buffer.clear();
    g_host_read_pos = 0;
    g_host_file_state = HOST_FILE_WAITING_READ;
    LOGI("Host file read requested: %s", filename);
    return true;
}

bool emu_host_file_open_write(const char* filename) {
    g_host_write_buffer.clear();
    g_host_write_filename = filename ? filename : "download.bin";
    g_host_file_state = HOST_FILE_WRITING;
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

void emu_host_file_close_write() {
    // On Android, we'd need to trigger a save dialog via JNI
    g_host_write_buffer.clear();
    g_host_write_filename.clear();
    g_host_file_state = HOST_FILE_IDLE;
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

const char* emu_host_file_get_write_name() {
    return g_host_write_filename.c_str();
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
Java_com_romwbw_cpmdroid_EmulatorEngine_nativeInit(JNIEnv* env, jobject thiz) {
    LOGI("Initializing emulator engine");

    if (g_initialized) {
        LOGI("Already initialized");
        return;
    }

    emu_io_init();

    g_memory = new RomWBWMem();
    g_cpu = new HBIOSCpu(*g_memory);

    g_callback_obj = env->NewGlobalRef(thiz);
    jclass clazz = env->GetObjectClass(thiz);
    g_on_output_method = env->GetMethodID(clazz, "onOutput", "([B)V");

    g_initialized = true;
    LOGI("Emulator engine initialized");
}

JNIEXPORT void JNICALL
Java_com_romwbw_cpmdroid_EmulatorEngine_nativeDestroy(JNIEnv* env, jobject thiz) {
    (void)thiz;
    LOGI("Destroying emulator engine");

    g_running = false;

    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }

    delete g_cpu;
    g_cpu = nullptr;

    delete g_memory;
    g_memory = nullptr;

    emu_io_cleanup();

    g_initialized = false;
    LOGI("Emulator engine destroyed");
}

JNIEXPORT jboolean JNICALL
Java_com_romwbw_cpmdroid_EmulatorEngine_nativeLoadRom(JNIEnv* env, jobject thiz,
                                                       jbyteArray romData) {
    (void)thiz;
    if (!g_initialized || !g_memory) {
        LOGE("Engine not initialized");
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(romData);
    jbyte* data = env->GetByteArrayElements(romData, nullptr);

    LOGI("Loading ROM, size: %d bytes", len);

    bool success = emu_load_rom_from_buffer(g_memory,
                                            reinterpret_cast<uint8_t*>(data),
                                            static_cast<size_t>(len));

    env->ReleaseByteArrayElements(romData, data, JNI_ABORT);

    if (success) {
        LOGI("ROM loaded successfully");
    } else {
        LOGE("Failed to load ROM");
    }

    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_romwbw_cpmdroid_EmulatorEngine_nativeLoadDisk(JNIEnv* env, jobject thiz,
                                                        jint unit, jbyteArray diskData) {
    (void)thiz;
    if (!g_initialized || !g_cpu) {
        LOGE("Engine not initialized");
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(diskData);
    jbyte* data = env->GetByteArrayElements(diskData, nullptr);

    LOGI("Loading disk unit %d, size: %d bytes", unit, len);

    bool success = g_cpu->getHBIOSDispatch().loadDisk(
        static_cast<uint8_t>(unit),
        reinterpret_cast<uint8_t*>(data),
        static_cast<size_t>(len)
    );

    env->ReleaseByteArrayElements(diskData, data, JNI_ABORT);

    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_romwbw_cpmdroid_EmulatorEngine_nativeCompleteInit(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_memory || !g_cpu) {
        LOGE("Engine not initialized");
        return;
    }

    LOGI("Completing emulator initialization");
    emu_complete_init(g_memory, &g_cpu->getHBIOSDispatch(), nullptr);

    g_cpu->set_cpu_mode(qkz80::MODE_Z80);
    g_cpu->regs.PC.set_pair16(0x0000);
    g_cpu->regs.SP.set_pair16(0x0000);

    LOGI("Emulator ready to run");
}

JNIEXPORT void JNICALL
Java_com_romwbw_cpmdroid_EmulatorEngine_nativeRun(JNIEnv* env, jobject thiz,
                                                   jint instructionCount) {
    (void)thiz;
    if (!g_initialized || !g_cpu) {
        return;
    }

    g_running = true;

    for (int i = 0; i < instructionCount && g_running; i++) {
        g_cpu->execute();
    }

    // Flush output queue
    std::vector<uint8_t> output;
    {
        std::lock_guard<std::mutex> lock(g_output_mutex);
        while (!g_output_queue.empty()) {
            output.push_back(g_output_queue.front());
            g_output_queue.pop();
        }
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
Java_com_romwbw_cpmdroid_EmulatorEngine_nativeStop(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    g_running = false;
}

JNIEXPORT void JNICALL
Java_com_romwbw_cpmdroid_EmulatorEngine_nativeQueueInput(JNIEnv* env, jobject thiz,
                                                          jint ch) {
    (void)env;
    (void)thiz;
    emu_console_queue_char(ch);
}

JNIEXPORT void JNICALL
Java_com_romwbw_cpmdroid_EmulatorEngine_nativeQueueInputString(JNIEnv* env, jobject thiz,
                                                                jstring str) {
    (void)thiz;
    const char* cstr = env->GetStringUTFChars(str, nullptr);
    for (size_t i = 0; cstr[i] != '\0'; i++) {
        emu_console_queue_char(static_cast<int>(cstr[i]));
    }
    env->ReleaseStringUTFChars(str, cstr);
}

JNIEXPORT void JNICALL
Java_com_romwbw_cpmdroid_EmulatorEngine_nativeReset(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (!g_initialized || !g_cpu) {
        return;
    }

    g_running = false;

    emu_console_clear_queue();
    {
        std::lock_guard<std::mutex> lock(g_output_mutex);
        while (!g_output_queue.empty()) g_output_queue.pop();
    }

    g_cpu->regs.PC.set_pair16(0x0000);
    g_cpu->regs.SP.set_pair16(0x0000);

    LOGI("Emulator reset");
}

} // extern "C"
