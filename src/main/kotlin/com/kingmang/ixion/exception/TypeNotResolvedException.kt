package com.kingmang.ixion.exception

class TypeNotResolvedException : IxException(
    21,
    "Variable `{0}` cannot be resolved to a type.",
    "Make sure all variables are properly spelled etc."
)