#!/usr/bin/env python3
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import frida
import json
import logging
import threading
import subprocess
from jdwp_frida import run_jdwp

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
                res = {"jsonrpc": "2.0", "result": result, "id": req.get("id")}
                try:
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    self.wfile.write(json.dumps(res).encode('utf-8'))
                except BrokenPipeError:
                    pass
            except Exception as e:
                logging.error(f"Error handling RPC: {e}")
                logging.error(f"Request body: {post_data.decode('utf-8', errors='replace')}")
                traceback.print_exc()
                err_res = {
                    "jsonrpc": "2.0",
                    "error": {"status": "unknown_error", "error_message": str(e)},
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
            
            if self.session:
                try:
                    if not self.session.is_detached and getattr(self.session, "_pid", None) == front_app.pid:
                        return self.session
                except:
                    pass

            logging.info(f"Attaching to {front_app.identifier} (PID: {front_app.pid})")
            self.session = device.attach(front_app.pid)
            self.session._pid = front_app.pid

            import os
            agent_path = os.path.join(os.path.dirname(__file__), 'agent.js')
            logging.info("Compiling Frida agent...")
            compiler = frida.Compiler()
            compiler.on('diagnostics', lambda diag: logging.warning(f"Compiler: {diag}"))
            bundle = compiler.build(agent_path)

            self.script = self.session.create_script(bundle)
            self.script.load()

            return self.session

    def _detach_frida(self):
        if self.session and not self.session.is_detached:
            logging.info("Detaching Frida session...")
            try:
                self.session.detach()
            except Exception as e:
                logging.warning(f"Error detaching: {e}")
            self.session = None
            self.script = None

    def _reattach_frida(self):
        logging.info("Re-attaching Frida session...")
        try:
            self.get_session()
            logging.info("Frida re-attached successfully")
        except Exception as e:
            logging.warning(f"Failed to re-attach Frida: {e}")

    def list_classes(self, search_param="", offset=0, limit=200):
        self.get_session()
        classes = self.script.exports_sync.listclasses(search_param)
        classes.sort()
        return classes[offset:offset+limit]

    def count_instances(self, class_name):
        self.get_session()
        count = self.script.exports_sync.countinstances(class_name)
        if count == -1:
            raise Exception(f"Failed to count instances for {class_name}")
        return count

    def handle_rpc(self, method, params):
        if method == "listClasses":
            return self.list_classes(
                search_param=params.get("search_param", ""),
                offset=params.get("offset", 0),
                limit=params.get("limit", 200)
            )

        elif method == "inspectClass":
            self.get_session()
            return self.script.exports_sync.inspectclass(params.get("className", ""))

        elif method == "countInstances":
            return self.count_instances(params.get("className", ""))

        elif method == "listInstances":
            self.get_session()
            return self.script.exports_sync.listinstances(params.get("className", ""))

        elif method == "inspectInstance":
            self.get_session()
            return self.script.exports_sync.inspectinstance(
                params.get("className", ""), 
                params.get("id", ""),
                params.get("offset", 0),
                params.get("limit", 50)
            )

        elif method == "setFieldValue":
            self.get_session()
            return self.script.exports_sync.setfieldvalue(
                params.get("className", ""), 
                params.get("id", ""), 
                params.get("fieldName", ""), 
                params.get("type", ""), 
                params.get("newValue", "")
            )

        elif method == "prepareEnvironment":
            port = 8700
            target = "127.0.0.1"

            device = self._get_device()
            front_app = device.get_frontmost_application()
            if not front_app:
                raise Exception("No frontmost application found")

            pid = front_app.pid
            package_name = front_app.identifier

            self._detach_frida()

            logging.info(f"Setting up adb forward tcp:{port} jdwp:{pid}")
            r = subprocess.run(
                ["adb", "forward", f"tcp:{port}", f"jdwp:{pid}"],
                capture_output=True, text=True
            )
            if r.returncode != 0:
                raise Exception(f"adb forward failed: {r.stderr}")

            logging.info("adb forward ok, waiting for JDWP to initialize...")
            time.sleep(1)

            return {
                "pid": pid,
                "package_name": package_name,
                "port": port,
                "target": target
            }

        elif method == "checkOrPushGadget":
            if params.get("loadlib"):
                check = subprocess.run(
                    ["adb", "shell", "ls", "/data/local/tmp/frida-gadget.so"],
                    capture_output=True, text=True
                )
                if check.returncode == 0:
                    logging.info("frida-gadget.so already on device, skipping push")
                else:
                    logging.info(f"Pushing {params['loadlib']}...")
                    r = subprocess.run(
                        ["adb", "push", params["loadlib"], "/data/local/tmp/frida-gadget.so"],
                        capture_output=True, text=True
                    )
                    if r.returncode != 0:
                        raise Exception(f"adb push failed: {r.stderr}")
                    logging.info(f"adb push ok: {r.stdout.strip()}")
            else:
                check = subprocess.run(
                    ["adb", "shell", "ls", "/data/local/tmp/frida-gadget.so"],
                    capture_output=True, text=True
                )
                if check.returncode != 0:
                    raise Exception("frida-gadget.so not found on device and no loadlib provided")

            return { "status": "ok" }

        elif method == "injectJdwp":
            try:
                result = run_jdwp(
                    target=params.get("target", "127.0.0.1"),
                    port=params.get("port", 8700),
                    cmd=params.get("cmd"),
                    loadlib=params.get("loadlib"),
                    break_on=params.get("break_on", "android.os.Handler.dispatchMessage"),
                    package_name=params.get("package_name")
                )
            except Exception as e:
                # run_jdwp failed, best-effort re-attach
                try:
                    self._reattach_frida()
                except Exception:
                    pass
                raise

            # run_jdwp succeeded, re-attach frida for subsequent operations
            try:
                self._reattach_frida()
            except Exception as reattach_err:
                raise Exception(f"unable to re-attach to frida-server: {reattach_err}")

            return result

        elif method == "getpackagename":
            self.get_session()
            return self.script.exports_sync.getpackagename()

        elif method == "hookMethod":
            self.get_session()
            return self.script.exports_sync.hookmethod(params.get("className"), params.get("methodSig"))
        
        elif method == "unhookMethod":
            self.get_session()
            return self.script.exports_sync.unhookmethod(params.get("className"), params.get("methodSig"))

        elif method == "getHookEvents":
            self.get_session()
            return self.script.exports_sync.gethookevents()

        else:
            raise Exception(f"Method {method} not found")

def run_server(port=8080):
    server = ThreadingHTTPServer(('127.0.0.1', port), RpcHandler)
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
