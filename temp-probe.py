#!/usr/bin/env python3
"""
wpprobe_scan.py - Live decoder for the "WPprobe" BLE meat temperature probe.

Watches BLE advertisements and prints meat + ambient temperature in real time.

Decoded protocol (reverse-engineered; verified against ice-water and
heating sweeps that matched the probe's own display):
    Manufacturer Specific Data payload (the 0xFF AD field), 15 bytes:
        [0:6]   MAC address, byte-reversed         e.g. FB 17 A4 16 42 F3
        [6:8]   fixed header                        01 01
        [8:10]  meat / tip temp, BE int16           value / 10 = deg C  (tip rated ~0-100C)
        [10]    battery (0-100, %)
        [11]    constant 0x1D (fixed flag)
        [12:14] ambient temp, BE int16              value / 10 = deg C;
                                                     0x8000 = "LO" (no reading)
        [14]    status byte (drifts loosely with the active temperature;
                              no checksum fit, not the precise reading)

    Dual-sensor design: the tip sensor maxes out near 100 C, while the ambient
    sensor reads much higher (handle/surface). When a sensor is inactive it
    reports a sentinel: meat ~0xFFFF, ambient 0x8000 -> shown as LO.

    Verified samples:
        0x0165 -> meat 35.7C   (~36C reported)
        ice sweep   0x0122..0x0082 -> 29.0C..13.0C
        heat sweep  ambient 0x0456..0x04AC -> 111.0C..119.6C (rising toward 140C)

    Note: the BLE stack splits manufacturer data into a 2-byte "company id"
    (here 0x17FB, really the first two MAC bytes) plus the value bytes. This
    script reconstructs the full payload before decoding.

Usage:
    pip install bleak
    python wpprobe_scan.py                  # live scan, show C and F
    python wpprobe_scan.py --fahrenheit      # Fahrenheit only
    python wpprobe_scan.py --raw             # also print raw payload hex
    python wpprobe_scan.py --selftest        # decode known packets, no hardware

On Linux you may need elevated privileges (or cap_net_raw on the python binary)
for BLE scanning to work.
"""

import argparse
import asyncio
import sys
from datetime import datetime

TARGET_NAME = "WPprobe"
MEAT_SENTINEL = 0xFFFF      # tip "no reading"
AMBIENT_SENTINEL = 0x8000   # ambient "no reading" -> LO
TEMP_LO, TEMP_HI = -55.0, 350.0   # plausible range guard (Celsius)


def c_to_f(c: float) -> float:
    return c * 9.0 / 5.0 + 32.0


def reconstruct_payload(manufacturer_data: dict) -> bytes | None:
    """Rebuild the full manufacturer payload (company-id bytes + value)."""
    for company_id, value in manufacturer_data.items():
        payload = company_id.to_bytes(2, "little") + bytes(value)
        if len(payload) >= 15:
            return payload
    return None


def _temp(word: int, sentinel: int) -> float | None:
    if word == sentinel:
        return None
    c = (word - 0x10000 if word & 0x8000 else word) / 10.0
    return c if TEMP_LO <= c <= TEMP_HI else None


def decode_payload(payload: bytes) -> dict:
    """Decode a 15-byte (or longer) reconstructed manufacturer payload."""
    if len(payload) < 15:
        raise ValueError(f"payload too short: {len(payload)} bytes")

    return {
        "meat_c": _temp(int.from_bytes(payload[8:10], "big"), MEAT_SENTINEL),
        "ambient_c": _temp(int.from_bytes(payload[12:14], "big"), AMBIENT_SENTINEL),
        "battery": payload[10],
        "status": payload[14],
        "mac": ":".join(f"{b:02X}" for b in reversed(payload[0:6])),
        "raw": payload.hex().upper(),
    }


def fmt_temp(c: float | None, unit: str) -> str:
    if c is None:
        return "  LO   "
    if unit == "c":
        return f"{c:6.1f}C"
    if unit == "f":
        return f"{c_to_f(c):6.1f}F"
    return f"{c:6.1f}C / {c_to_f(c):6.1f}F"


def print_reading(name: str, rssi, info: dict, unit: str, show_raw: bool) -> None:
    ts = datetime.now().strftime("%H:%M:%S")
    rssi_str = f"{rssi:>4} dBm" if rssi is not None else "  ? dBm"
    line = (
        f"[{ts}] {name:<8} {rssi_str}  "
        f"meat {fmt_temp(info['meat_c'], unit)}  "
        f"ambient {fmt_temp(info['ambient_c'], unit)}  "
        f"batt {info['battery']:>3}%  "
        f"st {info['status']:>3}"
    )
    if show_raw:
        line += f"  raw={info['raw']}"
    print(line, flush=True)


def make_callback(args):
    def callback(device, advertisement_data):
        name = advertisement_data.local_name or device.name or ""
        addr_match = args.address and device.address.upper() == args.address.upper()
        if name != args.name and not addr_match:
            return
        payload = reconstruct_payload(advertisement_data.manufacturer_data)
        if payload is None:
            return
        try:
            info = decode_payload(payload)
        except ValueError:
            return
        rssi = getattr(advertisement_data, "rssi", None)
        print_reading(name or TARGET_NAME, rssi, info, args.unit, args.raw)
    return callback


async def scan(args):
    from bleak import BleakScanner

    print(f"Scanning for '{args.name}' advertisements... (Ctrl+C to stop)\n",
          flush=True)
    scanner = BleakScanner(detection_callback=make_callback(args))
    await scanner.start()
    try:
        while True:
            await asyncio.sleep(1)
    except (KeyboardInterrupt, asyncio.CancelledError):
        pass
    finally:
        await scanner.stop()
        print("\nStopped.", flush=True)


# --- captured packets, for offline verification ---
KNOWN_PACKETS = {
    "warm tip (~36C)":      "10FFFB17A41642F301010165601D80001F0302E0CE0809575070726F6265",
    "ice tip (~13C)":       "10FFFB17A41642F301010082641D8000100302E0CE0809575070726F6265",
    "hot ambient (~120C)":  "10FFFB17A41642F30101FFFF641D04AC770302E0CE0809575070726F6265",
}


def extract_mfg_field(raw_hex: str) -> bytes:
    """Pull the 0xFF manufacturer-data payload out of a full raw AD dump."""
    data = bytes.fromhex(raw_hex.removeprefix("0x"))
    i = 0
    while i < len(data):
        length = data[i]
        if length == 0:
            break
        ad_type = data[i + 1]
        value = data[i + 2: i + 1 + length]
        if ad_type == 0xFF:
            return value
        i += 1 + length
    raise ValueError("no manufacturer data field found")


def selftest(args):
    print("Self-test against captured packets:\n")
    for label, raw in KNOWN_PACKETS.items():
        info = decode_payload(extract_mfg_field(raw))
        print(f"  {label}")
        print(f"    mac     {info['mac']}")
        print(f"    meat    {fmt_temp(info['meat_c'], 'both')}")
        print(f"    ambient {fmt_temp(info['ambient_c'], 'both')}")
        print(f"    battery {info['battery']}%   status {info['status']}\n")


def main():
    p = argparse.ArgumentParser(description="Live decoder for WPprobe BLE meat thermometer")
    p.add_argument("--name", default=TARGET_NAME, help="advertised name to match")
    p.add_argument("--address", default=None, help="optional MAC/UUID to also match")
    p.add_argument("--celsius", dest="unit", action="store_const", const="c")
    p.add_argument("--fahrenheit", dest="unit", action="store_const", const="f")
    p.add_argument("--raw", action="store_true", help="also print raw payload hex")
    p.add_argument("--selftest", action="store_true", help="decode known packets and exit")
    p.set_defaults(unit="both")
    args = p.parse_args()

    if args.selftest:
        selftest(args)
        return

    try:
        asyncio.run(scan(args))
    except ImportError:
        sys.exit("bleak is not installed. Run:  pip install bleak")


if __name__ == "__main__":
    main()
