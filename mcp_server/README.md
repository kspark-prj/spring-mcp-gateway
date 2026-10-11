# MCP Server Test Sandbox & Dummy Servers

Model Context Protocol(MCP) 서버와 클라이언트(Inspector) 간의 연동을 테스트하고 검증하기 위한 더미/오픈소스 서버 모음 및 실행 명령어 가이드입니다.

## 구동방법

```bash
uv run fast_server_http_1.py

```

## 🚀 Quick Start with MCP Inspector

[Model Context Protocol Inspector](https://github.com/modelcontextprotocol/inspector)를 사용하여 로컬 및 원격 MCP 서버를 인터랙티브하게 테스트할 수 있습니다.

````

### 1. HTTP 전송 방식 (로컬 포트)

```bash
# 게이트 웨이 인증 토큰헤더 추가가 안되어서 mcp_proxy.py 구동 후 프록시를 통해 테스
# 로컬 포트 8000 기반 MCP 서버 테스트
npx @modelcontextprotocol/inspector --server-url http://localhost:9000/mcp --transport http
npx -y @modelcontextprotocol/server-filesystem C:\Users\username\Downloads

#이러면 내부에서 spring mcp gateway url로 X-API-KEY 헤더값을 전달하여 호출

````

## 📚 Remote & Awesome MCP Resources

원격 MCP 서버 구축 및 다양한 오픈소스 구현체를 참고할 수 있는 유용한 Awesome 리포지토리 목록입니다.

- [jaw9c/awesome-remote-mcp-servers](https://github.com/jaw9c/awesome-remote-mcp-servers)
- [sylviangth/awesome-remote-mcp-servers](https://github.com/sylviangth/awesome-remote-mcp-servers)
