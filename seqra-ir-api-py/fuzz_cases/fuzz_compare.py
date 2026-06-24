from __future__ import annotations

import argparse
import importlib.util
import inspect
import random
import sys
from pathlib import Path


def load_module(path: Path, module_name: str):
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load module from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sample_args(arity: int, rng: random.Random) -> tuple[int, ...]:
    return tuple(rng.randint(-50, 50) for _ in range(arity))


def execute_call(fn, args: tuple[int, ...]):
    try:
        return True, fn(*args)
    except Exception as error:
        return False, (type(error).__name__, str(error))


def run_case(original_path: Path, generated_path: Path, iterations: int, seed: int) -> bool:
    original = load_module(original_path, f"orig_{original_path.stem}")
    generated = load_module(generated_path, f"gen_{generated_path.stem}")

    original_fn = getattr(original, "subject", None)
    generated_fn = getattr(generated, "subject", None)

    if not callable(original_fn) or not callable(generated_fn):
        print(f"FAIL {original_path.stem}: missing callable subject()")
        return False

    original_sig = inspect.signature(original_fn)
    generated_sig = inspect.signature(generated_fn)

    if len(original_sig.parameters) != len(generated_sig.parameters):
        print(
            f"FAIL {original_path.stem}: arity mismatch "
            f"{len(original_sig.parameters)} != {len(generated_sig.parameters)}"
        )
        return False

    rng = random.Random(seed)
    arity = len(original_sig.parameters)

    for _ in range(iterations):
        args = sample_args(arity, rng)
        original_ok, original_result = execute_call(original_fn, args)
        generated_ok, generated_result = execute_call(generated_fn, args)
        if (original_ok, original_result) != (generated_ok, generated_result):
            print(
                f"FAIL {original_path.stem}: args={args} "
                f"original={original_result!r} generated={generated_result!r}"
            )
            return False

    print(f"PASS {original_path.stem}: {iterations} iterations")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare original/generated Python modules by fuzzing.")
    parser.add_argument("--originals", type=Path)
    parser.add_argument("--generated", type=Path)
    parser.add_argument("--original-file", type=Path)
    parser.add_argument("--generated-file", type=Path)
    parser.add_argument("--iterations", type=int, default=200)
    parser.add_argument("--seed", type=int, default=12345)
    args = parser.parse_args()

    if args.original_file or args.generated_file:
        if args.original_file is None or args.generated_file is None:
            print("Both --original-file and --generated-file must be provided", file=sys.stderr)
            return 1
        return 0 if run_case(args.original_file, args.generated_file, args.iterations, args.seed) else 1

    if args.originals is None or args.generated is None:
        print("Either directory arguments or single-file arguments must be provided", file=sys.stderr)
        return 1

    originals = sorted(args.originals.glob("*.py"))
    if not originals:
        print("No original files found", file=sys.stderr)
        return 1

    ok = True
    for original_path in originals:
        generated_path = args.generated / f"{original_path.stem}_generated.py"
        if not generated_path.exists():
            print(f"FAIL {original_path.stem}: missing generated file {generated_path.name}")
            ok = False
            continue
        ok = run_case(original_path, generated_path, args.iterations, args.seed) and ok

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
