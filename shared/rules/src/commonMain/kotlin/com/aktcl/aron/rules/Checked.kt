package com.aktcl.aron.rules

/** Overflow-checked Long addition; throws [ArithmeticException] instead of wrapping (money never wraps silently). */
internal fun checkedAdd(a: Long, b: Long): Long {
    val r = a + b
    if (((a xor r) and (b xor r)) < 0) throw ArithmeticException("long overflow: $a + $b")
    return r
}

/** Overflow-checked Long multiplication; throws [ArithmeticException] instead of wrapping. */
internal fun checkedMul(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0L
    val r = a * b
    if (r / b != a || (a == Long.MIN_VALUE && b == -1L) || (b == Long.MIN_VALUE && a == -1L)) {
        throw ArithmeticException("long overflow: $a * $b")
    }
    return r
}
