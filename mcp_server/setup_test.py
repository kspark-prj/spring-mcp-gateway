import sys

import questionary
from fastmcp import FastMCP

mcp = FastMCP("My Interactive MCP Server")


@mcp.tool()
def hello_tool(name: str) -> str:
    return f"Hello, {name}!"


def interactive_setup():
    # 1. 터미널에 에이전트 선택 메뉴 출력
    target_agent = questionary.select(
        "어떤 에이전트에 MCP 서버를 설치하시겠습니까?",
        choices=["Claude Desktop", "Cursor IDE", "Claude Code", "전체 감지하여 자동 설치"],
    ).ask()

    # 2. 선택한 에이전트에 맞춰 FastMCP 인스톨러 실행
    if target_agent == "Claude Desktop":
        mcp.install(client="claude-desktop")
    elif target_agent == "Cursor IDE":
        mcp.install(client="cursor")
    elif target_agent == "Claude Code":
        mcp.install(client="claude-code")
    else:
        mcp.install()  # 자동 설치

    print(f"\n {target_agent}에 성공적으로 등록되었습니다!")


if __name__ == "__main__":
    if "--setup" in sys.argv:
        interactive_setup()
    else:
        mcp.run()
