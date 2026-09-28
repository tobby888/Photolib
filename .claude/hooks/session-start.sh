#!/bin/bash
# Claude Code 云端会话（claude.ai/code）的前置环境。本地开发不执行。
#
# 后端 generate-resources 阶段会调 backend/native/build-native.sh 编译原生图片组件，
# 应用上下文启动时缺了它们就起不来，于是几乎所有 @SpringBootTest 都会失败。这里补齐：
#   * zig（版本与校验值取自 .github/workflows/build.yml，和 CI 保持一致）、nasm、ninja；
#   * stb 源码包：云端网络策略拦 codeload.github.com，改用 git 取回后按 GitHub 相同的
#     方式（git archive | gzip -n）重建，校验值取自 build-native.sh，逐字节一致；
#   * UTF-8 locale：容器默认是 POSIX，JVM 解析中文文件名会抛 InvalidPathException；
#   * 前端 node_modules，以及一次 test-compile 预热 Maven 依赖和原生组件
#     （钩子结束后容器状态会被缓存，之后的会话直接复用）。
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

PROJECT_DIR=${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}
cd "$PROJECT_DIR"

log() { echo "[session-start] $*" >&2; }

value_of() { # value_of <文件> <正则>：取第一处匹配行冒号 / 等号之后的值
    grep -m1 -E "$2" "$1" | sed -E 's/^[^:=]*[:=][[:space:]]*//; s/[[:space:]"]*$//'
}

# ---------------------------------------------------------------- 系统工具
missing_packages=()
command -v nasm >/dev/null 2>&1 || missing_packages+=(nasm)
command -v ninja >/dev/null 2>&1 || missing_packages+=(ninja-build)
if [ ${#missing_packages[@]} -gt 0 ]; then
    log "安装 ${missing_packages[*]}"
    SUDO=""
    [ "$(id -u)" -ne 0 ] && SUDO="sudo"
    $SUDO apt-get update -qq
    DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq --no-install-recommends \
        "${missing_packages[@]}" >/dev/null
fi

# ---------------------------------------------------------------- zig
WORKFLOW=.github/workflows/build.yml
ZIG_VERSION=$(value_of "$WORKFLOW" '^[[:space:]]*ZIG_VERSION:')
ZIG_SHA256=$(value_of "$WORKFLOW" '^[[:space:]]*ZIG_SHA256:')
ZIG_HOME="$HOME/.local/zig"
if [ ! -x "$ZIG_HOME/zig" ] || [ "$("$ZIG_HOME/zig" version)" != "$ZIG_VERSION" ]; then
    log "安装 zig $ZIG_VERSION"
    tmp=$(mktemp -d)
    rm -rf "$ZIG_HOME"
    mkdir -p "$ZIG_HOME"
    if curl -fsSL --retry 3 --retry-all-errors -o "$tmp/zig.tar.xz" \
            "https://ziglang.org/download/$ZIG_VERSION/zig-x86_64-linux-$ZIG_VERSION.tar.xz" \
        && echo "$ZIG_SHA256  $tmp/zig.tar.xz" | sha256sum -c --quiet -; then
        tar -xJf "$tmp/zig.tar.xz" -C "$ZIG_HOME" --strip-components=1
    else
        # ziglang.org 被拦时退回 PyPI 上官方打包的同版本 zig。
        log "ziglang.org 不可用，改从 PyPI 取 ziglang==$ZIG_VERSION"
        python3 -m pip download --quiet --no-deps --only-binary=:all: \
            --platform manylinux_2_12_x86_64 -d "$tmp" "ziglang==$ZIG_VERSION"
        python3 -m zipfile -e "$tmp"/ziglang-*.whl "$tmp/wheel"
        cp -a "$tmp/wheel/ziglang/." "$ZIG_HOME/"
        chmod +x "$ZIG_HOME/zig"
    fi
    rm -rf "$tmp"
fi
"$ZIG_HOME/zig" version >/dev/null

# ---------------------------------------------------------------- stb 源码包
NATIVE_SCRIPT=backend/native/build-native.sh
STB_COMMIT=$(value_of "$NATIVE_SCRIPT" '^STB_COMMIT=')
STB_SHA256=$(grep -A4 'codeload.github.com/nothings/stb' "$NATIVE_SCRIPT" \
    | grep -m1 -oE '[0-9a-f]{64}')
ARCHIVE_DIRECTORY=${PHOTOLIB_NATIVE_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/photolib/native-archives}
STB_ARCHIVE="$ARCHIVE_DIRECTORY/stb-$STB_COMMIT.tar.gz"
mkdir -p "$ARCHIVE_DIRECTORY"
if ! echo "$STB_SHA256  $STB_ARCHIVE" | sha256sum -c --quiet - >/dev/null 2>&1; then
    log "重建 stb-$STB_COMMIT 源码包"
    tmp=$(mktemp -d)
    git init -q "$tmp/stb"
    git -C "$tmp/stb" fetch -q --depth 1 https://github.com/nothings/stb.git "$STB_COMMIT"
    git -C "$tmp/stb" archive --format=tar --prefix="stb-$STB_COMMIT/" "$STB_COMMIT" \
        | gzip -n > "$STB_ARCHIVE.part"
    mv -f "$STB_ARCHIVE.part" "$STB_ARCHIVE"
    rm -rf "$tmp"
    echo "$STB_SHA256  $STB_ARCHIVE" | sha256sum -c --quiet -
fi

# ---------------------------------------------------------------- 会话环境
export PATH="$ZIG_HOME:$PATH"
export LANG=C.UTF-8 LC_ALL=C.UTF-8
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
    {
        echo "export PATH=\"$ZIG_HOME:\$PATH\""
        echo 'export LANG=C.UTF-8'
        echo 'export LC_ALL=C.UTF-8'
    } >> "$CLAUDE_ENV_FILE"
fi

# ---------------------------------------------------------------- 依赖预热
# 用 npm ci 而不是 npm install：容器里的 npm 版本和锁文件生成时不同，npm install
# 会改写 package-lock.json。只在 node_modules 缺失或比锁文件旧时重装。
if [ ! -f node_modules/.package-lock.json ] || [ package-lock.json -nt node_modules/.package-lock.json ]; then
    log "安装前端依赖"
    npm ci --no-audit --no-fund --loglevel=error >&2
fi

log "预热 Maven 依赖与原生图片组件"
(cd backend && ./mvnw -q -DskipTests test-compile) >&2

log "完成"
