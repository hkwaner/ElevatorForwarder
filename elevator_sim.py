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
        self.move_interval = 2.0
        self.logs = []
        self.client_connected = False
        # 平层延迟: 到达目标楼层后保持运动中状态一段时间,模拟真实平层校准过程
        self.leveling = False
        self.leveling_delay = 3.0
        self.leveling_until = 0
        # MQTT 模拟发送配置(配合转发器测试)
        self.mqtt_host = "192.168.10.94"
        self.mqtt_port = 1883
        self.mqtt_user_id = "sim-user"
        self.mqtt_user_name = "模拟机器人"
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

    def status_str(self):
        if self.stuck:
            return "卡住(运动中)"
        if self.leveling:
            return "平层中(运动中)"
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
                "logs": self.logs[-30:],
                "mqtt_host": self.mqtt_host,
                "mqtt_port": self.mqtt_port,
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
            self.add_log(f"平层延迟设置为 {self.leveling_delay}秒")

    def handle_command(self, cmd, source="web"):
        with self.lock:
            if cmd["type"] == "select_floor":
                floor = cmd["floor"]
                self.target_floor = floor
                if self.stuck:
                    self.stuck = False
                    self.add_log(f"卡住状态收到选层指令 {floor}F,恢复运动")
                else:
                    self.add_log(f"收到选层指令: {floor}F")
                if source == "tcp":
                    self.add_log("收到服务指令: 选层")
                if self.mode == "auto":
                    self._start_moving(floor)
            elif cmd["type"] == "occupy":
                self.occupy = OCCUPY_SUCCESS
                self.add_log("收到独占指令 -> 已独占")
                if source == "tcp":
                    self.add_log("收到服务指令: 独占")
            elif cmd["type"] == "release":
                self.occupy = OCCUPY_NONE
                self.add_log("收到释放独占指令")
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
            # 平层延迟阶段: 到达楼层后保持运动中,延迟后才真正平层
            if self.leveling:
                if time.time() >= self.leveling_until:
                    self.leveling = False
                    self.in_motion = False
                    self.moving_up = False
                    self.moving_down = False
                    self.add_log(f"平层完成 {self.floor}F")
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
        """到达目标楼层: 进入平层延迟阶段(保持运动中状态,延迟后变平层)"""
        self.floor = self.target_floor
        self.moving_up = False
        self.moving_down = False
        self.in_motion = True  # 保持运动中,模拟平层校准
        self.leveling = True
        self.leveling_until = time.time() + self.leveling_delay
        self.add_log(f"到达目标楼层 {self.floor}F,平层校准中...")


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
<title>电梯模拟器</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
  background:#0d1117;color:#c9d1d9;min-height:100vh;padding:20px}
.wrap{max-width:820px;margin:0 auto}
h1{font-size:22px;font-weight:600;margin-bottom:4px}
.subtitle{color:#6e7681;font-size:13px;margin-bottom:20px}
.card{background:#161b22;border:1px solid #30363d;border-radius:10px;padding:18px;margin-bottom:16px}
.card-title{font-size:13px;font-weight:600;color:#8b949e;text-transform:uppercase;
  letter-spacing:.5px;margin-bottom:14px}
.row{display:flex;gap:16px;flex-wrap:wrap}
.row>*{flex:1;min-width:140px}
.stat-item{text-align:center;padding:10px;background:#0d1117;border-radius:8px}
.stat-label{font-size:12px;color:#6e7681;margin-bottom:4px}
.stat-value{font-size:20px;font-weight:700;color:#e6edf3}
.floor-big{font-size:48px;font-weight:800;text-align:center;color:#58a6ff;margin:8px 0}
.status-tag{display:inline-block;padding:4px 14px;border-radius:20px;font-size:13px;font-weight:600}
.tag-normal{background:#1a3a2e;color:#3fb950}
.tag-moving{background:#3b2e00;color:#d29922}
.tag-stuck{background:#3b0d0d;color:#f85149}
.btn{padding:8px 16px;border:1px solid #30363d;border-radius:6px;background:#21262d;
  color:#c9d1d9;font-size:14px;cursor:pointer;transition:.15s}
.btn:hover{background:#30363d;border-color:#8b949e}
.btn:active{transform:scale(.97)}
.btn-stuck{background:#3b0d0d;border-color:#f85149;color:#f85149;font-weight:600}
.btn-stuck:hover{background:#4d1010}
.btn-floor{min-width:56px;font-size:16px;font-weight:600}
.btn-active{background:#1f6feb;border-color:#1f6feb;color:#fff}
.btn-danger{background:#21262d;border-color:#f85149;color:#f85149}
.log-box{background:#0d1117;border-radius:8px;padding:12px;height:280px;overflow-y:auto;
  font-family:"SF Mono",Monaco,monospace;font-size:12px;line-height:1.7}
.log-line{white-space:pre-wrap;word-break:break-all}
.log-cmd{color:#d29922}
.log-stuck{color:#f85149;font-weight:600}
.log-arrive{color:#3fb950}
.log-info{color:#8b949e}
.conn-dot{width:10px;height:10px;border-radius:50%;display:inline-block;margin-right:6px}
.conn-on{background:#3fb950;box-shadow:0 0 6px #3fb950}
.conn-off{background:#6e7681}
.mode-group{display:inline-flex;gap:0;border:1px solid #30363d;border-radius:6px;overflow:hidden}
.mode-group .btn{border:none;border-radius:0}
.sep{height:1px;background:#30363d;margin:14px 0}
.hint{font-size:12px;color:#6e7681;line-height:1.8;margin-top:10px;padding:10px;
  background:#0d1117;border-radius:8px;border-left:3px solid #1f6feb}
#toast{position:fixed;top:16px;left:50%;transform:translateX(-50%) translateY(-80px);
  background:#1f6feb;color:#fff;padding:10px 20px;border-radius:8px;font-size:14px;
  font-weight:600;box-shadow:0 4px 16px rgba(0,0,0,.4);z-index:999;opacity:0;
  transition:transform .3s,opacity .3s;pointer-events:none;max-width:90vw;text-align:center}
#toast.show{transform:translateX(-50%) translateY(0);opacity:1}
</style>
</head>
<body>
<div id="toast"></div>
<div class="wrap">
  <h1>电梯模拟器</h1>
  <p class="subtitle">
    <span id="conn"><span class="conn-dot conn-off"></span>未连接</span>
    &nbsp;|&nbsp; TCP :20108 &nbsp;|&nbsp; 状态帧每秒广播
  </p>

  <div class="card">
    <div class="card-title">当前状态</div>
    <div class="floor-big" id="floor">1F</div>
    <div style="text-align:center;margin-bottom:14px">
      <span class="status-tag tag-normal" id="statusTag">平层</span>
    </div>
    <div class="row">
      <div class="stat-item"><div class="stat-label">目标楼层</div>
        <div class="stat-value" id="target">-</div></div>
      <div class="stat-item"><div class="stat-label">独占</div>
        <div class="stat-value" id="occupy">否</div></div>
      <div class="stat-item"><div class="stat-label">模式</div>
        <div class="stat-value" id="modeText">自动</div></div>
    </div>
  </div>

  <div class="card">
    <div class="card-title">控制面板</div>
    <div style="margin-bottom:14px">
      <div class="mode-group">
        <button class="btn btn-active" id="btnAuto" onclick="setMode('auto')">自动</button>
        <button class="btn" id="btnManual" onclick="setMode('manual')">手动</button>
      </div>
      <span style="font-size:13px;color:#6e7681;margin-left:16px">每层运动时间:</span>
      <input type="number" id="moveInterval" min="0.5" max="30" step="0.5" value="2"
        style="width:60px;background:#0d1117;color:#c9d1d9;border:1px solid #30363d;
        border-radius:6px;padding:4px 8px;font-size:14px;text-align:center">
      <span style="font-size:13px;color:#6e7681">秒</span>
      <button class="btn" onclick="setMoveInterval()" style="margin-left:4px">设置</button>
      <span style="font-size:13px;color:#6e7681;margin-left:16px">平层延迟:</span>
      <input type="number" id="levelingDelay" min="0" max="15" step="0.5" value="3"
        style="width:60px;background:#0d1117;color:#c9d1d9;border:1px solid #30363d;
        border-radius:6px;padding:4px 8px;font-size:14px;text-align:center">
      <span style="font-size:13px;color:#6e7681">秒</span>
      <button class="btn" onclick="setLevelingDelay()" style="margin-left:4px">设置</button>
    </div>
    <div style="margin-bottom:14px">
      <span style="font-size:13px;color:#6e7681;margin-right:8px">选层(逐层运动):</span>
      <button class="btn btn-floor" onclick="selectFloor(1)">1F</button>
      <button class="btn btn-floor" onclick="selectFloor(2)">2F</button>
      <button class="btn btn-floor" onclick="selectFloor(3)">3F</button>
      <button class="btn btn-floor" onclick="selectFloor(4)">4F</button>
      <button class="btn btn-floor" onclick="selectFloor(5)">5F</button>
    </div>
    <div style="margin-bottom:14px">
      <span style="font-size:13px;color:#6e7681;margin-right:8px">手动设楼层(直接跳转):</span>
      <button class="btn btn-floor" onclick="setFloor(1)">1F</button>
      <button class="btn btn-floor" onclick="setFloor(2)">2F</button>
      <button class="btn btn-floor" onclick="setFloor(3)">3F</button>
      <button class="btn btn-floor" onclick="setFloor(4)">4F</button>
      <button class="btn btn-floor" onclick="setFloor(5)">5F</button>
    </div>
    <div>
      <button class="btn btn-stuck" onclick="doStuck()">模拟卡住</button>
      <button class="btn btn-danger" onclick="doResume()">恢复/停止</button>
    </div>
    <div style="margin-top:14px">
      <button class="btn" onclick="doOccupy()" style="background:#1a3a2e;border-color:#3fb950;color:#3fb950;font-weight:600">模拟独占</button>
      <button class="btn" onclick="doRelease()">释放独占</button>
    </div>
    <div class="hint">
      <b>测试流程:</b><br>
      1. 调整「每层运动时间」和「平层延迟」(平层延迟模拟真实电梯到位校准时间)<br>
      2. 点「选层」按钮(如4F),电梯逐层运动,到达后先显示"平层中(运动中)",延迟后才变平层<br>
      3. 运动中点「模拟卡住」,电梯停在当前楼层但状态保持运动中(复现真实异常)<br>
      4. 如已连接转发器:60秒后转发器检测到卡住,自动跨楼层重试恢复<br>
      5. 「手动设楼层」直接跳转,不经过运动过程<br>
      6. 观察转发器日志中的 [电梯卡住检测] 和 [电梯卡住恢复] 信息
    </div>
  </div>

  <div class="card">
    <div class="card-title">MQTT 模拟发送(机器人/平台)</div>
    <div style="margin-bottom:14px">
      <span style="font-size:13px;color:#6e7681;margin-right:8px">Broker:</span>
      <input type="text" id="mqttHost" value="127.0.0.1"
        style="width:120px;background:#0d1117;color:#c9d1d9;border:1px solid #30363d;border-radius:6px;padding:4px 8px;font-size:14px;text-align:center">
      :
      <input type="number" id="mqttPort" value="1883" min="1" max="65535"
        style="width:70px;background:#0d1117;color:#c9d1d9;border:1px solid #30363d;border-radius:6px;padding:4px 8px;font-size:14px;text-align:center">
      <button class="btn" onclick="setMqttBroker()" style="margin-left:4px">设置</button>
    </div>
    <div style="margin-bottom:14px">
      <button class="btn" onclick="mqttOccupy()" style="background:#1a3a2e;border-color:#3fb950;color:#3fb950;font-weight:600">MQTT 占用</button>
      <button class="btn" onclick="mqttRelease()">MQTT 释放</button>
    </div>
    <div>
      <span style="font-size:13px;color:#6e7681;margin-right:8px">MQTT 选层:</span>
      <button class="btn btn-floor" onclick="mqttSelect(1)">1F</button>
      <button class="btn btn-floor" onclick="mqttSelect(2)">2F</button>
      <button class="btn btn-floor" onclick="mqttSelect(3)">3F</button>
      <button class="btn btn-floor" onclick="mqttSelect(4)">4F</button>
      <button class="btn btn-floor" onclick="mqttSelect(5)">5F</button>
    </div>
    <div class="hint">
      <b>MQTT 测试流程:</b><br>
      1. 先点「MQTT 占用」,等 1~2 秒让转发器确认独占<br>
      2. 点「MQTT 选层」(如4F),电梯经转发器指令开始运动<br>
      3. 运动中点「模拟卡住」,60秒后转发器自动跨楼层重试恢复<br>
      4. 转发器日志观察 [电梯卡住检测] 和 [电梯卡住恢复]
    </div>
  </div>

  <div class="card">
    <div class="card-title">事件日志</div>
    <div class="log-box" id="logBox"></div>
  </div>
</div>

<script>
function post(body){
  return fetch('/control',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify(body)});
}
function setFloor(f){post({action:'set_floor',floor:f})}
function selectFloor(f){post({action:'select_floor',floor:f})}
function doStuck(){post({action:'set_stuck'})}
function doResume(){post({action:'resume'})}
function doOccupy(){post({action:'occupy'})}
function doRelease(){post({action:'release'})}
function mqttOccupy(){post({action:'mqtt_occupy'})}
function mqttRelease(){post({action:'mqtt_release'})}
function mqttSelect(f){post({action:'mqtt_select',floor:f})}
function setMqttBroker(){
  var h=document.getElementById('mqttHost').value.trim();
  var p=parseInt(document.getElementById('mqttPort').value)||1883;
  post({action:'set_mqtt_broker',host:h,port:p});
}
function setMode(m){post({action:'set_mode',mode:m})}
function setMoveInterval(){
  const v=parseFloat(document.getElementById('moveInterval').value)||2;
  post({action:'set_move_interval',seconds:v});
}
function setLevelingDelay(){
  const v=parseFloat(document.getElementById('levelingDelay').value)||3;
  post({action:'set_leveling_delay',seconds:v});
}

function esc(s){const d=document.createElement('div');d.textContent=s;return d.innerHTML}

let lastServiceMsgSeq=-1;
let toastTimer=null;
function showToast(msg){
  const t=document.getElementById('toast');
  t.textContent=msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer=setTimeout(()=>t.classList.remove('show'),4000);
}

async function poll(){
  try{
    const r=await fetch('/state');
    const s=await r.json();
    document.getElementById('floor').textContent=s.floor+'F';
    document.getElementById('target').textContent=s.target_floor?s.target_floor+'F':'-';
    document.getElementById('occupy').textContent=s.occupy?'是':'否';
    document.getElementById('modeText').textContent=s.mode==='auto'?'自动':'手动';
    if(s.service_msg && s.service_msg_seq!==lastServiceMsgSeq){
      lastServiceMsgSeq=s.service_msg_seq;
      showToast(s.service_msg);
    }
    const mi=document.getElementById('moveInterval');
    if(document.activeElement!==mi) mi.value=s.move_interval;
    const ld=document.getElementById('levelingDelay');
    if(document.activeElement!==ld) ld.value=s.leveling_delay;
    const mh=document.getElementById('mqttHost');
    if(document.activeElement!==mh) mh.value=s.mqtt_host;
    const mp=document.getElementById('mqttPort');
    if(document.activeElement!==mp) mp.value=s.mqtt_port;

    const tag=document.getElementById('statusTag');
    tag.className='status-tag '+(s.stuck?'tag-stuck':(s.in_motion||s.leveling)?'tag-moving':'tag-normal');
    tag.textContent=s.status;

    const conn=document.getElementById('conn');
    conn.innerHTML='<span class="conn-dot '+(s.connected?'conn-on':'conn-off')+'"></span>'+
      (s.connected?'已连接':'未连接');

    document.getElementById('btnAuto').classList.toggle('btn-active',s.mode==='auto');
    document.getElementById('btnManual').classList.toggle('btn-active',s.mode==='manual');

    const box=document.getElementById('logBox');
    box.innerHTML=s.logs.map(l=>{
      let cls='log-info';
      if(l.includes('卡住'))cls='log-stuck';
      else if(l.includes('选层')||l.includes('独占')||l.includes('释放'))cls='log-cmd';
      else if(l.includes('到达')||l.includes('平层完成'))cls='log-arrive';
      else if(l.includes('经过')||l.includes('开始'))cls='log-info';
      return '<div class="log-line '+cls+'">'+esc(l)+'</div>';
    }).join('');
    box.scrollTop=box.scrollHeight;
  }catch(e){}
}
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
    state.add_log(f"模拟器启动 | 每层运动:{state.move_interval}秒 | 平层延迟:{state.leveling_delay}秒")
    print(f"[模拟器] 每层运动时间: {state.move_interval}秒")
    print(f"[模拟器] 平层延迟: {state.leveling_delay}秒")

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
