package com.kingmang.ixion.exception

class IdentifierNotFoundException : IxException(
    7,
    "Identifier `{0}` not found.",
    "Make sure that all identifiers are defined, builtin or imported."
)