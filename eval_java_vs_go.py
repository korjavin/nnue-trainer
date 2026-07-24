"""Evaluate the (freshly trained) Java NNUE bot against the baseline GoBot.

Starts server + one baseline GoBot (bot-hoster, deterministic, no exploration)
+ the Java bot (which challenges GoBot), plays N games, and reports the Java
bot's win rate. The Java bot loads src/main/resources/nnue_weights.json at
startup, so rebuild/restart happens via mvnw here.

Usage: python3 eval_java_vs_go.py [num_games=20]
"""
import subprocess
import time
import os
import signal
import sys
import sqlite3

N = int(sys.argv[1]) if len(sys.argv) > 1 else 20
DB = os.path.abspath("../virusgame/backend/data/games.db")
MAX_RUNTIME_S = 2 * 3600


def results():
    """(java_wins, go_wins, total) for JavaBot-vs-GoBot games after baseline."""
    try:
        con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
        rows = con.execute(
            "SELECT result, count(*) FROM games "
            "WHERE player1_name LIKE 'JavaBot%' AND player2_name LIKE 'GoBot%' "
            "AND rowid > ? GROUP BY result", (BASELINE_ROWID,)
        ).fetchall()
        con.close()
        by = dict(rows)
        jw, gw = by.get(1, 0), by.get(2, 0)
        return jw, gw, jw + gw
    except Exception:
        return 0, 0, 0


def max_rowid():
    try:
        con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
        r = con.execute("SELECT COALESCE(max(rowid), 0) FROM games").fetchone()[0]
        con.close()
        return r
    except Exception:
        return 0


def kill_group(p):
    try:
        os.killpg(os.getpgid(p.pid), signal.SIGTERM)
    except Exception:
        pass


BASELINE_ROWID = 0


def main():
    global BASELINE_ROWID
    BASELINE_ROWID = max_rowid()
    print(f"Evaluating trained Java bot vs baseline GoBot over {N} games...")

    server_log = open("server.log", "w")
    server = subprocess.Popen(
        ["./server"], cwd="../virusgame/backend",
        stdout=server_log, stderr=server_log, preexec_fn=os.setsid,
    )
    time.sleep(3)

    print("Starting baseline GoBot (deterministic, no exploration)...")
    gobot_log = open("gobot.log", "w")
    genv = os.environ.copy()
    genv["BACKEND_URL"] = "ws://localhost:8080/ws"
    genv["BOT_POOL_SIZE"] = "1"
    genv["BOT_NAME_PREFIX"] = "GoBot"
    # No BOT_CHALLENGER, no BOT_EXPLORE_EPSILON -> plain strong baseline that
    # only accepts challenges.
    gobot = subprocess.Popen(
        ["./bot-hoster"], cwd="../virusgame/backend",
        stdout=gobot_log, stderr=gobot_log, env=genv, preexec_fn=os.setsid,
    )

    print("Starting Java bot (mvnw spring-boot:run, loads trained weights)...")
    java_log = open("java_bot.log", "w")
    jenv = os.environ.copy()
    jenv["BACKEND_URL"] = "ws://localhost:8080/ws?bot=true&namePrefix=JavaBot"
    java = subprocess.Popen(
        ["./mvnw", "spring-boot:run"], cwd=".",
        stdout=java_log, stderr=java_log, env=jenv, preexec_fn=os.setsid,
    )

    def shutdown(*_):
        kill_group(java)
        kill_group(gobot)
        kill_group(server)

    signal.signal(signal.SIGINT, lambda *_: (shutdown(), sys.exit(0)))
    signal.signal(signal.SIGTERM, lambda *_: (shutdown(), sys.exit(0)))

    start = time.time()
    last = -1
    while True:
        jw, gw, total = results()
        if total != last:
            print(f"  games: {total}/{N} | Java {jw} - {gw} Go")
            last = total
        if total >= N:
            break
        if time.time() - start > MAX_RUNTIME_S:
            print("Timeout.")
            break
        time.sleep(10)

    jw, gw, total = results()
    shutdown()
    rate = (jw / total * 100) if total else 0.0
    print(f"\n=== RESULT: Java {jw} - {gw} Go over {total} games "
          f"| Java win rate {rate:.0f}% ===")


if __name__ == "__main__":
    main()
