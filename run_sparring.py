import subprocess
import time
import os
import signal
import sys

def main():
    print("Starting Go backend server...")
    # use preexec_fn=os.setsid to start process in new session so we can kill the entire process group
    backend_process = subprocess.Popen(
        ["go", "run", "main.go"],
        cwd="../virusgame/backend",
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        preexec_fn=os.setsid
    )

    time.sleep(3) # Wait for backend to start

    print("Starting Go bot...")
    # Use glob to run the go bot
    gobot_process = subprocess.Popen(
        "go run bot-templates/go/*.go",
        cwd="../virusgame",
        shell=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        preexec_fn=os.setsid
    )

    time.sleep(3) # Wait for go bot to connect

    print("Starting Java bot...")
    java_process = subprocess.Popen(
        ["./mvnw", "spring-boot:run"],
        cwd=".",
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
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
