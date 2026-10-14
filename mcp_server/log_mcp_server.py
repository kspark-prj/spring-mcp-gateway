import os
from pathlib import Path

from fastmcp import FastMCP

# 1. FastMCP 서버 인스턴스 생성
mcp = FastMCP(name="Server Log Analyzer MCP")

# 허용할 로그 디렉토리 설정 (실제 환경에 맞게 변경하세요)
LOG_DIR = Path("./logs").resolve()


def _validate_path(file_name: str) -> Path:
    """
    경로 순회 공격(Path Traversal) 방지 및 .log 확장자 강제 고정
    """
    # 사용자가 확장자를 입력했든 안 했든 .log 확장자로 고정
    if not file_name.endswith(".log"):
        file_name = f"{file_name}.log"

    target_path = (LOG_DIR / file_name).resolve()

    if not target_path.is_relative_to(LOG_DIR):
        raise ValueError("허용되지 않은 경로입니다.")
    if not target_path.exists() or not target_path.is_file():
        raise FileNotFoundError(f"로그 파일을 찾을 수 없습니다: {file_name}")
    return target_path


@mcp.tool()
def read_log_file(file_name: str, lines: int = 100) -> str:
    """
    지정된 로그 파일의 앞에서부터 지정한 줄 수만큼 읽어옵니다. (.log 확장자 자동 적용)

    Args:
        file_name: 조회할 로그 파일명 (예: 'app' 또는 'app.log')
        lines: 읽어올 최대 줄 수 (기본값: 100)
    """
    try:
        path = _validate_path(file_name)
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            content = [f.readline() for _ in range(lines)]
        return "".join([line for line in content if line])
    except Exception as e:
        return f"오류 발생: {e!s}"


@mcp.tool()
def tail_log(file_name: str, lines: int = 50, keyword: str | None = None) -> str:
    """
    로그 파일의 가장 마지막(최신) N줄을 조회합니다. 특정 키워드 필터링도 가능합니다. (.log 확장자 자동 적용)

    Args:
        file_name: 조회할 로그 파일명 (예: 'app' 또는 'app.log')
        lines: 가져올 마지막 줄 수 (기본값: 50)
        keyword: 특정 키워드가 포함된 라인만 필터링하고 싶을 때 입력 (선택 사항)
    """
    try:
        path = _validate_path(file_name)

        # 대용량 파일에서 효율적으로 마지막 줄들을 읽어오기 위한 역방향 읽기
        with open(path, "rb") as f:
            f.seek(0, os.SEEK_END)
            buffer_size = 1024
            block_counter = -1
            data = []
            while True:
                try:
                    f.seek(block_counter * buffer_size, os.SEEK_END)
                    data = f.readlines()
                except OSError:
                    f.seek(0)
                    data = f.readlines()
                    break

                if len(data) >= lines + 1 or f.tell() == 0:
                    break
                block_counter -= 1

        # 디코딩 및 필요한 라인만큼 자르기
        decoded_lines = [line.decode("utf-8", errors="ignore") for line in data]
        target_lines = decoded_lines[-lines:]

        # 키워드 필터링 적용 (키워드가 존재하고 비어있지 않은 경우에만)
        if keyword:
            target_lines = [l for l in target_lines if keyword in l]

        return "".join(target_lines) if target_lines else "조건에 맞는 로그가 없습니다."
    except Exception as e:
        return f"오류 발생: {e!s}"


if __name__ == "__main__":
    mcp.run()
