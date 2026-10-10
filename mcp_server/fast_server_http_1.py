import base64
import datetime

import uvicorn
from fastmcp import FastMCP

# MCP 서버 인스턴스 생성 (서버 이름: dev-tools, 포트: 8001)[cite: 3]
mcp = FastMCP("dev-tools")


@mcp.tool()
def get_system_time() -> str:
    """현재 시스템의 ISO formatted 날짜와 시간 정보를 반환합니다."""
    now = datetime.datetime.now()
    return f"Current System Time: {now.isoformat()}"


@mcp.tool()
def convert_to_base64(text: str) -> str:
    """입력받은 텍스트 문자열을 Base64로 인코딩합니다."""
    encoded_bytes = base64.b64encode(text.encode("utf-8"))
    return encoded_bytes.decode("utf-8")


@mcp.tool()
def count_words(text: str) -> str:
    """입력받은 텍스트의 글자 수 및 단어 수를 계산합니다."""
    char_count = len(text)
    word_count = len(text.split())
    return f"글자 수: {char_count}자 / 단어 수: {word_count}개"


# ▼▼▼ [추가] 리소스 (Resources) 테스트용 정의 ▼▼▼
@mcp.resource("file://info.txt")
def get_info_resource() -> str:
    """시스템 안내 정보를 제공하는 리소스입니다."""
    return "이곳은 Spring MCP Gateway 연동 테스트용 업스트림 서버(dev-tools)입니다."


# ▼▼▼ [추가] 리소스 템플릿 (Resource Templates) 테스트용 정의 ▼▼▼
@mcp.resource("config://{env}/settings")
def get_config_template(env: str) -> str:
    """환경별 설정을 동적으로 반환하는 템플릿 리소스입니다."""
    return f"Current Environment: {env}, Debug: True, Timeout: 30s"


# ▼▼▼ [추가] 프롬프트 (Prompts) 테스트용 정의 ▼▼▼
@mcp.prompt()
def review_code_prompt(code: str) -> str:
    """전달받은 코드를 리뷰해 주는 프롬프트 템플릿입니다."""
    return f"다음 코드를 검토하고 잠재적인 버그나 개선사항을 알려주세요:\n\n{code}"


# FastMCP 객체에서 ASGI 애플리케이션 추출[cite: 3]
app = mcp.http_app()

if __name__ == "__main__":
    # 포트 8001로 Streamable HTTP 서버 실행 (엔드포인트: http://localhost:8001/mcp)[cite: 3]
    uvicorn.run(app, host="0.0.0.0", port=8001)
