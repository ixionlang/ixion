package com.kingmang.ixion.exception

class MutabilityException : IxException(
    14,
    "Variable `{0}` is constant and cannot receive assignment.",
    "Declare a variable with the `var` keyword to allow mutability."
)