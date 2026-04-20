package com.kingmang.ixion.exception

class MutabilityException : IxException(
    14,
    "Variable `{0}` is immutable and cannot receive assignment.",
    "Declare a variable with the `mut` keyword to allow mutability."
)