import asyncio
import base64
import json
import logging
import os
import time
from typing import Callable, Optional
import aiohttp
import websockets

logger = logging.getLogger("NyaaVoiceBridge.AstrBot")

class AstrBotClient:
    """OneBot V11 反向 WebSocket 协议客户端，与 AstrBot PixNyaa 对接"""

    def __init__(self, config: dict, on_tts_received: Optional[Callable[[str], None]] = None):
        self.bot_cfg = config.get("astrbot", {})
        self.runtime_cfg = config.get("runtime", {})
        self.ws_url = self.bot_cfg.get("ws_url", "ws://127.0.0.1:6199/ws")
        self.token = self.bot_cfg.get("token", "")
        self.bot_id = self.bot_cfg.get("bot_id", 943653038)
        self.user_id = self.bot_cfg.get("user_id", 10001)
        self.group_id = self.bot_cfg.get("group_id", 0)
        self.is_group = self.bot_cfg.get("is_group", False)
        self.send_voice_flag = self.bot_cfg.get("send_voice_flag", True)
        self.reconnect_interval = self.bot_cfg.get("reconnect_interval", 5)
        self.temp_dir = self.runtime_cfg.get("temp_dir", "/tmp/nyaa_voice_bridge")
        self.on_tts_received = on_tts_received
        self._ws = None
        self._running = False
        self._msg_seq = int(time.time())

    async def connect_loop(self):
        """保持与 AstrBot 的 WebSocket 长连接与心跳"""
        self._running = True
        headers = {}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"

        while self._running:
            try:
                logger.info(f"正在连接 AstrBot: {self.ws_url}")
                async with websockets.connect(self.ws_url, extra_headers=headers) as ws:
                    self._ws = ws
                    logger.info("已成功连接到 AstrBot PixNyaa (OneBot V11)")

                    # 发送生命周期同步事件
                    lifecycle_event = {
                        "time": int(time.time()),
                        "self_id": self.bot_id,
                        "post_type": "meta_event",
                        "meta_event_type": "lifecycle",
                        "sub_type": "connect",
                    }
                    await ws.send(json.dumps(lifecycle_event))

                    # 启动心跳协程
                    heartbeat_task = asyncio.create_task(self._heartbeat_loop())

                    try:
                        async for raw_msg in ws:
                            await self._handle_message(raw_msg)
                    finally:
                        heartbeat_task.cancel()
            except Exception as e:
                logger.warning(f"WebSocket 连接中断/异常: {e}，将在 {self.reconnect_interval} 秒后重连")
                self._ws = None
                await asyncio.sleep(self.reconnect_interval)

    async def _heartbeat_loop(self):
        """定时心跳发送"""
        while self._running and self._ws:
            try:
                hb = {
                    "time": int(time.time()),
                    "self_id": self.bot_id,
                    "post_type": "meta_event",
                    "meta_event_type": "heartbeat",
                    "status": {"online": True, "good": True},
                    "interval": 15000,
                }
                await self._ws.send(json.dumps(hb))
                await asyncio.sleep(15)
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"心跳发送异常: {e}")
                break

    async def _handle_message(self, raw_msg: str):
        """解析 AstrBot 下发的 API 请求与回复消息"""
        try:
            data = json.loads(raw_msg)
        except Exception:
            return

        # 检查是否为 AstrBot 呼叫的发送消息动作
        action = data.get("action")
        params = data.get("params", {})
        echo = data.get("echo")

        if action in ("send_msg", "send_private_msg", "send_group_msg"):
            logger.info(f"收到 AstrBot 回复动作: {action}")
            await self._process_reply(params)

            # 回复 action 成功结果
            if echo and self._ws:
                resp = {
                    "status": "ok",
                    "retcode": 0,
                    "data": {"message_id": int(time.time()*1000)},
                    "echo": echo,
                }
                await self._ws.send(json.dumps(resp))

    async def _process_reply(self, params: dict):
        """从 AstrBot 的回复消息段中提取文本与 TTS 语音"""
        message = params.get("message")
        if not message:
            return

        # message 可能是 list 或 dict/str
        segments = []
        if isinstance(message, list):
            segments = message
        elif isinstance(message, dict):
            segments = [message]

        for seg in segments:
            if not isinstance(seg, dict):
                continue
            seg_type = seg.get("type")
            data = seg.get("data", {})

            # 提取语音消息段
            if seg_type == "record":
                file_info = data.get("file", "")
                logger.info(f"捕获到 PixNyaa TTS 语音段: {file_info[:60]}...")
                audio_path = await self._save_audio_segment(file_info)
                if audio_path and self.on_tts_received:
                    await self.on_tts_received(audio_path)

            elif seg_type == "text":
                text = data.get("text", "")
                logger.info(f"PixNyaa 文本回复: {text}")

    async def _save_audio_segment(self, file_info: str) -> Optional[str]:
        """将语音内容保存到本地临时文件"""
        try:
            target_path = os.path.join(self.temp_dir, f"tts_reply_{int(time.time()*1000)}.wav")
            # 1. Base64 编码格式
            if file_info.startswith("base64://"):
                raw_b64 = file_info[9:]
                with open(target_path, "wb") as f:
                    f.write(base64.b64decode(raw_b64))
                return target_path

            # 2. 本地文件绝对路径
            if file_info.startswith("file://"):
                local_path = file_info[7:]
                if os.path.exists(local_path):
                    return local_path

            # 3. HTTP URL 下载
            if file_info.startswith("http://") or file_info.startswith("https://"):
                async with aiohttp.ClientSession() as session:
                    async with session.get(file_info) as resp:
                        if resp.status == 200:
                            content = await resp.read()
                            with open(target_path, "wb") as f:
                                f.write(content)
                            return target_path

            # 4. 纯本地路径
            if os.path.exists(file_info):
                return file_info

            logger.error(f"无法解析的音频数据格式: {file_info[:50]}")
            return None
        except Exception as e:
            logger.error(f"保存 TTS 音频失败: {e}")
            return None

    async def send_user_message(self, text: str) -> bool:
        """向 AstrBot 发送一条模拟用户消息"""
        if not self._ws:
            logger.error("未连接到 AstrBot，发送失败")
            return False

        self._msg_seq += 1
        msg_id = self._msg_seq

        # 构造消息段
        message_segments = [{"type": "text", "data": {"text": text}}]
        # 附带语音标记
        if self.send_voice_flag:
            message_segments.append({"type": "record", "data": {"file": "voice_input.wav"}})

        event_payload = {
            "time": int(time.time()),
            "self_id": self.bot_id,
            "post_type": "message",
            "message_type": "group" if self.is_group else "private",
            "sub_type": "normal" if self.is_group else "friend",
            "message_id": msg_id,
            "user_id": self.user_id,
            "message": message_segments,
            "raw_message": text,
            "font": 0,
            "sender": {
                "user_id": self.user_id,
                "nickname": "NyaaCaster",
                "card": "",
                "role": "owner" if not self.is_group else "member",
            },
        }

        if self.is_group:
            event_payload["group_id"] = self.group_id

        logger.info(f"向 AstrBot 推送用户消息: user_id={self.user_id}, text='{text}'")
        await self._ws.send(json.dumps(event_payload))
        return True
