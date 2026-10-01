"""Deterministic synthetic source: Faker-generated contract CSVs with injected defects.

Determinism: one Faker("en_US") seeded via seed_instance(seed) and one
random.Random(seed); no global random, clock or uuid4; no set iteration in
output order; CSVs written with "\\n" line endings. Same seed + params +
Faker version produce byte-identical files (faker_version is recorded in
_manifest.json).

Invariant: after cleaning/loading, exactly ``n_candidates`` candidates and
``n_jobs`` jobs exist. Defects are either edits to clean rows that must still
load, or extra rows that must be rejected/deduplicated; every defect type
appears at least once when dirty_ratio > 0 and never when it is 0.
"""

from __future__ import annotations

import csv
import json
import logging
import random
import re
from pathlib import Path
from typing import Any, Callable

from faker import Faker

from ..cleaning import normalize_skill_name, skill_key
from ..contract import COLUMNS, FILES
from .base import SourceAdapter

logger = logging.getLogger(__name__)

MANIFEST_FILE = "_manifest.json"

SKILL_CATALOG: list[tuple[str, str]] = [
    # Languages
    ("Java", "Languages"), ("Python", "Languages"), ("JavaScript", "Languages"),
    ("TypeScript", "Languages"), ("Go", "Languages"), ("Kotlin", "Languages"),
    ("C#", "Languages"), ("C++", "Languages"), ("Rust", "Languages"),
    ("SQL", "Languages"), ("Scala", "Languages"), ("Ruby", "Languages"),
    # Frameworks
    ("Spring Boot", "Frameworks"), ("React", "Frameworks"), ("Angular", "Frameworks"),
    ("Vue.js", "Frameworks"), ("Django", "Frameworks"), ("Flask", "Frameworks"),
    ("FastAPI", "Frameworks"), ("Node.js", "Frameworks"), (".NET", "Frameworks"),
    ("Hibernate", "Frameworks"),
    # Data
    ("PostgreSQL", "Data"), ("MySQL", "Data"), ("MongoDB", "Data"), ("Redis", "Data"),
    ("Apache Kafka", "Data"), ("Apache Spark", "Data"), ("Pandas", "Data"),
    ("Machine Learning", "Data"), ("Elasticsearch", "Data"),
    # Cloud/DevOps
    ("AWS", "Cloud/DevOps"), ("Azure", "Cloud/DevOps"), ("Google Cloud", "Cloud/DevOps"),
    ("Docker", "Cloud/DevOps"), ("Kubernetes", "Cloud/DevOps"), ("Terraform", "Cloud/DevOps"),
    ("Jenkins", "Cloud/DevOps"), ("GitHub Actions", "Cloud/DevOps"),
    # Tools
    ("Git", "Tools"), ("Jira", "Tools"), ("Linux", "Tools"), ("Maven", "Tools"),
    ("Gradle", "Tools"),
]

# Never present in SKILL_CATALOG (by skill_key): load with category NULL.
UNCATALOGED_SKILLS: list[str] = [
    "Elixir", "Svelte", "Deno", "Zig", "Pulumi", "Snowflake", "Clojure", "Haskell",
]

SENIORITY: list[str] = ["Junior", "Mid-level", "Senior", "Lead", "Principal", "Staff"]
ROLES: list[str] = [
    "Backend Engineer", "Frontend Engineer", "Full Stack Developer", "Data Engineer",
    "Data Scientist", "DevOps Engineer", "Site Reliability Engineer",
    "Machine Learning Engineer", "Java Developer", "Python Developer",
    "Cloud Architect", "QA Automation Engineer", "Mobile Developer", "Platform Engineer",
]

CANDIDATE_DEFECTS: tuple[str, ...] = (
    "email_case_whitespace", "blank_full_name", "invalid_email",
    "duplicate_candidate_exact", "duplicate_candidate_email_variant",
)
JOB_DEFECTS: tuple[str, ...] = (
    "title_company_whitespace", "blank_title", "blank_company",
    "duplicate_job_exact", "duplicate_job_whitespace_variant",
)
CANDIDATE_SKILL_DEFECTS: tuple[str, ...] = (
    "skill_case_whitespace", "duplicate_skill_in_parent", "uncataloged_skill",
    "blank_skill_name", "negative_years", "non_numeric_years", "orphan_ref",
)
JOB_SKILL_DEFECTS: tuple[str, ...] = (
    "skill_case_whitespace", "duplicate_skill_in_parent", "uncataloged_skill",
    "blank_skill_name", "invalid_required", "orphan_ref",
)
ALL_DEFECTS: list[str] = sorted(
    set(CANDIDATE_DEFECTS) | set(JOB_DEFECTS) | set(CANDIDATE_SKILL_DEFECTS) | set(JOB_SKILL_DEFECTS)
)

_BAD_EMAILS = ("{u}.example.com", "{u}@", "{u}@example", "{u} x@example.com", "@example.com")
_BAD_YEARS = ("five", "3.5", "n/a", "~2", "ten years")
_BAD_REQUIRED = ("maybe", "unknown", "2", "sometimes")
_BLANKS = ("", "   ")


def faker_version() -> str:
    """Installed Faker version (recorded in the manifest)."""
    try:
        from importlib.metadata import version
        return version("Faker")
    except Exception:  # pragma: no cover - metadata unavailable
        from faker import VERSION
        return str(VERSION)


def _slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", s.lower()) or "x"


def _mixed_case(email: str) -> str:
    """john.doe3@example.com -> John.Doe3@Example.COM"""
    local, _, domain = email.partition("@")
    local_m = ".".join(p[:1].upper() + p[1:] for p in local.split("."))
    head, _, tail = domain.partition(".")
    domain_m = head[:1].upper() + head[1:] + ("." + tail.upper() if tail else "")
    return f"{local_m}@{domain_m}"


def _case_ws_variant(name: str, m: int) -> str:
    """Case/whitespace variant with the same normalized skill_key."""
    v = m % 3
    if v == 0:
        out = f" {name.lower()}"
    elif v == 1:
        out = name.upper() if name.upper() != name else name.lower()
    else:
        out = name.replace(" ", "  ") if " " in name else f"  {name}  "
    return out if out != name else f" {name} "


def _write_csv(path: Path, columns: list[str], rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh, lineterminator="\n")
        writer.writerow(columns)
        for r in rows:
            writer.writerow([r[c] for c in columns])


class _Generator:
    """Single-use builder holding the seeded RNGs and accumulated defects."""

    def __init__(self, seed: int, n_candidates: int, n_jobs: int, dirty_ratio: float) -> None:
        self.seed = seed
        self.n_candidates = n_candidates
        self.n_jobs = n_jobs
        self.ratio = dirty_ratio
        self.rng = random.Random(seed)
        self.fake = Faker("en_US")
        self.fake.seed_instance(seed)
        self.defects: dict[str, list[str]] = {d: [] for d in ALL_DEFECTS}
        self.uncataloged_used: list[str] = []
        self._counters: dict[str, int] = {}
        self.catalog_names = [n for n, _ in SKILL_CATALOG]
        self.catalog_keys = frozenset(skill_key(n) for n in self.catalog_names)

    # -- helpers -----------------------------------------------------------
    def _per_type(self, clean_count: int, n_types: int) -> int:
        if self.ratio <= 0 or clean_count == 0:
            return 0
        return max(1, round(self.ratio * clean_count / n_types))

    def _new_ref(self, prefix: str) -> str:
        n = self._counters.get(prefix, 0) + 1
        self._counters[prefix] = n
        return f"{prefix}{n:04d}"

    def _mark(self, defect: str, ref: str) -> None:
        self.defects[defect].append(ref)

    def _years(self) -> str:
        return "" if self.rng.random() < 0.10 else str(self.rng.randint(0, 15))

    def _required(self) -> str:
        return "true" if self.rng.random() < 0.70 else "false"

    def _job_title(self) -> str:
        return f"{self.rng.choice(SENIORITY)} {self.rng.choice(ROLES)}"

    # -- clean data --------------------------------------------------------
    def build_candidates(self) -> tuple[list[dict[str, str]], list[dict[str, str]], dict[str, set[str]]]:
        rows: list[dict[str, str]] = []
        links: list[dict[str, str]] = []
        keys: dict[str, set[str]] = {}
        for i in range(self.n_candidates):
            ref = f"C{i + 1:05d}"
            first, last = self.fake.first_name(), self.fake.last_name()
            email = f"{_slug(first)}.{_slug(last)}{i}@example.com"
            summary = self.fake.paragraph(nb_sentences=2) if self.rng.random() >= 0.15 else ""
            rows.append({"candidate_ref": ref, "full_name": f"{first} {last}",
                         "email": email, "summary": summary})
            skills = self.rng.sample(self.catalog_names, self.rng.randint(3, 8))
            keys[ref] = {skill_key(s) for s in skills}
            for s in skills:
                links.append({"candidate_ref": ref, "skill_name": s, "years_experience": self._years()})
        return rows, links, keys

    def build_jobs(self) -> tuple[list[dict[str, str]], list[dict[str, str]], dict[str, set[str]]]:
        rows: list[dict[str, str]] = []
        links: list[dict[str, str]] = []
        keys: dict[str, set[str]] = {}
        used: set[tuple[str, str]] = set()
        for j in range(self.n_jobs):
            ref = f"J{j + 1:05d}"
            for _ in range(10_000):
                title, company = self._job_title(), self.fake.company()
                if (title, company) not in used:
                    break
            else:  # pragma: no cover - practically unreachable
                raise RuntimeError("could not generate a unique (title, company)")
            used.add((title, company))
            rows.append({"job_ref": ref, "title": title, "company": company,
                         "description": self.fake.paragraph(nb_sentences=3)})
            skills = self.rng.sample(self.catalog_names, self.rng.randint(3, 7))
            keys[ref] = {skill_key(s) for s in skills}
            for s in skills:
                links.append({"job_ref": ref, "skill_name": s, "required": self._required()})
        return rows, links, keys

    # -- defects -----------------------------------------------------------
    def candidate_defects(self, rows: list[dict[str, str]]) -> tuple[list[dict[str, str]], list[tuple[str, str]]]:
        """Edit/extend candidate rows; return (extra rows, [(variant_ref, target_ref)])."""
        k = self._per_type(len(rows), len(CANDIDATE_DEFECTS))
        extras: list[dict[str, str]] = []
        variants: list[tuple[str, str]] = []
        if k == 0:
            return extras, variants
        n = len(rows)

        for idx in self.rng.sample(range(n), min(k, n)):
            row = rows[idx]
            row["email"] = f"  {_mixed_case(row['email'])} "
            self._mark("email_case_whitespace", row["candidate_ref"])
        for m in range(k):
            ref = self._new_ref("CX")
            extras.append({"candidate_ref": ref, "full_name": _BLANKS[m % 2],
                           "email": f"blank.name{m}@example.com", "summary": ""})
            self._mark("blank_full_name", ref)
        for m in range(k):
            ref = self._new_ref("CX")
            bad = _BAD_EMAILS[m % len(_BAD_EMAILS)].format(u=f"bad.email{m}")
            extras.append({"candidate_ref": ref, "full_name": self.fake.name(),
                           "email": bad, "summary": ""})
            self._mark("invalid_email", ref)
        for idx in self.rng.sample(range(n), min(k, n)):
            extras.append(dict(rows[idx]))
            self._mark("duplicate_candidate_exact", rows[idx]["candidate_ref"])
        for idx in self.rng.sample(range(n), min(k, n)):
            base = rows[idx]
            ref = self._new_ref("CX")
            clean_email = base["email"].strip().lower()
            extras.append({"candidate_ref": ref, "full_name": base["full_name"],
                           "email": f"  {clean_email.upper()}  ", "summary": base["summary"]})
            variants.append((ref, base["candidate_ref"]))
            self._mark("duplicate_candidate_email_variant", ref)
        return extras, variants

    def job_defects(self, rows: list[dict[str, str]]) -> list[dict[str, str]]:
        k = self._per_type(len(rows), len(JOB_DEFECTS))
        extras: list[dict[str, str]] = []
        if k == 0:
            return extras
        n = len(rows)

        for idx in self.rng.sample(range(n), min(k, n)):
            row = rows[idx]
            row["title"] = f"  {row['title']}"
            row["company"] = f"{row['company']}   "
            self._mark("title_company_whitespace", row["job_ref"])
        for m in range(k):
            ref = self._new_ref("JX")
            extras.append({"job_ref": ref, "title": _BLANKS[m % 2], "company": self.fake.company(),
                           "description": self.fake.sentence()})
            self._mark("blank_title", ref)
        for m in range(k):
            ref = self._new_ref("JX")
            extras.append({"job_ref": ref, "title": self._job_title(), "company": _BLANKS[m % 2],
                           "description": self.fake.sentence()})
            self._mark("blank_company", ref)
        for idx in self.rng.sample(range(n), min(k, n)):
            extras.append(dict(rows[idx]))
            self._mark("duplicate_job_exact", rows[idx]["job_ref"])
        for idx in self.rng.sample(range(n), min(k, n)):
            base = rows[idx]
            ref = self._new_ref("JX")
            extras.append({"job_ref": ref, "title": f" {base['title'].strip()} ",
                           "company": f"  {base['company'].strip()}",
                           "description": base["description"]})
            self._mark("duplicate_job_whitespace_variant", ref)
        return extras

    def link_edits(self, links: list[dict[str, str]], keys: dict[str, set[str]],
                   ref_col: str, k: int) -> None:
        """In-place edits that must still load: skill_case_whitespace, uncataloged_skill."""
        if k == 0 or not links:
            return
        pool = self.rng.sample(range(len(links)), len(links))  # distinct target rows
        for m in range(min(k, len(pool))):
            row = links[pool.pop()]
            row["skill_name"] = _case_ws_variant(row["skill_name"], m)
            self._mark("skill_case_whitespace", row[ref_col])
        for m in range(min(k, len(pool))):
            row = links[pool.pop()]
            owned = keys[row[ref_col]]
            rotated = UNCATALOGED_SKILLS[m % len(UNCATALOGED_SKILLS):] + \
                UNCATALOGED_SKILLS[:m % len(UNCATALOGED_SKILLS)]
            name = next((u for u in rotated if skill_key(u) not in owned), None)
            if name is None:  # parent already holds every uncataloged skill
                continue
            owned.discard(skill_key(row["skill_name"]))
            owned.add(skill_key(name))
            row["skill_name"] = name
            if name not in self.uncataloged_used:
                self.uncataloged_used.append(name)
            self._mark("uncataloged_skill", row[ref_col])

    def link_extras(self, links: list[dict[str, str]], keys: dict[str, set[str]], *,
                    ref_col: str, value_col: str, types: tuple[str, ...], k: int,
                    valid_value: Callable[[], str], orphan_prefix: str) -> list[dict[str, str]]:
        """Extra link rows: duplicate_skill_in_parent (merges) and rejected rows."""
        extras: list[dict[str, str]] = []
        if k == 0 or not links:
            return extras
        parents = sorted(keys)

        def row(ref: str, skill: str, value: str) -> dict[str, str]:
            return {ref_col: ref, "skill_name": skill, value_col: value}

        for _ in range(k):
            src = links[self.rng.randrange(len(links))]
            name = normalize_skill_name(src["skill_name"]) or src["skill_name"]
            if skill_key(name) in self.catalog_keys:
                # Catalog spelling wins in the cleaner, so a case variant is safe here.
                variant = name.upper() if name.upper() != name else name.lower()
                dup = f" {variant}"
            else:
                # Uncataloged: the stored spelling is the first one seen, so the
                # duplicate must differ only by whitespace to keep the manifest spelling.
                dup = f"  {name} "
            extras.append(row(src[ref_col], dup, valid_value()))
            self._mark("duplicate_skill_in_parent", src[ref_col])
        for m in range(k):
            ref = self.rng.choice(parents)
            extras.append(row(ref, _BLANKS[m % 2], valid_value()))
            self._mark("blank_skill_name", ref)
        bad_values: dict[str, Callable[[int], str]] = {
            "negative_years": lambda m: f"-{self.rng.randint(1, 5)}",
            "non_numeric_years": lambda m: _BAD_YEARS[m % len(_BAD_YEARS)],
            "invalid_required": lambda m: _BAD_REQUIRED[m % len(_BAD_REQUIRED)],
        }
        for defect, bad in bad_values.items():
            if defect not in types:
                continue
            for m in range(k):
                ref = self.rng.choice(parents)
                extras.append(row(ref, self.rng.choice(self.catalog_names), bad(m)))
                self._mark(defect, ref)
        for _ in range(k):
            ref = self._new_ref(orphan_prefix)
            extras.append(row(ref, self.rng.choice(self.catalog_names), valid_value()))
            self._mark("orphan_ref", ref)
        return extras

    # -- orchestration -----------------------------------------------------
    def run(self, out_dir: Path) -> dict[str, Any]:
        out_dir.mkdir(parents=True, exist_ok=True)
        rng = self.rng

        candidates, cand_links, cand_keys = self.build_candidates()
        jobs, job_links, job_keys = self.build_jobs()

        cand_extra, variants = self.candidate_defects(candidates)
        job_extra = self.job_defects(jobs)

        k_cs = self._per_type(len(cand_links), len(CANDIDATE_SKILL_DEFECTS))
        k_js = self._per_type(len(job_links), len(JOB_SKILL_DEFECTS))
        self.link_edits(cand_links, cand_keys, "candidate_ref", k_cs)
        self.link_edits(job_links, job_keys, "job_ref", k_js)

        # Email-variant rows carry one extra skill that must merge into the kept candidate.
        variant_links: list[dict[str, str]] = []
        for variant_ref, target_ref in variants:
            owned = cand_keys[target_ref]
            choices = [s for s in self.catalog_names if skill_key(s) not in owned]
            skill = rng.choice(choices)
            owned.add(skill_key(skill))
            variant_links.append({"candidate_ref": variant_ref, "skill_name": skill,
                                  "years_experience": self._years()})

        cs_extra = self.link_extras(cand_links, cand_keys, ref_col="candidate_ref",
                                    value_col="years_experience", types=CANDIDATE_SKILL_DEFECTS,
                                    k=k_cs, valid_value=self._years, orphan_prefix="CORPHAN")
        js_extra = self.link_extras(job_links, job_keys, ref_col="job_ref",
                                    value_col="required", types=JOB_SKILL_DEFECTS,
                                    k=k_js, valid_value=self._required, orphan_prefix="JORPHAN")

        expected_counts = {
            "skills": len(SKILL_CATALOG) + len(self.uncataloged_used),
            "candidates": len(candidates),
            "jobs": len(jobs),
            "candidate_skills": len(cand_links) + len(variant_links),
            "job_skills": len(job_links),
        }

        out_rows = {
            "skills": [{"skill_name": n, "category": c} for n, c in SKILL_CATALOG],
            "candidates": candidates + cand_extra,
            "jobs": jobs + job_extra,
            "candidate_skills": cand_links + variant_links + cs_extra,
            "job_skills": job_links + js_extra,
        }
        for key in ("candidates", "jobs", "candidate_skills", "job_skills"):
            rng.shuffle(out_rows[key])

        files: dict[str, int] = {}
        for key, filename in FILES.items():
            _write_csv(out_dir / filename, COLUMNS[filename], out_rows[key])
            files[filename] = len(out_rows[key])

        d = {t: len(refs) for t, refs in self.defects.items()}
        expected_rejects = {
            "blank_full_name": d["blank_full_name"],
            "invalid_email": d["invalid_email"],
            "duplicate_row": d["duplicate_candidate_exact"] + d["duplicate_job_exact"],
            "duplicate_natural_key": (d["duplicate_candidate_email_variant"]
                                      + d["duplicate_job_whitespace_variant"]),
            "blank_title": d["blank_title"],
            "blank_company": d["blank_company"],
            "blank_skill_name": d["blank_skill_name"],
            "invalid_years": d["negative_years"] + d["non_numeric_years"],
            "invalid_required": d["invalid_required"],
            "orphan_parent": d["orphan_ref"],
        }
        expected_rejects = {r: c for r, c in sorted(expected_rejects.items()) if c}

        manifest: dict[str, Any] = {
            "source": "synthetic",
            "seed": self.seed,
            "params": {
                "seed": self.seed,
                "n_candidates": self.n_candidates,
                "n_jobs": self.n_jobs,
                "dirty_ratio": self.ratio,
            },
            "faker_version": faker_version(),
            "files": files,
            "expected_counts": expected_counts,
            "expected_rejects": expected_rejects,
            "uncataloged_skills": sorted(self.uncataloged_used),
            "defects": self.defects,
        }
        with (out_dir / MANIFEST_FILE).open("w", encoding="utf-8", newline="\n") as fh:
            json.dump(manifest, fh, indent=2, sort_keys=True, ensure_ascii=False)
            fh.write("\n")
        logger.info("Wrote synthetic data to %s: %s", out_dir, files)
        return manifest


def generate(out_dir: Path | str, *, seed: int = 42, n_candidates: int = 200,
             n_jobs: int = 50, dirty_ratio: float = 0.10) -> dict[str, Any]:
    """Write contract CSVs + _manifest.json into ``out_dir``; return the manifest."""
    if n_candidates < 1:
        raise ValueError("n_candidates must be >= 1")
    if n_jobs < 1:
        raise ValueError("n_jobs must be >= 1")
    if not 0.0 <= dirty_ratio <= 1.0:
        raise ValueError("dirty_ratio must be between 0 and 1")
    return _Generator(seed, n_candidates, n_jobs, dirty_ratio).run(Path(out_dir))


class SyntheticSource(SourceAdapter):
    """SourceAdapter producing deterministic synthetic candidates and jobs."""

    name = "synthetic"

    def __init__(self, seed: int = 42, n_candidates: int = 200, n_jobs: int = 50,
                 dirty_ratio: float = 0.10) -> None:
        self.seed = seed
        self.n_candidates = n_candidates
        self.n_jobs = n_jobs
        self.dirty_ratio = dirty_ratio

    def generate(self, out_dir: Path | str) -> dict[str, Any]:
        """Generate files and return the manifest."""
        return generate(out_dir, seed=self.seed, n_candidates=self.n_candidates,
                        n_jobs=self.n_jobs, dirty_ratio=self.dirty_ratio)

    def produce(self, out_dir: Path) -> Path:
        self.generate(out_dir)
        return Path(out_dir)
