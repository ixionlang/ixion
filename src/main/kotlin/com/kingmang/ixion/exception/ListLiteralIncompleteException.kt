package com.kingmang.ixion.exception

class ListLiteralIncompleteException : IxException(
    9,
    "List literals must have one or more elements.",
    "To create an empty list do `type[]`."
)