"""Checklist 8 (read_raw contract) and 16 (load_db_config)."""

from __future__ import annotations

import os
from pathlib import Path

import pytest

from talentmatch_etl.config import DbConfig, load_db_config
from talentmatch_etl.contract import ContractError, read_raw
from talentmatch_etl.rejects import Reject, write_rejects_csv


def write(path: Path, text: str, encoding: str = "utf-8") -> None:
    path.write_bytes(text.encode(encoding))


def minimal_dir(tmp_path: Path) -> Path:
    write(tmp_path / "candidates.csv",
          "candidate_ref,full_name,email,summary\nC1,Ann,a@x.com,\nC2,,,\n")
    write(tmp_path / "jobs.csv", "job_ref,title,company,description\nJ1,Dev,Acme,\n")
    return tmp_path


# ---- 8. read_raw ------------------------------------------------------------

def test_read_raw_blanks_are_empty_strings_not_nan(tmp_path):
    b = read_raw(minimal_dir(tmp_path))
    assert b.candidates.loc[1, "full_name"] == ""
    assert b.candidates.loc[1, "email"] == ""
    assert b.candidates.loc[0, "summary"] == ""
    assert not b.candidates.isna().any().any()
    assert b.candidates["_line"].tolist() == [2, 3]


def test_read_raw_na_like_strings_kept(tmp_path):
    write(tmp_path / "candidates.csv",
          "candidate_ref,full_name,email,summary\nC1,NA,null@x.com,NaN\n")
    b = read_raw(tmp_path)
    assert b.candidates.loc[0, "full_name"] == "NA"
    assert b.candidates.loc[0, "summary"] == "NaN"


def test_read_raw_missing_link_files_are_empty(tmp_path):
    b = read_raw(minimal_dir(tmp_path))
    for df, cols in ((b.candidate_skills, ["candidate_ref", "skill_name", "years_experience"]),
                     (b.job_skills, ["job_ref", "skill_name", "required"]),
                     (b.skills, ["skill_name", "category"])):
        assert len(df) == 0
        assert list(df.columns) == cols + ["_line"]


def test_read_raw_missing_required_column(tmp_path):
    minimal_dir(tmp_path)
    write(tmp_path / "jobs.csv", "job_ref,title,description\nJ1,Dev,\n")
    with pytest.raises(ContractError, match="company"):
        read_raw(tmp_path)


def test_read_raw_header_only_file_is_empty_frame(tmp_path):
    minimal_dir(tmp_path)
    write(tmp_path / "job_skills.csv", "job_ref,skill_name,required\n")
    assert len(read_raw(tmp_path).job_skills) == 0


def test_read_raw_empty_file_is_contract_error(tmp_path):
    minimal_dir(tmp_path)
    write(tmp_path / "skills.csv", "")
    with pytest.raises(ContractError):
        read_raw(tmp_path)


def test_read_raw_both_parents_missing(tmp_path):
    write(tmp_path / "skills.csv", "skill_name,category\nJava,L\n")
    with pytest.raises(ContractError):
        read_raw(tmp_path)


def test_read_raw_missing_dir(tmp_path):
    with pytest.raises(ContractError):
        read_raw(tmp_path / "nope")


def test_read_raw_bom_extra_columns_and_reordered(tmp_path):
    minimal_dir(tmp_path)
    write(tmp_path / "skills.csv", "﻿category, skill_name ,extra\nLang,Java,zzz\n")
    b = read_raw(tmp_path)
    assert list(b.skills.columns) == ["skill_name", "category", "_line"]
    assert b.skills.loc[0, "skill_name"] == "Java"


def test_read_raw_quoted_fields(tmp_path):
    write(tmp_path / "candidates.csv",
          'candidate_ref,full_name,email,summary\nC1,"Doe, Jane",j@x.com,"said ""hi"""\n')
    b = read_raw(tmp_path)
    assert b.candidates.loc[0, "full_name"] == "Doe, Jane"
    assert b.candidates.loc[0, "summary"] == 'said "hi"'


def test_rejects_csv_header_only_when_empty(tmp_path):
    p = write_rejects_csv([], tmp_path / "r" / "rejects.csv")
    assert p.read_text(encoding="utf-8") == "source_file,line,ref,rule,detail,raw_json\n"
    write_rejects_csv([Reject("a.csv", 3, None, "missing_ref", "d", {"x": "é"})], p)
    assert p.read_text(encoding="utf-8").splitlines()[1] == 'a.csv,3,,missing_ref,d,"{""x"": ""é""}"'


# ---- 16. load_db_config -----------------------------------------------------

def test_config_defaults(tmp_path):
    cfg = load_db_config(env_file=tmp_path / "missing.env", environ={})
    assert (cfg.host, cfg.port, cfg.name, cfg.user, cfg.password) == (
        "localhost", 5432, "talentmatch", "talentmatch", "talentmatch")


def test_config_env_file_then_environ_override(tmp_path):
    env_file = tmp_path / ".env"
    write(env_file, "DB_HOST=filehost\nDB_PORT=5433\nDB_NAME=filedb\nDB_PASSWORD=filepw\n")
    cfg = load_db_config(env_file=env_file, environ={})
    assert (cfg.host, cfg.port, cfg.name, cfg.user, cfg.password) == (
        "filehost", 5433, "filedb", "talentmatch", "filepw")

    cfg = load_db_config(env_file=env_file, environ={"DB_PORT": "6000", "DB_HOST": "envhost",
                                                     "DB_PASSWORD": "envpw"})
    assert (cfg.host, cfg.port, cfg.name, cfg.password) == ("envhost", 6000, "filedb", "envpw")


def test_config_blank_env_value_falls_through(tmp_path):
    env_file = tmp_path / ".env"
    write(env_file, "DB_HOST=filehost\n")
    cfg = load_db_config(env_file=env_file, environ={"DB_HOST": "  "})
    assert cfg.host == "filehost"


def test_config_does_not_mutate_os_environ(tmp_path, monkeypatch):
    monkeypatch.delenv("DB_NAME", raising=False)
    env_file = tmp_path / ".env"
    write(env_file, "DB_NAME=fromfile\n")
    before = dict(os.environ)
    assert load_db_config(env_file=env_file).name == "fromfile"
    assert dict(os.environ) == before


def test_config_uses_os_environ_by_default(tmp_path, monkeypatch):
    monkeypatch.setenv("DB_USER", "osuser")
    assert load_db_config(env_file=tmp_path / "missing.env").user == "osuser"


@pytest.mark.parametrize("port", ["abc", "0", "70000"])
def test_config_bad_port(tmp_path, port):
    with pytest.raises(ValueError):
        load_db_config(env_file=tmp_path / "x.env", environ={"DB_PORT": port})


def test_password_not_in_repr_or_safe_dsn():
    cfg = DbConfig("h", 1, "d", "u", "s3cret")
    assert "s3cret" not in repr(cfg)
    assert "s3cret" not in cfg.safe_dsn()
    assert "password='s3cret'" in cfg.conninfo()


def test_conninfo_quoting():
    cfg = DbConfig("h", 1, "d", "u", "it's\\x")
    assert "password='it\\'s\\\\x'" in cfg.conninfo()
