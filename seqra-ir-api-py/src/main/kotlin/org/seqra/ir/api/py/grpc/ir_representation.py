import json
import os
from pathlib import Path
import tempfile
from typing import List

from google.protobuf.json_format import MessageToDict
from mypyc.analysis import dataflow
from mypyc.ir.class_ir import ClassIR

from mypy import build
from mypy.errors import CompileError
from mypy.options import Options
from mypyc.errors import Errors
from mypyc.ir.module_ir import ModuleIR
from mypyc.irbuild.main import build_ir
from mypyc.irbuild.mapper import Mapper
from mypyc.options import CompilerOptions
from mypyc.common import TOP_LEVEL_NAME
from mypyc.transform import exceptions
from mypyc.ir.pprint import format_func
import io
from contextlib import redirect_stdout


def print_ir_operations(module: ModuleIR):
    for func in module.functions:
        if func.decl.name.startswith('__'):
            continue

        print(f"\nФункция: {func.decl.name}")
        print("-" * 50)

        formatted_lines = format_func(func)
        for line in formatted_lines:
            print(line)


def print_ir_operations_to_string(module) -> str:
    output = io.StringIO()
    with redirect_stdout(output):
        print_ir_operations(module)

    return output.getvalue()


def print_ir_func(modules: List[ModuleIR]):
    for module in modules:
        output_str = print_ir_operations_to_string(module)
        with open('ir_operations_output.txt', 'a', encoding='utf-8') as f:
            f.write(output_str)


def _collect_source_files(files: List[str] = None, dir: str = None) -> List[Path]:
    if files:
        return [Path(file).resolve() for file in files]

    if dir:
        directory = Path(dir).resolve()
        exclude_patterns = {'__pycache__', '.git', 'venv', 'env', '.idea', '.vscode'}
        return sorted(
            file.resolve()
            for file in directory.rglob("*.py")
            if not any(pattern in file.parts for pattern in exclude_patterns)
        )

    return []


def _module_info(source_file: Path) -> tuple[str, str]:
    current = source_file.parent
    package_parts = []

    while (current / "__init__.py").exists():
        package_parts.insert(0, current.name)
        current = current.parent

    if source_file.name == "__init__.py":
        module_name = ".".join(package_parts) or source_file.parent.name
    else:
        module_name = ".".join(package_parts + [source_file.stem])

    return module_name, str(current)


def get_modules(files: List[str] = None, dir: str = None) -> List[ModuleIR]:
    source_files = _collect_source_files(files, dir)

    sources = []
    search_roots = set()
    for source_file in source_files:
        module_name, search_root = _module_info(source_file)
        search_roots.add(search_root)
        source = build.BuildSource(str(source_file), module_name, None)
        sources.append(source)

    compiler_options = CompilerOptions(capi_version=(3, 9))

    options = Options()
    options.show_traceback = True
    options.hide_error_codes = True
    options.use_builtins_fixtures = False
    options.strict_optional = True
    options.python_version = compiler_options.python_version or (3, 9)
    options.export_types = True
    options.preserve_asts = True
    options.allow_empty_bodies = True
    options.strict_bytes = True
    options.disable_bytearray_promotion = True
    options.disable_memoryview_promotion = True
    options.incremental = False
    options.sqlite_cache = False
    options.cache_dir = tempfile.mkdtemp(prefix="seqra-ir-mypy-cache-")
    options.mypy_path = sorted(search_roots)

    per_module_options = {}
    for source_file in source_files:
        module_name, _ = _module_info(source_file)
        per_module_options[module_name] = {"mypyc": True}
    options.per_module_options = per_module_options

    result = build.build(sources=sources, options=options)
    if result.errors:
        raise CompileError(result.errors)

    errors = Errors(options)
    mapper_dict = {name: None for name in per_module_options}
    mapper = Mapper(mapper_dict)

    build_files = [result.files[name] for name in per_module_options]
    modules = build_ir(build_files, result.graph, result.types, mapper, compiler_options, errors)
    modules_list = list(modules.values())

    return modules_list


def get_classes(files: List[str] = None, dir: str = None) -> List[ClassIR]:
    modules = get_modules(files, dir)
    classes = []
    for module in modules:
        classes.append(module.classes)

    return classes


def get_cfg(files: List[str] = None, dir: str = None):
    modules = get_modules(files, dir)

    funcs_cfg = []
    for module in modules:
        for fn in module.functions:
            if fn.name == TOP_LEVEL_NAME:
                continue

            exceptions.insert_exception_handling(fn, True)

            if fn.decl.name.startswith('__'):
                continue

            output = io.StringIO()
            with redirect_stdout(output):
                print(f"\nФункция: {fn.decl.name}")
                print("-" * 50)

                formatted_lines = format_func(fn)
                for line in formatted_lines:
                    print(line)

            output_str = output.getvalue()

            cfg = dataflow.get_cfg(fn.blocks)

            actual = []
            edges = []
            for block in fn.blocks:
                if block in cfg.succ:
                    for succ in cfg.succ[block]:
                        edges.append((block.label, succ.label))

            edges_by_source = {}
            for src, dst in edges:
                if src not in edges_by_source:
                    edges_by_source[src] = []
                edges_by_source[src].append(dst)

            actual.append("\n")
            actual.append("------------------------CFG-----------------------")
            for src in sorted(edges_by_source.keys()):
                dsts = sorted(edges_by_source[src])
                dsts_str = ', '.join(f'L{dst}' for dst in dsts)
                actual.append(f"L{src} -> [{dsts_str}]")
            actual.append("\n")

            with open('ir_operations_outputt.txt', 'a', encoding='utf-8') as f:
                f.write(output_str)
                f.writelines("\n".join(actual))

            funcs_cfg.append((fn, cfg))

    with open('cfgs_debug.json', 'w', encoding='utf-8') as f:
        json.dump(funcs_cfg, f, indent=2, ensure_ascii=False, default=str)

    print(len(funcs_cfg))
    return funcs_cfg


if __name__ == "__main__":
    sample = os.getenv("SEQRA_PY_SAMPLE")
    if sample:
        get_cfg([sample])