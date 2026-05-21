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

# spring-petclinic at tag 3.3.0 (stable, well-known class structure)
PETCLINIC_REPO = "https://github.com/spring-projects/spring-petclinic.git"
PETCLINIC_TAG  = "3.3.0"
PETCLINIC_DIR  = PROJECTS_DIR / "spring-petclinic"

SAMPLE_PROJECT_DIR = PROJECTS_DIR / "sample-java-project"


def check_sample_project() -> bool:
    """Verify the bundled sample project has all required source files."""
    required = [
        "src/main/java/com/example/User.java",
        "src/main/java/com/example/LegacyHelper.java",
        "src/main/java/com/example/Notifier.java",
        "src/main/java/com/example/utils/DateHelper.java",
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
    """Clone spring-petclinic at the known tag."""
    if PETCLINIC_DIR.exists():
        print(f"[OK] spring-petclinic already present at {PETCLINIC_DIR}")
        return True
    print(f"Cloning spring-petclinic {PETCLINIC_TAG} …")
    PROJECTS_DIR.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        ["git", "clone", "--depth", "1", "--branch", PETCLINIC_TAG, PETCLINIC_REPO, str(PETCLINIC_DIR)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        print(f"[ERROR] git clone failed:\n{result.stderr}")
        return False
    print(f"[OK] spring-petclinic cloned to {PETCLINIC_DIR}")
    print("     Open this directory in IntelliJ and let Maven sync complete before running benchmarks.")
    return True


def main():
    parser = argparse.ArgumentParser(description="Set up structured-refactoring-agent benchmark projects")
    parser.add_argument("--petclinic", action="store_true", help="Also clone spring-petclinic")
    args = parser.parse_args()

    ok = check_sample_project()

    if args.petclinic:
        ok = clone_petclinic() and ok

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
