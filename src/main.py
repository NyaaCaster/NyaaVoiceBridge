import argparse
import asyncio
import os
import signal
import sys

from src.service import VoiceBridgeService

def parse_args():
    parser = argparse.ArgumentParser(description="NyaaVoiceBridge 语音网桥服务")
    parser.add_argument(
        "--config",
        "-c",
        default="config/config.yaml",
        help="配置文件路径 (默认: config/config.yaml)",
    )
    parser.add_argument(
        "--trigger-once",
        action="store_true",
        help="立即触发一次单轮录音和对话（测试用）",
    )
    parser.add_argument(
        "--duration",
        "-d",
        type=float,
        default=None,
        help="单次录音时长 (秒)",
    )
    return parser.parse_args()

async def main_async(args):
    service = VoiceBridgeService(args.config)

    loop = asyncio.get_running_loop()
    stop_event = asyncio.Event()

    def _sig_handler():
        print("\n接收到终止信号，正在优雅关闭...")
        stop_event.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _sig_handler)
        except NotImplementedError:
            # Windows 平台兼容
            pass

    if args.trigger_once:
        # 单次录音测试模式
        print(">> 启动单次交互测试...")
        ws_task = asyncio.create_task(service.astrbot.connect_loop())
        # 等待 WS 连上
        await asyncio.sleep(1.5)
        await service.trigger_one_dialogue(args.duration)
        # 等待回复播放完成
        while service.is_busy:
            await asyncio.sleep(0.5)
        await asyncio.sleep(1.0)
        ws_task.cancel()
    else:
        # 常驻后台服务模式
        service_task = asyncio.create_task(service.run())
        await stop_event.wait()
        service._running = False
        service_task.cancel()
        try:
            await service_task
        except asyncio.CancelledError:
            pass

def main():
    args = parse_args()
    try:
        asyncio.run(main_async(args))
    except KeyboardInterrupt:
        pass

if __name__ == "__main__":
    main()
