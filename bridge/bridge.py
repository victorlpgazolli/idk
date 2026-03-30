#!/usr/bin/env python3
import frida
import json
import logging
from http.server import BaseHTTPRequestHandler, HTTPServer
import threading

logging.basicConfig(level=logging.INFO)

class RpcHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/ping':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({"status": "pong"}).encode('utf-8'))
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path == '/rpc':
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)
            try:
                req = json.loads(post_data.decode('utf-8'))
                method = req.get('method')
                params = req.get('params', {})
                result = self.server.bridge.handle_rpc(method, params)
                res = {
                    "jsonrpc": "2.0",
                    "result": result,
                    "id": req.get("id")
                }
                try:
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    self.wfile.write(json.dumps(res).encode('utf-8'))
                except BrokenPipeError:
                    pass
            except Exception as e:
                logging.error(f"Error handling RPC: {e}")
                err_res = {
                    "jsonrpc": "2.0",
                    "error": {"code": -32603, "message": str(e)},
                    "id": req.get("id") if 'req' in locals() and isinstance(req, dict) else None
                }
                try:
                    self.send_response(500)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    self.wfile.write(json.dumps(err_res).encode('utf-8'))
                except BrokenPipeError:
                    pass
        else:
            self.send_response(404)
            self.end_headers()

    # Disable logging per request to keep console clean
    def log_message(self, format, *args):
        pass

    def handle(self):
        try:
            super().handle()
        except BrokenPipeError:
            pass

class FridaBridge:
    def __init__(self):
        self.device = None
        self.session = None
        self.script = None
        self._lock = threading.Lock()

    def _get_device(self):
        if not self.device:
            self.device = frida.get_usb_device(timeout=2)
        return self.device

    def get_session(self):
        with self._lock:
            device = self._get_device()
            front_app = device.get_frontmost_application()
            if not front_app:
                raise Exception("No frontmost application found on device")
            
            # If we already have a session for the SAME PID and it's not detached
            if self.session:
                try:
                    if not self.session.is_detached and getattr(self.session, "_pid", None) == front_app.pid:
                        return self.session
                except:
                    pass
            
            # Create new session
            logging.info(f"Attaching to {front_app.identifier} (PID: {front_app.pid})")
            self.session = device.attach(front_app.pid)
            self.session._pid = front_app.pid
            
            # Compile and load script ONCE per session
            import os
            agent_path = os.path.join(os.path.dirname(__file__), 'agent.js')
            logging.info("Compiling Frida agent...")
            compiler = frida.Compiler()
            
            def on_compiler_diagnostics(diag):
                logging.warning(f"Compiler Diagnostics: {diag}")
                
            compiler.on('diagnostics', on_compiler_diagnostics)
            bundle = compiler.build(agent_path)
            
            self.script = self.session.create_script(bundle)
            self.script.load()
            
            return self.session

    def list_classes(self, search_param="", offset=0, limit=200):
        self.get_session() # Ensures session and self.script are loaded
        
        # Call the exported JS function
        classes = self.script.exports_sync.listclasses(search_param)
        classes.sort()
        return classes[offset:offset+limit]

    def handle_rpc(self, method, params):
        if method == "listClasses":
            return self.list_classes(
                search_param=params.get("search_param", ""),
                offset=params.get("offset", 0),
                limit=params.get("limit", 200)
            )
        else:
            raise Exception(f"Method {method} not found")

def run_server(port=8080):
    server = HTTPServer(('127.0.0.1', port), RpcHandler)
    server.bridge = FridaBridge()
    logging.info(f"Starting JSON-RPC Bridge on http://127.0.0.1:{port}...")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    server.server_close()
    logging.info("Server stopped.")

if __name__ == '__main__':
    run_server()
