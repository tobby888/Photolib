#ifndef PHOTOLIB_STB_BRIDGE_H
#define PHOTOLIB_STB_BRIDGE_H

#include <stddef.h>

int pl_png_info(const unsigned char *input, int input_length,
                int *width, int *height, int *channels);

unsigned char *pl_png_decode(const unsigned char *input, int input_length,
                             int *width, int *height, int *channels,
                             int desired_channels);

int pl_resize(const unsigned char *input, int input_width, int input_height,
              int channels, unsigned char *output,
              int output_width, int output_height);

unsigned char *pl_png_encode(const unsigned char *pixels, int width, int height,
                             int channels, size_t *output_length);

void pl_stb_free(void *pointer);

#endif
