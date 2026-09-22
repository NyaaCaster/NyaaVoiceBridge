#!/bin/bash
# 蓝牙音频自愈脚本 —— 确保 NDZ-03-GA 连接并切换到 HFP（双向）
# 由 bt-audio-setup.service 在开机/重连时调用，或由 NyaaVoiceBridge 异常自愈调用
set -u

MAC="${BT_MAC:-00:00:00:00:00:00}"
export XDG_RUNTIME_DIR=/run/user/0
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/0/bus
LOG_TAG="bt-audio-setup"

log() { logger -t "$LOG_TAG" "$*"; echo "[$(date '+%F %T')] $*"; }

wait_for() {  # wait_for <描述> <秒数> <命令...>
    local desc="$1" secs="$2"; shift 2
    local i=0
    while [ $i -lt "$secs" ]; do
        if "$@" >/dev/null 2>&1; then log "OK: $desc"; return 0; fi
        sleep 1; i=$((i+1))
    done
    log "TIMEOUT: $desc (${secs}s)"; return 1
}

log "=== 开始蓝牙音频自愈 ==="

# 1. 等蓝牙控制器就绪
wait_for "蓝牙控制器就绪" 30 bluetoothctl show || exit 1

# 2. 等 PipeWire 就绪
wait_for "PipeWire 就绪" 30 pw-cli info 0 || exit 1

# 3. 确保设备已信任
bluetoothctl trust "$MAC" >/dev/null 2>&1

# 4. 连接（若未连接）
if ! bluetoothctl info "$MAC" 2>/dev/null | grep -q "Connected: yes"; then
    log "设备未连接，尝试连接..."
    for attempt in 1 2 3 4 5; do
        bluetoothctl --timeout 20 connect "$MAC" >/dev/null 2>&1
        if bluetoothctl info "$MAC" 2>/dev/null | grep -q "Connected: yes"; then
            log "连接成功（第 $attempt 次尝试）"; break
        fi
        log "第 $attempt 次连接失败，5 秒后重试..."
        sleep 5
    done
fi

if ! bluetoothctl info "$MAC" 2>/dev/null | grep -q "Connected: yes"; then
    log "ERROR: 无法连接设备，退出"; exit 1
fi

# 5. 等 PipeWire 注册蓝牙设备
sleep 3

# 6. 切换到 HFP（双向：麦克风 + 输出）
DEV_ID=$(wpctl status 2>/dev/null | grep -A200 'Devices:' | grep 'NDZ-03-GA' | grep -oE '^\s*[│ ]*[0-9]+' | grep -oE '[0-9]+' | head -1)
if [ -n "${DEV_ID:-}" ]; then
    log "蓝牙 Device ID = $DEV_ID，切换到 HFP"
    pw-cli set-param "$DEV_ID" Profile '{"index":3,"name":"headset-head-unit"}' >/dev/null 2>&1
    sleep 2
else
    log "WARN: 未找到 NDZ-03-GA 设备 ID"
fi

# 7. 设置默认输出/输入为蓝牙
SINK=$(pw-cli list-objects Node 2>/dev/null | grep -oE 'bluez_output\.[A-F0-9_]+\.1' | head -1)
SRC=$(pw-cli list-objects Node 2>/dev/null | grep -oE 'bluez_input\.[A-F0-9_]+\.0' | head -1)
if [ -n "${SINK:-}" ]; then
    wpctl set-default "$(wpctl status 2>/dev/null | grep -B0 -A0 "$SINK" >/dev/null; echo)" >/dev/null 2>&1
    pw-metadata -n default 0 "default.audio.sink" "{\"name\":\"$SINK\"}" >/dev/null 2>&1
    log "默认输出 -> $SINK"
fi
if [ -n "${SRC:-}" ]; then
    pw-metadata -n default 0 "default.audio.source" "{\"name\":\"$SRC\"}" >/dev/null 2>&1
    log "默认输入 -> $SRC"
fi

# 8. 音量拉满
for id in $(wpctl status 2>/dev/null | grep -E 'NDZ-03-GA' | grep -oE '[0-9]+' | head -4); do
    wpctl set-volume "$id" 1.0 >/dev/null 2>&1
done

log "=== 蓝牙音频自愈完成 ==="
exit 0
