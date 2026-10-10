import http.server
import urllib.error
import urllib.request

# 설정 값
PROXY_PORT = 9000
TARGET_URL = "http://localhost:8080"
API_KEY = "mcp-gateway-master-key-2026"


class MCPProxyHandler(http.server.BaseHTTPRequestHandler):
    def do_request(self, method):
        # 대상 URL 결정 (프록시 주소 -> 실제 타겟 서버 주소로 변경)
        # 만약 TARGET_URL이 이미 전체 경로를 포함하고 있다면 self.path와 중복될 수 있으므로 주의
        target_full_url = TARGET_URL + self.path

        # 클라이언트가 보낸 헤더들을 딕셔너리로 변환
        headers = {key: value for key, value in self.headers.items()}

        # ★ 핵심: X-API-KEY 헤더 강제 추가 (대소문자 무관하게 인식되도록 설정)
        headers["X-API-KEY"] = API_KEY

        # 호스트 헤더 재설정 (포트 번호 포함하여 타겟 도메인으로 교체)
        target_host = TARGET_URL.replace("http://", "").replace("https://", "").split("/")[0]
        headers["Host"] = target_host

        # POST, PUT 등 Body가 있는 경우 데이터 읽기
        body = None
        if "Content-Length" in self.headers:
            content_length = int(self.headers["Content-Length"])
            body = self.rfile.read(content_length)

        # urllib Request 객체 생성 (Accept 헤더가 없거나 JSON-RPC 규격 대응 필요 시 기본값 주입 가능)
        if "Accept" not in headers:
            headers["Accept"] = "application/json, text/event-stream"

        req = urllib.request.Request(target_full_url, data=body, headers=headers, method=method)

        try:
            # 실제 MCP 서버로 요청 전송 및 응답 수신
            with urllib.request.urlopen(req) as response:
                response_body = response.read()

                # 프록시 클라이언트(Inspector)에게 응답 전송
                self.send_response(response.status)
                for key, value in response.headers.items():
                    if key.lower() not in ["transfer-encoding", "connection"]:
                        self.send_header(key, value)
                self.end_headers()
                self.wfile.write(response_body)

        except urllib.error.HTTPError as e:
            # 타겟 서버가 4xx, 5xx 에러를 반환했을 때 그대로 포워딩
            self.send_response(e.code)
            for key, value in e.headers.items():
                if key.lower() not in ["transfer-encoding", "connection"]:
                    self.send_header(key, value)
            self.end_headers()
            self.wfile.write(e.read())

        except Exception as e:
            # 서버 연결 실패 등의 에러 처리
            self.send_response(502)
            self.end_headers()
            self.wfile.write(f"MCP Proxy Error: {e!s}".encode())

    def do_GET(self):
        self.do_request("GET")

    def do_POST(self):
        self.do_request("POST")

    def do_PUT(self):
        self.do_request("PUT")

    def do_DELETE(self):
        self.do_request("DELETE")

    def log_message(self, format, *args):
        # 콘솔에 요청 로그 출력 (줄바꿈 오류 수정 완료)
        print(f"[Proxy Log] {self.client_address[0]} - - {format % args}")


if __name__ == "__main__":
    server_address = ("", PROXY_PORT)
    httpd = http.server.HTTPServer(server_address, MCPProxyHandler)
    print(f"🚀 MCP Python Proxy started on http://localhost:{PROXY_PORT}")
    print(f"👉 Forwards to {TARGET_URL} with X-API-KEY: {API_KEY}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n🛑 Proxy stopped.")
        httpd.server_close()
