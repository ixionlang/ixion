package com.kingmang.ixion.exception

class BadAssignmentException : IxException(
    1,
    "Variable `{0}` of type `{1}` cannot accept assignment of type `{2}`.",
    "Check that both sides of the assignment have the same type."
)