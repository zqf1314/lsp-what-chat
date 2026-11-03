#!/system/bin/sh
# 让微信进程不被 Android 的 cached-app freezer 冻住（需 root）。
#
# 背景：模块跑在微信进程里，微信一旦退到后台被判为 cached，系统就会把整个进程冻起来
#       （/proc/<pid>/task/*/wchan = do_freezer_trap，cgroup.freeze=1）。
#       此时 18765 端口还在 LISTEN，但 accept 线程拿不到 CPU，客户端连上后一个字节都收不到，
#       表现就是「端口不响应 / 请求挂死」。
#
# 用法：
#   sh wx-keepalive.sh once          # 手动解冻一次（调 MCP 前跑一下）
#   sh wx-keepalive.sh watch [秒]    # 常驻巡检，被冻就解冻，默认 5 秒一轮
#
# 开机自启（KernelSU / Magisk）：
#   cp wx-keepalive.sh /data/adb/service.d/wx-keepalive.sh
#   chmod 755 /data/adb/service.d/wx-keepalive.sh
# 并在文件末尾追加一行： ( sh "$0" watch 5 & )

PKG=com.tencent.mm

uid_of() {
    dumpsys package "$PKG" 2>/dev/null | grep -m1 -o 'userId=[0-9]*' | cut -d= -f2
}

unfreeze() {
    uid=$(uid_of)
    [ -z "$uid" ] && return 1
    f=/sys/fs/cgroup/apps/uid_${uid}/cgroup.freeze
    [ -f "$f" ] || return 1
    [ "$(cat "$f" 2>/dev/null)" = "1" ] || return 0
    echo 0 > "$f" 2>/dev/null && echo "unfroze uid=$uid" >&2
}

case "$1" in
    once)  unfreeze ;;
    watch) while :; do unfreeze; sleep "${2:-5}"; done ;;
    *)     echo "usage: $0 {once|watch [interval]}" >&2; exit 1 ;;
esac
