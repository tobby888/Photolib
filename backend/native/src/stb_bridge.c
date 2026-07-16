#include "stb_bridge.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

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
    int failed;
} output_buffer;

static void append_output(void *context, void *data, int size) {
    output_buffer *buffer = (output_buffer *)context;
    if (buffer->failed || size < 0) return;

    size_t requested = buffer->length + (size_t)size;
    if (requested < buffer->length) {
        buffer->failed = 1;
        return;
    }
    if (requested > buffer->capacity) {
        size_t capacity = buffer->capacity == 0 ? 4096 : buffer->capacity;
        while (capacity < requested) {
            if (capacity > SIZE_MAX / 2) {
                capacity = requested;
                break;
            }
            capacity *= 2;
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
                             int channels, size_t *output_length) {
    output_buffer buffer = {0};
    int stride = width * channels;
    if (!stbi_write_png_to_func(append_output, &buffer, width, height, channels,
                                pixels, stride) || buffer.failed) {
        free(buffer.data);
        return NULL;
    }
    *output_length = buffer.length;
    return buffer.data;
}

void pl_stb_free(void *pointer) {
    stbi_image_free(pointer);
}
