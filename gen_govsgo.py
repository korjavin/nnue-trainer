"""Generate GoBot-vs-GoBot games for NNUE training.

Starts the Go backend and ONE bot-hoster pool in challenger mode (even-indexed
bots challenge odd-indexed acceptor peers), then waits until N new go-vs-go games
have landed in games.db and shuts everything down.

Usage: python3 gen_govsgo.py [target_games=200] [pool_size=6]
"""
import subprocess
import time
import os
import signal
import sys
import sqlite3

TARGET = int(sys.argv[1]) if len(sys.argv) > 1 else 200
POOL = int(sys.argv[2]) if len(sys.argv) > 2 else 6
EPSILON = sys.argv[3] if len(sys.argv) > 3 else "0.1"  # exploration for game diversity
DB = os.path.abspath("../virusgame/backend/data/games.db")
MAX_RUNTIME_S = 3 * 3600  # safety cap


def govsgo_count():
    try:
        con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
        n = con.execute(
            "SELECT count(*) FROM games "
            "WHERE player1_name LIKE 'GoBot%' AND player2_name LIKE 'GoBot%'"
        ).fetchone()[0]
        con.close()
        return n
    except Exception:
        return 0


def kill_group(p):
    try:
        os.killpg(os.getpgid(p.pid), signal.SIGTERM)
    except Exception:
        pass


def main():
    print(f"Target: {TARGET} new go-vs-go games | pool size: {POOL} | epsilon: {EPSILON}")

    print("Starting Go backend server...")
    server_log = open("server.log", "w")
    server = subprocess.Popen(
        ["./server"], cwd="../virusgame/backend",
        stdout=server_log, stderr=server_log, preexec_fn=os.setsid,
    )
    time.sleep(3)

    print("Starting bot-hoster (challenger mode)...")
    gobot_log = open("gobot.log", "w")
    env = os.environ.copy()
    env["BACKEND_URL"] = "ws://localhost:8080/ws"
    env["BOT_POOL_SIZE"] = str(POOL)
    env["BOT_CHALLENGER"] = "true"
    env["BOT_NAME_PREFIX"] = "GoBot"
    env["BOT_EXPLORE_EPSILON"] = EPSILON
    hoster = subprocess.Popen(
        ["./bot-hoster"], cwd="../virusgame/backend",
        stdout=gobot_log, stderr=gobot_log, env=env, preexec_fn=os.setsid,
    )

    baseline = govsgo_count()
    print(f"Baseline go-vs-go games already in db: {baseline}")

    def shutdown(*_):
        print("\nStopping...")
        kill_group(hoster)
        kill_group(server)
        sys.exit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    start = time.time()
    last = -1
    while True:
        made = govsgo_count() - baseline
        if made != last:
            print(f"  go-vs-go games generated: {made}/{TARGET}")
            last = made
        if made >= TARGET:
            print(f"Done: generated {made} go-vs-go games.")
            break
        if time.time() - start > MAX_RUNTIME_S:
            print(f"Timeout after {MAX_RUNTIME_S}s with {made}/{TARGET} games.")
            break
        time.sleep(10)

    kill_group(hoster)
    kill_group(server)


if __name__ == "__main__":
    main()
