#!/usr/bin/env python3
"""
电梯模拟器 - 模拟电梯硬件 TCP 服务，提供 Web 界面控制
配合 ElevatorForwarder 测试卡住检测和跨楼层重试恢复

用法:
  1. 修改 ElevatorForwarder 的 Config.ELEVATOR_HOST 为 "127.0.0.1"
  2. 启动本模拟器: python3 elevator_sim.py
  3. 启动 ElevatorForwarder
  4. 浏览器打开 http://localhost:8080 控制模拟器
  5. 通过 MQTT 发送选层指令,观察转发器日志中的卡住检测和恢复过程
"""

import socket
import struct
import threading
import time
import json
from http.server import HTTPServer, BaseHTTPRequestHandler

# ==================== 协议常量 ====================
DEVICE_ADDR = 0xA0
TCP_PORT = 20108
WEB_PORT = 8080

FLOOR_MASK = 0x1F
MOVING_UP = 0x20
MOVING_DOWN = 0x40
IN_MOTION = 0x80

STATUS_NORMAL = 0x00
OCCUPY_NONE = 0x00
OCCUPY_SUCCESS = 0x80


# ==================== CRC-8 (多项式 0x07) ====================
def crc8(data: bytes) -> int:
    crc = 0x00
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if (crc & 0x80) else (crc << 1) & 0xFF
    return crc


# ==================== 帧构建 / 解析 ====================
def make_status_frame(floor, in_motion, moving_up, moving_down,
                      is_normal=True, occupy=OCCUPY_NONE):
    data0 = floor & FLOOR_MASK
    if moving_up:
        data0 |= MOVING_UP
    if moving_down:
        data0 |= MOVING_DOWN
    if in_motion:
        data0 |= IN_MOTION
    data1 = STATUS_NORMAL if is_normal else 0xFF
    body = bytes([DEVICE_ADDR, data0, data1, occupy, 0x00])
    return body + bytes([crc8(body)])


def parse_command(data: bytes):
    if len(data) < 5 or data[0] != DEVICE_ADDR:
        return None
    if crc8(data[:4]) != data[4]:
        return None
    d0, d1 = data[1], data[2]
    if d1 == 0x12 and d0 != 0x00:
        return {"type": "select_floor", "floor": d0 & FLOOR_MASK}
    if d1 == 0x12 and d0 == 0x00:
        return {"type": "occupy"}
    if d1 == 0x02:
        return {"type": "release"}
    return {"type": "unknown", "raw": f"{d0:02X} {d1:02X} {data[3]:02X}"}


# ==================== MQTT 发布(纯socket,模拟机器人/平台发指令) ====================
def _encode_len(n):
    out = b""
    while True:
        d = n % 128
        n //= 128
        if n > 0:
            out += bytes([d | 0x80])
        else:
            out += bytes([d])
            return out


def mqtt_publish(host, port, payload, topic="topic-insbot", timeout=3):
    """向 MQTT broker 发布一条 QOS 0 消息(无认证)"""
    sock = socket.create_connection((host, port), timeout=timeout)
    try:
        client_id = "elevator_sim_%d" % (int(time.time() * 1000) % 1000000)
        proto = b"\x00\x04MQTT\x04\x02\x00\x3c" + struct.pack(">H", len(client_id)) + client_id.encode()
        sock.sendall(b"\x10" + bytes([len(proto)]) + proto)
        connack = sock.recv(4)
        if len(connack) < 4 or connack[3] != 0:
            raise RuntimeError("CONNACK 失败: %r" % connack)
        tb = topic.encode()
        pb = payload.encode()
        pkt = b"\x30" + _encode_len(len(tb) + 2 + len(pb)) + struct.pack(">H", len(tb)) + tb + pb
        sock.sendall(pkt)
    finally:
        sock.close()


def mqtt_subscribe_loop(host, port, topic, on_message, timeout=3):
    """订阅 MQTT 主题并持续接收消息(纯socket, QOS0),断线自动重连"""
    while True:
        try:
            sock = socket.create_connection((host, port), timeout=timeout)
            sock.settimeout(10)
            client_id = "elevator_sim_sub_%d" % (int(time.time() * 1000) % 1000000)
            proto = b"\x00\x04MQTT\x04\x02\x00\x3c" + struct.pack(">H", len(client_id)) + client_id.encode()
            sock.sendall(b"\x10" + bytes([len(proto)]) + proto)
            connack = sock.recv(4)
            if len(connack) < 4 or connack[3] != 0:
                raise RuntimeError("CONNACK 失败: %r" % connack)
            tb = topic.encode()
            sub_payload = struct.pack(">H", 1) + struct.pack(">H", len(tb)) + tb + b"\x00"
            sock.sendall(b"\x82" + _encode_len(len(sub_payload)) + sub_payload)
            suback = sock.recv(5)
            if len(suback) < 5 or suback[0] != 0x90:
                raise RuntimeError("SUBACK 失败: %r" % suback)
            last_ping = time.time()
            buf = b""
            while True:
                try:
                    data = sock.recv(1024)
                except socket.timeout:
                    if time.time() - last_ping > 20:
                        sock.sendall(b"\xc0\x00")
                        last_ping = time.time()
                    continue
                if not data:
                    break
                buf += data
                while len(buf) >= 2:
                    pkt_type = buf[0] >> 4
                    idx = 1
                    multiplier = 1
                    remaining = 0
                    while idx < len(buf):
                        byte = buf[idx]
                        remaining += (byte & 0x7F) * multiplier
                        multiplier *= 128
                        idx += 1
                        if not (byte & 0x80):
                            break
                    total = idx + remaining
                    if len(buf) < total:
                        break
                    pkt = buf[:total]
                    buf = buf[total:]
                    if pkt_type == 3:  # PUBLISH
                        tl = struct.unpack(">H", pkt[idx:idx + 2])[0]
                        topic_name = pkt[idx + 2:idx + 2 + tl].decode(errors="replace")
                        payload = pkt[idx + 2 + tl:].decode(errors="replace")
                        on_message(topic_name, payload)
                if time.time() - last_ping > 20:
                    sock.sendall(b"\xc0\x00")
                    last_ping = time.time()
        except Exception:
            time.sleep(3)


# ==================== 电梯状态 ====================
class ElevatorState:
    def __init__(self):
        self.lock = threading.Lock()
        self.floor = 1
        self.target_floor = 0
        self.in_motion = False
        self.moving_up = False
        self.moving_down = False
        self.is_normal = True
        self.occupy = OCCUPY_NONE
        self.mode = "auto"
        self.stuck = False
        self.last_move_time = 0
        self.move_interval = 10.0
        # 到位延迟: 到达目标楼层后保持原运动状态延迟一段时间,才真正平层
        self.leveling = False
        self.leveling_delay = 3.0
        self.leveling_until = 0
        self.logs = []
        self.client_connected = False
        # MQTT 模拟发送配置(配合转发器测试)
        self.mqtt_host = "192.168.10.94"
        self.mqtt_port = 1883
        self.mqtt_user_id = "sim-user"
        self.mqtt_user_name = "模拟user"
        # 最近一次来自转发器(服务)的指令提示,用于页面 toast 展示
        self.last_service_msg = None
        self.service_msg_seq = 0

    def set_mqtt_broker(self, host, port):
        with self.lock:
            self.mqtt_host = host
            self.mqtt_port = port
            self.add_log(f"MQTT broker 设置为 {host}:{port}")

    def mqtt_send(self, action, value):
        """通过 MQTT 发送电梯控制指令给转发器(模拟机器人/平台)"""
        now_ms = int(time.time() * 1000)
        payload = json.dumps({
            "type": "ELEVATOR_CONTROL",
            "action": action,
            "source": "elevator_sim",
            "target": "elevator_proxy",
            "id": now_ms % 2147483647,
            "timeStamp": now_ms,
            "value": json.dumps(value, ensure_ascii=False),
        }, ensure_ascii=False)
        try:
            mqtt_publish(self.mqtt_host, self.mqtt_port, payload)
            self.add_log(f"MQTT发送 {action} -> {self.mqtt_host}:{self.mqtt_port}")
        except Exception as e:
            self.add_log(f"MQTT发送失败 {action}: {e}")

    def start_mqtt_subscribe(self):
        """启动 MQTT 订阅线程,接收转发器返回的 RESULT 消息"""
        threading.Thread(target=self._mqtt_sub_worker, daemon=True).start()

    def _mqtt_sub_worker(self):
        def on_message(topic, payload):
            try:
                msg = json.loads(payload)
                if msg.get("type") != "RESULT":
                    return
                action = msg.get("action", "")
                original_action = msg.get("originalAction", "")
                value = msg.get("value", "")
                action_cn = {
                    "OCCUPY_ELEVATOR": "独占",
                    "SELECT_FLOORS": "选层",
                    "RELEASE_ELEVATOR": "释放",
                }.get(original_action, original_action)
                if action == "RESULT_SUCCESS":
                    text = f"服务返回成功: {action_cn} {value}"
                else:
                    text = f"服务返回失败: {action_cn} {value}"
                with self.lock:
                    self.last_service_msg = text.strip()
                    self.service_msg_seq += 1
                self.add_log(f"MQTT收到 {action} ({original_action})")
            except Exception:
                pass
        while True:
            try:
                mqtt_subscribe_loop(self.mqtt_host, self.mqtt_port, "topic-insbot", on_message)
            except Exception:
                time.sleep(3)

    def add_log(self, msg):
        ts = time.strftime("%H:%M:%S")
        self.logs.append(f"[{ts}] {msg}")
        self.logs = self.logs[-100:]

    def clear_logs(self):
        with self.lock:
            self.logs = []
        self.add_log("已清空日志")

    def status_str(self):
        if self.stuck:
            return "卡住(运动中)"
        if self.moving_up:
            return "上行中"
        if self.moving_down:
            return "下行中"
        if self.in_motion:
            return "运动中"
        return "平层"

    def to_dict(self):
        with self.lock:
            return {
                "floor": self.floor,
                "target_floor": self.target_floor,
                "in_motion": self.in_motion,
                "moving_up": self.moving_up,
                "moving_down": self.moving_down,
                "is_normal": self.is_normal,
                "occupy": self.occupy == OCCUPY_SUCCESS,
                "mode": self.mode,
                "stuck": self.stuck,
                "status": self.status_str(),
                "connected": self.client_connected,
                "move_interval": self.move_interval,
                "leveling_delay": self.leveling_delay,
                "leveling": self.leveling,
                "logs": self.logs,
                "mqtt_host": self.mqtt_host,
                "mqtt_port": self.mqtt_port,
                "user_name": self.mqtt_user_name,
                "service_msg": self.last_service_msg,
                "service_msg_seq": self.service_msg_seq,
            }

    def select_floor(self, floor):
        """Web界面选层: 触发逐层运动(和收到TCP指令效果一致)"""
        self.handle_command({"type": "select_floor", "floor": floor})

    def sim_occupy(self):
        """Web界面模拟独占: 与收到TCP独占指令效果一致"""
        self.handle_command({"type": "occupy"})

    def sim_release(self):
        """Web界面模拟释放独占: 与收到TCP释放指令效果一致"""
        self.handle_command({"type": "release"})

    def set_floor(self, floor):
        with self.lock:
            self.floor = floor
            self.in_motion = False
            self.moving_up = False
            self.moving_down = False
            self.stuck = False
            self.leveling = False
            self.target_floor = 0
            self.add_log(f"手动设置楼层: {floor}F")

    def set_stuck(self):
        with self.lock:
            self.stuck = True
            self.in_motion = True
            self.moving_up = False
            self.moving_down = False
            self.leveling = False
            self.add_log(f"*** 模拟卡住 *** 楼层{self.floor}F,运动中但楼层不变")

    def resume(self):
        with self.lock:
            self.stuck = False
            self.in_motion = False
            self.moving_up = False
            self.moving_down = False
            self.leveling = False
            self.target_floor = 0
            self.add_log("恢复手动控制,已停止")

    def set_mode(self, mode):
        with self.lock:
            self.mode = mode
            self.add_log(f"模式切换: {'自动' if mode == 'auto' else '手动'}")

    def set_move_interval(self, seconds):
        with self.lock:
            self.move_interval = max(0.5, min(30.0, seconds))
            self.add_log(f"每层运动时间设置为 {self.move_interval}秒")

    def set_leveling_delay(self, seconds):
        with self.lock:
            self.leveling_delay = max(0.0, min(15.0, seconds))
            self.add_log(f"到位延迟设置为 {self.leveling_delay}秒")

    def set_user_name(self, name):
        with self.lock:
            name = name.strip()
            if name:
                self.mqtt_user_name = name
            self.add_log(f"用户名设置为: {self.mqtt_user_name}")

    def handle_command(self, cmd, source="web"):
        with self.lock:
            # 来自转发器的 TCP 指令不携带用户名,标注为"服务";Web 面板模拟操作带上当前用户名
            actor = "" if source == "tcp" else self.mqtt_user_name
            if cmd["type"] == "select_floor":
                floor = cmd["floor"]
                self.target_floor = floor
                if self.stuck:
                    self.stuck = False
                    self.add_log(f"卡住状态收到选层指令 {floor}F,恢复运动 {actor}".rstrip())
                else:
                    self.add_log(f"收到选层指令: {floor}F {actor}".rstrip())
                if source == "tcp":
                    self.add_log("收到服务指令: 选层")
                if self.mode == "auto":
                    self._start_moving(floor)
            elif cmd["type"] == "occupy":
                self.occupy = OCCUPY_SUCCESS
                self.add_log(f"收到独占指令 -> 已独占 {actor}".rstrip())
                if source == "tcp":
                    self.add_log("收到服务指令: 独占")
            elif cmd["type"] == "release":
                self.occupy = OCCUPY_NONE
                self.add_log(f"收到释放独占指令 {actor}".rstrip())
                if source == "tcp":
                    self.add_log("收到服务指令: 释放独占")

    def _start_moving(self, target):
        if target > self.floor:
            self.moving_up = True
            self.moving_down = False
            self.in_motion = True
            self.add_log(f"开始上行: {self.floor}F -> {target}F")
        elif target < self.floor:
            self.moving_up = False
            self.moving_down = True
            self.in_motion = True
            self.add_log(f"开始下行: {self.floor}F -> {target}F")
        else:
            self.in_motion = False
            self.add_log(f"已在目标楼层 {target}F")
        self.last_move_time = time.time()

    def update(self):
        with self.lock:
            # 到位延迟阶段: 保持原上/下行与运动中状态,延迟后才真正平层
            if self.leveling:
                if time.time() >= self.leveling_until:
                    self._finish_level()
                return

            if self.stuck or self.mode != "auto" or not self.in_motion:
                return
            if self.target_floor <= 0:
                return
            now = time.time()
            if now - self.last_move_time < self.move_interval:
                return
            self.last_move_time = now
            if self.moving_up and self.floor < self.target_floor:
                self.floor += 1
                if self.floor >= self.target_floor:
                    self._arrive()
                else:
                    self.add_log(f"上行经过 {self.floor}F")
            elif self.moving_down and self.floor > self.target_floor:
                self.floor -= 1
                if self.floor <= self.target_floor:
                    self._arrive()
                else:
                    self.add_log(f"下行经过 {self.floor}F")

    def _arrive(self):
        """到达目标楼层: 保持原上/下行与运动中状态,等待到位延迟后才真正平层"""
        self.floor = self.target_floor
        self.in_motion = True       # 保持运动中
        # moving_up / moving_down 保持不变(仍显示上行中/下行中)
        self.leveling = True
        self.leveling_until = time.time() + self.leveling_delay
        if self.leveling_delay > 0:
            self.add_log(f"到达目标楼层 {self.floor}F,到位校准中...")
        else:
            self._finish_level()

    def _finish_level(self):
        """到位延迟结束: 真正平层"""
        self.leveling = False
        self.in_motion = False
        self.moving_up = False
        self.moving_down = False
        self.add_log(f"平层完成 {self.floor}F")


# ==================== 独立运动更新线程 ====================
class ElevatorUpdateThread(threading.Thread):
    """独立线程: 每500ms调用update(),驱动电梯逐层运动。
    不依赖TCP连接,Web界面也能看到运动过程。"""
    def __init__(self, state):
        super().__init__(daemon=True)
        self.state = state

    def run(self):
        while True:
            self.state.update()
            time.sleep(0.5)


# ==================== TCP 电梯模拟服务 ====================
class ElevatorTcpServer(threading.Thread):
    def __init__(self, state, port=TCP_PORT):
        super().__init__(daemon=True)
        self.state = state
        self.port = port

    def run(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("0.0.0.0", self.port))
        srv.listen(1)
        print(f"[模拟器] TCP 电梯服务: 0.0.0.0:{self.port}")

        while True:
            try:
                sock, addr = srv.accept()
                with self.state.lock:
                    self.state.client_connected = True
                self.state.add_log(f"转发器已连接: {addr[0]}:{addr[1]}")
                print(f"[模拟器] 转发器已连接: {addr}")
                self._handle(sock)
            except Exception as e:
                print(f"[模拟器] accept 异常: {e}")
            finally:
                with self.state.lock:
                    self.state.client_connected = False

    def _handle(self, sock):
        threading.Thread(target=self._recv, args=(sock,), daemon=True).start()
        while True:
            try:
                # 只负责广播状态帧,运动逻辑由独立线程驱动
                frame = make_status_frame(
                    floor=self.state.floor,
                    in_motion=self.state.in_motion,
                    moving_up=self.state.moving_up,
                    moving_down=self.state.moving_down,
                    is_normal=self.state.is_normal,
                    occupy=self.state.occupy,
                )
                sock.sendall(frame)
                time.sleep(1.0)
            except Exception:
                self.state.add_log("转发器连接断开")
                break

    def _recv(self, sock):
        buf = b""
        while True:
            try:
                data = sock.recv(1024)
                if not data:
                    break
                buf += data
                while len(buf) >= 5:
                    cmd = parse_command(buf[:5])
                    buf = buf[5:]
                    if cmd:
                        self.state.handle_command(cmd, source="tcp")
            except Exception:
                break


# ==================== Web 服务 ====================
class WebHandler(BaseHTTPRequestHandler):
    state: ElevatorState = None

    def do_GET(self):
        if self.path == "/" or self.path == "/index.html":
            self._send_html(HTML_PAGE)
        elif self.path == "/state":
            self._send_json(self.state.to_dict())
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path != "/control":
            self.send_response(404)
            self.end_headers()
            return
        try:
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            data = json.loads(body)
            action = data.get("action")
            if action == "select_floor":
                self.state.select_floor(int(data["floor"]))
            elif action == "set_floor":
                self.state.set_floor(int(data["floor"]))
            elif action == "set_stuck":
                self.state.set_stuck()
            elif action == "occupy":
                self.state.sim_occupy()
            elif action == "release":
                self.state.sim_release()
            elif action == "mqtt_occupy":
                self.state.mqtt_send("OCCUPY_ELEVATOR",
                                     {"userId": self.state.mqtt_user_id, "userName": self.state.mqtt_user_name})
            elif action == "mqtt_select":
                self.state.mqtt_send("SELECT_FLOORS",
                                     {"userId": self.state.mqtt_user_id, "targetFloor": int(data["floor"])})
            elif action == "mqtt_release":
                self.state.mqtt_send("RELEASE_ELEVATOR", {"userId": self.state.mqtt_user_id})
            elif action == "set_mqtt_broker":
                self.state.set_mqtt_broker(data["host"], int(data["port"]))
            elif action == "resume":
                self.state.resume()
            elif action == "set_mode":
                self.state.set_mode(data["mode"])
            elif action == "set_move_interval":
                self.state.set_move_interval(float(data["seconds"]))
            elif action == "set_leveling_delay":
                self.state.set_leveling_delay(float(data["seconds"]))
            elif action == "clear_logs":
                self.state.clear_logs()
            elif action == "set_user_name":
                self.state.set_user_name(data.get("name", ""))
            self._send_json({"ok": True})
        except Exception as e:
            self._send_json({"error": str(e)}, 400)

    def _send_html(self, content):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(content.encode("utf-8"))

    def _send_json(self, obj, code=200):
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.end_headers()
        self.wfile.write(json.dumps(obj).encode("utf-8"))

    def log_message(self, *args):
        pass


# ==================== Web 页面 ====================
HTML_PAGE = r"""<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>电梯模拟器 · ELEVATOR CONSOLE</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Chakra+Petch:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500;600&display=swap" rel="stylesheet">
<style>
:root{
  --bg:#070a10; --bg2:#0b0f17;
  --panel:#0e131c; --panel2:#121826; --panel3:#0a0f17;
  --line:#1c2636; --line2:#26324a;
  --txt:#dbe4f0; --muted:#72809a; --faint:#4a5568;
  --cyan:#37c6ff; --green:#2fe6a7; --amber:#ffb454; --red:#ff4d5e;
  --gap:14px;
  --font-disp:"Chakra Petch",sans-serif;
  --font-mono:"IBM Plex Mono",monospace;
}
*{margin:0;padding:0;box-sizing:border-box}
html,body{min-height:100%}
body{
  font-family:var(--font-mono);color:var(--txt);
  background:
    radial-gradient(1200px 600px at 80% -10%, rgba(55,198,255,.06), transparent 60%),
    radial-gradient(900px 500px at 0% 100%, rgba(47,230,167,.05), transparent 55%),
    var(--bg);
  background-attachment:fixed;
  padding:18px;
}
.wrap{
  width:100%;max-width:1800px;margin:0 auto;
  display:grid;grid-template-columns:repeat(auto-fit,minmax(330px,1fr));
  gap:var(--gap);align-items:start;
}

/* ---------- 顶栏 ---------- */
.topbar{grid-column:1/-1;display:flex;align-items:center;justify-content:space-between;
  gap:16px;flex-wrap:wrap;padding:4px 2px 10px}
.brand{display:flex;align-items:baseline;gap:14px}
.brand h1{font-family:var(--font-disp);font-size:21px;font-weight:600;letter-spacing:.5px}
.brand h1 b{color:var(--cyan);font-weight:700}
.eyebrow{font-size:10px;letter-spacing:3px;color:var(--faint);text-transform:uppercase}
.chips{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
.chip{display:inline-flex;align-items:center;gap:7px;padding:6px 12px;font-size:12px;
  border:1px solid var(--line);border-radius:30px;background:var(--panel2);color:var(--muted)}
.chip .find{color:var(--txt);font-weight:500}
.dot{width:8px;height:8px;border-radius:50%;flex:none}
.dot.on{background:var(--green);box-shadow:0 0 8px var(--green)}
.dot.off{background:var(--faint)}
.dot.own{background:var(--amber);box-shadow:0 0 8px var(--amber)}

/* ---------- 状态驱动主色 ---------- */
.card{position:relative;background:linear-gradient(180deg,var(--panel),var(--bg2));
  border:1px solid var(--line);border-radius:14px;padding:16px;
  box-shadow:0 8px 30px rgba(0,0,0,.35);
  overflow:hidden}
.card::before{content:"";position:absolute;inset:0 0 auto 0;height:2px;
  background:var(--line2);opacity:.6}
.card.amb::before{background:var(--amber)}
.card.grn::before{background:var(--green)}
.card.red::before{background:var(--red)}
.card-title{font-family:var(--font-disp);font-size:11px;letter-spacing:2.5px;text-transform:uppercase;
  color:var(--faint);margin-bottom:12px;display:flex;align-items:center;gap:8px}
.card-title::after{content:"";flex:1;height:1px;background:var(--line)}

/* ---------- 状态 HERO ---------- */
.sthero{grid-column:span 2;display:grid;grid-template-columns:minmax(150px,210px) 1fr;gap:20px}
.floor-readout{display:flex;flex-direction:column;justify-content:center;align-items:flex-start;gap:8px}
.floor-num{font-family:var(--font-disp);font-size:92px;font-weight:700;line-height:.9;
  letter-spacing:-2px;text-shadow:0 0 30px rgba(55,198,255,.25);transition:color .3s}
.floor-num small{font-size:26px;font-weight:500;color:var(--muted);letter-spacing:0}
.floor-unit{font-size:12px;color:var(--faint);letter-spacing:2px;text-transform:uppercase}
.motion-row{display:flex;align-items:center;gap:10px}
.dir{font-family:var(--font-disp);font-size:26px;line-height:1;transition:color .3s}
.badge{padding:4px 12px;border-radius:20px;font-size:12px;font-weight:500;letter-spacing:1px}
.badge.grn{background:rgba(47,230,167,.12);color:var(--green);border:1px solid rgba(47,230,167,.35)}
.badge.amb{background:rgba(255,180,84,.12);color:var(--amber);border:1px solid rgba(255,180,84,.35)}
.badge.red{background:rgba(255,77,94,.12);color:var(--red);border:1px solid rgba(255,77,94,.4)}
.badge.cyan{background:rgba(55,198,255,.12);color:var(--cyan);border:1px solid rgba(55,198,255,.3)}
.stat-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:10px;margin-top:16px}
.stat{background:var(--panel3);border:1px solid var(--line);border-radius:10px;padding:10px 12px}
.stat .k{font-size:10px;letter-spacing:1.5px;color:var(--faint);text-transform:uppercase;margin-bottom:5px}
.stat .v{font-family:var(--font-disp);font-size:18px;font-weight:600}
.stat .v small{font-size:12px;color:var(--muted);font-weight:500}
.warn-banner{margin-top:14px;display:none;align-items:center;gap:10px;padding:10px 14px;border-radius:10px;
  background:rgba(255,77,94,.1);border:1px solid rgba(255,77,94,.4);color:var(--red);
  font-size:13px;font-weight:500;animation:pulse 1.6s ease-in-out infinite}
.warn-banner.show{display:flex}
@keyframes pulse{0%,100%{box-shadow:0 0 0 0 rgba(255,77,94,.35)}50%{box-shadow:0 0 0 8px rgba(255,77,94,0)}}

/* ---------- 楼层竖井 ---------- */
.shaft{height:210px;display:flex;gap:0;position:relative;
  background:repeating-linear-gradient(0deg,transparent,transparent 25%,rgba(38,50,74,.25) 25%,rgba(38,50,74,.25) 26%);
  border-left:1px solid var(--line);border-right:1px solid var(--line)}
.shaft .rail{width:10px;background:linear-gradient(180deg,#1a2333,#0c111b);border-left:1px solid var(--line2);border-right:1px solid var(--line)}
.floors{flex:1;display:flex;flex-direction:column;position:relative;min-width:0}
.floors .f{flex:1;position:relative;display:flex;align-items:center;padding-left:14px;border-bottom:1px dashed var(--line)}
.floors .f:last-child{border-bottom:none}
.floors .f .lbl{font-family:var(--font-disp);font-size:14px;color:var(--muted);letter-spacing:1px}
.floors .f .lbl b{color:var(--faint);font-weight:500;font-size:11px}
.floors .f.current{background:linear-gradient(90deg,rgba(55,198,255,.16),transparent)}
.floors .f.current .lbl{color:var(--cyan);font-weight:700}
.floors .f.current .target-marker{position:absolute;right:10px;font-size:11px;letter-spacing:1px;color:var(--cyan)}
.floors .f.goal{outline:1px solid rgba(255,180,84,.55);outline-offset:-1px;background:rgba(255,180,84,.06)}
.floors .f.goal .lbl{color:var(--amber)}
.floors .car{position:absolute;right:14px;transition:top .5s cubic-bezier(.4,0,.2,1);
  width:34px;height:34px;border-radius:8px;background:linear-gradient(160deg,var(--cyan),#0f7fb3);
  box-shadow:0 0 16px rgba(55,198,255,.5)}
.floors .car::after{content:"▤";position:absolute;inset:0;display:flex;align-items:center;justify-content:center;
  color:#03141f;font-size:13px;font-weight:700}
.floors .car.stuck{background:linear-gradient(160deg,#ff5a68,#b3122a);box-shadow:0 0 18px rgba(255,77,94,.6)}
.floors .car.level{background:linear-gradient(160deg,#3bf5ae,#0f9967);box-shadow:0 0 16px rgba(47,230,167,.5)}

/* ---------- 操作面板 ---------- */
.pgrid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px}
.pane{background:var(--panel3);border:1px solid var(--line);border-radius:12px;padding:12px}
.pane-title{font-size:10px;letter-spacing:2px;color:var(--faint);text-transform:uppercase;margin-bottom:10px}
.btn{font-family:var(--font-mono);padding:10px 14px;font-size:13px;color:var(--txt);
  background:linear-gradient(180deg,#182235,#111725);border:1px solid var(--line2);border-radius:9px;cursor:pointer;
  transition:.15s;user-select:none}
.btn:hover{border-color:var(--cyan);color:var(--cyan);transform:translateY(-1px)}
.btn:active{transform:scale(.97)}
.btn.primary{background:linear-gradient(180deg,#0e7fae,#0a5b83);border-color:transparent;color:#eaf7ff;font-weight:600}
.btn.primary:hover{box-shadow:0 0 18px rgba(55,198,255,.35)}
.btn.good{background:linear-gradient(180deg,#158f6b,#0d6b50);border-color:transparent;color:#eafff7;font-weight:600}
.btn.good:hover{box-shadow:0 0 18px rgba(47,230,167,.32)}
.btn.danger{background:linear-gradient(180deg,#c22330,#8d1220);border-color:transparent;color:#ffe7e9;font-weight:600}
.btn.danger:hover{box-shadow:0 0 18px rgba(255,77,94,.4)}
.btn.ghost{background:transparent;border-color:var(--line2)}
.btn.small{padding:7px 10px;font-size:12px;border-radius:8px}
.btn:disabled{opacity:.4;cursor:not-allowed}
.btn-row{display:flex;flex-wrap:wrap;gap:8px}
.floor-btn{flex:1;min-width:52px;padding:12px 4px;font-family:var(--font-disp);font-size:17px;font-weight:600;
  border:1px solid var(--line2);border-radius:9px;background:var(--panel2);color:var(--txt);cursor:pointer;transition:.15s}
.floor-btn:hover{border-color:var(--cyan);color:var(--cyan);box-shadow:0 0 12px rgba(55,198,255,.2)}
.floor-btn:active{transform:scale(.95)}
.field{display:flex;align-items:center;gap:8px;font-size:12px;color:var(--muted)}
.field input{width:64px;padding:7px 8px;background:var(--panel3);color:var(--txt);
  border:1px solid var(--line2);border-radius:8px;font-family:var(--font-mono);font-size:13px;text-align:center}
.seg{display:inline-flex;border:1px solid var(--line2);border-radius:9px;overflow:hidden}
.seg .btn{border:none;border-radius:0}
.seg .btn.on{background:var(--cyan);color:#04131f;font-weight:600}

/* ---------- 日志 ---------- */
.logs{grid-column:1/-1}
.log-head{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.log-head input{width:130px;padding:7px 10px;background:var(--panel3);color:var(--txt);
  border:1px solid var(--line2);border-radius:8px;font-family:var(--font-mono);font-size:12px}
.log-head input:focus{outline:none;border-color:var(--cyan)}
.log-box{margin-top:12px;background:var(--panel3);border:1px solid var(--line);border-radius:10px;
  padding:10px 12px;height:260px;overflow-y:auto;font-size:12px;line-height:1.75}
.log-line{white-space:pre-wrap;word-break:break-all;border-bottom:1px solid rgba(28,38,54,.4)}
.log-cmd{color:var(--amber)} .log-stuck{color:var(--red);font-weight:600}
.log-arrive{color:var(--green)} .log-info{color:#8b99b3}
.log-box::-webkit-scrollbar{width:8px}
.log-box::-webkit-scrollbar-thumb{background:var(--line2);border-radius:8px}

/* ---------- toast ---------- */
#toast{position:fixed;top:18px;left:50%;transform:translateX(-50%) translateY(-90px);
  background:linear-gradient(180deg,#0e7fae,#0a5b83);color:#fff;padding:11px 22px;border-radius:10px;
  font-size:13px;font-weight:500;box-shadow:0 8px 24px rgba(0,0,0,.5);z-index:999;opacity:0;
  transition:transform .3s,opacity .3s;pointer-events:none;max-width:90vw;text-align:center}
#toast.show{transform:translateX(-50%) translateY(0);opacity:1}

@media (max-width:760px){
  .sthero{grid-template-columns:1fr}
  .floor-num{font-size:64px}
}
</style>
</head>
<body>
<div id="toast"></div>
<div class="wrap">

  <!-- 顶栏 -->
  <div class="topbar">
    <div class="brand">
      <div>
        <div class="eyebrow">Elevator Hardware Simulator</div>
        <h1>电梯<b>模拟器</b></h1>
      </div>
    </div>
    <div class="chips">
      <span class="chip"><span class="dot" id="connDot"></span>转发器<span class="find" id="connTxt">未连接</span></span>
      <span class="chip">TCP <span class="find">:20108</span></span>
      <span class="chip">状态帧 <span class="find">1Hz</span></span>
      <span class="chip">独占 <span class="dot off" id="occDot"></span><span class="find" id="occChip">未占用</span></span>
      <span class="chip">操作员 <span class="find" id="userChip">-</span></span>
    </div>
  </div>

  <!-- 状态 HERO -->
  <div class="card sthero grn" id="statusCard">
    <div class="shaft" id="shaft"></div>
    <div>
      <div class="card-title">运行状态 / Live Telemetry</div>
      <div class="floor-readout">
        <div class="motion-row"><span class="dir" id="dir">●</span><span class="badge grn" id="badge">平层</span></div>
        <div class="floor-num"><span id="floorNum">1</span><small>F</small></div>
        <div class="floor-unit">当前位置 · tube position</div>
      </div>
      <div class="stat-grid">
        <div class="stat"><div class="k">目标楼层</div><div class="v" id="target">-</div></div>
        <div class="stat"><div class="k">运转模式</div><div class="v" id="mode">自动</div></div>
        <div class="stat"><div class="k">通讯</div><div class="v" id="chan">-</div></div>
      </div>
      <div class="warn-banner" id="warn"><span>STUCK</span> 电梯已卡住,运动中但未到站 —— 等待转发器检测并恢复</div>
    </div>
  </div>

  <!-- 选层 -->
  <div class="card">
    <div class="card-title">选层 · Dispatch</div>
    <div class="pane">
      <div class="pane-title">目的地(逐层运动)</div>
      <div class="btn-row" id="destRow"></div>
    </div>
    <div class="pane" style="margin-top:12px">
      <div class="pane-title">手动跳转 · Teleport</div>
      <div class="btn-row" id="tpRow"></div>
    </div>
  </div>

  <!-- 独占 / 卡住 / 参数 -->
  <div class="card">
    <div class="card-title">调度控制 · Control</div>
    <div class="pane">
      <div class="pane-title">独占占用</div>
      <div class="btn-row">
        <button class="btn good" style="flex:1" onclick="doOccupy()">模拟独占</button>
        <button class="btn ghost" style="flex:1" onclick="doRelease()">释放独占</button>
      </div>
    </div>
    <div class="pane" style="margin-top:12px">
      <div class="pane-title">异常注入 / 走行参数</div>
      <div class="btn-row">
        <button class="btn danger" style="flex:1" onclick="doStuck()">模拟卡住</button>
        <button class="btn ghost" style="flex:1" onclick="doResume()">恢复 / 停止</button>
      </div>
      <div style="display:flex;flex-wrap:wrap;gap:14px;margin-top:14px">
        <div class="field">每层<select id="moveInterval" onchange="setMoveInterval()">
          <option>0.5</option><option>1</option><option>2</option><option>3</option>
          <option>5</option><option>10</option><option>20</option><option>30</option>
        </select>秒</div>
        <div class="field">到位<select id="levelingDelay" onchange="setLevelingDelay()">
          <option>0</option><option>1</option><option>2</option><option>3</option>
          <option>5</option><option>8</option><option>10</option>
        </select>秒</div>
      </div>
    </div>
    <div class="pane" style="margin-top:12px">
      <div class="pane-title">运转模式</div>
      <div class="seg" id="modeSeg">
        <button class="btn on" data-mode="auto" onclick="setMode('auto')">自动</button>
        <button class="btn" data-mode="manual" onclick="setMode('manual')">手动</button>
      </div>
    </div>
  </div>

  <!-- 机器人通道 MQTT -->
  <div class="card">
    <div class="card-title">机器人通道 · MQTT</div>
    <div class="pane">
      <div class="pane-title">身份</div>
      <div class="field" style="margin-bottom:10px">
        操作员<input type="text" id="mUserName" placeholder="模拟user" class="usr">
        <button class="btn small ghost" onclick="setUserName()">设置</button>
      </div>
      <div class="field">
        Broker
        <input type="text" id="mqttHost" style="width:110px">
        <span>:</span>
        <input type="text" id="mqttPort" style="width:64px">
        <button class="btn small ghost" onclick="setMqttBroker()">设置</button>
      </div>
    </div>
    <div class="pane" style="margin-top:12px">
      <div class="pane-title">指令下发(经转发器)</div>
      <div class="btn-row">
        <button class="btn good" style="flex:1" onclick="mqttOccupy()">MQTT 占用</button>
        <button class="btn ghost" style="flex:1" onclick="mqttRelease()">MQTT 释放</button>
      </div>
      <div class="pane-title" style="margin-top:12px">MQTT 选层</div>
      <div class="btn-row" id="mqRow"></div>
    </div>
  </div>

  <!-- 日志 -->
  <div class="card logs">
    <div class="card-title">诊断日志 · Event Log
      <span class="log-head" style="margin-left:auto">
        <input type="text" id="filterInclude" placeholder="包含…" oninput="renderLogs()">
        <input type="text" id="filterExclude" placeholder="排除…" oninput="renderLogs()">
        <button class="btn small ghost" onclick="clearLogs()">清空</button>
      </span>
    </div>
    <div class="log-box" id="logBox"></div>
  </div>

</div>

<script>
const MAXFLOOR=5;
function post(body){return fetch('/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});}

/* 动态按钮组 */
function buildRows(){
  const dest=[],tp=[],mq=[];
  for(let f=1;f<=MAXFLOOR;f++){
    dest.push('<button class="floor-btn" onclick="doDest('+f+')">'+f+'F</button>');
    tp.push('<button class="floor-btn" onclick="doTp('+f+')">'+f+'F</button>');
    mq.push('<button class="floor-btn" onclick="mqttSelect('+f+')">'+f+'F</button>');
  }
  document.getElementById('destRow').innerHTML=dest.join('');
  document.getElementById('tpRow').innerHTML=tp.join('');
  document.getElementById('mqRow').innerHTML=mq.join('');
}

/* 竖井可视化 */
function renderShaft(s){
  const box=document.getElementById('shaft');
  let rows='';
  for(let f=MAXFLOOR;f>=1;f--){
    const cur=f===s.floor?' current':'';
    const goal=(s.target_floor>0&&s.target_floor===f)?' goal':'';
    const marker=(s.target_floor===f)?'<span class="target-marker">TARGET</span>':'';
    rows+='<div class="f'+cur+goal+'"><span class="lbl">'+f+'F<b>&nbsp;FL'+f+'</b></span>'+marker+'</div>';
  }
  box.innerHTML='<div class="rail"></div><div class="floors">'+rows+
    '<div class="car '+(s.stuck?'stuck':'')+'" id="car"></div></div>';
  const floorsWrap=box.querySelector('.floors');
  const idx=MAXFLOOR-s.floor; // 顶部为最高层
  const h=floorsWrap.clientHeight/MAXFLOOR;
  const car=box.querySelector('.car');
  car.style.top=(idx*h+(h-34)/2)+'px';
}

/* 状态轮询 */
let lastSeq=-1,toastTimer=null,myLogs=[];
function showToast(m){const t=document.getElementById('toast');t.textContent=m;t.classList.add('show');
  clearTimeout(toastTimer);toastTimer=setTimeout(()=>t.classList.remove('show'),4000);}

function poll(){
  fetch('/state').then(r=>r.json()).then(s=>{
    document.getElementById('floorNum').textContent=s.floor;
    document.getElementById('target').textContent=s.target_floor?s.target_floor+'F':'—';
    document.getElementById('mode').textContent=s.mode==='auto'?'自动':'手动';
    document.getElementById('chan').textContent=s.stuck?'卡住':(s.in_motion?'运动中':'就绪');
    document.getElementById('userChip').textContent=s.user_name||'-';
    document.getElementById('occChip').textContent=s.occupy?'已占用':'未占用';

    /* 连接点 */
    const cd=document.getElementById('connDot');
    cd.className='dot '+(s.connected?'on':'off');
    document.getElementById('connTxt').textContent=s.connected?'已连接':'未连接';
    document.getElementById('occDot').className='dot '+(s.occupy?'own':'off');

    /* 状态卡配色 + 徽章 + 方向 */
    const card=document.getElementById('statusCard');
    const badge=document.getElementById('badge');
    const dir=document.getElementById('dir');
    card.className='card sthero '+(s.stuck?'red':'grn');
    if(s.stuck){badge.className='badge red';badge.textContent='卡住';dir.textContent='⚠';dir.style.color='var(--red)';}
    else if(s.moving_up){badge.className='badge amb';badge.textContent='上行';dir.textContent='▲';dir.style.color='var(--cyan)';}
    else if(s.moving_down){badge.className='badge amb';badge.textContent='下行';dir.textContent='▼';dir.style.color='var(--cyan)';}
    else{badge.className='badge grn';badge.textContent='平层';dir.textContent='●';dir.style.color='var(--green)';}
    card.classList.toggle('amb',s.in_motion&&!s.stuck);
    document.getElementById('warn').classList.toggle('show',s.stuck);

    /* 模式分段钮 */
    document.querySelectorAll('#modeSeg .btn').forEach(b=>b.classList.toggle('on',b.dataset.mode===s.mode));

    /* 参数回显 */
    const mi=document.getElementById('moveInterval');
    if(document.activeElement!==mi) mi.value=parseFloat(s.move_interval);
    const ld=document.getElementById('levelingDelay');
    if(document.activeElement!==ld) ld.value=parseFloat(s.leveling_delay);
    const mh=document.getElementById('mqttHost');
    if(document.activeElement!==mh) mh.value=s.mqtt_host;
    const mp=document.getElementById('mqttPort');
    if(document.activeElement!==mp) mp.value=s.mqtt_port;
    const un=document.getElementById('mUserName');
    if(document.activeElement!==un) un.value=s.user_name;

    /* 服务消息 toast */
    if(s.service_msg && s.service_msg_seq!==lastSeq){lastSeq=s.service_msg_seq;showToast(s.service_msg);}

    renderShaft(s);
    myLogs=s.logs;
    renderLogs();
  }).catch(()=>{});
}

function renderLogs(){
  const inc=document.getElementById('filterInclude').value.trim();
  const exc=document.getElementById('filterExclude').value.trim();
  const box=document.getElementById('logBox');
  box.innerHTML=myLogs.filter(l=>{
    if(inc&&!l.includes(inc))return false;
    if(exc&&l.includes(exc))return false;
    return true;
  }).map(ln=>{
    let cls='log-info';
    if(ln.includes('卡住'))cls='log-stuck';
    else if(ln.includes('选层')||ln.includes('独占')||ln.includes('释放'))cls='log-cmd';
    else if(ln.includes('到达')||ln.includes('平层完成'))cls='log-arrive';
    return '<div class="log-line '+cls+'">'+ln.replace(/&/g,'&amp;').replace(/</g,'&lt;')+'</div>';
  }).join('');
  box.scrollTop=box.scrollHeight;
}

/* 动作 */
function doDest(f){post({action:'select_floor',floor:f})}
function doTp(f){post({action:'set_floor',floor:f})}
function doStuck(){post({action:'set_stuck'})}
function doResume(){post({action:'resume'})}
function doOccupy(){post({action:'occupy'})}
function doRelease(){post({action:'release'})}
function mqttOccupy(){post({action:'mqtt_occupy'})}
function mqttRelease(){post({action:'mqtt_release'})}
function mqttSelect(f){post({action:'mqtt_select',floor:f})}
function setUserName(){post({action:'set_user_name',name:document.getElementById('mUserName').value.trim()});}
function setMqttBroker(){post({action:'set_mqtt_broker',host:document.getElementById('mqttHost').value.trim(),port:parseInt(document.getElementById('mqttPort').value)||1883});}
function setMode(m){post({action:'set_mode',mode:m})}
function setMoveInterval(){post({action:'set_move_interval',seconds:parseFloat(document.getElementById('moveInterval').value)||1});}
function setLevelingDelay(){post({action:'set_leveling_delay',seconds:parseFloat(document.getElementById('levelingDelay').value)||0});}
function clearLogs(){post({action:'clear_logs'})}

buildRows();
try{renderShaft({floor:1,target_floor:0,stuck:false});}catch(e){}
poll();
setInterval(poll,500);
</script>
</body>
</html>
"""


# ==================== 主函数 ====================
def main():
    state = ElevatorState()
    WebHandler.state = state

    # 启动日志:打印默认配置
    state.add_log(f"模拟器启动 | 每层运动:{state.move_interval}秒")
    print(f"[模拟器] 每层运动时间: {state.move_interval}秒")

    # 独立运动线程:不依赖TCP连接,Web界面也能看到逐层运动
    update_thread = ElevatorUpdateThread(state)
    update_thread.start()

    # MQTT 订阅线程:接收转发器返回的 RESULT 消息
    state.start_mqtt_subscribe()

    tcp = ElevatorTcpServer(state, TCP_PORT)
    tcp.start()

    web = HTTPServer(("0.0.0.0", WEB_PORT), WebHandler)
    print(f"[模拟器] Web 界面: http://localhost:{WEB_PORT}")
    print(f"[模拟器] 按 Ctrl+C 停止")
    print()

    try:
        web.serve_forever()
    except KeyboardInterrupt:
        print("\n[模拟器] 已停止")


if __name__ == "__main__":
    main()