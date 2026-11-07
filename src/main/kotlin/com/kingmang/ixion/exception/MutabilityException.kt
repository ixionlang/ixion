package com.kingmang.ixion.exception

class MutabilityException : IxException(
    14,
    "Binding `{0}` is immutable and cannot be reassigned.",
    "Declare a local binding with `let mut` to allow reassignment."
)