#include "stb_bridge.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#include <wchar.h>
#endif

#define STBI_ONLY_PNG
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#define STB_IMAGE_RESIZE_IMPLEMENTATION
#include "stb_image_resize2.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

typedef struct {
    unsigned char *data;
    size_t length;
    size_t capacity;
    size_t maximum_length;
    int failed;
    int too_large;
} output_buffer;

static void append_output(void *context, void *data, int size) {
    output_buffer *buffer = (output_buffer *)context;
    if (buffer->failed || size < 0) return;

    size_t addition = (size_t)size;
    if (addition > buffer->maximum_length - buffer->length) {
        buffer->failed = 1;
        buffer->too_large = 1;
        return;
    }
    size_t requested = buffer->length + addition;
    if (requested > buffer->capacity) {
        size_t capacity = buffer->capacity == 0 ? 4096 : buffer->capacity;
        while (capacity < requested) {
            if (capacity > buffer->maximum_length / 2) {
                capacity = buffer->maximum_length;
                break;
            }
            capacity *= 2;
        }
        if (capacity > buffer->maximum_length)
            capacity = buffer->maximum_length;
        if (capacity < requested) {
            buffer->failed = 1;
            buffer->too_large = 1;
            return;
        }
        unsigned char *grown = (unsigned char *)realloc(buffer->data, capacity);
        if (grown == NULL) {
            buffer->failed = 1;
            return;
        }
        buffer->data = grown;
        buffer->capacity = capacity;
    }
    memcpy(buffer->data + buffer->length, data, (size_t)size);
    buffer->length = requested;
}

int pl_png_info(const unsigned char *input, int input_length,
                int *width, int *height, int *channels) {
    return stbi_info_from_memory(input, input_length, width, height, channels);
}

unsigned char *pl_png_decode(const unsigned char *input, int input_length,
                             int *width, int *height, int *channels,
                             int desired_channels) {
    return stbi_load_from_memory(input, input_length, width, height, channels,
                                 desired_channels);
}

int pl_resize(const unsigned char *input, int input_width, int input_height,
              int channels, unsigned char *output,
              int output_width, int output_height) {
    stbir_pixel_layout layout = channels == 4 ? STBIR_RGBA : STBIR_RGB;
    return stbir_resize_uint8_linear(input, input_width, input_height, 0,
                                     output, output_width, output_height, 0,
                                     layout) != NULL;
}

unsigned char *pl_png_encode(const unsigned char *pixels, int width, int height,
                             int channels, size_t maximum_output_length,
                             size_t *output_length, int *output_too_large) {
    output_buffer buffer = {0};
    buffer.maximum_length = maximum_output_length;
    if (output_length == NULL || output_too_large == NULL ||
        maximum_output_length == 0) return NULL;
    *output_length = 0;
    *output_too_large = 0;
    int stride = width * channels;
    if (!stbi_write_png_to_func(append_output, &buffer, width, height, channels,
                                pixels, stride) || buffer.failed) {
        free(buffer.data);
        *output_too_large = buffer.too_large;
        return NULL;
    }
    *output_length = buffer.length;
    return buffer.data;
}

int pl_output_length_allowed(size_t length, size_t maximum_length) {
    return length > 0 && length <= maximum_length;
}

void pl_stb_free(void *pointer) {
    stbi_image_free(pointer);
}

static FILE *open_utf8(const char *path, const char *mode) {
#ifdef _WIN32
    int path_length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
                                          path, -1, NULL, 0);
    int mode_length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
                                          mode, -1, NULL, 0);
    if (path_length <= 0 || mode_length <= 0) return NULL;

    wchar_t *wide_path = (wchar_t *)malloc((size_t)path_length * sizeof(wchar_t));
    wchar_t *wide_mode = (wchar_t *)malloc((size_t)mode_length * sizeof(wchar_t));
    if (wide_path == NULL || wide_mode == NULL) {
        free(wide_path);
        free(wide_mode);
        return NULL;
    }
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1,
                            wide_path, path_length) <= 0 ||
        MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, mode, -1,
                            wide_mode, mode_length) <= 0) {
        free(wide_path);
        free(wide_mode);
        return NULL;
    }
    FILE *file = _wfopen(wide_path, wide_mode);
    free(wide_path);
    free(wide_mode);
    return file;
#else
    return fopen(path, mode);
#endif
}

unsigned char *pl_file_read_utf8(const char *path, size_t *output_length) {
    if (path == NULL || output_length == NULL) return NULL;
    FILE *file = open_utf8(path, "rb");
    if (file == NULL) return NULL;
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        return NULL;
    }
    long signed_length = ftell(file);
    if (signed_length <= 0 || fseek(file, 0, SEEK_SET) != 0) {
        fclose(file);
        return NULL;
    }
    size_t length = (size_t)signed_length;
    unsigned char *data = (unsigned char *)malloc(length);
    if (data == NULL) {
        fclose(file);
        return NULL;
    }
    size_t read = fread(data, 1, length, file);
    int close_result = fclose(file);
    if (read != length || close_result != 0) {
        free(data);
        return NULL;
    }
    *output_length = length;
    return data;
}

int pl_file_write_utf8(const char *path, const unsigned char *data, size_t length) {
    if (path == NULL || data == NULL || length == 0) return 0;
    FILE *file = open_utf8(path, "wb");
    if (file == NULL) return 0;
    size_t written = fwrite(data, 1, length, file);
    int close_result = fclose(file);
    return written == length && close_result == 0;
}

int pl_file_matches_format_utf8(const char *path, int format) {
    if (path == NULL) return 0;
    FILE *file = open_utf8(path, "rb");
    if (file == NULL) return 0;
    unsigned char signature[8] = {0};
    size_t length = fread(signature, 1, sizeof(signature), file);
    fclose(file);
    if (format == 1) {
        return length >= 3 && signature[0] == 0xff &&
               signature[1] == 0xd8 && signature[2] == 0xff;
    }
    if (format == 2) {
        static const unsigned char png_signature[8] = {
            0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
        return length == sizeof(signature) &&
               memcmp(signature, png_signature, sizeof(signature)) == 0;
    }
    return 0;
}
