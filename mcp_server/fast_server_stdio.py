import os

from fastmcp import FastMCP

# MCP 서버 인스턴스 생성
mcp = FastMCP("file-manager")

BASE_DIR = os.path.abspath("./workspace")
os.makedirs(BASE_DIR, exist_ok=True)


def _get_safe_path(file_path: str) -> str:
    safe_path = os.path.normpath(os.path.join(BASE_DIR, file_path))
    if not safe_path.startswith(BASE_DIR):
        raise ValueError("허용되지 않은 경로입니다. (workspace 외부 접근 불가)")
    return safe_path


@mcp.tool()
def list_files() -> str:
    """workspace 디렉토리 내의 파일 목록을 조회합니다."""
    try:
        files = os.listdir(BASE_DIR)
        if not files:
            return "workspace 디렉토리가 비어 있습니다."
        return "파일 목록:\n" + "\n".join([f"- {f}" for f in files])
    except Exception as e:
        return f"파일 목록 조회 실패: {e!s}"


@mcp.tool()
def read_file(file_path: str) -> str:
    """workspace 내 지정된 파일의 내용을 읽어옵니다."""
    try:
        target_path = _get_safe_path(file_path)
        if not os.path.exists(target_path):
            return f"오류: '{file_path}' 파일을 찾을 수 없습니다."
        with open(target_path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception as e:
        return f"파일 읽기 실패: {e!s}"


@mcp.tool()
def write_file(file_path: str, content: str) -> str:
    """workspace 내 지정된 파일에 내용을 작성(또는 덮어쓰기)합니다."""
    try:
        target_path = _get_safe_path(file_path)
        os.makedirs(os.path.dirname(target_path), exist_ok=True)
        with open(target_path, "w", encoding="utf-8") as f:
            f.write(content)
        return f"성공적으로 '{file_path}' 파일에 저장되었습니다."
    except Exception as e:
        return f"파일 쓰기 실패: {e!s}"


@mcp.tool()
def delete_file(file_path: str) -> str:
    """workspace 내 지정된 파일을 삭제합니다."""
    try:
        target_path = _get_safe_path(file_path)
        if not os.path.exists(target_path):
            return f"오류: '{file_path}' 파일을 찾을 수 없습니다."
        os.remove(target_path)
        return f"성공적으로 '{file_path}' 파일을 삭제했습니다."
    except Exception as e:
        return f"파일 삭제 실패: {e!s}"


if __name__ == "__main__":
    # stdio 방식으로 실행 (별도의 서버 포트를 열지 않음)
    mcp.run()
