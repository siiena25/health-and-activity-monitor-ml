"""
通过蓝牙接收 Portenta H7 + BMI160 的加速度计/陀螺仪数据，保存为 CSV。

用法:
    python imu_logger.py walking          # 一直录，按 Ctrl+C 停止
    python imu_logger.py running 60       # 录 60 秒后自动停止

输出文件: <动作>_<日期>_<时间>.csv
列: t_ms, ax, ay, az (m/s^2), gx, gy, gz (rad/s), label
"""

import asyncio
import csv
import math
import struct
import sys
from datetime import datetime

from bleak import BleakClient, BleakScanner

DEVICE_NAME = "IMU-Logger"
SERVICE_UUID = "12345678-1234-1234-1234-123456789ac0"
IMU_CHAR_UUID = "12345678-1234-1234-1234-123456789ac1"

# 必须和固件里的量程设置一致
ACC_SCALE = 9.80665 / 4096.0                    # ±8g -> m/s^2
GYR_SCALE = (1.0 / 16.4) * math.pi / 180.0      # ±2000°/s -> rad/s

PACKET_FORMAT = "<I6h"   # uint32 时间戳(ms) + 6 个 int16
PACKET_SIZE = struct.calcsize(PACKET_FORMAT)
SAMPLE_PERIOD_MS = 50    # 20 Hz


def is_target(device, adv):
    uuids = [u.lower() for u in (adv.service_uuids or [])]
    return SERVICE_UUID in uuids or adv.local_name == DEVICE_NAME


async def record(label, duration):
    print(f"正在扫描 {DEVICE_NAME}（最多 20 秒）...")
    device = await BleakScanner.find_device_by_filter(is_target, timeout=20)
    if device is None:
        print("没找到设备。请确认板子已通电、绿灯亮着，并且没有被别的程序连着。")
        return

    filename = f"{label}_{datetime.now():%Y%m%d_%H%M%S}.csv"
    stats = {"rows": 0, "first_t": None, "last_t": None, "gaps": 0}

    with open(filename, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["t_ms", "ax", "ay", "az", "gx", "gy", "gz", "label"])

        def on_packet(_, data):
            if len(data) < PACKET_SIZE:
                return
            t, ax, ay, az, gx, gy, gz = struct.unpack(PACKET_FORMAT, bytes(data[:PACKET_SIZE]))
            if stats["last_t"] is not None and t - stats["last_t"] > SAMPLE_PERIOD_MS * 1.5:
                stats["gaps"] += 1
            if stats["first_t"] is None:
                stats["first_t"] = t
            stats["last_t"] = t
            stats["rows"] += 1
            writer.writerow([
                t,
                f"{ax * ACC_SCALE:.3f}", f"{ay * ACC_SCALE:.3f}", f"{az * ACC_SCALE:.3f}",
                f"{gx * GYR_SCALE:.4f}", f"{gy * GYR_SCALE:.4f}", f"{gz * GYR_SCALE:.4f}",
                label,
            ])

        try:
            async with BleakClient(device) as client:
                await client.start_notify(IMU_CHAR_UUID, on_packet)
                if duration:
                    print(f"已连接，开始录制「{label}」，{duration} 秒后自动停止。")
                else:
                    print(f"已连接，开始录制「{label}」，按 Ctrl+C 停止。")

                elapsed = 0
                while client.is_connected and (not duration or elapsed < duration):
                    await asyncio.sleep(1)
                    elapsed += 1
                    print(f"\r已录 {elapsed} 秒，{stats['rows']} 行", end="", flush=True)

                if not client.is_connected:
                    print("\n蓝牙连接断开了。")
        except (asyncio.CancelledError, KeyboardInterrupt):
            pass
        finally:
            f.flush()

    print()
    rows = stats["rows"]
    if rows > 1:
        seconds = (stats["last_t"] - stats["first_t"]) / 1000.0
        rate = (rows - 1) / seconds if seconds > 0 else 0
        print(f"已保存 {filename}")
        print(f"共 {rows} 行，时长 {seconds:.1f} 秒，实际采样率 {rate:.1f} Hz，丢包间隔 {stats['gaps']} 处")
    else:
        print(f"没有收到数据（文件 {filename} 只有表头）。")


def main():
    if len(sys.argv) < 2:
        print("用法: python imu_logger.py <动作名称> [秒数]")
        print("例如: python imu_logger.py walking 60")
        return
    label = sys.argv[1]
    duration = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    try:
        asyncio.run(record(label, duration))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
