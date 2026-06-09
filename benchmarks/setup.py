#!/usr/bin/env python3
"""
Set up benchmark projects for the structured-refactoring-agent evaluation.

Usage:
    python benchmarks/setup.py                # verify sample-java-project only
    python benchmarks/setup.py --petclinic    # also clone spring-petclinic

After this script:
  benchmarks/projects/sample-java-project/   — minimal 5-class Maven project
  benchmarks/projects/spring-petclinic/      — (if --petclinic) cloned at known tag

Open each project in the sandbox IntelliJ instance before running benchmarks.
The plugin's tool server must be running (started automatically on project open).
"""

import argparse
import subprocess
import sys
from pathlib import Path

PROJECTS_DIR = Path(__file__).parent / "projects"

# spring-petclinic — clone main and pin to the SHA recorded after first clone
# (the repo doesn't ship semantic-version tags; we capture the commit SHA into
# a sibling file so benchmark runs remain reproducible across sessions).
PETCLINIC_REPO   = "https://github.com/spring-projects/spring-petclinic.git"
PETCLINIC_BRANCH = "main"
PETCLINIC_DIR    = PROJECTS_DIR / "spring-petclinic"
PETCLINIC_SHA_FILE = PROJECTS_DIR / "spring-petclinic.sha"

SAMPLE_PROJECT_DIR = PROJECTS_DIR / "sample-java-project"

# Apache Commons Lang — larger, real-world second project (246 source files) used as
# the scale / external-validity benchmark. Ships semantic release tags.
COMMONS_LANG_REPO = "https://github.com/apache/commons-lang.git"
COMMONS_LANG_TAG  = "rel/commons-lang-3.14.0"
COMMONS_LANG_DIR  = PROJECTS_DIR / "commons-lang"


def check_sample_project() -> bool:
    """Verify the bundled sample project has all required source files."""
    required = [
        "src/main/java/com/example/User.java",
        "src/main/java/com/example/LegacyHelper.java",
        "src/main/java/com/example/Notifier.java",
        "src/main/java/com/example/utils/DateHelper.java",
        "src/main/java/com/example/OrderProcessor.java",
        "src/main/java/com/example/NotificationController.java",
        "src/main/java/com/example/ServiceLayer.java",
        "src/main/java/com/example/payments",
        "pom.xml",
    ]
    missing = [p for p in required if not (SAMPLE_PROJECT_DIR / p).exists()]
    if missing:
        print(f"[WARN] sample-java-project missing: {missing}")
        return False
    print(f"[OK] sample-java-project verified at {SAMPLE_PROJECT_DIR}")
    return True


def clone_petclinic() -> bool:
    """Clone spring-petclinic at the known branch and record the HEAD SHA."""
    if PETCLINIC_DIR.exists():
        print(f"[OK] spring-petclinic already present at {PETCLINIC_DIR}")
        return True
    print(f"Cloning spring-petclinic {PETCLINIC_BRANCH} ...")
    PROJECTS_DIR.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        ["git", "clone", "--depth", "1", "--branch", PETCLINIC_BRANCH, PETCLINIC_REPO, str(PETCLINIC_DIR)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        print(f"[ERROR] git clone failed:\n{result.stderr}")
        return False
    # Record the SHA so benchmarks remain reproducible
    sha_r = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=PETCLINIC_DIR, capture_output=True, text=True,
    )
    sha = sha_r.stdout.strip()
    PETCLINIC_SHA_FILE.write_text(sha + "\n", encoding="utf-8")
    print(f"[OK] spring-petclinic cloned to {PETCLINIC_DIR}")
    print(f"     HEAD SHA recorded: {sha}")
    print("     Open this directory in IntelliJ and let Maven sync complete before running benchmarks.")
    return True


def clone_commons_lang() -> bool:
    """Clone Apache Commons Lang at a pinned release tag (the larger, real-world
    second project used for the scale / external-validity benchmark)."""
    if COMMONS_LANG_DIR.exists():
        print(f"[OK] commons-lang already present at {COMMONS_LANG_DIR}")
        return True
    print(f"Cloning Apache Commons Lang {COMMONS_LANG_TAG} ...")
    PROJECTS_DIR.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        ["git", "clone", "--depth", "1", "--branch", COMMONS_LANG_TAG,
         COMMONS_LANG_REPO, str(COMMONS_LANG_DIR)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        print(f"[ERROR] git clone failed:\n{result.stderr}")
        return False
    print(f"[OK] commons-lang cloned to {COMMONS_LANG_DIR}")
    print("     Open it in the sandbox IntelliJ (Maven), let the import finish, then run")
    print("     run_petclinic_direct.py --tasks benchmarks/tasks_commons_lang.json --allow-project-mismatch")
    return True


def main():
    parser = argparse.ArgumentParser(description="Set up structured-refactoring-agent benchmark projects")
    parser.add_argument("--petclinic", action="store_true", help="Also clone spring-petclinic")
    parser.add_argument("--commons-lang", action="store_true", help="Also clone Apache Commons Lang (larger second project)")
    args = parser.parse_args()

    ok = check_sample_project()

    if args.petclinic:
        ok = clone_petclinic() and ok

    if args.commons_lang:
        ok = clone_commons_lang() and ok

    if not ok:
        print("\nSome checks failed — see warnings above.")
        sys.exit(1)

    print("\nSetup complete.")
    if args.petclinic:
        print("Next steps:")
        print("  1. Open benchmarks/projects/sample-java-project in IntelliJ (sandbox runIde instance).")
        print("  2. Run benchmarks/run_benchmarks.py --tasks benchmarks/tasks.json")
        print("  3. Open benchmarks/projects/spring-petclinic in IntelliJ.")
        print("  4. Run benchmarks/run_benchmarks.py --tasks benchmarks/tasks_petclinic.json")
    else:
        print("  Open benchmarks/projects/sample-java-project in the sandbox IntelliJ instance,")
        print("  then run: python benchmarks/run_benchmarks.py --tasks benchmarks/tasks.json")


if __name__ == "__main__":
    main()
