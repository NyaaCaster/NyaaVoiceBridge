#!/bin/bash
# 蓝牙音箱 NDZ-03-GA 健康监控与自动自愈看门狗脚本
# 支持检测断连并调用 bt-audio-setup.sh 进行自愈
LOG_FILE="/var/log/bt-audio-monitor.log"
MAC="${BT_MAC:-00:00:00:00:00:00}"
SETUP_SCRIPT="/usr/local/bin/bt-audio-setup.sh"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

cleanup() {
    log "=== 收到退出信号，停止 NDZ-03-GA 蓝牙音箱自愈看门狗守护进程 (PID: $$) ==="
    exit 0
}

trap cleanup SIGINT SIGTERM SIGHUP EXIT

# 状态记录变量
prev_bt_state=""
prev_sink_state=""
prev_source_state=""
consecutive_heals=0
last_heal_time=0

trigger_auto_heal() {
    local reason="$1"
    local now
    now=$(date +%s)
    # 防抖机制：60秒内最多自愈3次，防止狂暴重试
    if [ $((now - last_heal_time)) -lt 20 ]; then
        log "【自愈抑制】距离上次自愈不足20秒，稍候重试 (原因: $reason)"
        return
    fi

    log "【触发自愈】开始执行 $SETUP_SCRIPT (原因: $reason)..."
    last_heal_time=$now
    if [ -x "$SETUP_SCRIPT" ]; then
        if bash "$SETUP_SCRIPT" >> "$LOG_FILE" 2>&1; then
            log "【自愈成功】蓝牙与音频流恢复正常！"
            consecutive_heals=0
        else
            consecutive_heals=$((consecutive_heals + 1))
            log "【自愈失败】执行返回非零状态码 (连续失败: $consecutive_heals)"
        fi
    else
        log "【错误】未找到可执行自愈脚本: $SETUP_SCRIPT"
    fi
}

while true; do
    export XDG_RUNTIME_DIR=/run/user/0
    export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/0/bus

    # 1. 检查 Bluetoothctl 物理连接
    bt_info=$(bluetoothctl info "$MAC" 2>/dev/null)
    if echo "$bt_info" | grep -q "Connected: yes"; then
        bt_state="CONNECTED"
    else
        bt_state="DISCONNECTED"
    fi

    # 2. 检查 PipeWire Default Sink & Source
    def_sink=$(pw-metadata -n default 2>/dev/null | grep "default.audio.sink" | grep -o 'bluez_output[^"]*')
    def_source=$(pw-metadata -n default 2>/dev/null | grep "default.audio.source" | grep -o 'bluez_input[^"]*')

    # 3. 检查 wpctl 活动拓扑 (* 选中状态)
    wp_sink=$(wpctl status 2>/dev/null | sed -n '/Sinks:/,/Sink endpoints:/p' | grep "\*" | grep "NDZ-03-GA")
    wp_source=$(wpctl status 2>/dev/null | sed -n '/Sources:/,/Source endpoints:/p' | grep "\*" | grep "NDZ-03-GA")

    sink_ok=false
    source_ok=false
    if [ -n "$def_sink" ] && [ -n "$wp_sink" ]; then
        sink_ok=true
    fi
    if [ -n "$def_source" ] && [ -n "$wp_source" ]; then
        source_ok=true
    fi

    # 检测并汇报蓝牙断连
    if [ "$bt_state" != "$prev_bt_state" ]; then
        if [ "$bt_state" = "DISCONNECTED" ]; then
            log "【异常警报】蓝牙音箱 $MAC 物理断开连接！(Connected: no)"
            trigger_auto_heal "蓝牙物理断连"
        elif [ "$bt_state" = "CONNECTED" ]; then
            log "【状态通知】蓝牙音箱 $MAC 物理连接已恢复！(Connected: yes)"
        fi
        prev_bt_state="$bt_state"
    fi

    # 检测并汇报音频路由异常（蓝牙已连但音频流丢失）
    if [ "$bt_state" = "CONNECTED" ]; then
        current_sink_state="OK"
        current_source_state="OK"
        if [ "$sink_ok" = false ]; then
            current_sink_state="FAIL"
        fi
        if [ "$source_ok" = false ]; then
            current_source_state="FAIL"
        fi

        if [ "$current_sink_state" != "$prev_sink_state" ]; then
            if [ "$current_sink_state" = "FAIL" ]; then
                log "【异常警报】蓝牙音箱默认音频输出 Sink 丢失！当前 default.audio.sink 异常或未选中"
                trigger_auto_heal "音频输出 Sink 丢失"
            elif [ "$prev_sink_state" = "FAIL" ] && [ "$current_sink_state" = "OK" ]; then
                log "【状态通知】蓝牙音箱默认音频输出 Sink 已恢复正常 (bluez_output)"
            fi
            prev_sink_state="$current_sink_state"
        fi

        if [ "$current_source_state" != "$prev_source_state" ]; then
            if [ "$current_source_state" = "FAIL" ]; then
                log "【异常警报】蓝牙音箱默认音频输入 Source 丢失！当前 default.audio.source 异常或未选中"
                trigger_auto_heal "音频输入 Source 丢失"
            elif [ "$prev_source_state" = "FAIL" ] && [ "$current_source_state" = "OK" ]; then
                log "【状态通知】蓝牙音箱默认音频输入 Source 已恢复正常 (bluez_input)"
            fi
            prev_source_state="$current_source_state"
        fi
    fi

    sleep 5
done
