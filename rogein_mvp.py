#!/usr/bin/env python3
"""MVP result processor for rogaining CSV files.

Features:
- Import CSV into SQLite
- Store settings and manual corrections
- Recalculate results with validation and penalties
- Print and export computed results
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import io
import json
import math
import os
import signal
import sqlite3
import time
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlparse


DEFAULT_SETTINGS = {
    "control_minutes": "240",
    "penalty_per_minute": "1",
    "dq_minutes": "60",
    "finish_cp": "240",
}

WEB_DIR = Path(__file__).parent / "web"


class CSVFormatError(ValueError):
    """CSV has invalid structure or values."""


@dataclass
class Mark:
    cp_number: int
    mark_time: dt.datetime
    seq: int


@dataclass
class ExclusionRule:
    from_cp: int
    to_cp: int
    direction: str  # forward | reverse | both
    apply_mode: str  # once | always
    max_leg_seconds: int | None
    source: str


def parse_time(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.strip())


def connect_db(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        PRAGMA foreign_keys = ON;

        CREATE TABLE IF NOT EXISTS participants (
            participant_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            start_station_id INTEGER NOT NULL,
            start_time TEXT NOT NULL,
            source_row INTEGER
        );

        CREATE TABLE IF NOT EXISTS marks_raw (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            participant_id TEXT NOT NULL,
            seq INTEGER NOT NULL,
            cp_number INTEGER NOT NULL,
            mark_time TEXT NOT NULL,
            FOREIGN KEY (participant_id) REFERENCES participants(participant_id) ON DELETE CASCADE
        );

        CREATE INDEX IF NOT EXISTS idx_marks_raw_participant
            ON marks_raw(participant_id);

        CREATE TABLE IF NOT EXISTS corrections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            participant_id TEXT NOT NULL,
            correction_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (participant_id) REFERENCES participants(participant_id) ON DELETE CASCADE
        );

        CREATE INDEX IF NOT EXISTS idx_corrections_participant
            ON corrections(participant_id, id);

        CREATE TABLE IF NOT EXISTS leg_exclusion_rules (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            participant_id TEXT NULL,
            from_cp INTEGER NOT NULL,
            to_cp INTEGER NOT NULL,
            direction TEXT NOT NULL DEFAULT 'forward',
            apply_mode TEXT NOT NULL DEFAULT 'once',
            max_leg_seconds INTEGER NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (participant_id) REFERENCES participants(participant_id) ON DELETE CASCADE
        );

        CREATE INDEX IF NOT EXISTS idx_leg_exclusion_rules_participant
            ON leg_exclusion_rules(participant_id, id);

        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS results (
            participant_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            status TEXT NOT NULL,
            points_raw INTEGER NOT NULL,
            penalty_points INTEGER NOT NULL,
            points_final INTEGER NOT NULL,
            elapsed_seconds INTEGER NOT NULL,
            delay_seconds INTEGER NOT NULL,
            penalty_minutes INTEGER NOT NULL,
            diagnostics_json TEXT NOT NULL,
            computed_at TEXT NOT NULL DEFAULT (datetime('now'))
        );
        """
    )
    for key, value in DEFAULT_SETTINGS.items():
        conn.execute(
            "INSERT OR IGNORE INTO settings(key, value) VALUES(?, ?)",
            (key, value),
        )
    conn.commit()


def reset_import_data(conn: sqlite3.Connection, commit: bool = True) -> None:
    conn.execute("DELETE FROM marks_raw")
    conn.execute("DELETE FROM participants")
    conn.execute("DELETE FROM results")
    conn.execute("DELETE FROM corrections")
    conn.execute("DELETE FROM leg_exclusion_rules")
    if commit:
        conn.commit()


def parse_int_or_none(value: str) -> int | None:
    cleaned = value.strip()
    if not cleaned:
        return None
    try:
        return int(cleaned)
    except ValueError:
        return None


def import_csv(conn: sqlite3.Connection, csv_path: str, reset: bool) -> None:
    with open(csv_path, "r", encoding="utf-8-sig", newline="") as f:
        import_csv_from_stream(conn, f, reset=reset)


def import_csv_from_content(conn: sqlite3.Connection, csv_content: str, reset: bool) -> None:
    stream = io.StringIO(csv_content)
    import_csv_from_stream(conn, stream, reset=reset)


def import_csv_from_stream(conn: sqlite3.Connection, stream: Any, reset: bool) -> None:
    participants_to_upsert: list[tuple[str, str, int, str, int]] = []
    marks_to_insert: list[tuple[str, int, int, str]] = []

    reader = csv.reader(stream, delimiter=";", quotechar='"')
    header = next(reader, None)
    if not header:
        raise CSVFormatError("CSV is empty")
    if len(header) < 10:
        raise CSVFormatError("CSV header has too few columns (expected at least 10)")

    for source_row, row in enumerate(reader, start=2):
        if not row or all(not c.strip() for c in row):
            continue
        if len(row) < 10:
            # Some exports add one-line footer metadata after data rows.
            if len(row) == 1 and row[0].strip().startswith("Результаты выгружены из SFR Reader"):
                continue
            raise CSVFormatError(f"Row {source_row}: too few columns (expected at least 10)")

        participant_id = row[2].strip()
        name = row[3].strip()
        start_station = parse_int_or_none(row[7])
        start_time = row[8].strip()

        if not participant_id or not name or start_station is None or not start_time:
            raise CSVFormatError(
                f"Row {source_row}: required columns invalid (id/name/start station/start time)"
            )
        try:
            parse_time(start_time)
        except ValueError as exc:
            raise CSVFormatError(f"Row {source_row}: invalid start time '{start_time}'") from exc

        participants_to_upsert.append(
            (participant_id, name, start_station, start_time, source_row)
        )

        seq = 1
        for idx in range(9, len(row) - 1, 2):
            cp_raw = row[idx].strip()
            ts_raw = row[idx + 1].strip()
            if not cp_raw and not ts_raw:
                continue
            if not cp_raw or not ts_raw:
                raise CSVFormatError(
                    f"Row {source_row}: CP/time pair is incomplete at columns {idx + 1}/{idx + 2}"
                )
            cp_number = parse_int_or_none(cp_raw)
            if cp_number is None:
                raise CSVFormatError(f"Row {source_row}: invalid CP number '{cp_raw}'")
            try:
                parse_time(ts_raw)
            except ValueError as exc:
                raise CSVFormatError(f"Row {source_row}: invalid mark time '{ts_raw}'") from exc
            marks_to_insert.append((participant_id, seq, cp_number, ts_raw))
            seq += 1

    if not participants_to_upsert:
        raise CSVFormatError("CSV has no valid participants")

    try:
        conn.execute("BEGIN")
        if reset:
            reset_import_data(conn, commit=False)

        conn.executemany(
            """
            INSERT INTO participants(participant_id, name, start_station_id, start_time, source_row)
            VALUES(?, ?, ?, ?, ?)
            ON CONFLICT(participant_id) DO UPDATE SET
                name=excluded.name,
                start_station_id=excluded.start_station_id,
                start_time=excluded.start_time,
                source_row=excluded.source_row
            """,
            participants_to_upsert,
        )
        conn.executemany(
            """
            INSERT INTO marks_raw(participant_id, seq, cp_number, mark_time)
            VALUES(?, ?, ?, ?)
            """,
            marks_to_insert,
        )
        recalculate(conn)
        conn.commit()
    except Exception:
        conn.rollback()
        raise


def get_settings(conn: sqlite3.Connection) -> dict[str, str]:
    rows = conn.execute("SELECT key, value FROM settings").fetchall()
    values = {row["key"]: row["value"] for row in rows}
    for key, default_value in DEFAULT_SETTINGS.items():
        values.setdefault(key, default_value)
    return values


def set_settings(conn: sqlite3.Connection, updates: dict[str, Any]) -> None:
    for key, value in updates.items():
        if value is None:
            continue
        conn.execute(
            """
            INSERT INTO settings(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            """,
            (key, str(value)),
        )
    conn.commit()


def insert_correction(
    conn: sqlite3.Connection, participant_id: str, correction_type: str, payload: dict[str, Any]
) -> None:
    conn.execute(
        """
        INSERT INTO corrections(participant_id, correction_type, payload_json)
        VALUES(?, ?, ?)
        """,
        (participant_id, correction_type, json.dumps(payload, ensure_ascii=False)),
    )
    conn.commit()


def insert_leg_exclusion_rule(
    conn: sqlite3.Connection,
    participant_id: str | None,
    from_cp: int,
    to_cp: int,
    direction: str,
    apply_mode: str,
    max_leg_seconds: int | None,
) -> None:
    if direction not in {"forward", "reverse", "both"}:
        raise ValueError("direction must be one of: forward, reverse, both")
    if apply_mode not in {"once", "always"}:
        raise ValueError("apply_mode must be one of: once, always")
    if max_leg_seconds is not None and max_leg_seconds < 0:
        raise ValueError("max_leg_seconds must be >= 0")
    conn.execute(
        """
        INSERT INTO leg_exclusion_rules(
            participant_id, from_cp, to_cp, direction, apply_mode, max_leg_seconds
        ) VALUES(?, ?, ?, ?, ?, ?)
        """,
        (participant_id, from_cp, to_cp, direction, apply_mode, max_leg_seconds),
    )
    conn.commit()


def load_leg_exclusion_rules(conn: sqlite3.Connection, participant_id: str) -> list[ExclusionRule]:
    rows = conn.execute(
        """
        SELECT id, participant_id, from_cp, to_cp, direction, apply_mode, max_leg_seconds
        FROM leg_exclusion_rules
        WHERE participant_id = ? OR participant_id IS NULL
        ORDER BY id
        """,
        (participant_id,),
    ).fetchall()
    rules: list[ExclusionRule] = []
    for row in rows:
        source = f"rule:{row['id']}"
        if row["participant_id"] is None:
            source += ":global"
        else:
            source += ":participant"
        rules.append(
            ExclusionRule(
                from_cp=int(row["from_cp"]),
                to_cp=int(row["to_cp"]),
                direction=row["direction"],
                apply_mode=row["apply_mode"],
                max_leg_seconds=row["max_leg_seconds"],
                source=source,
            )
        )
    return rules


def query_leg_exclusion_rules(
    conn: sqlite3.Connection, participant_id: str | None = None
) -> list[dict[str, Any]]:
    if participant_id:
        rows = conn.execute(
            """
            SELECT id, participant_id, from_cp, to_cp, direction, apply_mode, max_leg_seconds, created_at
            FROM leg_exclusion_rules
            WHERE participant_id = ? OR participant_id IS NULL
            ORDER BY id
            """,
            (participant_id,),
        ).fetchall()
    else:
        rows = conn.execute(
            """
            SELECT id, participant_id, from_cp, to_cp, direction, apply_mode, max_leg_seconds, created_at
            FROM leg_exclusion_rules
            ORDER BY id
            """
        ).fetchall()
    return [dict(r) for r in rows]


def update_leg_exclusion_rule(
    conn: sqlite3.Connection,
    rule_id: int,
    participant_id: str | None,
    from_cp: int,
    to_cp: int,
    direction: str,
    apply_mode: str,
    max_leg_seconds: int | None,
) -> None:
    if direction not in {"forward", "reverse", "both"}:
        raise ValueError("direction must be one of: forward, reverse, both")
    if apply_mode not in {"once", "always"}:
        raise ValueError("apply_mode must be one of: once, always")
    if max_leg_seconds is not None and max_leg_seconds < 0:
        raise ValueError("max_leg_seconds must be >= 0")
    row = conn.execute("SELECT id FROM leg_exclusion_rules WHERE id = ?", (rule_id,)).fetchone()
    if not row:
        raise ValueError(f"Rule {rule_id} not found")
    if participant_id:
        require_participant(conn, participant_id)
    conn.execute(
        """
        UPDATE leg_exclusion_rules
        SET participant_id = ?, from_cp = ?, to_cp = ?, direction = ?, apply_mode = ?, max_leg_seconds = ?
        WHERE id = ?
        """,
        (participant_id, from_cp, to_cp, direction, apply_mode, max_leg_seconds, rule_id),
    )
    conn.commit()


def delete_leg_exclusion_rule(conn: sqlite3.Connection, rule_id: int) -> None:
    row = conn.execute("SELECT id FROM leg_exclusion_rules WHERE id = ?", (rule_id,)).fetchone()
    if not row:
        raise ValueError(f"Rule {rule_id} not found")
    conn.execute("DELETE FROM leg_exclusion_rules WHERE id = ?", (rule_id,))
    conn.commit()


def load_participant_marks(conn: sqlite3.Connection, participant_id: str) -> list[Mark]:
    rows = conn.execute(
        """
        SELECT cp_number, mark_time, seq
        FROM marks_raw
        WHERE participant_id = ?
        ORDER BY seq
        """,
        (participant_id,),
    ).fetchall()
    return [Mark(row["cp_number"], parse_time(row["mark_time"]), row["seq"]) for row in rows]


def apply_corrections(
    conn: sqlite3.Connection, participant_id: str, marks: list[Mark]
) -> tuple[list[Mark], list[ExclusionRule]]:
    exclusion_rules = load_leg_exclusion_rules(conn, participant_id)
    correction_rows = conn.execute(
        """
        SELECT id, correction_type, payload_json
        FROM corrections
        WHERE participant_id = ?
        ORDER BY id
        """,
        (participant_id,),
    ).fetchall()

    for row in correction_rows:
        correction_type = row["correction_type"]
        payload = json.loads(row["payload_json"])
        if correction_type == "add_cp":
            marks.append(
                Mark(
                    cp_number=int(payload["cp_number"]),
                    mark_time=parse_time(payload["mark_time"]),
                    seq=1_000_000 + int(row["id"]),
                )
            )
        elif correction_type == "remove_cp":
            cp_number = int(payload["cp_number"])
            mark_time_raw = payload.get("mark_time")
            remove_all = bool(payload.get("remove_all", False))
            removed = []
            for idx, m in enumerate(marks):
                if m.cp_number != cp_number:
                    continue
                if mark_time_raw and m.mark_time != parse_time(mark_time_raw):
                    continue
                removed.append(idx)
                if not remove_all:
                    break
            for idx in reversed(removed):
                del marks[idx]
        elif correction_type == "exclude_leg_time":
            exclusion_rules.append(
                ExclusionRule(
                    from_cp=int(payload["from_cp"]),
                    to_cp=int(payload["to_cp"]),
                    direction=payload.get("direction", "forward"),
                    apply_mode=payload.get("apply_mode", "once"),
                    max_leg_seconds=payload.get("max_leg_seconds"),
                    source=f"legacy:{row['id']}",
                )
            )

    marks.sort(key=lambda x: (x.mark_time, x.seq))
    return marks, exclusion_rules


def _rule_matches_leg(rule: ExclusionRule, from_cp: int, to_cp: int) -> bool:
    if rule.direction == "forward":
        return from_cp == rule.from_cp and to_cp == rule.to_cp
    if rule.direction == "reverse":
        return from_cp == rule.to_cp and to_cp == rule.from_cp
    # both
    return (
        (from_cp == rule.from_cp and to_cp == rule.to_cp)
        or (from_cp == rule.to_cp and to_cp == rule.from_cp)
    )


def apply_exclusion_rules(marks: list[Mark], exclusion_rules: list[ExclusionRule], elapsed_seconds: int) -> int:
    adjusted = elapsed_seconds
    if len(marks) < 2 or not exclusion_rules:
        return adjusted

    legs: list[tuple[int, int, int]] = []
    for idx in range(len(marks) - 1):
        from_cp = marks[idx].cp_number
        to_cp = marks[idx + 1].cp_number
        delta = int((marks[idx + 1].mark_time - marks[idx].mark_time).total_seconds())
        if delta < 0:
            continue
        legs.append((from_cp, to_cp, delta))

    for rule in exclusion_rules:
        matches = 0
        for from_cp, to_cp, delta in legs:
            if not _rule_matches_leg(rule, from_cp, to_cp):
                continue
            cap = delta if rule.max_leg_seconds is None else min(delta, int(rule.max_leg_seconds))
            adjusted = max(0, adjusted - max(0, cap))
            matches += 1
            if rule.apply_mode == "once":
                break
    return adjusted


def calculate_result(
    participant: sqlite3.Row,
    marks: list[Mark],
    exclusion_rules: list[ExclusionRule],
    settings: dict[str, str],
) -> dict[str, Any]:
    diagnostics: list[str] = []
    finish_cp = int(settings["finish_cp"])
    control_seconds = int(settings["control_minutes"]) * 60
    dq_seconds = (control_seconds + int(settings["dq_minutes"]) * 60)
    penalty_per_minute = int(settings["penalty_per_minute"])

    if not marks:
        diagnostics.append("no_marks")
        elapsed_seconds = 0
        adjusted_elapsed_seconds = 0
    else:
        if all(m.cp_number != finish_cp for m in marks):
            diagnostics.append("finish_missing")
        if marks[-1].cp_number != finish_cp:
            diagnostics.append("finish_not_last")

        start_time = parse_time(participant["start_time"])
        finish_time = marks[-1].mark_time
        elapsed_seconds = max(0, int((finish_time - start_time).total_seconds()))
        adjusted_elapsed_seconds = elapsed_seconds

        adjusted_elapsed_seconds = apply_exclusion_rules(
            marks=marks,
            exclusion_rules=exclusion_rules,
            elapsed_seconds=adjusted_elapsed_seconds,
        )

    excluded_cps = {int(participant["start_station_id"]), finish_cp}
    unique_cps: set[int] = set()
    for mark in marks:
        if mark.cp_number in excluded_cps:
            continue
        unique_cps.add(mark.cp_number)
    points_raw = sum(cp // 10 for cp in unique_cps)

    delay_seconds = max(0, adjusted_elapsed_seconds - control_seconds)
    penalty_minutes = math.ceil(delay_seconds / 60) if delay_seconds > 0 else 0
    penalty_points = penalty_minutes * penalty_per_minute
    points_final = points_raw - penalty_points

    status = "OK"
    if diagnostics:
        status = "ERR"
    elif adjusted_elapsed_seconds > dq_seconds:
        status = "DQ"

    return {
        "participant_id": participant["participant_id"],
        "name": participant["name"],
        "status": status,
        "points_raw": points_raw,
        "penalty_points": penalty_points,
        "points_final": points_final,
        "elapsed_seconds": adjusted_elapsed_seconds,
        "delay_seconds": delay_seconds,
        "penalty_minutes": penalty_minutes,
        "diagnostics_json": json.dumps(diagnostics, ensure_ascii=False),
    }


def recalculate(conn: sqlite3.Connection) -> None:
    settings = get_settings(conn)
    conn.execute("DELETE FROM results")
    participants = conn.execute("SELECT * FROM participants ORDER BY participant_id").fetchall()

    for participant in participants:
        marks = load_participant_marks(conn, participant["participant_id"])
        marks, exclusion_rules = apply_corrections(conn, participant["participant_id"], marks)
        result = calculate_result(participant, marks, exclusion_rules, settings)
        conn.execute(
            """
            INSERT INTO results(
                participant_id, name, status, points_raw, penalty_points, points_final,
                elapsed_seconds, delay_seconds, penalty_minutes, diagnostics_json
            ) VALUES (
                :participant_id, :name, :status, :points_raw, :penalty_points, :points_final,
                :elapsed_seconds, :delay_seconds, :penalty_minutes, :diagnostics_json
            )
            """,
            result,
        )
    conn.commit()


def fmt_hms(total_seconds: int) -> str:
    hours, rem = divmod(max(0, total_seconds), 3600)
    minutes, seconds = divmod(rem, 60)
    return f"{hours:02}:{minutes:02}:{seconds:02}"


def print_results(conn: sqlite3.Connection, limit: int | None = None) -> None:
    query = """
        SELECT participant_id, name, status, points_raw, penalty_points, points_final,
               elapsed_seconds, penalty_minutes, diagnostics_json
        FROM results
        ORDER BY
            CASE status WHEN 'OK' THEN 0 WHEN 'DQ' THEN 1 ELSE 2 END,
            points_final DESC,
            elapsed_seconds ASC
    """
    params: list[Any] = []
    if limit and limit > 0:
        query += " LIMIT ?"
        params.append(limit)

    rows = conn.execute(query, params).fetchall()
    if not rows:
        print("No computed results. Run: recalculate")
        return

    print(
        "rank | participant_id | name | status | points_raw | penalty | final | elapsed | diagnostics"
    )
    rank = 0
    for idx, row in enumerate(rows, start=1):
        if row["status"] == "OK":
            rank += 1
            rank_value = str(rank)
        else:
            rank_value = "-"
        print(
            f"{rank_value:>4} | {row['participant_id']:<14} | {row['name']:<30} | "
            f"{row['status']:<3} | {row['points_raw']:<10} | {row['penalty_points']:<7} | "
            f"{row['points_final']:<5} | {fmt_hms(row['elapsed_seconds'])} | {row['diagnostics_json']}"
        )


def export_results(conn: sqlite3.Connection, output_csv: str) -> None:
    rows = conn.execute(
        """
        SELECT participant_id, name, status, points_raw, penalty_points, points_final,
               elapsed_seconds, delay_seconds, penalty_minutes, diagnostics_json, computed_at
        FROM results
        ORDER BY
            CASE status WHEN 'OK' THEN 0 WHEN 'DQ' THEN 1 ELSE 2 END,
            points_final DESC,
            elapsed_seconds ASC
        """
    ).fetchall()
    with open(output_csv, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f, delimiter=";")
        writer.writerow(
            [
                "participant_id",
                "name",
                "status",
                "points_raw",
                "penalty_points",
                "points_final",
                "elapsed_seconds",
                "delay_seconds",
                "penalty_minutes",
                "diagnostics_json",
                "computed_at",
            ]
        )
        for row in rows:
            writer.writerow([row[k] for k in row.keys()])


def query_results(
    conn: sqlite3.Connection, limit: int = 200, status: str | None = None, search: str | None = None
) -> list[dict[str, Any]]:
    safe_limit = max(1, min(2000, int(limit)))
    query = """
        SELECT participant_id, name, status, points_raw, penalty_points, points_final,
               elapsed_seconds, delay_seconds, penalty_minutes, diagnostics_json, computed_at
        FROM results
        WHERE 1=1
    """
    params: list[Any] = []
    if status and status in {"OK", "DQ", "ERR"}:
        query += " AND status = ?"
        params.append(status)
    if search:
        query += " AND (participant_id LIKE ? OR name LIKE ?)"
        wildcard = f"%{search}%"
        params.extend([wildcard, wildcard])
    query += """
        ORDER BY
            CASE status WHEN 'OK' THEN 0 WHEN 'DQ' THEN 1 ELSE 2 END,
            points_final DESC,
            elapsed_seconds ASC
        LIMIT ?
    """
    params.append(safe_limit)
    rows = conn.execute(query, params).fetchall()
    return [dict(row) for row in rows]


def query_status_counts(conn: sqlite3.Connection) -> dict[str, int]:
    rows = conn.execute("SELECT status, COUNT(*) as cnt FROM results GROUP BY status").fetchall()
    output = {"OK": 0, "DQ": 0, "ERR": 0}
    for row in rows:
        output[row["status"]] = row["cnt"]
    return output


def query_participant_details(conn: sqlite3.Connection, participant_id: str) -> dict[str, Any]:
    participant = conn.execute(
        """
        SELECT participant_id, name, start_station_id, start_time, source_row
        FROM participants
        WHERE participant_id = ?
        """,
        (participant_id,),
    ).fetchone()
    if not participant:
        raise ValueError(f"Participant {participant_id} not found")

    raw_rows = conn.execute(
        """
        SELECT seq, cp_number, mark_time
        FROM marks_raw
        WHERE participant_id = ?
        ORDER BY seq
        """,
        (participant_id,),
    ).fetchall()
    raw_marks = [dict(r) for r in raw_rows]

    marks = load_participant_marks(conn, participant_id)
    corrected_marks, exclusion_rules = apply_corrections(conn, participant_id, marks)
    corrections_rows = conn.execute(
        """
        SELECT id, correction_type, payload_json, created_at
        FROM corrections
        WHERE participant_id = ?
        ORDER BY id
        """,
        (participant_id,),
    ).fetchall()
    corrections = []
    for row in corrections_rows:
        payload = json.loads(row["payload_json"])
        corrections.append(
            {
                "id": row["id"],
                "correction_type": row["correction_type"],
                "payload": payload,
                "created_at": row["created_at"],
            }
        )

    result = conn.execute(
        """
        SELECT participant_id, name, status, points_raw, penalty_points, points_final,
               elapsed_seconds, delay_seconds, penalty_minutes, diagnostics_json, computed_at
        FROM results
        WHERE participant_id = ?
        """,
        (participant_id,),
    ).fetchone()

    return {
        "participant": dict(participant),
        "result": dict(result) if result else None,
        "raw_marks": raw_marks,
        "corrected_marks": [
            {"seq": m.seq, "cp_number": m.cp_number, "mark_time": m.mark_time.isoformat(sep=" ")}
            for m in corrected_marks
        ],
        "excluded_legs": [
            {
                "from_cp": r.from_cp,
                "to_cp": r.to_cp,
                "direction": r.direction,
                "apply_mode": r.apply_mode,
                "max_leg_seconds": r.max_leg_seconds,
                "source": r.source,
            }
            for r in exclusion_rules
        ],
        "corrections": corrections,
    }


def _json_bytes(payload: dict[str, Any]) -> bytes:
    return json.dumps(payload, ensure_ascii=False).encode("utf-8")


def _web_pid_file(db_path: str, host: str, port: int) -> Path:
    host_part = host.replace(".", "_").replace(":", "_")
    db_dir = Path(db_path).resolve().parent
    return db_dir / f".rogein_web_{host_part}_{port}.pid"


def _stop_pid_from_file(pid_file: Path) -> None:
    if not pid_file.exists():
        return
    raw = pid_file.read_text(encoding="utf-8").strip()
    if not raw:
        pid_file.unlink(missing_ok=True)
        return
    try:
        pid = int(raw)
    except ValueError:
        pid_file.unlink(missing_ok=True)
        return
    if pid == os.getpid():
        pid_file.unlink(missing_ok=True)
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        pid_file.unlink(missing_ok=True)
        return
    except PermissionError:
        # Another process owner or restricted environment.
        return

    # Wait up to 2 seconds for graceful stop.
    deadline = time.time() + 2
    while time.time() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            pid_file.unlink(missing_ok=True)
            return
        time.sleep(0.1)

    # Escalate if still alive.
    try:
        os.kill(pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass
    pid_file.unlink(missing_ok=True)


def serve_web(db_path: str, host: str, port: int, restart: bool = False) -> None:
    web_index_path = WEB_DIR / "index.html"
    web_participant_path = WEB_DIR / "participant.html"
    if not web_index_path.exists():
        raise FileNotFoundError(f"Web file not found: {web_index_path}")
    if not web_participant_path.exists():
        raise FileNotFoundError(f"Web file not found: {web_participant_path}")
    pid_file = _web_pid_file(db_path, host, port)
    if restart:
        _stop_pid_from_file(pid_file)

    class AppHandler(BaseHTTPRequestHandler):
        server_version = "RogeinMVP/0.1"

        def _read_json_body(self) -> dict[str, Any]:
            content_length = int(self.headers.get("Content-Length", "0"))
            if content_length == 0:
                return {}
            raw = self.rfile.read(content_length)
            return json.loads(raw.decode("utf-8"))

        def _send_json(self, status: int, payload: dict[str, Any]) -> None:
            body = _json_bytes(payload)
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _send_text(self, status: int, content: str, content_type: str) -> None:
            body = content.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _serve_index(self) -> None:
            self._send_text(
                HTTPStatus.OK,
                web_index_path.read_text(encoding="utf-8"),
                "text/html; charset=utf-8",
            )

        def _serve_participant_page(self) -> None:
            self._send_text(
                HTTPStatus.OK,
                web_participant_path.read_text(encoding="utf-8"),
                "text/html; charset=utf-8",
            )

        def _handle_api_get(self, route_path: str, parsed_query: dict[str, list[str]]) -> None:
            conn = connect_db(db_path)
            try:
                init_db(conn)
                if route_path == "/api/settings":
                    self._send_json(HTTPStatus.OK, {"ok": True, "settings": get_settings(conn)})
                    return

                if route_path == "/api/results":
                    limit = int(parsed_query.get("limit", ["200"])[0])
                    status = parsed_query.get("status", [None])[0]
                    search = parsed_query.get("search", [None])[0]
                    rows = query_results(conn, limit=limit, status=status, search=search)
                    self._send_json(
                        HTTPStatus.OK,
                        {"ok": True, "rows": rows, "counts": query_status_counts(conn)},
                    )
                    return

                if route_path == "/api/participants":
                    limit = int(parsed_query.get("limit", ["200"])[0])
                    rows = conn.execute(
                        """
                        SELECT participant_id, name
                        FROM participants
                        ORDER BY name
                        LIMIT ?
                        """,
                        (limit,),
                    ).fetchall()
                    self._send_json(HTTPStatus.OK, {"ok": True, "rows": [dict(r) for r in rows]})
                    return

                if route_path == "/api/exclusion-rules":
                    participant_id = parsed_query.get("participant_id", [None])[0]
                    rows = query_leg_exclusion_rules(conn, participant_id=participant_id)
                    self._send_json(HTTPStatus.OK, {"ok": True, "rows": rows})
                    return

                if route_path.startswith("/api/participant/"):
                    participant_id = unquote(route_path.removeprefix("/api/participant/"))
                    details = query_participant_details(conn, participant_id)
                    self._send_json(HTTPStatus.OK, {"ok": True, "details": details})
                    return

                self._send_json(HTTPStatus.NOT_FOUND, {"ok": False, "error": "Not found"})
            except Exception as exc:  # noqa: BLE001
                self._send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": str(exc)})
            finally:
                conn.close()

        def _handle_api_post(self, route_path: str) -> None:
            conn = connect_db(db_path)
            try:
                init_db(conn)
                payload = self._read_json_body()
                if route_path == "/api/settings":
                    updates = {
                        "control_minutes": payload.get("control_minutes"),
                        "penalty_per_minute": payload.get("penalty_per_minute"),
                        "dq_minutes": payload.get("dq_minutes"),
                        "finish_cp": payload.get("finish_cp"),
                    }
                    set_settings(conn, updates)
                    self._send_json(HTTPStatus.OK, {"ok": True, "settings": get_settings(conn)})
                    return

                if route_path == "/api/recalculate":
                    recalculate(conn)
                    self._send_json(HTTPStatus.OK, {"ok": True, "counts": query_status_counts(conn)})
                    return

                if route_path == "/api/import-csv":
                    csv_content = str(payload.get("csv_content", ""))
                    if not csv_content.strip():
                        raise ValueError("csv_content is empty")
                    reset = bool(payload.get("reset", True))
                    import_csv_from_content(conn, csv_content=csv_content, reset=reset)
                    self._send_json(
                        HTTPStatus.OK,
                        {
                            "ok": True,
                            "participants_count": conn.execute(
                                "SELECT COUNT(*) FROM participants"
                            ).fetchone()[0],
                        },
                    )
                    return

                if route_path == "/api/corrections/add-cp":
                    participant_id = str(payload["participant_id"])
                    cp_number = int(payload["cp_number"])
                    mark_time = str(payload["mark_time"])
                    require_participant(conn, participant_id)
                    parse_time(mark_time)
                    insert_correction(
                        conn,
                        participant_id=participant_id,
                        correction_type="add_cp",
                        payload={"cp_number": cp_number, "mark_time": mark_time},
                    )
                    self._send_json(HTTPStatus.OK, {"ok": True})
                    return

                if route_path == "/api/corrections/remove-cp":
                    participant_id = str(payload["participant_id"])
                    cp_number = int(payload["cp_number"])
                    mark_time = payload.get("mark_time")
                    remove_all = bool(payload.get("remove_all", False))
                    require_participant(conn, participant_id)
                    if mark_time:
                        parse_time(str(mark_time))
                    insert_correction(
                        conn,
                        participant_id=participant_id,
                        correction_type="remove_cp",
                        payload={
                            "cp_number": cp_number,
                            "mark_time": mark_time,
                            "remove_all": remove_all,
                        },
                    )
                    self._send_json(HTTPStatus.OK, {"ok": True})
                    return

                if route_path == "/api/corrections/exclude-leg":
                    participant_scope = str(payload.get("participant_scope", "one"))
                    participant_id = payload.get("participant_id")
                    from_cp = int(payload["from_cp"])
                    to_cp = int(payload["to_cp"])
                    direction = str(payload.get("direction", "forward"))
                    apply_mode = str(payload.get("apply_mode", "once"))
                    max_leg_seconds_raw = payload.get("max_leg_seconds")
                    max_leg_seconds = (
                        None if max_leg_seconds_raw in ("", None) else int(max_leg_seconds_raw)
                    )
                    scoped_participant_id: str | None = None
                    if participant_scope == "one":
                        if not participant_id:
                            raise ValueError("participant_id is required for participant_scope=one")
                        scoped_participant_id = str(participant_id)
                        require_participant(conn, scoped_participant_id)
                    elif participant_scope != "all":
                        raise ValueError("participant_scope must be one of: one, all")
                    insert_leg_exclusion_rule(
                        conn,
                        participant_id=scoped_participant_id,
                        from_cp=from_cp,
                        to_cp=to_cp,
                        direction=direction,
                        apply_mode=apply_mode,
                        max_leg_seconds=max_leg_seconds,
                    )
                    self._send_json(HTTPStatus.OK, {"ok": True})
                    return

                if route_path == "/api/exclusion-rules/update":
                    rule_id = int(payload["rule_id"])
                    participant_scope = str(payload.get("participant_scope", "one"))
                    participant_id_raw = payload.get("participant_id")
                    scoped_participant_id: str | None = None
                    if participant_scope == "one":
                        if not participant_id_raw:
                            raise ValueError("participant_id is required for participant_scope=one")
                        scoped_participant_id = str(participant_id_raw)
                    elif participant_scope != "all":
                        raise ValueError("participant_scope must be one of: one, all")
                    max_leg_seconds_raw = payload.get("max_leg_seconds")
                    max_leg_seconds = (
                        None if max_leg_seconds_raw in ("", None) else int(max_leg_seconds_raw)
                    )
                    update_leg_exclusion_rule(
                        conn,
                        rule_id=rule_id,
                        participant_id=scoped_participant_id,
                        from_cp=int(payload["from_cp"]),
                        to_cp=int(payload["to_cp"]),
                        direction=str(payload.get("direction", "forward")),
                        apply_mode=str(payload.get("apply_mode", "once")),
                        max_leg_seconds=max_leg_seconds,
                    )
                    self._send_json(HTTPStatus.OK, {"ok": True})
                    return

                if route_path == "/api/exclusion-rules/delete":
                    rule_id = int(payload["rule_id"])
                    delete_leg_exclusion_rule(conn, rule_id)
                    self._send_json(HTTPStatus.OK, {"ok": True})
                    return

                self._send_json(HTTPStatus.NOT_FOUND, {"ok": False, "error": "Not found"})
            except Exception as exc:  # noqa: BLE001
                self._send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": str(exc)})
            finally:
                conn.close()

        def do_GET(self) -> None:  # noqa: N802
            parsed = urlparse(self.path)
            route_path = parsed.path
            if route_path == "/":
                self._serve_index()
                return
            if route_path.startswith("/participant/"):
                self._serve_participant_page()
                return
            if route_path.startswith("/api/"):
                self._handle_api_get(route_path, parse_qs(parsed.query))
                return
            self._send_json(HTTPStatus.NOT_FOUND, {"ok": False, "error": "Not found"})

        def do_POST(self) -> None:  # noqa: N802
            parsed = urlparse(self.path)
            route_path = parsed.path
            if route_path.startswith("/api/"):
                self._handle_api_post(route_path)
                return
            self._send_json(HTTPStatus.NOT_FOUND, {"ok": False, "error": "Not found"})

        def log_message(self, format: str, *args: Any) -> None:  # noqa: A003
            return

    try:
        server = ThreadingHTTPServer((host, port), AppHandler)
    except OSError as exc:
        raise RuntimeError(
            f"Cannot start server on {host}:{port}. "
            f"If backend is already running, use --restart."
        ) from exc

    pid_file.write_text(str(os.getpid()), encoding="utf-8")
    print(f"Web UI: http://{host}:{port}")
    print("Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    finally:
        pid_file.unlink(missing_ok=True)


def require_participant(conn: sqlite3.Connection, participant_id: str) -> None:
    row = conn.execute(
        "SELECT participant_id FROM participants WHERE participant_id = ?",
        (participant_id,),
    ).fetchone()
    if not row:
        raise ValueError(f"Participant {participant_id} not found")


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be >= 0")
    return parsed


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Rogein MVP result processor (SQLite + CSV).")
    parser.add_argument("--db", default="rogein.sqlite3", help="Path to SQLite database file.")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("init-db", help="Create database schema.")

    p_import = sub.add_parser("import-csv", help="Import CSV into SQLite.")
    p_import.add_argument("--csv", required=True, help="Input CSV file path.")
    p_import.add_argument("--no-reset", action="store_true", help="Do not clear old data before import.")

    p_settings = sub.add_parser("set-settings", help="Update competition settings.")
    p_settings.add_argument("--control-minutes", type=positive_int)
    p_settings.add_argument("--penalty-per-minute", type=positive_int)
    p_settings.add_argument(
        "--dq-minutes",
        type=positive_int,
        help="Disqualification overtime in minutes after control time.",
    )
    p_settings.add_argument("--finish-cp", type=positive_int)

    p_add = sub.add_parser("add-cp", help="Add manual CP mark.")
    p_add.add_argument("--participant-id", required=True)
    p_add.add_argument("--cp", type=int, required=True)
    p_add.add_argument("--time", required=True, help="ISO time, example 2026-08-02 16:05:00")

    p_remove = sub.add_parser("remove-cp", help="Remove CP mark (first or all matches).")
    p_remove.add_argument("--participant-id", required=True)
    p_remove.add_argument("--cp", type=int, required=True)
    p_remove.add_argument("--time", help="Optional exact mark time.")
    p_remove.add_argument("--all", action="store_true", help="Remove all matching CP marks.")

    p_exclude = sub.add_parser("exclude-leg", help="Exclude leg duration from total time.")
    p_exclude.add_argument("--participant-id")
    p_exclude.add_argument(
        "--all-participants",
        action="store_true",
        help="Apply exclusion rule to all participants.",
    )
    p_exclude.add_argument("--from-cp", type=int, required=True)
    p_exclude.add_argument("--to-cp", type=int, required=True)
    p_exclude.add_argument(
        "--direction",
        choices=["forward", "reverse", "both"],
        default="forward",
        help="Leg direction to match.",
    )
    p_exclude.add_argument(
        "--mode",
        choices=["once", "always"],
        default="once",
        help="Apply exclusion only once or for every matching leg.",
    )
    p_exclude.add_argument(
        "--max-leg-seconds",
        type=positive_int,
        help="Maximum subtraction per matched leg in seconds.",
    )

    p_list_exclude = sub.add_parser("list-exclude-leg", help="List exclusion rules.")
    p_list_exclude.add_argument("--participant-id")

    p_update_exclude = sub.add_parser("update-exclude-leg", help="Update exclusion rule by id.")
    p_update_exclude.add_argument("--rule-id", type=int, required=True)
    p_update_exclude.add_argument("--participant-id")
    p_update_exclude.add_argument("--all-participants", action="store_true")
    p_update_exclude.add_argument("--from-cp", type=int, required=True)
    p_update_exclude.add_argument("--to-cp", type=int, required=True)
    p_update_exclude.add_argument(
        "--direction",
        choices=["forward", "reverse", "both"],
        default="forward",
    )
    p_update_exclude.add_argument(
        "--mode",
        choices=["once", "always"],
        default="once",
    )
    p_update_exclude.add_argument("--max-leg-seconds", type=positive_int)

    p_delete_exclude = sub.add_parser("delete-exclude-leg", help="Delete exclusion rule by id.")
    p_delete_exclude.add_argument("--rule-id", type=int, required=True)

    sub.add_parser("recalculate", help="Recalculate results from raw marks + corrections.")

    p_show = sub.add_parser("show-results", help="Print computed results.")
    p_show.add_argument("--limit", type=positive_int)

    p_export = sub.add_parser("export-results", help="Export results to CSV.")
    p_export.add_argument("--csv", required=True, help="Output CSV file path.")

    p_serve = sub.add_parser("serve-web", help="Run local web UI.")
    p_serve.add_argument("--host", default="127.0.0.1")
    p_serve.add_argument("--port", type=int, default=8787)
    p_serve.add_argument(
        "--restart",
        action="store_true",
        help="Stop previous backend on same host/port, then start new one.",
    )

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    db_path = args.db
    conn = connect_db(db_path)

    try:
        if args.command == "init-db":
            init_db(conn)
            print(f"Database initialized: {db_path}")

        elif args.command == "import-csv":
            init_db(conn)
            import_csv(conn, args.csv, reset=not args.no_reset)
            print(f"CSV imported and recalculated: {args.csv}")

        elif args.command == "set-settings":
            init_db(conn)
            updates = {
                "control_minutes": args.control_minutes,
                "penalty_per_minute": args.penalty_per_minute,
                "dq_minutes": args.dq_minutes,
                "finish_cp": args.finish_cp,
            }
            set_settings(conn, updates)
            print("Settings updated")

        elif args.command == "add-cp":
            init_db(conn)
            require_participant(conn, args.participant_id)
            parse_time(args.time)
            insert_correction(
                conn,
                participant_id=args.participant_id,
                correction_type="add_cp",
                payload={"cp_number": args.cp, "mark_time": args.time},
            )
            print("Correction added: add_cp")

        elif args.command == "remove-cp":
            init_db(conn)
            require_participant(conn, args.participant_id)
            if args.time:
                parse_time(args.time)
            insert_correction(
                conn,
                participant_id=args.participant_id,
                correction_type="remove_cp",
                payload={"cp_number": args.cp, "mark_time": args.time, "remove_all": args.all},
            )
            print("Correction added: remove_cp")

        elif args.command == "exclude-leg":
            init_db(conn)
            participant_id: str | None = None
            if args.all_participants:
                participant_id = None
            else:
                if not args.participant_id:
                    raise ValueError("--participant-id is required unless --all-participants is set")
                participant_id = args.participant_id
                require_participant(conn, participant_id)
            insert_leg_exclusion_rule(
                conn,
                participant_id=participant_id,
                from_cp=args.from_cp,
                to_cp=args.to_cp,
                direction=args.direction,
                apply_mode=args.mode,
                max_leg_seconds=args.max_leg_seconds,
            )
            print("Exclusion rule added")

        elif args.command == "list-exclude-leg":
            init_db(conn)
            rows = query_leg_exclusion_rules(conn, participant_id=args.participant_id)
            if not rows:
                print("No exclusion rules")
            else:
                print("id | scope | from_cp | to_cp | direction | mode | max_leg_seconds")
                for row in rows:
                    scope = row["participant_id"] if row["participant_id"] else "all"
                    print(
                        f"{row['id']} | {scope} | {row['from_cp']} | {row['to_cp']} | "
                        f"{row['direction']} | {row['apply_mode']} | {row['max_leg_seconds']}"
                    )

        elif args.command == "update-exclude-leg":
            init_db(conn)
            participant_id: str | None = None
            if args.all_participants:
                participant_id = None
            else:
                if not args.participant_id:
                    raise ValueError("--participant-id is required unless --all-participants is set")
                participant_id = args.participant_id
                require_participant(conn, participant_id)
            update_leg_exclusion_rule(
                conn,
                rule_id=args.rule_id,
                participant_id=participant_id,
                from_cp=args.from_cp,
                to_cp=args.to_cp,
                direction=args.direction,
                apply_mode=args.mode,
                max_leg_seconds=args.max_leg_seconds,
            )
            print("Exclusion rule updated")

        elif args.command == "delete-exclude-leg":
            init_db(conn)
            delete_leg_exclusion_rule(conn, args.rule_id)
            print("Exclusion rule deleted")

        elif args.command == "recalculate":
            init_db(conn)
            recalculate(conn)
            print("Results recalculated")

        elif args.command == "show-results":
            init_db(conn)
            print_results(conn, limit=args.limit)

        elif args.command == "export-results":
            init_db(conn)
            export_results(conn, args.csv)
            print(f"Results exported: {args.csv}")

        elif args.command == "serve-web":
            init_db(conn)
            conn.close()
            serve_web(db_path=args.db, host=args.host, port=args.port, restart=args.restart)
            return

        else:
            parser.print_help()

    finally:
        conn.close()


if __name__ == "__main__":
    main()
