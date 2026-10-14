# 🛠️ MCP Server Test Sandbox & Guide

Model Context Protocol(MCP) 서버와 클라이언트(Inspector) 간의 연동을 테스트하고 검증하기 위한 더미/오픈소스 서버 모음 및 실행 명령어 가이드입니다.

## 🚀 Quick Start

로컬 환경에서 MCP 서버를 구동하고 검증하는 기본 방법입니다.

### 1. HTTP 전송 방식 서버 구동

```bash
uv run fast_server_http_1.py

```

### 2. MCP Inspector를 활용한 인터랙티브 테스트

[Model Context Protocol Inspector](https://github.com/modelcontextprotocol/inspector)를 사용하여 브라우저 환경에서 MCP 서버의 툴과 리소스를 직접 테스트할 수 있습니다.

#### ① HTTP / SSE 전송 방식 테스트 (프록시 경유)

> **💡 프록시 사용 이유:**
> 공식 `@modelcontextprotocol/inspector`는 요청 시 커스텀 인증 헤더(예: `X-API-KEY`)를 직접 추가하는 기능이 제한적입니다. 따라서 로컬에 **`mcp_proxy.py`**를 구동하여, Inspector의 요청을 받아 **인증 헤더를 강제로 주입한 뒤 Spring MCP Gateway로 전달**하는 프록시 방식을 사용합니다.

**1단계: 로컬 프록시 서버 구동하기**

```bash
uv run mcp_proxy.py

```

**2단계: 인스펙터 실행하기 (새 터미널 창)**

```bash
# 로컬 프록시를 경유하여 Spring MCP Gateway URL로 X-API-KEY 헤더 전달 테스트
npx @modelcontextprotocol/inspector --server-url http://localhost:9000/mcp --transport http

```

#### ② Stdio (표준 입출력) 방식 테스트

파이썬 스크립트를 직접 인스펙터에 연동하여 테스트합니다.

```bash
# 방식 A (공식 인스펙터 패키지 활용)
npx @modelcontextprotocol/inspector python fast_server_stdio.py

# 방식 B (FastMCP 내장 명령어 활용)
fastmcp dev inspector fast_server_stdio.py

```

#### ③ 서버 장애 로그 분석용 테스트

`./logs` 폴더 생성 및 로그 파일 복사 이후 연동 상태를 확인합니다.

```bash
npx @modelcontextprotocol/inspector python log_mcp_server.py
# 또는
fastmcp dev inspector log_mcp_server.py

```

---

## 📚 Remote & Awesome MCP Resources

원격 MCP 서버 구축 및 다양한 오픈소스 구현체를 참고할 수 있는 유용한 외부 리포지토리 목록입니다.

- [jaw9c/awesome-remote-mcp-servers](https://github.com/jaw9c/awesome-remote-mcp-servers)
- [sylviangth/awesome-remote-mcp-servers](https://github.com/sylviangth/awesome-remote-mcp-servers)
