#!/usr/bin/env python3
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import frida
import json
import logging
import threading
import subprocess
import argparse
import os
import sys
from jdwp_frida import run_jdwp

logging.basicConfig(level=logging.INFO)

# Configures environment variables so bundled Frida binaries can be located when running as a PyInstaller executable
def setup_runtime_env():
    if hasattr(sys, '_MEIPASS'):
        mei_path = sys._MEIPASS
        # Frida puts its binaries/helpers in a subdirectory within the package
        # We need to find where _frida.abi3.so and friends are
        frida_dir = os.path.join(mei_path, 'frida')
        
        # Add both to library search paths
        for env_var in ['DYLD_LIBRARY_PATH', 'LD_LIBRARY_PATH']:
            current = os.environ.get(env_var, '')
            paths = [mei_path, frida_dir]
            if current:
                paths.append(current)
            os.environ[env_var] = ':'.join(paths)
        
        # Add to PATH for helper binaries
        current_path = os.environ.get('PATH', '')
        os.environ['PATH'] = f"{mei_path}:{frida_dir}:{current_path}"
        
        logging.info(f"Runtime environment setup. _MEIPASS: {mei_path}")

setup_runtime_env()

# Resolves the absolute path for bundled resources, handling both dev environments and PyInstaller extraction paths
def get_resource_path(relative_path):
    """ Get absolute path to resource, works for dev and for PyInstaller """
    try:
        # PyInstaller creates a temp folder and stores path in _MEIPASS
        base_path = sys._MEIPASS
    except Exception:
        base_path = os.path.dirname(os.path.abspath(__file__))

    return os.path.join(base_path, relative_path)

# Custom HTTP request handler to process JSON-RPC calls and health checks
class RpcHandler(BaseHTTPRequestHandler):
    
    # Handles GET requests, specifically acting as a health check endpoint on '/ping'
    def do_GET(self):
        if self.path == '/ping':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({"status": "pong"}).encode('utf-8'))
        else:
            self.send_response(404)
            self.end_headers()

    # Handles incoming POST requests for the JSON-RPC interface on '/rpc', parsing parameters and returning results
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

    # Suppresses the default HTTP server logging output to keep the console clean
    def log_message(self, format, *args):
        pass

    # Overrides the default handle method to gracefully ignore BrokenPipeErrors caused by disconnected clients
    def handle(self):
        try:
            super().handle()
        except BrokenPipeError:
            pass

# Main bridge class that orchestrates Frida sessions, ADB connections, and Gadget injection
class FridaBridge:
    
    # Initializes the bridge state, threading locks, and default network targets
    def __init__(self, serial=None):
        self.device = None
        self.session = None
        self.script = None
        self.serial = serial
        self._lock = threading.Lock()
        self.gadget_port = 8700
        self.gadget_target = "127.0.0.1"
        self.is_injecting_gadget = False

    # Connects to the appropriate Frida device object via USB or specific ADB serial
    def _get_device(self):
        try:
            if self.serial:
                logging.info(f"[_get_device] Targeting specific device serial: {self.serial}")
                return frida.get_device(self.serial, timeout=60)
            else:
                return frida.get_usb_device(timeout=60)
        except Exception as e:
            raise Exception(f"Failed to find device: {e}")

    # Retrieves the frontmost application directly using the Frida API
    def _get_front_app(self, device):
        return device.get_frontmost_application()

    # Fallback method to discover the frontmost application's package name using ADB shell dumpsys
    def _get_front_app_using_adb(self):
        logging.info("[_get_front_app_using_adb] Failed to get frontmost app after retries, attempting fallback via adb...")
        try:
            adb_cmd = ["adb"]
            if self.serial:
                adb_cmd.extend(["-s", self.serial])
            adb_cmd.extend(["shell", "dumpsys", "window", "|", "grep", "-E", "\"mCurrentFocus\"", "|", "xargs", "|", "cut", "-d' '", "-f3", "|", "cut", "-d'/'", "-f1"])
            result = subprocess.run(adb_cmd, capture_output=True, text=True)
            if result.returncode == 0:
                package_name = result.stdout.strip()
                logging.info(f"[_get_front_app_using_adb] Fallback got frontmost package: {package_name}")
                return package_name
            else:
                logging.warning(f"[_get_front_app_using_adb] ADB fallback failed: {result.stderr}")
        except Exception as e:
            logging.warning(f"ADB fallback exception: {e}")

    # Fallback method to get the PID of a given package name using ADB shell pidof
    def _get_front_app_pid_using_adb(self, package_name):
        adb_cmd = ["adb"]
        if self.serial:
            adb_cmd.extend(["-s", self.serial])
        adb_cmd.extend(["shell", "pidof", package_name])
        result = subprocess.run(adb_cmd, capture_output=True, text=True)
        if result.returncode == 0:
            pid = int(result.stdout.strip())
            logging.info(f"[_get_front_app_pid_using_adb] Got PID {pid} for package {package_name} via adb fallback")
            return pid
        else:
            raise Exception(f"[_get_front_app_pid_using_adb] Could not get pid of frontmost app: {result.stderr}")

    # Orchestrates the retrieval of the target app's PID and package, trying Frida first, then falling back to ADB
    def _get_application_pid_and_package(self):
        try:
            device = self._get_device()
            front_app = self._get_front_app(device)
            if front_app:
                logging.info(f"[_get_application_pid_and_package] Got frontmost app: {front_app.identifier} (PID: {front_app.pid})")
                return front_app.pid, front_app.identifier
        except Exception as e:
            logging.warning(f"[_get_application_pid_and_package] Error getting frontmost app: {e}")
        package_name = self._get_front_app_using_adb()
        pid = self._get_front_app_pid_using_adb(package_name)
        return pid, package_name

    # Checks if the Frida Gadget library is on the device, downloads it if missing based on architecture, and pushes it via ADB
    def _pushGadget(self):
        adb_base = ["adb"]
        if self.serial:
            adb_base.extend(["-s", self.serial])

        check = subprocess.run(
            adb_base + ["shell", "ls", "/data/local/tmp/frida-gadget.so"],
            capture_output=True, text=True
        )

        if check.returncode == 0:
            logging.info("[_pushGadget] frida-gadget.so already on device, skipping push")
            return { "status": "ok" }

        logging.info("[_pushGadget] frida-gadget.so not found on device, downloading and pushing...")

        device_arch = subprocess.run(
            adb_base + ["shell", "uname", "-m"],
            capture_output=True, text=True
        ).stdout.strip()

        device_arch_parsed = "arm64" if "aarch64" in device_arch else ("x86_64" if "x86_64" in device_arch else "unknown")

        logging.info(f"[_pushGadget] Device architecture: {device_arch_parsed}")

        cache_dir = os.path.expanduser("~/.cache/idk")
        os.makedirs(cache_dir, exist_ok=True)
        gadget_path = os.path.join(cache_dir, "frida-gadget.so")

        frida_version = "17.9.1"  # Update this version as needed
        download_url = f"https://github.com/frida/frida/releases/download/{frida_version}/frida-gadget-{frida_version}-android-{device_arch_parsed}.so.xz"

        logging.info(f"[_pushGadget] Downloading Frida gadget from {download_url}...")
        try:
            import urllib.request
            urllib.request.urlretrieve(download_url, gadget_path)
            logging.info(f"[_pushGadget] Frida gadget downloaded to {gadget_path}")
        except Exception as e:
            raise Exception(f"[_pushGadget] Failed to download Frida gadget: {e}")
        
        # adb push to device
        r = subprocess.run(
            adb_base + ["push", gadget_path, "/data/local/tmp/frida-gadget.so"],
            capture_output=True, text=True
        )
        if r.returncode != 0:
            raise Exception(f"[_pushGadget] adb push failed: {r.stderr}")
        logging.info(f"[_pushGadget] adb push ok: {r.stdout.strip()}")

    # Clears current Frida sessions and sets up the initial ADB port forward required for JDWP communication
    def _prepare_gadget(self, pid):
        self._detach_frida()

        logging.info(f"[_prepare_gadget] Setting up adb forward tcp:{self.gadget_port} jdwp:{pid}")
        adb_cmd = ["adb"]
        if self.serial:
            adb_cmd.extend(["-s", self.serial])
        adb_cmd.extend(["forward", f"tcp:{self.gadget_port}", f"jdwp:{pid}"])

        r = subprocess.run(
            adb_cmd,
            capture_output=True, text=True
        )
        if r.returncode != 0:
            raise Exception(f"[_prepare_gadget] adb forward failed: {r.stderr}")

        logging.info(f"[_prepare_gadget] adb forward ok, waiting for JDWP to initialize...")

    # Utility method to identify the foreground app's package name and PID directly via ADB commands
    def _get_front_package_and_pid(self):
            """Descobre o package e o PID do app em primeiro plano via ADB."""
            adb_cmd = ["adb"]
            if self.serial:
                adb_cmd.extend(["-s", self.serial])

            logging.info("[inject] Getting frontmost package and PID...")
            pkg_cmd = adb_cmd.copy() + ["shell", "dumpsys", "window", "|", "grep", "-E", "\"mCurrentFocus\"", "|", "xargs", "|", "cut", "-d' '", "-f3", "|", "cut", "-d'/'", "-f1"]
            r_pkg = subprocess.run(" ".join(pkg_cmd), shell=True, capture_output=True, text=True)
            if r_pkg.returncode != 0:
                raise Exception(f"Failed to get frontmost package: {r_pkg.stderr}")
            
            package_name = r_pkg.stdout.strip()

            pid_cmd = adb_cmd.copy() + ["shell", "pidof", package_name]
            r_pid = subprocess.run(" ".join(pid_cmd), shell=True, capture_output=True, text=True)
            if r_pid.returncode != 0:
                raise Exception(f"Failed to get PID for {package_name}: {r_pid.stderr}")
            
            pid = r_pid.stdout.strip()
            logging.info(f"[inject] Target: {package_name} (PID: {pid})")
            return package_name, pid

    # Idempotent helper to map local TCP ports to the device's JDWP and Gadget ports only if not already mapped
    def _setup_forwards_if_needed(self, pid):
        """Só recria os forwards do ADB se eles não existirem para o PID atual."""
        adb_cmd = ["adb"]
        if self.serial:
            adb_cmd.extend(["-s", self.serial])

        # Verifica o estado atual
        r_list = subprocess.run(adb_cmd + ["forward", "--list"], capture_output=True, text=True)
        current_forwards = r_list.stdout.strip()

        expected_jdwp = f"tcp:{self.gadget_port} jdwp:{pid}"
        expected_gadget = "tcp:27042 tcp:27042"

        if expected_jdwp in current_forwards and expected_gadget in current_forwards:
            logging.info("[inject] ADB forwards are already set up correctly. Skipping.")
            return

        logging.info("[inject] Setting up new ADB forwards...")
        subprocess.run(adb_cmd + ["forward", "--remove-all"], capture_output=True)

        r_jdwp = subprocess.run(adb_cmd + ["forward", f"tcp:{self.gadget_port}", f"jdwp:{pid}"], capture_output=True, text=True)
        if r_jdwp.returncode != 0:
            raise Exception(f"Failed to set JDWP forward: {r_jdwp.stderr}")

        r_gadget = subprocess.run(adb_cmd + ["forward", "tcp:27042", "tcp:27042"], capture_output=True, text=True)
        if r_gadget.returncode != 0:
            raise Exception(f"Failed to set Gadget forward: {r_gadget.stderr}")

    # Performs a silent TCP socket check on localhost to verify if the Gadget is already listening
    def _is_gadget_listening(self):
        """Faz um 'ping' silencioso na porta TCP para ver se o Gadget já está rodando."""
        import socket
        try:
            with socket.create_connection(('127.0.0.1', 27042), timeout=1):
                return True
        except OSError:
            return False

    # Establishes connection to the remote Frida Gadget TCP server and conditionally loads the JavaScript agent
    def _connect_and_load_agent(self, pid):
        """Conecta a sessão do Frida e carrega o agente apenas se necessário."""
        logging.info("[inject] Connecting to remote Gadget...")
        device_manager = frida.get_device_manager()
        self.device = device_manager.add_remote_device('127.0.0.1:27042')
        
        # Pequeno delay para garantir que a ponte de rede do Frida estabilizou
        time.sleep(1) 
        
        self.session = self.device.attach("Gadget")
        self.session._pid = int(pid)

        # Evita recarregar o JS se ele já estiver injetado e ativo nesta sessão
        if self.script and not getattr(self.script, "is_destroyed", False):
            logging.info("[inject] Agent is already loaded. Skipping JS injection.")
            return

        logging.info("[inject] Successfully attached to process, loading Frida agent...")
        agent_path = get_resource_path('agent.bundle.js')
        with open(agent_path, 'r', encoding='utf-8') as f:
            source = f.read()

        self.script = self.session.create_script(source)
        self.script.load()
        logging.info("[inject] Agent loaded successfully.")

    # =================================================================
    # FUNÇÃO ORQUESTRADORA PRINCIPAL
    # =================================================================

    # Main orchestrator function that safely executes the entire JDWP gadget injection lifecycle
    def inject_gadget_from_scratch(self):
        with self._lock:
            if self.is_injecting_gadget:
                while self.is_injecting_gadget:
                    logging.info("Already injecting gadget, waiting for current injection to finish...")
                    time.sleep(1)
                return
                
            self.is_injecting_gadget = True
            try:
                logging.info("\n=== [ STARTING INJECTION SEQUENCE ] ===")
                
                # 1. Obter alvo
                package_name, pid = self._get_front_package_and_pid()

                self._setup_forwards_if_needed(pid)
            
                logging.info("[*] Gadget not detected. Proceeding with JDWP injection...")
                self._pushGadget()
                
                adb_clear_cmd = f"adb {'-s ' + self.serial if self.serial else ''} shell am clear-debug-app"
                subprocess.run(adb_clear_cmd, shell=True, capture_output=True)

                self._inject_with_retry(package_name)
                time.sleep(2)

                self._connect_and_load_agent(pid)
                
                logging.info("=== [ INJECTION SEQUENCE COMPLETED ] ===\n")

            finally:
                self.is_injecting_gadget = False

    # Returns the active Frida session, creating a new attachment and script load if the current one is invalid
    def get_session(self):
        with self._lock:
            while self.is_injecting_gadget:
                logging.info("Waiting for gadget injection to complete...")
                time.sleep(1)
            device = self._get_device()
            front_app = self._get_front_app(device)
            if not front_app:
                raise Exception("No frontmost application found on device")
            
            if self.session:
                try:
                    if not self.session.is_detached and getattr(self.session, "_pid", None) == front_app.pid:
                        return self.session
                except:
                    pass

            logging.info(f"[get_session] Attaching to {front_app.identifier} (PID: {front_app.pid})")
            self.session = device.attach(front_app.identifier)
            self.session._pid = front_app.pid

            logging.info("[get_session] Successfully attached to process, now loading Frida agent...")
            agent_path = get_resource_path('agent.bundle.js')
            logging.info(f"[get_session] Loading Frida agent from: {agent_path}")
            
            with open(agent_path, 'r', encoding='utf-8') as f:
                source = f.read()

            self.script = self.session.create_script(source)
            self.script.load()

            return self.session

    # Gracefully disconnects the current Frida session and unloads the loaded script
    def _detach_frida(self):
        if self.session and not self.session.is_detached:
            logging.info("Detaching Frida session...")
            try:
                self.session.detach()
            except Exception as e:
                logging.warning(f"Error detaching: {e}")
            self.session = None
            self.script = None

    # Forces the bridge to clear the existing connection state and reconnect to the target application
    def _reattach_frida(self):
        logging.info("Re-attaching Frida session...")
        try:
            self.get_session()
            logging.info("Frida re-attached successfully")
        except Exception as e:
            logging.warning(f"Failed to re-attach Frida: {e}")

    # Executes the external JDWP exploitation script to load the injected payload library
    def _run_jdwp(self, cmd=None, break_on="android.os.Handler.dispatchMessage", package_name=None):
        return run_jdwp(
            target=self.gadget_target,
            port=self.gadget_port,
            cmd=cmd,
            break_on=break_on,
            package_name=package_name,
            serial=self.serial
        )

    # Force closes and subsequently restarts the target Android application using ADB monkey events
    def _force_restart_app(self, package_name):
        adb_base = ["adb"]
        if self.serial:
            adb_base.extend(["-s", self.serial])
            
        logging.info(f"[*] Forcing stop of app: {package_name}")
        
        subprocess.run(
            adb_base + ["shell", "am", "force-stop", package_name],
            capture_output=True
        )
        
        time.sleep(1) 
        
        logging.info(f"[*] Restarting app: {package_name}")
        
        r_start = subprocess.run(
            adb_base + ["shell", "monkey", "-p", package_name, "-c", "android.intent.category.LAUNCHER", "1"],
            capture_output=True, text=True
        )
        
        if r_start.returncode != 0 or "monkey aborted" in r_start.stderr.lower():
            raise Exception(f"[-] Failed to restart the app: {r_start.stderr}")
            
        logging.info("[+] App restarted successfully, waiting for it to come up...")
        time.sleep(3)

    # Attempts JDWP injection and applies an automatic app-restart fallback if the JDWP port is busy/locked
    def _inject_with_retry(self, package_name):
        try:
            result = self._run_jdwp(package_name=package_name)
        except Exception as e:
            pass
        try:
            if result.get("status") == "unknown_error" and "Failed to handshake" in result.get("error_message", ""):
                self._force_restart_app(package_name)
                
                new_pid = self._get_front_app_pid_using_adb(package_name)
                
                adb_cmd = ["adb"]
                if self.serial:
                    adb_cmd.extend(["-s", self.serial])
                
                subprocess.run(" ".join(adb_cmd + ["forward", f"tcp:{self.gadget_port}", f"jdwp:{new_pid}"]), shell=True, capture_output=True)
                subprocess.run(" ".join(adb_cmd + ["forward", "tcp:27042", "tcp:27042"]), shell=True, capture_output=True)
                
                result = self._run_jdwp(package_name=package_name)
                
            if result.get("status") not in ["completed", "gadget_detected"]:
                raise Exception(f"Injection failed: {result}; Suggestion: Try closing Android Studio, normally the JDWP port is locked by the IDE's debugger.")
                
        except Exception as e:
            raise e

    # RPC endpoint: Retrieves loaded Java classes with a custom sorting heuristic based on target package
    def list_classes(self, search_param="", app_package="", offset=0, limit=200):
        self.get_session()
        classes = self.script.exports_sync.listclasses(search_param)
        
        def get_priority(class_name):
            priority = 0
            if app_package:
                parts = app_package.split(".")
                first_two = ".".join(parts[:2]) if len(parts) >= 2 else ""
                
                if class_name.startswith(f"{app_package}.") or class_name == app_package:
                    priority = 3
                elif first_two and (class_name.startswith(f"{first_two}.") or class_name == first_two):
                    priority = 2
                else:
                    priority = 1
            else:
                priority = 1
                
            if "[" in class_name:
                priority = -1
                
            if search_param and priority >= 0:
                simple_name = class_name.split(".")[-1]
                if simple_name.lower().startswith(search_param.lower()) or class_name.lower().startswith(search_param.lower()):
                    priority += 10
                    
            return priority
            
        classes.sort(key=lambda c: (-get_priority(c), c))
        return classes[offset:offset+limit]

    # RPC endpoint: Counts the number of active instances of a specified Java class
    def count_instances(self, class_name):
        self.get_session()
        count = self.script.exports_sync.countinstances(class_name)
        if count == -1:
            raise Exception(f"Failed to count instances for {class_name}")
        return count

    # Routing logic mapping string method names from the JSON-RPC payload to their internal Python implementations
    def handle_rpc(self, method, params):
        if method == "listClasses":
            return self.list_classes(
                search_param=params.get("search_param", ""),
                app_package=params.get("app_package", ""),
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
            pid, package_name = self._get_application_pid_and_package()
            self._prepare_gadget(pid)

            return {
                "pid": pid,
                "package_name": package_name,
                "port": self.gadget_port,
                "target": self.gadget_target
            }

        elif method == "checkOrPushGadget":
            self._pushGadget()

            return { "status": "ok" }

        elif method == "injectGadgetFromScratch":
            self.inject_gadget_from_scratch()
            return { "status": "ok" }
        elif method == "injectJdwp":
            result = self._run_jdwp(
                cmd=params.get("cmd"),
                break_on=params.get("break_on", "android.os.Handler.dispatchMessage"),
                package_name=params.get("package_name"),
            )
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

# Bootstraps and starts the JSON-RPC local HTTP server blocking the main thread
def run_server(port=8080, serial=None):
    server = ThreadingHTTPServer(('127.0.0.1', port), RpcHandler)
    server.bridge = FridaBridge(serial=serial)
    logging.info(f"[run_server] Starting JSON-RPC Bridge on http://127.0.0.1:{port}...")
    if serial:
        logging.info(f"[run_server] Targeting ADB serial: {serial}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    server.server_close()
    logging.info("Server stopped.")

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='idk JSON-RPC Bridge')
    parser.add_argument('--port', type=int, default=8080, help='Listen port (default: 8080)')
    parser.add_argument('--serial', type=str, help='Target ADB device serial number')
    args = parser.parse_args()
    
    run_server(port=args.port, serial=args.serial)
