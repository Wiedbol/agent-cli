"""Replay the Windows console encoding mismatch using the real local CLI."""

import argparse
from pathlib import Path
import subprocess
import sys


REPO = Path(__file__).resolve().parents[2]
SAMPLE = "你好，Agent"
EXPECTED = "I heard: " + SAMPLE


def capture_echo(java: str, jar: Path, encoding: str) -> bytes:
    result = subprocess.run(
        [java, f"-Dagent.encoding={encoding}", "-jar", str(jar), "--local"],
        input=f"{SAMPLE}\nexit\n".encode(encoding),
        capture_output=True,
        timeout=15,
        cwd=REPO,
    )
    if result.returncode:
        detail = result.stderr.decode(encoding, errors="replace").strip()
        raise ValueError(f"Java 退出码 {result.returncode}。{detail}")
    return result.stdout


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="用实际 JAR 重放中文输出乱码和恢复。")
    parser.add_argument("--java", default="java", help="Java 命令或 java.exe 的绝对路径")
    parser.add_argument("--jar", type=Path, default=REPO / "target/agent-cli-0.1.0.jar")
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.exit(1, f"未找到 JAR：{jar}\n请先按演示指南打包。\n")
    try:
        gbk = capture_echo(args.java, jar, "GBK")
        wrong = gbk.decode("utf-8", errors="replace").rstrip("\r\n")
        corrected = gbk.decode("gbk").rstrip("\r\n")
        utf8 = capture_echo(args.java, jar, "UTF-8").decode("utf-8").rstrip("\r\n")
        if wrong == EXPECTED or corrected != EXPECTED or utf8 != EXPECTED:
            raise ValueError("未满足预期：错配应产生乱码，两组一致编码应完整回显。")
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f"演示验证失败：{error}\n")

    print("OFFLINE CONSOLE ENCODING DEMO")
    print(f"固定输入：{SAMPLE}")
    print(f"[1] JAR 输出 GBK，接收端按 UTF-8 解码：{wrong}")
    print(f"    原始 stdout 字节：{gbk.hex(' ')}")
    print(f"[2] 同一份字节，接收端改用 GBK 解码：{corrected}")
    print(f"[3] JAR 输出和接收端统一使用 UTF-8：{utf8}")
    print("PASS：成功复现编码错配，两组一致编码均准确回显。")


if __name__ == "__main__":
    main()
