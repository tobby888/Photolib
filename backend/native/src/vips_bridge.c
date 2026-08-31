#include "vips_bridge.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <sys/stat.h>
#include <windows.h>
#include <wchar.h>
#else
#include <pthread.h>
#include <sys/stat.h>
#endif

/*
 * Keep the Zig-facing API fixed-width and non-variadic. libvips exposes most
 * operations through varargs; containing those calls in this C bridge avoids
 * relying on Zig's C-varargs ABI and gives us one place to pin every option.
 * The declarations below are the stable libvips C ABI used by 8.18.3.
 */
typedef struct _VipsImage VipsImage;

extern int vips_init(const char *argv0);
extern void vips_concurrency_set(int concurrency);
extern void vips_cache_set_max(int maximum);
extern void vips_cache_set_max_mem(size_t maximum_memory);
extern void vips_cache_set_max_files(int maximum_files);
extern const char *vips_error_buffer(void);
extern void vips_error_clear(void);
extern VipsImage *vips_image_new_from_file(const char *name, ...);
extern int vips_image_get_width(const VipsImage *image);
extern int vips_image_get_height(const VipsImage *image);
extern int vips_image_get_bands(const VipsImage *image);
extern int vips_image_get_orientation(VipsImage *image);
extern size_t vips_image_get_typeof(const VipsImage *image, const char *name);
extern int vips_image_get_int(const VipsImage *image, const char *name, int *output);
extern int vips_thumbnail(const char *filename, VipsImage **output,
                          int width, ...);
extern int vips_jpegsave(VipsImage *input, const char *filename, ...);
extern int vips_pngsave(VipsImage *input, const char *filename, ...);
extern int vips_webpsave(VipsImage *input, const char *filename, ...);
extern void g_object_unref(void *object);

enum {
    PL_FORMAT_JPEG = 1,
    PL_FORMAT_PNG = 2,
    PL_FORMAT_WEBP = 3,
    PL_OPERATION_COMPRESS = 1,
    PL_OPERATION_THUMBNAIL = 2,
    PL_VIPS_ACCESS_SEQUENTIAL = 1,
    PL_VIPS_SIZE_DOWN = 2,
    PL_VIPS_FAIL_ON_ERROR = 2,
    PL_VIPS_FOREIGN_SUBSAMPLE_ON = 1,
    PL_JPEG_MINIMUM_QUALITY = 82,
    PL_JPEG_MAXIMUM_QUALITY = 97,
    PL_MINIMUM_DIMENSION = 320,
    PL_MAXIMUM_RESIZE_ATTEMPTS = 24,
    PL_MAXIMUM_INPUT_MIB = 100
};

static int vips_initialized = 0;

#ifdef _WIN32
static SRWLOCK vips_mutex = SRWLOCK_INIT;

static void lock_vips(void) {
    AcquireSRWLockExclusive(&vips_mutex);
}

static void unlock_vips(void) {
    ReleaseSRWLockExclusive(&vips_mutex);
}
#else
static pthread_mutex_t vips_mutex = PTHREAD_MUTEX_INITIALIZER;

static void lock_vips(void) {
    pthread_mutex_lock(&vips_mutex);
}

static void unlock_vips(void) {
    pthread_mutex_unlock(&vips_mutex);
}
#endif

static void copy_error(char *output, size_t capacity, const char *message) {
    if (output == NULL || capacity == 0) return;
    if (message == NULL || message[0] == '\0') message = "unknown libvips error";
    size_t length = strlen(message);
    if (length >= capacity) length = capacity - 1;
    memcpy(output, message, length);
    output[length] = '\0';
}

static int fail_with_vips_error(char *output, size_t capacity) {
    copy_error(output, capacity, vips_error_buffer());
    vips_error_clear();
    return 0;
}

static int ensure_vips(char *error_message, size_t error_capacity) {
    if (!vips_initialized) {
        if (vips_init("photolib-image") != 0)
            return fail_with_vips_error(error_message, error_capacity);
        vips_concurrency_set(1);
        vips_cache_set_max(0);
        vips_cache_set_max_mem(0);
        vips_cache_set_max_files(0);
        vips_initialized = 1;
    }
    return 1;
}

static int dimensions_are_safe(int width, int height,
                               int maximum_dimension,
                               uint64_t maximum_pixels) {
    if (width <= 0 || height <= 0 || maximum_dimension <= 0 ||
        width > maximum_dimension || height > maximum_dimension)
        return 0;
    return (uint64_t)width * (uint64_t)height <= maximum_pixels;
}

static int image_metadata_is_true(VipsImage *image, const char *name) {
    if (vips_image_get_typeof(image, name) == 0) return 0;
    int value = 0;
    if (vips_image_get_int(image, name, &value) != 0) {
        vips_error_clear();
        return 0;
    }
    return value != 0;
}

static int file_size_utf8(const char *path, uint64_t *length) {
#ifdef _WIN32
    int wide_length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
                                          path, -1, NULL, 0);
    if (wide_length <= 0) return 0;
    wchar_t *wide_path = (wchar_t *)malloc((size_t)wide_length * sizeof(wchar_t));
    if (wide_path == NULL) return 0;
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1,
                            wide_path, wide_length) <= 0) {
        free(wide_path);
        return 0;
    }
    struct _stat64 status;
    int result = _wstat64(wide_path, &status);
    free(wide_path);
    if (result != 0 || status.st_size <= 0) return 0;
    *length = (uint64_t)status.st_size;
#else
    struct stat status;
    if (stat(path, &status) != 0 || status.st_size <= 0) return 0;
    *length = (uint64_t)status.st_size;
#endif
    return 1;
}

static void remove_utf8(const char *path) {
#ifdef _WIN32
    int wide_length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
                                          path, -1, NULL, 0);
    if (wide_length <= 0) return;
    wchar_t *wide_path = (wchar_t *)malloc((size_t)wide_length * sizeof(wchar_t));
    if (wide_path == NULL) return;
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1,
                            wide_path, wide_length) > 0)
        _wremove(wide_path);
    free(wide_path);
#else
    remove(path);
#endif
}

static int dimensions_file_unlocked(const char *input_path,
                                    int format,
                                    int maximum_dimension,
                                    uint64_t maximum_pixels,
                                    int streaming_threshold_dimension,
                                    uint64_t streaming_threshold_pixels,
                                    uint64_t streaming_threshold_decoded_bytes,
                                    int *width,
                                    int *height,
                                    int *channels,
                                    int *orientation,
                                    char *error_message,
                                    size_t error_capacity) {
    if (input_path == NULL || width == NULL || height == NULL ||
        channels == NULL || orientation == NULL) {
        copy_error(error_message, error_capacity, "invalid libvips dimension arguments");
        return 0;
    }
    if (format != PL_FORMAT_JPEG && format != PL_FORMAT_PNG) {
        copy_error(error_message, error_capacity, "unsupported input format");
        return 0;
    }
    uint64_t input_length = 0;
    if (!file_size_utf8(input_path, &input_length) ||
        input_length > (uint64_t)PL_MAXIMUM_INPUT_MIB * 1024 * 1024) {
        copy_error(error_message, error_capacity,
                   "输入图片为空或超过 100 MiB 原生安全上限");
        return 0;
    }
    if (!ensure_vips(error_message, error_capacity)) return 0;

    vips_error_clear();
    VipsImage *image = vips_image_new_from_file(
        input_path,
        "access", PL_VIPS_ACCESS_SEQUENTIAL,
        "fail_on", PL_VIPS_FAIL_ON_ERROR,
        NULL);
    if (image == NULL) return fail_with_vips_error(error_message, error_capacity);

    int image_width = vips_image_get_width(image);
    int image_height = vips_image_get_height(image);
    int image_channels = vips_image_get_bands(image);
    int image_orientation = vips_image_get_orientation(image);
    if (image_orientation < 1 || image_orientation > 8) image_orientation = 1;
    if (!dimensions_are_safe(image_width, image_height,
                             maximum_dimension, maximum_pixels)) {
        g_object_unref(image);
        copy_error(error_message, error_capacity,
                   "图片像素尺寸超过原生安全上限");
        return 0;
    }
    uint64_t image_pixels = (uint64_t)image_width * (uint64_t)image_height;
    uint64_t decoded_channels = format == PL_FORMAT_JPEG
        ? UINT64_C(3)
        : (image_channels == 2 || image_channels == 4
            ? UINT64_C(4) : UINT64_C(3));
    uint64_t decoded_bytes = image_pixels * decoded_channels;
    int requires_streaming = image_width >= streaming_threshold_dimension ||
        image_height >= streaming_threshold_dimension ||
        image_pixels >= streaming_threshold_pixels ||
        decoded_bytes > streaming_threshold_decoded_bytes;
    if (requires_streaming && format == PL_FORMAT_JPEG &&
        image_metadata_is_true(image, "jpeg-multiscan")) {
        g_object_unref(image);
        copy_error(error_message, error_capacity,
                   "超大渐进式 JPEG 无法安全流式处理");
        return 0;
    }
    if (requires_streaming && format == PL_FORMAT_PNG &&
        image_metadata_is_true(image, "interlaced")) {
        g_object_unref(image);
        copy_error(error_message, error_capacity,
                   "超大隔行 PNG 无法安全流式处理");
        return 0;
    }
    g_object_unref(image);
    *width = image_width;
    *height = image_height;
    *channels = image_channels;
    *orientation = image_orientation;
    return 1;
}

int pl_vips_dimensions_file(const char *input_path,
                            int format,
                            int maximum_dimension,
                            uint64_t maximum_pixels,
                            int streaming_threshold_dimension,
                            uint64_t streaming_threshold_pixels,
                            uint64_t streaming_threshold_decoded_bytes,
                            int *width,
                            int *height,
                            int *channels,
                            int *orientation,
                            char *error_message,
                            size_t error_capacity) {
    lock_vips();
    int result = dimensions_file_unlocked(
        input_path, format, maximum_dimension, maximum_pixels,
        streaming_threshold_dimension, streaming_threshold_pixels,
        streaming_threshold_decoded_bytes,
        width, height, channels, orientation, error_message, error_capacity);
    unlock_vips();
    return result;
}

/*
 * Preview encoder. `format` is the OUTPUT format, which for a preview is always
 * WebP regardless of what the source was.
 *
 * WebP replaced a pair of bad options. PNG previews were written losslessly, so
 * a 480px photo cost ~305 KB and the compression ratio had nothing to act on —
 * every ratio produced byte-identical files. Quantising them to a palette did
 * make the ratio bite, but at a visible cost in banding and dither noise. On the
 * same 480px photo WebP Q80 is 19.8 KB: 15x smaller than the lossless PNG, 3x
 * smaller than the palette PNG, and visually far closer to the lossless one.
 * It also beats the JPEG preview it replaces by ~30% at matched quality, and it
 * carries alpha, so transparent sources no longer need a separate format.
 *
 * PNG and JPEG stay here for the finished object (PL_OPERATION_COMPRESS), whose
 * bytes existing checksums and objects depend on. Those two branches must keep
 * emitting exactly what they emitted before.
 */
static int save_image(VipsImage *image, const char *output_path,
                      int format, int quality,
                      char *error_message, size_t error_capacity) {
    int result;
    if (format == PL_FORMAT_JPEG) {
        result = vips_jpegsave(
            image, output_path,
            "Q", quality,
            "strip", 1,
            "optimize_coding", 1,
            "interlace", 0,
            "subsample_mode", PL_VIPS_FOREIGN_SUBSAMPLE_ON,
            NULL);
    } else if (format == PL_FORMAT_PNG) {
        result = vips_pngsave(
            image, output_path,
            "compression", 9,
            "strip", 1,
            "interlace", 0,
            NULL);
    } else if (format == PL_FORMAT_WEBP) {
        // effort 6 of 6: previews are encoded once and served many times, and at
        // 480px the extra CPU is a few milliseconds. smart_subsample keeps
        // saturated edges (uniforms, banners) from bleeding at 4:2:0.
        result = vips_webpsave(
            image, output_path,
            "Q", quality,
            "strip", 1,
            "effort", 6,
            "smart_subsample", 1,
            NULL);
    } else {
        copy_error(error_message, error_capacity, "unsupported output format");
        return 0;
    }
    if (result != 0) return fail_with_vips_error(error_message, error_capacity);
    return 1;
}

/* `format` is the OUTPUT format; the input format is whatever the file holds. */
static int render_file(const char *input_path, const char *output_path,
                       int format, int maximum_dimension, int quality,
                       uint64_t maximum_output_bytes,
                       uint64_t *output_length,
                       int *output_width, int *output_height,
                       char *error_message, size_t error_capacity) {
    VipsImage *image = NULL;
    vips_error_clear();
    if (vips_thumbnail(
            input_path, &image, maximum_dimension,
            "height", maximum_dimension,
            "size", PL_VIPS_SIZE_DOWN,
            "auto_rotate", 1,
            "fail_on", PL_VIPS_FAIL_ON_ERROR,
            NULL) != 0)
        return fail_with_vips_error(error_message, error_capacity);

    int width = vips_image_get_width(image);
    int height = vips_image_get_height(image);
    if (!save_image(image, output_path, format, quality,
                    error_message, error_capacity)) {
        g_object_unref(image);
        remove_utf8(output_path);
        return 0;
    }
    g_object_unref(image);

    uint64_t length = 0;
    if (!file_size_utf8(output_path, &length)) {
        remove_utf8(output_path);
        copy_error(error_message, error_capacity,
                   "libvips did not create a readable output file");
        return 0;
    }
    if (length > maximum_output_bytes) {
        remove_utf8(output_path);
        copy_error(error_message, error_capacity,
                   "libvips output exceeds the native safety limit");
        return 0;
    }
    *output_length = length;
    *output_width = width;
    *output_height = height;
    return 1;
}

static int bounded_next_dimension(int current_dimension,
                                  uint64_t target_bytes,
                                  uint64_t current_bytes) {
    double ratio = (double)target_bytes / (double)current_bytes;
    double scale = sqrt(ratio) * 0.98;
    if (scale < 0.50) scale = 0.50;
    if (scale > 0.92) scale = 0.92;
    int next = (int)floor((double)current_dimension * scale);
    if (next >= current_dimension) next = current_dimension - 1;
    return next < 1 ? 1 : next;
}

static int dimensions_at_minimum(int source_width, int source_height) {
    int smallest = source_width < source_height ? source_width : source_height;
    int largest = source_width > source_height ? source_width : source_height;
    if (smallest <= PL_MINIMUM_DIMENSION) return largest;
    double scale = (double)PL_MINIMUM_DIMENSION / (double)smallest;
    int result = (int)ceil((double)largest * scale);
    return result < 1 ? 1 : result;
}

static int maximize_jpeg_quality(const char *input_path,
                                 const char *output_path,
                                 int maximum_dimension,
                                 int minimum_quality,
                                 uint64_t target_bytes,
                                 uint64_t maximum_output_bytes,
                                 uint64_t *output_length,
                                 int *output_width,
                                 int *output_height,
                                 char *error_message,
                                 size_t error_capacity) {
    int low = minimum_quality;
    int high = PL_JPEG_MAXIMUM_QUALITY;
    int best = minimum_quality;
    int rendered_quality = minimum_quality;
    uint64_t rendered_length = *output_length;
    int rendered_width = *output_width;
    int rendered_height = *output_height;

    for (int iteration = 0; iteration < 7 && low < high; iteration++) {
        int candidate = (low + high + 1) / 2;
        if (!render_file(input_path, output_path, PL_FORMAT_JPEG,
                         maximum_dimension, candidate, maximum_output_bytes,
                         &rendered_length, &rendered_width, &rendered_height,
                         error_message, error_capacity))
            return 0;
        rendered_quality = candidate;
        if (rendered_length <= target_bytes) {
            best = candidate;
            low = candidate;
        } else {
            high = candidate - 1;
        }
    }
    if (rendered_quality != best || rendered_length > target_bytes) {
        if (!render_file(input_path, output_path, PL_FORMAT_JPEG,
                         maximum_dimension, best, maximum_output_bytes,
                         &rendered_length, &rendered_width, &rendered_height,
                         error_message, error_capacity))
            return 0;
    }
    *output_length = rendered_length;
    *output_width = rendered_width;
    *output_height = rendered_height;
    return 1;
}

static int process_file_unlocked(const char *input_path,
                                 const char *output_path,
                                 int format,
                                 int output_format,
                                 int operation,
                                 uint64_t target_bytes,
                                 int maximum_dimension,
                                 double quality,
                                 int safety_maximum_dimension,
                                 uint64_t safety_maximum_pixels,
                                 int streaming_threshold_dimension,
                                 uint64_t streaming_threshold_pixels,
                                 uint64_t streaming_threshold_decoded_bytes,
                                 uint64_t maximum_output_bytes,
                                 uint64_t *output_length,
                                 int *output_width,
                                 int *output_height,
                                 char *error_message,
                                 size_t error_capacity) {
    if (input_path == NULL || output_path == NULL || output_length == NULL ||
        output_width == NULL || output_height == NULL ||
        (format != PL_FORMAT_JPEG && format != PL_FORMAT_PNG) ||
        (output_format != PL_FORMAT_JPEG && output_format != PL_FORMAT_PNG &&
         output_format != PL_FORMAT_WEBP) ||
        (operation != PL_OPERATION_COMPRESS &&
         operation != PL_OPERATION_THUMBNAIL)) {
        copy_error(error_message, error_capacity, "invalid libvips process arguments");
        return 0;
    }
    // The finished object keeps its source format: its bytes are already
    // referenced by stored checksums and object keys. Only previews re-encode
    // into a different container.
    if (operation == PL_OPERATION_COMPRESS && output_format != format) {
        copy_error(error_message, error_capacity,
                   "libvips compression cannot change the output format");
        return 0;
    }
    if (!ensure_vips(error_message, error_capacity)) return 0;

    int source_width = 0;
    int source_height = 0;
    int source_channels = 0;
    int source_orientation = 1;
    if (!dimensions_file_unlocked(input_path, format,
                                  safety_maximum_dimension,
                                  safety_maximum_pixels,
                                  streaming_threshold_dimension,
                                  streaming_threshold_pixels,
                                  streaming_threshold_decoded_bytes,
                                  &source_width,
                                  &source_height, &source_channels,
                                  &source_orientation,
                                  error_message, error_capacity))
        return 0;
    (void)source_channels;
    (void)source_orientation;

    if (operation == PL_OPERATION_THUMBNAIL) {
        if (maximum_dimension <= 0 || quality <= 0.0 || quality > 1.0) {
            copy_error(error_message, error_capacity,
                       "invalid libvips thumbnail arguments");
            return 0;
        }
        int encode_quality = (int)floor(quality * 100.0 + 0.5);
        if (encode_quality < 1) encode_quality = 1;
        if (encode_quality > 100) encode_quality = 100;
        return render_file(input_path, output_path, output_format,
                           maximum_dimension, encode_quality,
                           maximum_output_bytes, output_length,
                           output_width, output_height,
                           error_message, error_capacity);
    }

    if (target_bytes == 0) {
        copy_error(error_message, error_capacity,
                   "invalid libvips compression target");
        return 0;
    }

    int source_maximum = source_width > source_height
        ? source_width : source_height;
    int minimum_maximum = dimensions_at_minimum(source_width, source_height);
    int current_maximum = source_maximum;
    uint64_t source_length = 0;
    if (!file_size_utf8(input_path, &source_length)) {
        copy_error(error_message, error_capacity,
                   "cannot read the libvips input file size");
        return 0;
    }
    /*
     * Do not start a huge-image job by encoding every source pixel. A noisy
     * 1 Gpx image can otherwise create a massive first candidate before the
     * adaptive loop gets a chance to shrink it. For near-target inputs we
     * retain all pixels and let JPEG quality / PNG compression do the work.
     */
    double initial_scale = 1.0;
    uint64_t source_pixels = (uint64_t)source_width * (uint64_t)source_height;
    if (source_pixels > UINT64_C(100000000))
        initial_scale = sqrt(100000000.0 / (double)source_pixels);
    if (source_length > target_bytes &&
        (double)target_bytes / (double)source_length < 0.85) {
        double byte_scale = sqrt((double)target_bytes /
                                 (double)source_length) * 0.98;
        if (byte_scale < 0.25) byte_scale = 0.25;
        if (byte_scale > 0.95) byte_scale = 0.95;
        if (byte_scale < initial_scale) initial_scale = byte_scale;
    }
    if (initial_scale < 1.0) {
        current_maximum = (int)floor((double)source_maximum * initial_scale);
        if (current_maximum < minimum_maximum)
            current_maximum = minimum_maximum;
    }
    int jpeg_quality = PL_JPEG_MINIMUM_QUALITY;
    if (!render_file(input_path, output_path, format, current_maximum,
                     jpeg_quality, maximum_output_bytes, output_length,
                     output_width, output_height,
                     error_message, error_capacity))
        return 0;

    for (int attempt = 0;
         *output_length > target_bytes &&
             current_maximum > minimum_maximum &&
             attempt < PL_MAXIMUM_RESIZE_ATTEMPTS;
         attempt++) {
        int next = bounded_next_dimension(current_maximum, target_bytes,
                                          *output_length);
        if (next < minimum_maximum) next = minimum_maximum;
        current_maximum = next;
        if (!render_file(input_path, output_path, format, current_maximum,
                         jpeg_quality, maximum_output_bytes, output_length,
                         output_width, output_height,
                         error_message, error_capacity))
            return 0;
    }

    if (format == PL_FORMAT_JPEG && *output_length > target_bytes) {
        jpeg_quality = 40;
        if (!render_file(input_path, output_path, format, current_maximum,
                         jpeg_quality, maximum_output_bytes, output_length,
                         output_width, output_height,
                         error_message, error_capacity))
            return 0;
    }
    if (format == PL_FORMAT_JPEG && *output_length <= target_bytes) {
        if (!maximize_jpeg_quality(input_path, output_path, current_maximum,
                                   jpeg_quality, target_bytes,
                                   maximum_output_bytes, output_length,
                                   output_width, output_height,
                                   error_message, error_capacity))
            return 0;
    }
    return 1;
}

int pl_vips_process_file(const char *input_path,
                         const char *output_path,
                         int format,
                         int output_format,
                         int operation,
                         uint64_t target_bytes,
                         int maximum_dimension,
                         double quality,
                         int safety_maximum_dimension,
                         uint64_t safety_maximum_pixels,
                         int streaming_threshold_dimension,
                         uint64_t streaming_threshold_pixels,
                         uint64_t streaming_threshold_decoded_bytes,
                         uint64_t maximum_output_bytes,
                         uint64_t *output_length,
                         int *output_width,
                         int *output_height,
                         char *error_message,
                         size_t error_capacity) {
    lock_vips();
    int result = process_file_unlocked(
        input_path, output_path, format, output_format, operation, target_bytes,
        maximum_dimension, quality, safety_maximum_dimension,
        safety_maximum_pixels, streaming_threshold_dimension,
        streaming_threshold_pixels, streaming_threshold_decoded_bytes,
        maximum_output_bytes, output_length,
        output_width, output_height, error_message, error_capacity);
    unlock_vips();
    return result;
}
