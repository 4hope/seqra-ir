import argparse
import json
import grpc
from concurrent import futures
import os
from pathlib import Path
import logging
import traceback
import pickle

from google.protobuf.json_format import MessageToDict
from mypyc.ir.ops import Integer, Op

import ir_pb2
import ir_pb2_grpc

from mypyc.analysis import dataflow
from mypyc.ir.class_ir import ClassIR as MypyClassIR
from mypyc.ir.module_ir import ModuleIR
from mypyc.ir.func_ir import FuncIR, Register
from mypyc.common import TOP_LEVEL_NAME
from mypyc.transform import exceptions

from ir_representation import get_modules, get_cfg

logging.basicConfig(level=logging.DEBUG)
logger = logging.getLogger(__name__)


def parse_source(request):
    if request.HasField("files"):
        return {"files": list(request.files.files)}
    elif request.HasField("directory"):
        return {"dir": request.directory.path}
    else:
        raise ValueError("SourceRequest must contain files or directory")


def iter_classes(classes_obj):
    if classes_obj is None:
        return []
    if isinstance(classes_obj, dict):
        return list(classes_obj.values())
    return list(classes_obj)


def safe_str(value, default=""):
    if value is None:
        return default
    try:
        return str(value)
    except Exception:
        return default


def safe_int(value, default=0):
    if value is None:
        return default
    if hasattr(value, "label"):
        value = value.label
    try:
        return int(value)
    except Exception:
        return default


def safe_bool(value, default=False):
    if value is None:
        return default
    try:
        return bool(value)
    except Exception:
        return default


def safe_bytes(value):
    if value is None:
        return b""
    if isinstance(value, bytes):
        return value
    try:
        return pickle.dumps(value)
    except Exception:
        return b""


def build_block_label_map(blocks) -> dict[int, int]:
    return {id(block): index for index, block in enumerate(blocks)}


def resolve_block_label(block_like, block_labels: dict[int, int], default=0) -> int:
    if block_like is None:
        return default

    mapped = block_labels.get(id(block_like))
    if mapped is not None:
        return mapped

    label = getattr(block_like, "label", None)
    if label is not None:
        mapped = block_labels.get(id(label))
        if mapped is not None:
            return mapped

    return safe_int(block_like, default)


def convert_error_kind(error_kind) -> int:
    if error_kind is None:
        return ir_pb2.ERR_NEVER

    if hasattr(error_kind, "value"):
        error_kind = error_kind.value

    mapping = {
        0: ir_pb2.ERR_NEVER,
        1: ir_pb2.ERR_MAGIC,
        2: ir_pb2.ERR_FALSE,
        3: ir_pb2.ERR_ALWAYS,
        4: ir_pb2.ERR_MAGIC_OVERLAPPING,
    }
    return mapping.get(safe_int(error_kind, 0), ir_pb2.ERR_NEVER)


def convert_module(module: ModuleIR) -> ir_pb2.Module:
    final_names = []
    if hasattr(module, "final_names"):
        for name, rtype in module.final_names:
            final_names.append(
                ir_pb2.StringRtype(
                    name=safe_str(name),
                    type=convert_rtype(rtype),
                )
            )

    return ir_pb2.Module(
        fullname=safe_str(module.fullname),
        imports=list(getattr(module, "imports", [])),
        functions=[convert_function(f) for f in getattr(module, "functions", [])],
        classes=[convert_class(c) for c in iter_classes(getattr(module, "classes", None))],
        final_names=final_names,
        type_var_names=list(getattr(module, "type_var_names", [])),
    )


def convert_rtype(rtype) -> ir_pb2.RType:
    if rtype is None:
        return ir_pb2.RType(name="None")

    rtype_proto = ir_pb2.RType(
        name=safe_str(rtype),
        is_unboxed=safe_bool(getattr(rtype, "is_unboxed", False)),
        c_undefined=safe_str(getattr(rtype, "c_undefined", "")),
        is_refcounted=safe_bool(getattr(rtype, "is_refcounted", False)),
        ctype=safe_str(getattr(rtype, "_ctype", "")),
        error_overlap=safe_bool(getattr(rtype, "error_overlap", False)),
    )

    class_name = getattr(getattr(rtype, "__class__", None), "__name__", "")

    if class_name == "RPrimitive":
        rtype_proto.rprimitive.CopyFrom(
            ir_pb2.RPrimitive(
                is_native_int=safe_bool(getattr(rtype, "is_native_int", False)),
                is_signed=safe_bool(getattr(rtype, "is_signed", False)),
                size=safe_int(getattr(rtype, "size", 0), 0),
                may_be_immortal=safe_bool(getattr(rtype, "may_be_immortal", False)),
            )
        )

    elif class_name == "RTuple":
        rtuple = ir_pb2.RTuple(
            unique_id=safe_str(getattr(rtype, "unique_id", "")),
            struct_name=safe_str(getattr(rtype, "struct_name", "")),
        )
        for t in getattr(rtype, "types", []):
            rtuple.types.append(convert_rtype(t))
        rtype_proto.rtuple.CopyFrom(rtuple)

    elif class_name == "RStruct":
        rstruct = ir_pb2.RStruct()
        rstruct.names.extend(list(getattr(rtype, "names", [])))
        for t in getattr(rtype, "types", []):
            rstruct.types.append(convert_rtype(t))
        rstruct.offsets.extend(list(getattr(rtype, "offsets", [])))
        rstruct.size = safe_int(getattr(rtype, "size", 0), 0)
        rtype_proto.rstruct.CopyFrom(rstruct)

    elif class_name == "RVoid":
        rtype_proto.rvoid.CopyFrom(ir_pb2.RVoid())

    elif class_name == "RInstance":
        # Полный class_ir можно добавить потом, если понадобится.
        rtype_proto.rinstance.CopyFrom(ir_pb2.RInstance())

    elif class_name == "RUnion":
        runion = ir_pb2.RUnion()
        for item in getattr(rtype, "items", []):
            runion.items.append(convert_rtype(item))
        for item in getattr(rtype, "items_set", []):
            runion.items_set.append(convert_rtype(item))
        rtype_proto.runion.CopyFrom(runion)

    elif class_name == "RArray":
        rarray = ir_pb2.RArray(
            length=safe_int(getattr(rtype, "length", 0), 0),
        )
        item_type = getattr(rtype, "item_type", None)
        if item_type is not None:
            rarray.item_type.CopyFrom(convert_rtype(item_type))
        rtype_proto.rarray.CopyFrom(rarray)

    return rtype_proto


def convert_register(reg: Register) -> ir_pb2.Register:
    return ir_pb2.Register(
        name=safe_str(getattr(reg, "name", "")),
        type=convert_rtype(getattr(reg, "type", None)),
        is_arg=safe_bool(getattr(reg, "is_arg", False)),
        is_borrowed=safe_bool(getattr(reg, "is_borrowed", False)),
        line=safe_int(getattr(reg, "line", -1), -1),
    )


def convert_value(value) -> ir_pb2.Value:
    value_proto = ir_pb2.Value(
        line=safe_int(getattr(value, "line", -1), -1),
        type=convert_rtype(getattr(value, "type", None)),
        is_borrowed=safe_bool(getattr(value, "is_borrowed", False)),
    )

    if value is None:
        return value_proto

    class_name = getattr(getattr(value, "__class__", None), "__name__", "")

    if isinstance(value, Register):
        value_proto.register.CopyFrom(convert_register(value))

    elif isinstance(value, Integer):
        value_proto.integer.CopyFrom(
            ir_pb2.Integer(
                value=getattr(value, "value", 0),
                type=convert_rtype(getattr(value, "type", None)),
                line=safe_int(getattr(value, "line", -1), -1),
            )
        )

    elif class_name == "Float":
        raw_value = getattr(value, "value", 0.0)
        if isinstance(raw_value, float):
            raw_value = int(raw_value)
        value_proto.float_val.CopyFrom(
            ir_pb2.Float(
                value=safe_int(raw_value, 0),
                type=convert_rtype(getattr(value, "type", None)),
                line=safe_int(getattr(value, "line", -1), -1),
            )
        )

    elif class_name == "CString":
        c_value = getattr(value, "value", "")
        if isinstance(c_value, bytes):
            try:
                c_value = c_value.decode("utf-8", errors="replace")
            except Exception:
                c_value = str(c_value)
        value_proto.cstring.CopyFrom(
            ir_pb2.CString(
                value=safe_int(c_value, 0),
                type=convert_rtype(getattr(value, "type", None)),
                line=safe_int(getattr(value, "line", -1), -1),
            )
        )

    elif class_name == "Undef":
        value_proto.undef.CopyFrom(
            ir_pb2.Undef(
                type=convert_rtype(getattr(value, "type", None))
            )
        )

    elif isinstance(value, Op):
        value_proto.op.CopyFrom(
            ir_pb2.Op(
                name=safe_str(getattr(value, "__class__", None).__name__ if getattr(value, "__class__", None) else ""),
                value=convert_op_metadata(value),
            )
        )

    return value_proto

def convert_op_metadata(op) -> ir_pb2.Value:
    return ir_pb2.Value(
        line=safe_int(getattr(op, "line", -1), -1),
        type=convert_rtype(getattr(op, "type", None)),
        is_borrowed=safe_bool(getattr(op, "is_borrowed", False)),
    )

def convert_op(op: Op) -> ir_pb2.Op:
    op_proto = ir_pb2.Op()
    try:
        op_proto.value.CopyFrom(convert_op_metadata(op))
    except Exception:
        pass

def convert_func_decl(decl) -> ir_pb2.FuncDecl:
    if not decl:
        return ir_pb2.FuncDecl()

    func_decl_proto = ir_pb2.FuncDecl()

    try:
        func_decl_proto.name = safe_str(getattr(decl, "name", ""))

        class_name = getattr(decl, "class_name", None)
        if class_name is not None:
            func_decl_proto.class_name = safe_str(class_name)

        func_decl_proto.module_name = safe_str(getattr(decl, "module_name", ""))

        kind_value = getattr(decl, "kind", None)
        if hasattr(kind_value, "value"):
            kind_value = kind_value.value

        kind_mapping = {
            0: ir_pb2.FunctionKind.FUNC_UNSPECIFIED,
            1: ir_pb2.FunctionKind.FUNC_NORMAL,
            2: ir_pb2.FunctionKind.FUNC_STATICMETHOD,
            3: ir_pb2.FunctionKind.FUNC_CLASSMETHOD,
            4: ir_pb2.FunctionKind.FUNC_PROPERTY_GETTER,
            5: ir_pb2.FunctionKind.FUNC_PROPERTY_SETTER,
        }
        func_decl_proto.kind = kind_mapping.get(safe_int(kind_value, 0), ir_pb2.FunctionKind.FUNC_UNSPECIFIED)

        func_decl_proto.is_prop_setter = safe_bool(getattr(decl, "is_prop_setter", False))
        func_decl_proto.is_prop_getter = safe_bool(getattr(decl, "is_prop_getter", False))
        func_decl_proto.is_generator = safe_bool(getattr(decl, "is_generator", False))
        func_decl_proto.is_coroutine = safe_bool(getattr(decl, "is_coroutine", False))
        func_decl_proto.implicit = safe_bool(getattr(decl, "implicit", False))
        func_decl_proto.internal = safe_bool(getattr(decl, "internal", False))

        line_val = getattr(decl, "line", None)
        if line_val is not None:
            func_decl_proto.line = safe_int(line_val, 0)

        sig = getattr(decl, "sig", None)
        if sig:
            func_decl_proto.sig.CopyFrom(convert_func_signature(sig))

        bound_sig = getattr(decl, "bound_sig", None)
        if bound_sig:
            func_decl_proto.bound_sig.CopyFrom(convert_func_signature(bound_sig))

    except Exception as e:
        logger.warning(f"Error converting FuncDecl: {e}")
        logger.debug(traceback.format_exc())

    return func_decl_proto


def convert_func_signature(sig) -> ir_pb2.FuncSignature:
    if not sig:
        return ir_pb2.FuncSignature()

    sig_proto = ir_pb2.FuncSignature()

    try:
        for arg_type in getattr(sig, "args", []):
            sig_proto.args.append(convert_rtype(arg_type))

        ret_type = getattr(sig, "ret_type", None)
        if ret_type is not None:
            sig_proto.ret_type.CopyFrom(convert_rtype(ret_type))

        sig_proto.num_bitmap_args = safe_int(getattr(sig, "num_bitmap_args", 0), 0)

    except Exception as e:
        logger.warning(f"Error converting FuncSignature: {e}")
        logger.debug(traceback.format_exc())

    return sig_proto


def convert_op(op: Op, block_labels: dict[int, int] | None = None) -> ir_pb2.Op:
    op_proto = ir_pb2.Op()
    try:
        op_proto.value.CopyFrom(convert_value(op))
    except Exception:
        pass

    try:
        op_class_name = op.__class__.__name__
        logger.debug(f"Converting operation: {op_class_name}")
        op_proto.name = op_class_name

        if op_class_name == "Assign":
            base_assign = ir_pb2.BaseAssign()

            if hasattr(op, "dest") and op.dest is not None:
                base_assign.dest.CopyFrom(convert_register(op.dest))

            if hasattr(op, "src") and op.src is not None:
                assign = ir_pb2.Assign()
                assign.src.CopyFrom(convert_value(op.src))
                base_assign.assign.CopyFrom(assign)

            op_proto.base_assing.CopyFrom(base_assign)

        elif op_class_name == "AssignMulti":
            base_assign = ir_pb2.BaseAssign()

            if hasattr(op, "dest") and op.dest is not None:
                base_assign.dest.CopyFrom(convert_register(op.dest))

            if hasattr(op, "src") and op.src is not None:
                assign_multi = ir_pb2.AssignMulti()
                for src in op.src:
                    assign_multi.src.append(convert_value(src))
                base_assign.assing_multi.CopyFrom(assign_multi)

            op_proto.base_assing.CopyFrom(base_assign)

        elif op_class_name == "Goto":
            control_op = ir_pb2.ControlOp()
            target = getattr(op, "label", None)
            target_label = resolve_block_label(target, block_labels or {}, 0)
            control_op.goto.CopyFrom(ir_pb2.Goto(label=target_label))
            op_proto.control_op.CopyFrom(control_op)

        elif op_class_name == "Branch":
            control_op = ir_pb2.ControlOp()
            branch = ir_pb2.Branch()

            if hasattr(op, "value") and op.value is not None:
                branch.value.CopyFrom(convert_value(op.value))
            if hasattr(op, "true") and op.true is not None:
                branch.true_label.CopyFrom(
                    ir_pb2.BasicBlock(label=resolve_block_label(op.true, block_labels or {}, 0))
                )
            if hasattr(op, "false") and op.false is not None:
                branch.false_label.CopyFrom(
                    ir_pb2.BasicBlock(label=resolve_block_label(op.false, block_labels or {}, 0))
                )

            op_value = getattr(op, "op", None)
            if op_value == "is_error":
                branch.op = ir_pb2.Branch.OpType.IS_ERROR
            else:
                branch.op = ir_pb2.Branch.OpType.BOOL

            branch.negated = safe_bool(getattr(op, "negated", False))
            branch.rare = safe_bool(getattr(op, "rare", False))

            traceback_entry = getattr(op, "traceback_entry", None)
            if traceback_entry:
                branch.traceback_entry.CopyFrom(
                    ir_pb2.TracebackEntry(
                        func_name=safe_str(getattr(traceback_entry, "func_name", "")),
                        line=safe_int(getattr(traceback_entry, "line", 0), 0),
                    )
                )

            control_op.branch.CopyFrom(branch)
            op_proto.control_op.CopyFrom(control_op)

        elif op_class_name == "Return":
            control_op = ir_pb2.ControlOp()
            return_op = ir_pb2.Return()
            if hasattr(op, "value") and op.value is not None:
                return_op.value.CopyFrom(convert_value(op.value))

            yield_target = getattr(op, "yield_target", None)
            if yield_target is not None:
                return_op.yield_target = safe_int(yield_target, 0)

            getattr(control_op, "return").CopyFrom(return_op)
            op_proto.control_op.CopyFrom(control_op)

        elif op_class_name == "Unreachable":
            control_op = ir_pb2.ControlOp()
            control_op.unreachable.CopyFrom(ir_pb2.Unreachable())
            op_proto.control_op.CopyFrom(control_op)

        elif op_class_name == "IncRef":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            if hasattr(op, "src") and op.src is not None:
                register_op.inc_ref.CopyFrom(ir_pb2.IncRef(src=convert_value(op.src)))
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "DecRef":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            dec_ref = ir_pb2.DecRef(is_xdec=safe_bool(getattr(op, "is_xdec", False)))
            if hasattr(op, "src") and op.src is not None:
                dec_ref.src.CopyFrom(convert_value(op.src))
            register_op.dec_ref.CopyFrom(dec_ref)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "Call":
            register_op = ir_pb2.RegisterOp(error_kind=convert_error_kind(getattr(op, "error_kind", None)))
            call = ir_pb2.Call()

            fn_decl = getattr(op, "fn", None)
            if fn_decl:
                call.fn.CopyFrom(convert_func_decl(fn_decl))

            for arg in getattr(op, "args", []):
                call.args.append(convert_value(arg))

            if hasattr(op, "type"):
                call.type.CopyFrom(convert_rtype(op.type))

            register_op.call.CopyFrom(call)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "MethodCall":
            register_op = ir_pb2.RegisterOp(error_kind=convert_error_kind(getattr(op, "error_kind", None)))
            method_call = ir_pb2.MethodCall()

            if hasattr(op, "obj") and op.obj is not None:
                method_call.obj.CopyFrom(convert_value(op.obj))

            method_call.method = safe_str(getattr(op, "method", ""))

            for arg in getattr(op, "args", []):
                method_call.args.append(convert_value(arg))

            if hasattr(op, "receiver_type"):
                method_call.receiver_type.CopyFrom(convert_rtype(op.receiver_type))

            if hasattr(op, "type"):
                method_call.type.CopyFrom(convert_rtype(op.type))

            register_op.method_call.CopyFrom(method_call)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "PrimitiveOp":
            register_op = ir_pb2.RegisterOp(error_kind=convert_error_kind(getattr(op, "error_kind", None)))
            primitive_op = ir_pb2.PrimitiveOp()

            for arg in getattr(op, "args", []):
                primitive_op.args.append(convert_value(arg))

            if hasattr(op, "type"):
                primitive_op.type.CopyFrom(convert_rtype(op.type))

            desc = getattr(op, "desc", None)
            if desc:
                primitive_desc = ir_pb2.PrimitiveDescription(
                    name=safe_str(getattr(desc, "name", "")),
                    c_function_name=safe_str(getattr(desc, "c_function_name", "")),
                    error_kind=convert_error_kind(getattr(desc, "error_kind", None)),
                    is_borrowed=safe_bool(getattr(desc, "is_borrowed", False)),
                    priority=safe_int(getattr(desc, "priority", 0), 0),
                    is_pure=safe_bool(getattr(desc, "is_pure", False)),
                    experimental=safe_bool(getattr(desc, "experimental", False)),
                    is_ambiguous=safe_bool(getattr(desc, "is_ambiguous", False)),
                )
                for t in getattr(desc, "arg_types", []):
                    primitive_desc.arg_types.append(convert_rtype(t))
                ret_type = getattr(desc, "return_type", None)
                if ret_type is not None:
                    primitive_desc.return_type.CopyFrom(convert_rtype(ret_type))
                var_arg_type = getattr(desc, "var_arg_type", None)
                if var_arg_type is not None:
                    primitive_desc.var_arg_type.CopyFrom(convert_rtype(var_arg_type))
                truncated_type = getattr(desc, "truncated_type", None)
                if truncated_type is not None:
                    primitive_desc.truncated_type.CopyFrom(convert_rtype(truncated_type))
                ordering = getattr(desc, "ordering", None)
                if ordering:
                    primitive_desc.ordering.extend(list(ordering))
                primitive_op.desc.CopyFrom(primitive_desc)

            register_op.primitive_op.CopyFrom(primitive_op)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "LoadErrorValue":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            load_error = ir_pb2.LoadErrorValue(
                is_borrowed=safe_bool(getattr(op, "is_borrowed", False)),
                undefines=safe_bool(getattr(op, "undefines", False)),
            )
            if hasattr(op, "type"):
                load_error.type.CopyFrom(convert_rtype(op.type))
            register_op.load_error_value.CopyFrom(load_error)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "LoadLiteral":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            load_literal = ir_pb2.LoadLiteral()

            if hasattr(op, "type"):
                load_literal.type.CopyFrom(convert_rtype(op.type))

            literal = ir_pb2.LiteralValue()
            literal_value = getattr(op, "value", None)
            if isinstance(literal_value, bool):
                literal.bool_value = literal_value
            elif isinstance(literal_value, int):
                literal.int_value = literal_value
            elif isinstance(literal_value, float):
                literal.float_value = literal_value
            elif isinstance(literal_value, str):
                literal.str_value = literal_value

            load_literal.value.CopyFrom(literal)
            register_op.load_literal.CopyFrom(load_literal)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "GetAttr":
            register_op = ir_pb2.RegisterOp(error_kind=convert_error_kind(getattr(op, "error_kind", None)))
            get_attr = ir_pb2.GetAttr(
                attr=safe_str(getattr(op, "attr", "")),
                allow_error_value=safe_bool(getattr(op, "allow_error_value", False)),
                is_borrowed=safe_bool(getattr(op, "is_borrowed", False)),
            )

            if hasattr(op, "obj") and op.obj is not None:
                get_attr.obj.CopyFrom(convert_value(op.obj))
            if hasattr(op, "class_type"):
                get_attr.class_type.CopyFrom(convert_rtype(op.class_type))

            register_op.get_attr.CopyFrom(get_attr)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "SetAttr":
            register_op = ir_pb2.RegisterOp(error_kind=convert_error_kind(getattr(op, "error_kind", None)))
            set_attr = ir_pb2.SetAttr(
                attr=safe_str(getattr(op, "attr", "")),
                is_init=safe_bool(getattr(op, "is_init", False)),
            )

            if hasattr(op, "obj") and op.obj is not None:
                set_attr.obj.CopyFrom(convert_value(op.obj))
            if hasattr(op, "src") and op.src is not None:
                set_attr.src.CopyFrom(convert_value(op.src))
            if hasattr(op, "class_type"):
                set_attr.class_type.CopyFrom(convert_rtype(op.class_type))
            if hasattr(op, "type"):
                set_attr.type.CopyFrom(convert_rtype(op.type))

            register_op.set_attr.CopyFrom(set_attr)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "LoadStatic":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            load_static = ir_pb2.LoadStatic(
                identifier=safe_str(getattr(op, "identifier", "")),
                module_name=safe_str(getattr(op, "module_name", "")),
                namespace=safe_str(getattr(op, "namespace", "")),
                ann=safe_bytes(getattr(op, "ann", None)),
            )

            if hasattr(op, "type"):
                load_static.type.CopyFrom(convert_rtype(op.type))

            register_op.load_static.CopyFrom(load_static)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "InitStatic":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            init_static = ir_pb2.InitStatic(
                identifier=safe_str(getattr(op, "identifier", "")),
                module_name=safe_str(getattr(op, "module_name", "")),
                namespace=safe_str(getattr(op, "namespace", "")),
            )

            if hasattr(op, "value") and op.value is not None:
                init_static.value.CopyFrom(convert_value(op.value))

            register_op.init_static.CopyFrom(init_static)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "TupleSet":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            tuple_set = ir_pb2.TupleSet()

            for item in getattr(op, "items", []):
                tuple_set.items.append(convert_value(item))

            tuple_type = getattr(op, "tuple_type", None)
            if tuple_type is not None:
                tuple_set.tuple_type.CopyFrom(convert_rtype(tuple_type))

            if hasattr(op, "type"):
                tuple_set.type.CopyFrom(convert_rtype(op.type))

            register_op.tuple_set.CopyFrom(tuple_set)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "TupleGet":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            tuple_get = ir_pb2.TupleGet(
                index=safe_int(getattr(op, "index", 0), 0),
                is_borrowed=safe_bool(getattr(op, "is_borrowed", False)),
            )

            if hasattr(op, "src") and op.src is not None:
                tuple_get.src.CopyFrom(convert_value(op.src))
            if hasattr(op, "type"):
                tuple_get.type.CopyFrom(convert_rtype(op.type))

            register_op.tuple_get.CopyFrom(tuple_get)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "Cast":
            register_op = ir_pb2.RegisterOp(error_kind=convert_error_kind(getattr(op, "error_kind", None)))
            cast = ir_pb2.Cast(
                is_borrowed=safe_bool(getattr(op, "is_borrowed", False)),
                is_unchecked=safe_bool(getattr(op, "is_unchecked", False)),
            )

            if hasattr(op, "src") and op.src is not None:
                cast.src.CopyFrom(convert_value(op.src))
            if hasattr(op, "type"):
                cast.type.CopyFrom(convert_rtype(op.type))

            register_op.cast.CopyFrom(cast)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "Box":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            box = ir_pb2.Box(
                is_borrowed=safe_bool(getattr(op, "is_borrowed", False))
            )

            if hasattr(op, "src") and op.src is not None:
                box.src.CopyFrom(convert_value(op.src))
            if hasattr(op, "type"):
                box.type.CopyFrom(convert_rtype(op.type))

            register_op.box.CopyFrom(box)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "Unbox":
            register_op = ir_pb2.RegisterOp(error_kind=convert_error_kind(getattr(op, "error_kind", None)))
            unbox = ir_pb2.Unbox()

            if hasattr(op, "src") and op.src is not None:
                unbox.src.CopyFrom(convert_value(op.src))
            if hasattr(op, "type"):
                unbox.type.CopyFrom(convert_rtype(op.type))

            register_op.unbox.CopyFrom(unbox)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "RaiseStandardError":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_FALSE)
            raise_error = ir_pb2.RaiseStandardError(
                class_name=safe_str(getattr(op, "class_name", ""))
            )

            value = getattr(op, "value", None)
            if isinstance(value, str):
                raise_error.str_value = value
            elif value is not None:
                raise_error.value.CopyFrom(convert_value(value))

            if hasattr(op, "type"):
                raise_error.type.CopyFrom(convert_rtype(op.type).rprimitive)

            register_op.raise_standard_error.CopyFrom(raise_error)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "CallC":
            register_op = ir_pb2.RegisterOp(error_kind=convert_error_kind(getattr(op, "error_kind", None)))
            call_c = ir_pb2.CallC(
                function_name=safe_str(getattr(op, "function_name", "")),
                is_borrowed=safe_bool(getattr(op, "is_borrowed", False)),
                var_arg_idx=safe_int(getattr(op, "var_arg_idx", 0), 0),
                is_pure=safe_bool(getattr(op, "is_pure", False)),
                returns_null=safe_bool(getattr(op, "returns_null", False)),
            )

            for arg in getattr(op, "args", []):
                call_c.args.append(convert_value(arg))

            if hasattr(op, "type"):
                call_c.type.CopyFrom(convert_rtype(op.type))

            register_op.call_c.CopyFrom(call_c)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "Truncate":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            truncate = ir_pb2.Truncate()

            if hasattr(op, "src") and op.src is not None:
                truncate.src.CopyFrom(convert_value(op.src))
            if hasattr(op, "type"):
                truncate.type.CopyFrom(convert_rtype(op.type))
            if hasattr(op, "src_type"):
                truncate.src_type.CopyFrom(convert_rtype(op.src_type))

            register_op.truncate.CopyFrom(truncate)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "Extend":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            extend = ir_pb2.Extend(
                signed=safe_bool(getattr(op, "signed", False))
            )

            if hasattr(op, "src") and op.src is not None:
                extend.src.CopyFrom(convert_value(op.src))
            if hasattr(op, "type"):
                extend.type.CopyFrom(convert_rtype(op.type))
            if hasattr(op, "src_type"):
                extend.src_type.CopyFrom(convert_rtype(op.src_type))

            register_op.extend.CopyFrom(extend)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "LoadGlobal":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            load_global = ir_pb2.LoadGlobal(
                identifier=safe_str(getattr(op, "identifier", "")),
                ann=safe_bytes(getattr(op, "ann", None)),
            )

            if hasattr(op, "type"):
                load_global.type.CopyFrom(convert_rtype(op.type))

            register_op.load_global.CopyFrom(load_global)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "IntOp":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            int_op = ir_pb2.IntOp()

            if hasattr(op, "type"):
                int_op.type.CopyFrom(convert_rtype(op.type))
            if hasattr(op, "lhs") and op.lhs is not None:
                int_op.lhs.CopyFrom(convert_value(op.lhs))
            if hasattr(op, "rhs") and op.rhs is not None:
                int_op.rhs.CopyFrom(convert_value(op.rhs))

            op_mapping = {
                0: ir_pb2.IntOp.OpType.ADD,
                1: ir_pb2.IntOp.OpType.SUB,
                2: ir_pb2.IntOp.OpType.MUL,
                3: ir_pb2.IntOp.OpType.DIV,
                4: ir_pb2.IntOp.OpType.MOD,
                200: ir_pb2.IntOp.OpType.AND,
                201: ir_pb2.IntOp.OpType.OR,
                202: ir_pb2.IntOp.OpType.XOR,
                203: ir_pb2.IntOp.OpType.LEFT_SHIFT,
                204: ir_pb2.IntOp.OpType.RIGHT_SHIFT,
            }
            int_op.op = op_mapping.get(getattr(op, "op", 0), ir_pb2.IntOp.OpType.ADD)

            register_op.int_op.CopyFrom(int_op)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "ComparisonOp":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            comparison_op = ir_pb2.ComparisonOp()
            if hasattr(op, "type"):
                comparison_op.type.CopyFrom(convert_rtype(op.type))
            if hasattr(op, "lhs") and op.lhs is not None:
                comparison_op.lhs.CopyFrom(convert_value(op.lhs))
            if hasattr(op, "rhs") and op.rhs is not None:
                comparison_op.rhs.CopyFrom(convert_value(op.rhs))

            raw_op = getattr(op, "op", None)
            if hasattr(raw_op, "value"):
                raw_op = raw_op.value

            op_mapping = {
                "==": ir_pb2.ComparisonOp.OpType.EQ,
                "!=": ir_pb2.ComparisonOp.OpType.NEQ,
                "<": ir_pb2.ComparisonOp.OpType.SLT,
                ">": ir_pb2.ComparisonOp.OpType.SGT,
                "<=": ir_pb2.ComparisonOp.OpType.SLE,
                ">=": ir_pb2.ComparisonOp.OpType.SGE,
                "<U": ir_pb2.ComparisonOp.OpType.ULT,
                ">U": ir_pb2.ComparisonOp.OpType.UGT,
                "<=U": ir_pb2.ComparisonOp.OpType.ULE,
                ">=U": ir_pb2.ComparisonOp.OpType.UGE,
                100: ir_pb2.ComparisonOp.OpType.EQ,
                101: ir_pb2.ComparisonOp.OpType.NEQ,
                102: ir_pb2.ComparisonOp.OpType.SLT,
                103: ir_pb2.ComparisonOp.OpType.SGT,
                104: ir_pb2.ComparisonOp.OpType.SLE,
                105: ir_pb2.ComparisonOp.OpType.SGE,
                106: ir_pb2.ComparisonOp.OpType.ULT,
                107: ir_pb2.ComparisonOp.OpType.UGT,
                108: ir_pb2.ComparisonOp.OpType.ULE,
                109: ir_pb2.ComparisonOp.OpType.UGE,
            }

            mapped = op_mapping.get(raw_op)
            if mapped is None:
                logger.warning(f"Unknown ComparisonOp.op={raw_op!r}, using Default")
                mapped = ir_pb2.ComparisonOp.OpType.Default

            comparison_op.op = mapped
            register_op.comparison_op.CopyFrom(comparison_op)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "FloatOp":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            float_op = ir_pb2.FloatOp()

            if hasattr(op, "type"):
                float_op.type.CopyFrom(convert_rtype(op.type))
            if hasattr(op, "lhs") and op.lhs is not None:
                float_op.lhs.CopyFrom(convert_value(op.lhs))
            if hasattr(op, "rhs") and op.rhs is not None:
                float_op.rhs.CopyFrom(convert_value(op.rhs))

            op_mapping = {
                0: ir_pb2.FloatOp.OpType.ADD,
                1: ir_pb2.FloatOp.OpType.SUB,
                2: ir_pb2.FloatOp.OpType.MUL,
                3: ir_pb2.FloatOp.OpType.DIV,
                4: ir_pb2.FloatOp.OpType.MOD,
            }
            float_op.op = op_mapping.get(getattr(op, "op", 0), ir_pb2.FloatOp.OpType.ADD)

            register_op.float_op.CopyFrom(float_op)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "FloatNeg":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            float_neg = ir_pb2.FloatNeg()

            if hasattr(op, "type"):
                float_neg.type.CopyFrom(convert_rtype(op.type))
            if hasattr(op, "src") and op.src is not None:
                float_neg.src.CopyFrom(convert_value(op.src))

            register_op.float_neg.CopyFrom(float_neg)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "FloatComparisonOp":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            float_comparison_op = ir_pb2.FloatComparisonOp()

            if hasattr(op, "type"):
                float_comparison_op.type.CopyFrom(convert_rtype(op.type))
            if hasattr(op, "lhs") and op.lhs is not None:
                float_comparison_op.lhs.CopyFrom(convert_value(op.lhs))
            if hasattr(op, "rhs") and op.rhs is not None:
                float_comparison_op.rhs.CopyFrom(convert_value(op.rhs))

            op_mapping = {
                "==": ir_pb2.FloatComparisonOp.OpType.EQ,
                "!=": ir_pb2.FloatComparisonOp.OpType.NEQ,
                "<": ir_pb2.FloatComparisonOp.OpType.LT,
                ">": ir_pb2.FloatComparisonOp.OpType.GT,
                "<=": ir_pb2.FloatComparisonOp.OpType.LE,
                ">=": ir_pb2.FloatComparisonOp.OpType.GE,
            }
            float_comparison_op.op = op_mapping.get(getattr(op, "op", None), ir_pb2.FloatComparisonOp.OpType.Default)

            register_op.float_comparison_op.CopyFrom(float_comparison_op)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "LoadMem":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            load_mem = ir_pb2.LoadMem(
                is_borrowed=safe_bool(getattr(op, "is_borrowed", False))
            )

            if hasattr(op, "type"):
                load_mem.type.CopyFrom(convert_rtype(op.type))
            if hasattr(op, "src") and op.src is not None:
                load_mem.src.CopyFrom(convert_value(op.src))

            register_op.load_mem.CopyFrom(load_mem)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "SetMem":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            set_mem = ir_pb2.SetMem()

            if hasattr(op, "type"):
                set_mem.type.CopyFrom(convert_rtype(op.type).rvoid)
            if hasattr(op, "dest_type"):
                set_mem.dest_type.CopyFrom(convert_rtype(op.dest_type))
            if hasattr(op, "src") and op.src is not None:
                set_mem.src.CopyFrom(convert_value(op.src))
            if hasattr(op, "dest") and op.dest is not None:
                set_mem.dest.CopyFrom(convert_value(op.dest))

            register_op.set_mem.CopyFrom(set_mem)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "GetElementPtr":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            get_element_ptr = ir_pb2.GetElementPtr(
                field=safe_str(getattr(op, "field", ""))
            )

            if hasattr(op, "type"):
                get_element_ptr.type.CopyFrom(convert_rtype(op.type).rvoid)
            if hasattr(op, "src") and op.src is not None:
                get_element_ptr.src.CopyFrom(convert_value(op.src))
            if hasattr(op, "src_type"):
                get_element_ptr.src_type.CopyFrom(convert_rtype(op.src_type).rprimitive)

            register_op.get_element_ptr.CopyFrom(get_element_ptr)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "SetElement":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            set_element = ir_pb2.SetElement(
                field=safe_str(getattr(op, "field", ""))
            )

            if hasattr(op, "type"):
                set_element.type.CopyFrom(convert_rtype(op.type).rvoid)
            if hasattr(op, "src") and op.src is not None:
                set_element.src.CopyFrom(convert_value(op.src))
            if hasattr(op, "item") and op.item is not None:
                set_element.item.CopyFrom(convert_value(op.item))

            register_op.set_element.CopyFrom(set_element)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "LoadAddress":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            load_address = ir_pb2.LoadAddress()

            if hasattr(op, "type"):
                load_address.type.CopyFrom(convert_rtype(op.type).rvoid)

            src = getattr(op, "src", None)
            if isinstance(src, str):
                load_address.str_src = src
            elif isinstance(src, Register):
                load_address.reg_src.CopyFrom(convert_register(src))
            elif getattr(getattr(src, "__class__", None), "__name__", "") == "LoadStatic":
                static_src = ir_pb2.LoadStatic(
                    identifier=safe_str(getattr(src, "identifier", "")),
                    module_name=safe_str(getattr(src, "module_name", "")),
                    namespace=safe_str(getattr(src, "namespace", "")),
                    ann=safe_bytes(getattr(src, "ann", None)),
                )
                if hasattr(src, "type"):
                    static_src.type.CopyFrom(convert_rtype(src.type))
                load_address.static_src.CopyFrom(static_src)

            register_op.load_address.CopyFrom(load_address)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "KeepAlive":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            keep_alive = ir_pb2.KeepAlive(
                steal=safe_bool(getattr(op, "steal", False))
            )

            for src in getattr(op, "src", []):
                keep_alive.src.append(convert_value(src))

            register_op.keep_alive.CopyFrom(keep_alive)
            op_proto.register_op.CopyFrom(register_op)

        elif op_class_name == "Unborrow":
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            unborrow = ir_pb2.Unborrow()

            if hasattr(op, "src") and op.src is not None:
                unborrow.src.CopyFrom(convert_value(op.src))
            if hasattr(op, "type"):
                unborrow.type.CopyFrom(convert_rtype(op.type).rvoid)

            register_op.unborrow.CopyFrom(unborrow)
            op_proto.register_op.CopyFrom(register_op)

        else:
            logger.debug(f"Unknown operation type: {op_class_name}, using fallback PrimitiveOp")
            register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
            primitive_op = ir_pb2.PrimitiveOp()
            if hasattr(op, "type"):
                primitive_op.type.CopyFrom(convert_rtype(op.type))
            register_op.primitive_op.CopyFrom(primitive_op)
            op_proto.register_op.CopyFrom(register_op)

    except Exception as e:
        logger.warning(f"Error converting operation {op.__class__.__name__}: {e}")
        logger.debug(traceback.format_exc())
        register_op = ir_pb2.RegisterOp(error_kind=ir_pb2.ERR_NEVER)
        register_op.primitive_op.CopyFrom(ir_pb2.PrimitiveOp())
        op_proto.register_op.CopyFrom(register_op)

    return op_proto


def convert_function(fn: FuncIR) -> ir_pb2.Function:
    decl = convert_func_decl(getattr(fn, "decl", None))
    if not decl.name:
        decl.name = safe_str(getattr(fn, "name", ""))
    if not decl.module_name:
        decl.module_name = safe_str(getattr(getattr(fn, "decl", None), "module_name", ""))

    source_blocks = list(getattr(fn, "blocks", []))
    block_labels = build_block_label_map(source_blocks)

    blocks = []
    for block in source_blocks:
        ops = [convert_op(op, block_labels) for op in getattr(block, "ops", [])]

        error_handler = None
        block_error_handler = getattr(block, "error_handler", None)
        if block_error_handler is not None:
            error_handler = ir_pb2.BasicBlock(
                label=resolve_block_label(block_error_handler, block_labels, 0)
            )

        blocks.append(
            ir_pb2.BasicBlock(
                label=resolve_block_label(block, block_labels, 0),
                ops=ops,
                error_handler=error_handler,
                referenced=safe_bool(getattr(block, "referenced", False)),
            )
        )

    return ir_pb2.Function(
        decl=decl,
        arg_regs=[convert_register(r) for r in getattr(fn, "arg_regs", [])],
        blocks=blocks,
        traceback_name=safe_str(getattr(fn, "traceback_name", "")),
    )


def convert_class(cls: MypyClassIR) -> ir_pb2.Class:
    attributes = {}
    for name, rtype in getattr(cls, "attributes", {}).items():
        attributes[safe_str(name)] = convert_rtype(rtype)

    methods = {}
    for name, method in getattr(cls, "methods", {}).items():
        methods[safe_str(name)] = convert_function(method)

    return ir_pb2.Class(
        name=safe_str(getattr(cls, "name", "")),
        module_name=safe_str(getattr(cls, "module_name", "")),
        is_trait=safe_bool(getattr(cls, "is_trait", False)),
        is_generated=safe_bool(getattr(cls, "is_generated", False)),
        is_abstract=safe_bool(getattr(cls, "is_abstract", False)),
        is_ext_class=safe_bool(getattr(cls, "is_ext_class", False)),
        is__class=True,
        is_augmented=safe_bool(getattr(cls, "is_augmented", False)),
        inherits_python=safe_bool(getattr(cls, "inherits_python", False)),
        has_dict=safe_bool(getattr(cls, "has_dict", False)),
        allow_interpreted_subclasses=safe_bool(getattr(cls, "allow_interpreted_subclasses", False)),
        needs_getseters=safe_bool(getattr(cls, "needs_getseters", False)),
        serializable=safe_bool(getattr(cls, "_serializable", False)),
        builtin_base=safe_str(getattr(cls, "builtin_base", "") or ""),
        attributes=attributes,
        deletable=list(getattr(cls, "deletable", [])),
        methods=methods,
    )


def convert_cfg(fn: FuncIR, cfg) -> ir_pb2.FunctionCFG:
    logger.info(f"=== Converting CFG for function: {getattr(fn, 'name', '<unknown>')} ===")
    logger.info(f"Function object type: {type(fn)}")
    logger.info(f"Function name: {getattr(fn, 'name', '<unknown>')}")
    logger.info(f"Function decl name: {getattr(getattr(fn, 'decl', None), 'name', 'No decl')}")
    logger.info(f"Number of blocks: {len(getattr(fn, 'blocks', []))}")

    source_blocks = list(getattr(fn, "blocks", []))
    block_labels = build_block_label_map(source_blocks)

    for i, block in enumerate(source_blocks):
        logger.info(f"  Block {i}: label={getattr(block, 'label', -1)}, ops={len(getattr(block, 'ops', []))}")
        for j, op in enumerate(getattr(block, "ops", [])):
            logger.info(f"    Op {j}: type={type(op).__name__}, line={getattr(op, 'line', -1)}")

    cfg_proto = ir_pb2.CFG()

    exits = []
    for block in getattr(cfg, "exits", []):
        logger.info(f"Exit block label: {getattr(block, 'label', -1)}")
        exits.append(ir_pb2.BasicBlock(label=resolve_block_label(block, block_labels, 0)))
    cfg_proto.exits.extend(exits)

    blocks = []
    for block in source_blocks:
        ops = []
        for op in getattr(block, "ops", []):
            try:
                op_proto = convert_op(op, block_labels)
                logger.info(f"Converting op: {type(op).__name__} at line {getattr(op, 'line', -1)}")
                ops.append(op_proto)
            except Exception as e:
                logger.warning(f"Error converting op {op}: {e}")
                logger.debug(traceback.format_exc())

        error_handler = None
        block_error_handler = getattr(block, "error_handler", None)
        if block_error_handler is not None:
            error_handler = ir_pb2.BasicBlock(
                label=resolve_block_label(block_error_handler, block_labels, 0),
                referenced=safe_bool(getattr(block_error_handler, "referenced", False)),
            )

        blocks.append(
            ir_pb2.BasicBlock(
                label=resolve_block_label(block, block_labels, 0),
                ops=ops,
                error_handler=error_handler,
                referenced=safe_bool(getattr(block, "referenced", False)),
            )
        )
        logger.info(f"Converted block with label: {getattr(block, 'label', -1)}")

    edges = []
    succ = getattr(cfg, "succ", None)
    if succ:
        try:
            logger.info(f"CFG succ keys: {list(succ.keys())}")
        except Exception:
            pass

        for src_block, dst_blocks in succ.items():
            if not src_block or not dst_blocks:
                continue

            src_label = resolve_block_label(src_block, block_labels, 0)
            for dst_block in dst_blocks:
                if not dst_block:
                    continue

                dst_label = resolve_block_label(dst_block, block_labels, 0)
                edges.append(
                    ir_pb2.CFGEdge(
                        source=src_label,
                        target=dst_label,
                        type="normal",
                    )
                )
                logger.info(f"Added edge: {src_label} -> {dst_label}")

    logger.info(f"Total edges: {len(edges)}")
    logger.info(f"Total blocks in proto: {len(blocks)}")

    return ir_pb2.FunctionCFG(
        function_name=safe_str(getattr(fn, "name", "")),
        cfg=cfg_proto,
        blocks=blocks,
        edges=edges,
    )


class IRService(ir_pb2_grpc.IRServiceServicer):
    def GetModules(self, request, context):
        logger.info("=== GetModules called ===")
        try:
            args = parse_source(request)
            modules = get_modules(**args)

            return ir_pb2.ModuleResponse(
                success=True,
                modules=[convert_module(m) for m in modules],
            )
        except Exception as e:
            logger.error(f"GetModules error: {e}")
            logger.error(traceback.format_exc())
            return ir_pb2.ModuleResponse(
                success=False,
                errors=[str(e)],
            )

    def GetClasses(self, request, context):
        logger.info("=== GetClasses called ===")
        try:
            args = parse_source(request)
            modules = get_modules(**args)

            classes = []
            for module in modules:
                classes.extend(iter_classes(getattr(module, "classes", None)))

            return ir_pb2.ClassResponse(
                success=True,
                classes=[convert_class(c) for c in classes],
            )
        except Exception as e:
            logger.error(f"GetClasses error: {e}")
            logger.error(traceback.format_exc())
            return ir_pb2.ClassResponse(
                success=False,
                errors=[str(e)],
            )

    def GetCFG(self, request, context):
        logger.info("=== GetCFG called ===")
        try:
            args = parse_source(request)

            if "files" in args:
                for file_path in args["files"]:
                    if not Path(file_path).exists():
                        error_msg = f"File not found: {file_path}"
                        logger.error(error_msg)
                        return ir_pb2.CFGResponse(
                            success=False,
                            errors=[error_msg],
                        )
                    logger.info(f"File exists: {file_path}")

            modules = get_cfg(**args)
            logger.info(f"Got {len(modules)} modules for conversion")

            cfgs_proto = []
            for fn, cfg in modules:
                cfg_proto = convert_cfg(fn, cfg)
                cfgs_proto.append(cfg_proto)
                logger.info(f"Successfully converted CFG for {getattr(fn, 'name', '<unknown>')}")

            logger.info(f"Total CFGs generated: {len(cfgs_proto)}")

            cfgs_dict = []
            for cfg in cfgs_proto:
                try:
                    cfg_dict = MessageToDict(cfg, preserving_proto_field_name=True)
                    cfgs_dict.append(cfg_dict)
                except Exception as e:
                    logger.error(f"Error converting to dict: {e}")
                    cfgs_dict.append({"error": str(e)})

            with open("cfgs_debug.json", "w", encoding="utf-8") as f:
                json.dump(cfgs_dict, f, indent=2, ensure_ascii=False, default=str)

            logger.info("Saved debug info to cfgs_debug.json")

            return ir_pb2.CFGResponse(
                success=bool(cfgs_proto),
                function_cfgs=cfgs_proto,
            )

        except Exception as e:
            error_msg = str(e)
            logger.error(f"GetCFG error: {error_msg}")
            logger.error(traceback.format_exc())
            return ir_pb2.CFGResponse(
                success=False,
                errors=[error_msg],
            )

    def GetAll(self, request, context):
        logger.info("=== GetAll called ===")
        try:
            args = parse_source(request)
            modules = get_modules(**args)

            module_response = ir_pb2.ModuleResponse(
                success=True,
                modules=[convert_module(m) for m in modules],
            )

            classes = []
            for module in modules:
                classes.extend(iter_classes(getattr(module, "classes", None)))

            class_response = ir_pb2.ClassResponse(
                success=True,
                classes=[convert_class(c) for c in classes],
            )

            cfgs = []
            for module in modules:
                for fn in getattr(module, "functions", []):
                    if getattr(fn, "name", None) == TOP_LEVEL_NAME:
                        continue
                    try:
                        exceptions.insert_exception_handling(fn, False)
                        cfg = dataflow.get_cfg(fn.blocks)
                        cfgs.append(convert_cfg(fn, cfg))
                    except Exception as e:
                        logger.debug(f"Failed to get CFG for {getattr(fn, 'name', '<unknown>')}: {e}")
                        logger.debug(traceback.format_exc())
                        continue

            cfg_response = ir_pb2.CFGResponse(
                success=bool(cfgs),
                function_cfgs=cfgs,
            )

            return ir_pb2.CompleteResponse(
                success=True,
                modules=module_response,
                classes=class_response,
                cfgs=cfg_response,
            )

        except Exception as e:
            error_msg = str(e)
            logger.error(f"GetAll error: {error_msg}")
            logger.error(traceback.format_exc())
            return ir_pb2.CompleteResponse(
                success=False,
                errors=[str(e)],
            )


def serve(host: str = "127.0.0.1", port: int = 50051):
    address = f"{host}:{port}"
    logger.info("Starting gRPC server on %s...", address)
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=8))
    ir_pb2_grpc.add_IRServiceServicer_to_server(IRService(), server)
    bound_port = server.add_insecure_port(address)
    if bound_port == 0:
        raise RuntimeError(f"Failed to bind gRPC server to {address}")
    server.start()
    logger.info("IR gRPC server started on %s", address)
    server.wait_for_termination()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Serve mypy IR over gRPC")
    parser.add_argument(
        "--host",
        default=os.getenv("SEQRA_PY_GRPC_HOST", "127.0.0.1"),
        help="Host address to bind the gRPC server to",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.getenv("SEQRA_PY_GRPC_PORT", "50051")),
        help="TCP port for the gRPC server",
    )
    args = parser.parse_args()
    serve(host=args.host, port=args.port)