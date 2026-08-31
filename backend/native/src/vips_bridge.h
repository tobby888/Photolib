#ifndef PHOTOLIB_VIPS_BRIDGE_H
#define PHOTOLIB_VIPS_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

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
                            size_t error_capacity);

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
                         size_t error_capacity);

#endif
