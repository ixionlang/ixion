package com.kingmang.ixion.exception

class ReturnTypeMismatchException : IxException(
    20,
    "Function `{0}` has return type of `{1}`. Cannot have another return statement with type `{2}`.",
    "Make sure all return statements in your function are returning the same type."
)