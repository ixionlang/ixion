package com.kingmang.ixion.exception

class FunctionSignatureMismatchException : IxException(
    6,
    "No function overloads exist on `{0}` that match the parameters `{1}`.",
    "Check the parameter positions and types of the called function."
)