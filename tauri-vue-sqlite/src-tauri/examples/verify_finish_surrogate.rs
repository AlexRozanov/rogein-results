use std::path::PathBuf;

fn main() -> Result<(), String> {
    let home = std::env::var("HOME").map_err(|e| e.to_string())?;
    let src = PathBuf::from(home).join(".local/share/ru.rogein.desktop.v01/rogein_v01.sqlite3");
    let dst = PathBuf::from("/tmp/rogein_verify_finish.sqlite3");
    std::fs::copy(&src, &dst).map_err(|e| format!("copy db: {e}"))?;

    let mut conn = app_lib::domain::open_and_init_db(&dst)?;
    let csv = std::fs::read_to_string("/data/projects/Rogein/csv/results-190-teams.csv")
        .map_err(|e| format!("read csv: {e}"))?;
    let summary = app_lib::domain::import_csv_content(&mut conn, &csv, false)?;
    println!("import: {:?}", summary);

    let mut stmt = conn
        .prepare(
            r#"
            SELECT id, participant_id, chip_raw_id, name
            FROM participants
            WHERE participant_id = '63'
            "#,
        )
        .map_err(|e| e.to_string())?;
    let rows: Vec<(i64, String, Option<String>, String)> = stmt
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))
        .map_err(|e| e.to_string())?
        .collect::<Result<_, _>>()
        .map_err(|e| e.to_string())?;
    println!("participants bib63: {:?}", rows);
    assert!(!rows.is_empty(), "expected bib 63 finish row");
    assert!(rows[0].2.is_none(), "bib 63 chip_raw_id must be NULL");

    let marks: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM marks_raw WHERE finish_participant_id = ?",
            [rows[0].0],
            |r| r.get(0),
        )
        .map_err(|e| e.to_string())?;
    println!("marks for bib63 finish_id {}: {}", rows[0].0, marks);
    assert!(marks > 0, "marks must link via finish_participant_id");

    let mut rstmt = conn
        .prepare(
            r#"
            SELECT finish_participant_id, chip_raw_id, participant_id, name, status, diagnostics_json,
                   elapsed_seconds
            FROM results
            WHERE participant_id = '4'
            ORDER BY name
            "#,
        )
        .map_err(|e| e.to_string())?;
    let bib4: Vec<(Option<i64>, Option<String>, String, String, String, String, i64)> = rstmt
        .query_map([], |r| {
            Ok((
                r.get(0)?,
                r.get(1)?,
                r.get(2)?,
                r.get(3)?,
                r.get(4)?,
                r.get(5)?,
                r.get(6)?,
            ))
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<_, _>>()
        .map_err(|e| e.to_string())?;
    println!("results bib4:");
    for row in &bib4 {
        println!("  {:?}", row);
    }

    let erm = bib4
        .iter()
        .find(|r| r.3.contains("Ермаченков"))
        .expect("Ермаченков result");
    assert_eq!(erm.4, "OK", "Ермаченков should be OK");
    assert!(erm.0.is_some(), "Ермаченков finish_participant_id set");

    let gap = bib4
        .iter()
        .find(|r| r.3.contains("Гапунич"))
        .expect("Гапунич result");
    assert_eq!(gap.4, "Ошибка", "Гапунич bib4 should be Ошибка (дубль id в финише)");
    assert!(
        gap.5.contains("duplicate_bib_in_finish"),
        "Гапунич should have duplicate_bib_in_finish, got {}",
        gap.5
    );

    let null_chip: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM participants WHERE participant_id = '63' AND chip_raw_id IS NULL",
            [],
            |r| r.get(0),
        )
        .map_err(|e| e.to_string())?;
    assert_eq!(null_chip, 1);

    // Personal tables must FK to participants(id) via finish_participant_id.
    for table in [
        "corrections",
        "manual_corrections",
        "participant_anomalies",
        "leg_exclusion_rules",
    ] {
        let cols: Vec<String> = conn
            .prepare(&format!("PRAGMA table_info({table})"))
            .map_err(|e| e.to_string())?
            .query_map([], |r| r.get::<_, String>(1))
            .map_err(|e| e.to_string())?
            .collect::<Result<_, _>>()
            .map_err(|e| e.to_string())?;
        assert!(
            cols.iter().any(|c| c == "finish_participant_id"),
            "{table} must have finish_participant_id"
        );
        let fks: Vec<(String, String)> = conn
            .prepare(&format!("PRAGMA foreign_key_list({table})"))
            .map_err(|e| e.to_string())?
            .query_map([], |r| Ok((r.get::<_, String>(2)?, r.get::<_, String>(3)?)))
            .map_err(|e| e.to_string())?
            .collect::<Result<_, _>>()
            .map_err(|e| e.to_string())?;
        assert!(
            fks.iter()
                .any(|(t, c)| t == "participants" && c == "finish_participant_id"),
            "{table} must FK finish_participant_id → participants, got {fks:?}"
        );
    }

    let finish63 = rows[0].0;
    app_lib::domain::add_cp_correction(&conn, &finish63.to_string(), 999, "")?;
    let personal: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM manual_corrections WHERE finish_participant_id = ? AND correction_type = 'add_cp'",
            [finish63],
            |r| r.get(0),
        )
        .map_err(|e| e.to_string())?;
    assert_eq!(personal, 1);

    println!("OK");
    Ok(())
}
