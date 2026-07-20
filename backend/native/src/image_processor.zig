const std = @import("std");

const c = @cImport({
    @cInclude("stdlib.h");
    @cInclude("string.h");
    @cInclude("turbojpeg.h");
    @cInclude("stb_bridge.h");
});

const FORMAT_JPEG: i32 = 1;
const FORMAT_PNG: i32 = 2;
const OP_COMPRESS: i32 = 1;
const OP_THUMBNAIL: i32 = 2;
const MAX_DIMENSION: i32 = 30_000;
const MAX_PIXELS: u64 = 100_000_000;
const MAX_INPUT_BYTES: usize = 100 * 1024 * 1024;
const MIN_DIMENSION: i32 = 320;
const JPEG_MIN_QUALITY: i32 = 82;
const JPEG_MAX_QUALITY: i32 = 97;

const NativeError = error{
    InvalidInput,
    UnsupportedFormat,
    InvalidDimensions,
    DecodeFailed,
    EncodeFailed,
    ResizeFailed,
    OutOfMemory,
    OutputTooLarge,
    ReadFailed,
    WriteFailed,
};

pub const PlDimensions = extern struct {
    width: i32,
    height: i32,
    channels: i32,
    error_message: [256]u8,
};

pub const PlFileResult = extern struct {
    length: u64,
    width: i32,
    height: i32,
    error_message: [256]u8,
};

const Dimensions = struct {
    width: i32,
    height: i32,
    channels: i32,
};

const Pixels = struct {
    data: [*]u8,
    width: i32,
    height: i32,
    channels: i32,

    fn length(self: Pixels) NativeError!usize {
        const pixel_count = try checkedPixelCount(self.width, self.height);
        return std.math.mul(usize, pixel_count, @intCast(self.channels)) catch NativeError.InvalidDimensions;
    }

    fn deinit(self: Pixels) void {
        c.free(self.data);
    }
};

const Encoded = struct {
    data: [*]u8,
    length: usize,

    fn deinit(self: Encoded) void {
        c.free(self.data);
    }
};

const FileSource = struct {
    data: [*]u8,
    length: usize,

    fn deinit(self: FileSource) void {
        c.free(self.data);
    }
};

export fn photolib_dimensions_file(input_path: ?[*:0]const u8, format: i32, output: ?*PlDimensions) callconv(.c) i32 {
    const out = output orelse return 1;
    out.* = std.mem.zeroes(PlDimensions);
    const path = input_path orelse return fail(&out.error_message, NativeError.InvalidInput);
    const source = readFile(path) catch |err|
        return fail(&out.error_message, err);
    defer source.deinit();

    const dimensions = readDimensions(source.data, source.length, format) catch |err|
        return fail(&out.error_message, err);
    out.width = dimensions.width;
    out.height = dimensions.height;
    out.channels = dimensions.channels;
    return 0;
}

export fn photolib_process_file(input_path: ?[*:0]const u8, output_path: ?[*:0]const u8, format: i32, operation: i32, target_bytes: u64, max_dimension: i32, quality: f64, output: ?*PlFileResult) callconv(.c) i32 {
    const out = output orelse return 1;
    out.* = std.mem.zeroes(PlFileResult);
    const source_path = input_path orelse return fail(&out.error_message, NativeError.InvalidInput);
    const destination_path = output_path orelse return fail(&out.error_message, NativeError.InvalidInput);
    const source = readFile(source_path) catch |err|
        return fail(&out.error_message, err);
    defer source.deinit();

    const processed = process(source.data[0..source.length], format, operation, target_bytes, max_dimension, quality) catch |err|
        return fail(&out.error_message, err);
    defer processed.encoded.deinit();
    if (c.pl_file_write_utf8(destination_path, processed.encoded.data, processed.encoded.length) == 0)
        return fail(&out.error_message, NativeError.WriteFailed);
    out.length = processed.encoded.length;
    out.width = processed.width;
    out.height = processed.height;
    return 0;
}

const Processed = struct {
    encoded: Encoded,
    width: i32,
    height: i32,
};

fn readFile(path: [*:0]const u8) NativeError!FileSource {
    var length: usize = 0;
    const memory = c.pl_file_read_utf8(path, &length) orelse return NativeError.ReadFailed;
    const data: [*]u8 = @ptrCast(memory);
    if (length == 0 or length > MAX_INPUT_BYTES) {
        c.free(data);
        return NativeError.InvalidInput;
    }
    return .{ .data = data, .length = length };
}

fn process(source: []const u8, format: i32, operation: i32, target_bytes: u64, max_dimension: i32, quality: f64) NativeError!Processed {
    _ = try readDimensions(source.ptr, source.len, format);
    return switch (operation) {
        OP_COMPRESS => compress(source, format, target_bytes),
        OP_THUMBNAIL => thumbnail(source, format, max_dimension, quality),
        else => NativeError.InvalidInput,
    };
}

fn compress(source: []const u8, format: i32, target_bytes: u64) NativeError!Processed {
    if (target_bytes == 0) return NativeError.InvalidInput;
    var pixels = try decode(source, format, null);
    defer pixels.deinit();

    const encoded = if (format == FORMAT_JPEG)
        try compressJpeg(&pixels, target_bytes)
    else
        try compressPng(&pixels, target_bytes);
    return .{ .encoded = encoded, .width = pixels.width, .height = pixels.height };
}

fn thumbnail(source: []const u8, format: i32, max_dimension: i32, quality: f64) NativeError!Processed {
    if (max_dimension <= 0 or quality <= 0 or quality > 1) return NativeError.InvalidInput;
    const original = try readDimensions(source.ptr, source.len, format);
    const target = scaledDimensions(original.width, original.height, max_dimension);
    var pixels = try decode(source, format, target);
    defer pixels.deinit();

    if (pixels.width != target.width or pixels.height != target.height) {
        const resized = try resizePixels(pixels, target.width, target.height);
        pixels.deinit();
        pixels = resized;
    }

    const encoded = if (format == FORMAT_JPEG)
        try encodeJpeg(pixels, @intFromFloat(@round(quality * 100.0)))
    else
        try encodePng(pixels);
    return .{ .encoded = encoded, .width = pixels.width, .height = pixels.height };
}

fn readDimensions(source: [*]const u8, source_length: usize, format: i32) NativeError!Dimensions {
    if (source_length == 0 or source_length > std.math.maxInt(c_int)) return NativeError.InvalidInput;
    var result: Dimensions = undefined;
    if (format == FORMAT_JPEG) {
        const handle = c.tj3Init(c.TJINIT_DECOMPRESS) orelse return NativeError.DecodeFailed;
        defer c.tj3Destroy(handle);
        if (c.tj3DecompressHeader(handle, source, source_length) != 0) return NativeError.DecodeFailed;
        result = .{
            .width = c.tj3Get(handle, c.TJPARAM_JPEGWIDTH),
            .height = c.tj3Get(handle, c.TJPARAM_JPEGHEIGHT),
            .channels = 3,
        };
    } else if (format == FORMAT_PNG) {
        if (source_length < 24 or source[0] != 0x89 or source[1] != 0x50 or
            source[2] != 0x4e or source[3] != 0x47 or source[12] != 'I' or
            source[13] != 'H' or source[14] != 'D' or source[15] != 'R')
            return NativeError.DecodeFailed;
        const header_width = readBigEndianU32(source + 16);
        const header_height = readBigEndianU32(source + 20);
        if (header_width > std.math.maxInt(i32) or header_height > std.math.maxInt(i32))
            return NativeError.InvalidDimensions;
        _ = try checkedPixelCount(@intCast(header_width), @intCast(header_height));
        var width: c_int = 0;
        var height: c_int = 0;
        var channels: c_int = 0;
        if (c.pl_png_info(source, @intCast(source_length), &width, &height, &channels) == 0)
            return NativeError.DecodeFailed;
        result = .{ .width = width, .height = height, .channels = channels };
    } else {
        return NativeError.UnsupportedFormat;
    }
    _ = try checkedPixelCount(result.width, result.height);
    return result;
}

fn decode(source: []const u8, format: i32, requested: ?Dimensions) NativeError!Pixels {
    return if (format == FORMAT_JPEG)
        decodeJpeg(source, requested)
    else if (format == FORMAT_PNG)
        decodePng(source)
    else
        NativeError.UnsupportedFormat;
}

fn decodeJpeg(source: []const u8, requested: ?Dimensions) NativeError!Pixels {
    const handle = c.tj3Init(c.TJINIT_DECOMPRESS) orelse return NativeError.DecodeFailed;
    defer c.tj3Destroy(handle);
    if (c.tj3DecompressHeader(handle, source.ptr, source.len) != 0) return NativeError.DecodeFailed;
    const original_width = c.tj3Get(handle, c.TJPARAM_JPEGWIDTH);
    const original_height = c.tj3Get(handle, c.TJPARAM_JPEGHEIGHT);
    _ = try checkedPixelCount(original_width, original_height);

    var width = original_width;
    var height = original_height;
    if (requested) |target| {
        var factor_count: c_int = 0;
        const factors = c.tj3GetScalingFactors(&factor_count) orelse return NativeError.DecodeFailed;
        var selected: ?c.tjscalingfactor = null;
        var selected_pixels: u64 = std.math.maxInt(u64);
        var index: usize = 0;
        while (index < @as(usize, @intCast(factor_count))) : (index += 1) {
            const factor = factors[index];
            if (factor.num > factor.denom) continue;
            const candidate_width = scaledByFactor(original_width, factor);
            const candidate_height = scaledByFactor(original_height, factor);
            if (candidate_width < target.width or candidate_height < target.height) continue;
            const candidate_pixels = @as(u64, @intCast(candidate_width)) * @as(u64, @intCast(candidate_height));
            if (candidate_pixels < selected_pixels) {
                selected = factor;
                selected_pixels = candidate_pixels;
                width = candidate_width;
                height = candidate_height;
            }
        }
        if (selected) |factor| {
            if (c.tj3SetScalingFactor(handle, factor) != 0) return NativeError.DecodeFailed;
        }
    }

    const pixel_count = try checkedPixelCount(width, height);
    const length = std.math.mul(usize, pixel_count, 3) catch return NativeError.InvalidDimensions;
    const memory = c.malloc(length) orelse return NativeError.OutOfMemory;
    const pixels: [*]u8 = @ptrCast(memory);
    errdefer c.free(memory);
    if (c.tj3Decompress8(handle, source.ptr, source.len, pixels, 0, c.TJPF_RGB) != 0)
        return NativeError.DecodeFailed;
    return .{ .data = pixels, .width = width, .height = height, .channels = 3 };
}

fn decodePng(source: []const u8) NativeError!Pixels {
    if (source.len > std.math.maxInt(c_int)) return NativeError.InvalidInput;
    var width: c_int = 0;
    var height: c_int = 0;
    var source_channels: c_int = 0;
    if (c.pl_png_info(source.ptr, @intCast(source.len), &width, &height, &source_channels) == 0)
        return NativeError.DecodeFailed;
    _ = try checkedPixelCount(width, height);
    const channels: c_int = if (source_channels == 2 or source_channels == 4) 4 else 3;
    const memory = c.pl_png_decode(source.ptr, @intCast(source.len), &width, &height, &source_channels, channels) orelse return NativeError.DecodeFailed;
    return .{ .data = memory, .width = width, .height = height, .channels = channels };
}

fn compressJpeg(pixels: *Pixels, target_bytes: u64) NativeError!Encoded {
    while (true) {
        const minimum = try encodeJpeg(pixels.*, JPEG_MIN_QUALITY);
        if (minimum.length <= target_bytes) {
            var best = minimum;
            errdefer best.deinit();
            var low = JPEG_MIN_QUALITY;
            var high = JPEG_MAX_QUALITY;
            var iteration: usize = 0;
            while (iteration < 7 and low < high) : (iteration += 1) {
                const quality = @divTrunc(low + high + 1, 2);
                var candidate = try encodeJpeg(pixels.*, quality);
                if (candidate.length <= target_bytes) {
                    best.deinit();
                    best = candidate;
                    low = quality;
                } else {
                    candidate.deinit();
                    high = quality - 1;
                }
            }
            return best;
        }

        const scale = boundedScale(target_bytes, minimum.length);
        minimum.deinit();
        if (scaledBelowMinimum(pixels.*, scale)) {
            if (pixels.width > MIN_DIMENSION and pixels.height > MIN_DIMENSION) {
                const minimum_size = dimensionsAtMinimum(pixels.*);
                const resized = try resizePixels(pixels.*, minimum_size.width, minimum_size.height);
                pixels.deinit();
                pixels.* = resized;
            }
            return try jpegAtLastResort(pixels.*, target_bytes);
        }
        const width = @max(1, @as(i32, @intFromFloat(@round(@as(f64, @floatFromInt(pixels.width)) * scale))));
        const height = @max(1, @as(i32, @intFromFloat(@round(@as(f64, @floatFromInt(pixels.height)) * scale))));
        const resized = try resizePixels(pixels.*, width, height);
        pixels.deinit();
        pixels.* = resized;
    }
}

fn jpegAtLastResort(pixels: Pixels, target_bytes: u64) NativeError!Encoded {
    const minimum = try encodeJpeg(pixels, 40);
    if (minimum.length > target_bytes) return minimum;
    var best = minimum;
    errdefer best.deinit();
    var low: i32 = 40;
    var high: i32 = JPEG_MIN_QUALITY;
    var iteration: usize = 0;
    while (iteration < 7 and low < high) : (iteration += 1) {
        const quality = @divTrunc(low + high + 1, 2);
        var candidate = try encodeJpeg(pixels, quality);
        if (candidate.length <= target_bytes) {
            best.deinit();
            best = candidate;
            low = quality;
        } else {
            candidate.deinit();
            high = quality - 1;
        }
    }
    return best;
}

fn compressPng(pixels: *Pixels, target_bytes: u64) NativeError!Encoded {
    var output = try encodePng(pixels.*);
    if (output.length <= target_bytes) return output;
    while (pixels.width > MIN_DIMENSION and pixels.height > MIN_DIMENSION) {
        const scale = boundedScale(target_bytes, output.length);
        output.deinit();
        const dimensions = if (scaledBelowMinimum(pixels.*, scale))
            dimensionsAtMinimum(pixels.*)
        else
            Dimensions{
                .width = @max(1, @as(i32, @intFromFloat(@round(@as(f64, @floatFromInt(pixels.width)) * scale)))),
                .height = @max(1, @as(i32, @intFromFloat(@round(@as(f64, @floatFromInt(pixels.height)) * scale)))),
                .channels = pixels.channels,
            };
        const resized = try resizePixels(pixels.*, dimensions.width, dimensions.height);
        pixels.deinit();
        pixels.* = resized;
        output = try encodePng(pixels.*);
        if (output.length <= target_bytes) return output;
    }
    return output;
}

fn encodeJpeg(pixels: Pixels, quality: i32) NativeError!Encoded {
    const handle = c.tj3Init(c.TJINIT_COMPRESS) orelse return NativeError.EncodeFailed;
    defer c.tj3Destroy(handle);
    if (c.tj3Set(handle, c.TJPARAM_QUALITY, @max(1, @min(100, quality))) != 0 or
        c.tj3Set(handle, c.TJPARAM_SUBSAMP, c.TJSAMP_420) != 0)
        return NativeError.EncodeFailed;

    var jpeg_buffer: [*c]u8 = null;
    var jpeg_length: usize = 0;
    if (c.tj3Compress8(handle, pixels.data, pixels.width, 0, pixels.height, c.TJPF_RGB, &jpeg_buffer, &jpeg_length) != 0) return NativeError.EncodeFailed;
    defer c.tj3Free(jpeg_buffer);
    const memory = c.malloc(jpeg_length) orelse return NativeError.OutOfMemory;
    const output: [*]u8 = @ptrCast(memory);
    @memcpy(output[0..jpeg_length], jpeg_buffer[0..jpeg_length]);
    return .{ .data = output, .length = jpeg_length };
}

fn encodePng(pixels: Pixels) NativeError!Encoded {
    var output_length: usize = 0;
    const output = c.pl_png_encode(pixels.data, pixels.width, pixels.height, pixels.channels, &output_length) orelse return NativeError.EncodeFailed;
    return .{ .data = output, .length = output_length };
}

fn resizePixels(source: Pixels, width: i32, height: i32) NativeError!Pixels {
    const pixel_count = try checkedPixelCount(width, height);
    const length = std.math.mul(usize, pixel_count, @intCast(source.channels)) catch
        return NativeError.InvalidDimensions;
    const memory = c.malloc(length) orelse return NativeError.OutOfMemory;
    const output: [*]u8 = @ptrCast(memory);
    errdefer c.free(memory);
    if (c.pl_resize(source.data, source.width, source.height, source.channels, output, width, height) == 0) return NativeError.ResizeFailed;
    return .{ .data = output, .width = width, .height = height, .channels = source.channels };
}

fn checkedPixelCount(width: i32, height: i32) NativeError!usize {
    if (width <= 0 or height <= 0 or width > MAX_DIMENSION or height > MAX_DIMENSION)
        return NativeError.InvalidDimensions;
    const pixels = @as(u64, @intCast(width)) * @as(u64, @intCast(height));
    if (pixels > MAX_PIXELS) return NativeError.InvalidDimensions;
    return std.math.cast(usize, pixels) orelse NativeError.InvalidDimensions;
}

fn scaledDimensions(width: i32, height: i32, max_dimension: i32) Dimensions {
    const largest = @max(width, height);
    if (largest <= max_dimension) return .{ .width = width, .height = height, .channels = 0 };
    const scale = @as(f64, @floatFromInt(max_dimension)) / @as(f64, @floatFromInt(largest));
    return .{
        .width = @max(1, @as(i32, @intFromFloat(@round(@as(f64, @floatFromInt(width)) * scale)))),
        .height = @max(1, @as(i32, @intFromFloat(@round(@as(f64, @floatFromInt(height)) * scale)))),
        .channels = 0,
    };
}

fn scaledByFactor(value: i32, factor: c.tjscalingfactor) i32 {
    return @intCast(@divTrunc(@as(i64, value) * factor.num + factor.denom - 1, factor.denom));
}

fn readBigEndianU32(bytes: [*]const u8) u32 {
    return (@as(u32, bytes[0]) << 24) |
        (@as(u32, bytes[1]) << 16) |
        (@as(u32, bytes[2]) << 8) |
        @as(u32, bytes[3]);
}

fn boundedScale(target_bytes: u64, current_bytes: usize) f64 {
    const ratio = @as(f64, @floatFromInt(target_bytes)) /
        @as(f64, @floatFromInt(current_bytes));
    return @max(0.75, @min(0.95, @sqrt(ratio) * 0.98));
}

fn scaledBelowMinimum(pixels: Pixels, scale: f64) bool {
    return @as(f64, @floatFromInt(pixels.width)) * scale < MIN_DIMENSION or
        @as(f64, @floatFromInt(pixels.height)) * scale < MIN_DIMENSION;
}

fn dimensionsAtMinimum(pixels: Pixels) Dimensions {
    const scale = @max(
        @as(f64, MIN_DIMENSION) / @as(f64, @floatFromInt(pixels.width)),
        @as(f64, MIN_DIMENSION) / @as(f64, @floatFromInt(pixels.height)),
    );
    if (scale >= 1.0) return .{ .width = pixels.width, .height = pixels.height, .channels = pixels.channels };
    return .{
        .width = @max(1, @as(i32, @intFromFloat(@round(@as(f64, @floatFromInt(pixels.width)) * scale)))),
        .height = @max(1, @as(i32, @intFromFloat(@round(@as(f64, @floatFromInt(pixels.height)) * scale)))),
        .channels = pixels.channels,
    };
}

fn fail(buffer: *[256]u8, err: NativeError) i32 {
    const message = switch (err) {
        NativeError.InvalidInput => "输入参数无效",
        NativeError.UnsupportedFormat => "不支持的图片格式",
        NativeError.InvalidDimensions => "图片像素尺寸超过安全上限",
        NativeError.DecodeFailed => "原生图片解码失败",
        NativeError.EncodeFailed => "原生图片编码失败",
        NativeError.ResizeFailed => "原生图片缩放失败",
        NativeError.OutOfMemory => "原生图片内存分配失败",
        NativeError.OutputTooLarge => "原生图片输出过大",
        NativeError.ReadFailed => "无法读取本地图片文件",
        NativeError.WriteFailed => "无法写入本地图片文件",
    };
    @memcpy(buffer[0..message.len], message);
    buffer[message.len] = 0;
    return 1;
}
