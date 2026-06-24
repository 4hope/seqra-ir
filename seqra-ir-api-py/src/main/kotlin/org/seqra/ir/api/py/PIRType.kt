package org.seqra.ir.api.py

import org.seqra.ir.api.common.CommonType
import org.seqra.ir.api.common.CommonTypeName

interface PIRType : CommonType, CommonTypeName {
    val name: String
    val isUnboxed: Boolean
    val isRefcounted: Boolean
    val errorOverlap: Boolean
    val mayBeImmortal: Boolean

    override val typeName: String get() = name
    override val nullable: Boolean? get() = null

    fun <T> accept(visitor: PIRTypeVisitor<T>): T
}

interface PIRTypeVisitor<T> {
    fun visitPrimitive(type: PIRPrimitiveType): T
    fun visitInstance(type: PIRInstanceType): T
    fun visitUnion(type: PIRUnionType): T
    fun visitTuple(type: PIRTupleType): T
    fun visitStruct(type: PIRStructType): T
    fun visitArray(type: PIRArrayType): T
    fun visitVoid(type: PIRVoidType): T
}

class PIRVoidType(
    override val name: String = "void",
    override val isUnboxed: Boolean = false,
    override val isRefcounted: Boolean = false,
    override val errorOverlap: Boolean = false,
    override val mayBeImmortal: Boolean = false
) : PIRType {
    override fun <T> accept(visitor: PIRTypeVisitor<T>): T = visitor.visitVoid(this)
}

class PIRPrimitiveType(
    override val name: String,
    override val isUnboxed: Boolean,
    override val isRefcounted: Boolean,
    override val errorOverlap: Boolean = false,
    override val mayBeImmortal: Boolean = true,
    val isNativeInt: Boolean = false,
    val isSigned: Boolean = false,
    val ctype: String = "PyObject *",
    val size: Int = PLATFORM_SIZE,
    val cUndefined: String = "NULL"
) : PIRType {
    override fun <T> accept(visitor: PIRTypeVisitor<T>): T = visitor.visitPrimitive(this)
}

class PIRInstanceType(
    private val classIr: PIRClass,
    override val name: String = classIr.fullname,
    override val isUnboxed: Boolean = false,
    override val isRefcounted: Boolean = true,
    override val errorOverlap: Boolean = false,
    override val mayBeImmortal: Boolean = false,
    val ctype: String = "PyObject *"
) : PIRType {
    override fun <T> accept(visitor: PIRTypeVisitor<T>): T = visitor.visitInstance(this)
}

 class PIRTupleType(
    val types: List<PIRType>,
    override val name: String = "tuple",
    override val isUnboxed: Boolean = true,
    override val isRefcounted: Boolean = types.any { it.isRefcounted },
    override val errorOverlap: Boolean = types.all { it.errorOverlap } && types.isNotEmpty(),
    override val mayBeImmortal: Boolean = false,
    val uniqueId: String = "",
    val structName: String = ""
) : PIRType {
    override fun <T> accept(visitor: PIRTypeVisitor<T>): T = visitor.visitTuple(this)
}

class PIRUnionType(
    val items: List<PIRType>,
    override val name: String = "union",
    override val isUnboxed: Boolean = false,
    override val isRefcounted: Boolean = true,
    override val errorOverlap: Boolean = false,
    override val mayBeImmortal: Boolean = items.any { it.mayBeImmortal },
    val itemsSet: Set<PIRType> = items.toSet()
) : PIRType {
    override fun <T> accept(visitor: PIRTypeVisitor<T>): T = visitor.visitUnion(this)
}

class PIRStructType(
    override val name: String,
    val names: List<String>,
    val types: List<PIRType>,
    override val isUnboxed: Boolean = false,
    override val isRefcounted: Boolean = false,
    override val errorOverlap: Boolean = false,
    override val mayBeImmortal: Boolean = false,
    val offsets: List<Int> = emptyList(),
    val size: Int = 0
) : PIRType {
    override fun <T> accept(visitor: PIRTypeVisitor<T>): T = visitor.visitStruct(this)
}

class PIRArrayType(
    private val itemType: PIRType,
    private val length: Int,
    override val name: String = "${itemType.name}[$length]",
    override val isUnboxed: Boolean = false,
    override val isRefcounted: Boolean = false,
    override val errorOverlap: Boolean = false,
    override val mayBeImmortal: Boolean = false
) : PIRType {
    override fun <T> accept(visitor: PIRTypeVisitor<T>): T = visitor.visitArray(this)
}

const val PLATFORM_SIZE = 8

object PIRPrimitiveTypes {
    val OBJECT = PIRPrimitiveType(
        name = "builtins.object",
        isUnboxed = false,
        isRefcounted = true,
        mayBeImmortal = true
    )

    val INT = PIRPrimitiveType(
        name = "builtins.int",
        isUnboxed = true,
        isRefcounted = true,
        ctype = "CPyTagged"
    )

    val STR = PIRPrimitiveType(
        name = "builtins.str",
        isUnboxed = false,
        isRefcounted = true,
        mayBeImmortal = true
    )

    val LIST = PIRPrimitiveType(
        name = "builtins.list",
        isUnboxed = false,
        isRefcounted = true,
        mayBeImmortal = false
    )

    val DICT = PIRPrimitiveType(
        name = "builtins.dict",
        isUnboxed = false,
        isRefcounted = true,
        mayBeImmortal = true
    )

    val BOOL = PIRPrimitiveType(
        name = "builtins.bool",
        isUnboxed = true,
        isRefcounted = false,
        ctype = "char",
        size = 1
    )

    val NONE = PIRPrimitiveType(
        name = "builtins.None",
        isUnboxed = true,
        isRefcounted = false,
        ctype = "char",
        size = 1
    )

    val FLOAT = PIRPrimitiveType(
        name = "builtins.float",
        isUnboxed = true,
        isRefcounted = false,
        ctype = "double",
        size = 8,
        errorOverlap = true
    )

    val BYTES = PIRPrimitiveType(
        name = "builtins.bytes",
        isUnboxed = false,
        isRefcounted = true,
        mayBeImmortal = true
    )

    val TUPLE = PIRPrimitiveType(
        name = "builtins.tuple",
        isUnboxed = false,
        isRefcounted = true,
        mayBeImmortal = true
    )

    val INT32 = PIRPrimitiveType(
        name = "i32",
        isUnboxed = true,
        isRefcounted = false,
        isNativeInt = true,
        isSigned = true,
        ctype = "int32_t",
        size = 4,
        errorOverlap = true,
        cUndefined = "-113"
    )

    val INT64 = PIRPrimitiveType(
        name = "i64",
        isUnboxed = true,
        isRefcounted = false,
        isNativeInt = true,
        isSigned = true,
        ctype = "int64_t",
        size = 8,
        errorOverlap = true,
        cUndefined = "-113"
    )

    val BITMAP = PIRPrimitiveType(
        name = "u32",
        isUnboxed = true,
        isRefcounted = false,
        isNativeInt = true,
        isSigned = false,
        ctype = "uint32_t",
        size = 4,
        errorOverlap = true,
        cUndefined = "239"
    )

    val POINTER = PIRPrimitiveType(
        name = "ptr",
        isUnboxed = true,
        isRefcounted = false,
        ctype = "CPyPtr",
        cUndefined = "0"
    )

    val C_POINTER = PIRPrimitiveType(
        name = "c_ptr",
        isUnboxed = false,
        isRefcounted = false,
        ctype = "void *",
        cUndefined = "NULL"
    )

    val C_STRING = PIRPrimitiveType(
        name = "cstring",
        isUnboxed = true,
        isRefcounted = false,
        ctype = "const char *",
        cUndefined = "NULL"
    )
}

val PIR_VOID = PIRVoidType()

val EXC_TUPLE = PIRTupleType(
    types = listOf(PIRPrimitiveTypes.OBJECT, PIRPrimitiveTypes.OBJECT, PIRPrimitiveTypes.OBJECT)
)

fun isIntRPrimitive(type: PIRType): Boolean =
    type is PIRPrimitiveType && type.name == "builtins.int"

fun isStrRPrimitive(type: PIRType): Boolean =
    type is PIRPrimitiveType && type.name == "builtins.str"

fun isListRPrimitive(type: PIRType): Boolean =
    type is PIRPrimitiveType && type.name == "builtins.list"

fun isDictRPrimitive(type: PIRType): Boolean =
    type is PIRPrimitiveType && type.name == "builtins.dict"

fun isBoolRPrimitive(type: PIRType): Boolean =
    type is PIRPrimitiveType && type.name == "builtins.bool"

fun isNoneRPrimitive(type: PIRType): Boolean =
    type is PIRPrimitiveType && type.name == "builtins.None"

fun isObjectRPrimitive(type: PIRType): Boolean =
    type is PIRPrimitiveType && type.name == "builtins.object"

fun isOptionalType(type: PIRType): Boolean {
    return type is PIRUnionType && type.items.size == 2 &&
            type.items.any { isNoneRPrimitive(it) }
}

fun optionalValueType(type: PIRType): PIRType? {
    if (type is PIRUnionType && type.items.size == 2) {
        return when {
            isNoneRPrimitive(type.items[0]) -> type.items[1]
            isNoneRPrimitive(type.items[1]) -> type.items[0]
            else -> null
        }
    }
    return null
}