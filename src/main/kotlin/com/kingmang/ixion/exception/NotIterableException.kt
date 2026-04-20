package com.kingmang.ixion.exception

class NotIterableException : IxException(
    15,
    "Expression of type `{0}` is not iterable.",
    "Check to be sure the type is iterable, like a list or range."
)