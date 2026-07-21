import subprocess
import time
import os
import signal
import sys

def main():
    print("Starting Go backend server...")
    server_log = open("server.log", "w")
    # use preexec_fn=os.setsid to start process in new session so we can kill the entire process group
    backend_process = subprocess.Popen(
        ["./server"],
        cwd="../virusgame/backend",
        stdout=server_log,
        stderr=server_log,
        preexec_fn=os.setsid
    )

    time.sleep(3) # Wait for backend to start

    print("Starting Go bot (real bot-hoster, pool of 1)...")
    gobot_log = open("gobot.log", "w")
    gobot_env = os.environ.copy()
    # Use the real bot-hoster (backend/cmd/bot-hoster) with its actual search AI,
    # not the bot-templates/go stub. It accepts 1v1 challenges when idle.
    gobot_env["BACKEND_URL"] = "ws://localhost:8080/ws"
    gobot_env["BOT_POOL_SIZE"] = "1"
    gobot_env["BOT_NAME_PREFIX"] = "GoBot"
    gobot_process = subprocess.Popen(
        ["./bot-hoster"],
        cwd="../virusgame/backend",
        stdout=gobot_log,
        stderr=gobot_log,
        env=gobot_env,
        preexec_fn=os.setsid
    )

    time.sleep(3) # Wait for go bot to connect

    print("Starting Java bot...")
    java_log = open("java_bot.log", "w")
    java_env = os.environ.copy()
    java_env["BACKEND_URL"] = "ws://localhost:8080/ws?bot=true&namePrefix=JavaBot"
    java_process = subprocess.Popen(
        ["./mvnw", "spring-boot:run"],
        cwd=".",
        stdout=java_log,
        stderr=java_log,
        env=java_env,
        preexec_fn=os.setsid
    )

    print("Bots are running. Sparring should be taking place!")
    print("Press Ctrl+C to stop.")

    def kill_process_group(process):
        try:
            os.killpg(os.getpgid(process.pid), signal.SIGTERM)
        except Exception:
            pass

    def signal_handler(sig, frame):
        print("\nStopping processes...")
        kill_process_group(java_process)
        kill_process_group(gobot_process)
        kill_process_group(backend_process)
        sys.exit(0)

    signal.signal(signal.SIGINT, signal_handler)

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    
    print("\nStopping processes...")
    kill_process_group(java_process)
    kill_process_group(gobot_process)
    kill_process_group(backend_process)

if __name__ == "__main__":
    main()
