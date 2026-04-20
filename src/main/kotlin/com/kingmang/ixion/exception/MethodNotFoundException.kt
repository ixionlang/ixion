package com.kingmang.ixion.exception

class MethodNotFoundException : IxException(
    12,
    "Method `{0}` not found.",
    null
)