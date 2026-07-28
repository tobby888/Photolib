#include "stb_bridge.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

int main(void) {
    enum { WIDTH = 32, HEIGHT = 32, CHANNELS = 4 };
    unsigned char pixels[WIDTH * HEIGHT * CHANNELS];
    for (size_t index = 0; index < sizeof(pixels); index++)
        pixels[index] = (unsigned char)((index * 73U + 19U) & 0xffU);

    size_t encoded_length = 0;
    int too_large = 0;
    unsigned char *encoded = pl_png_encode(
        pixels, WIDTH, HEIGHT, CHANNELS, 1024 * 1024,
        &encoded_length, &too_large);
    if (encoded == NULL || too_large || encoded_length == 0) {
        fprintf(stderr, "unbounded PNG control encode failed\n");
        free(encoded);
        return 1;
    }
    free(encoded);

    size_t exact_length = 0;
    encoded = pl_png_encode(
        pixels, WIDTH, HEIGHT, CHANNELS, encoded_length,
        &exact_length, &too_large);
    if (encoded == NULL || too_large || exact_length != encoded_length) {
        fprintf(stderr, "PNG exact output boundary was rejected\n");
        free(encoded);
        return 2;
    }
    free(encoded);

    size_t rejected_length = 0;
    encoded = pl_png_encode(
        pixels, WIDTH, HEIGHT, CHANNELS, encoded_length - 1,
        &rejected_length, &too_large);
    if (encoded != NULL || !too_large || rejected_length != 0) {
        fprintf(stderr, "PNG output above the boundary was not rejected\n");
        free(encoded);
        return 3;
    }

    if (!pl_output_length_allowed(256, 256) ||
        pl_output_length_allowed(257, 256) ||
        pl_output_length_allowed(0, 256)) {
        fprintf(stderr, "encoded output length boundary is incorrect\n");
        return 4;
    }
    return 0;
}
